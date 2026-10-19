package com.example.portofit.ui.feature.experience

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.portofit.PortoFitApplication
import com.example.portofit.data.auth.SessionState
import com.example.portofit.data.model.ExperienceRules
import com.example.portofit.data.model.ExperienceTextProblem
import com.example.portofit.data.repository.ExperienceChange
import com.example.portofit.data.repository.ExperienceChanges
import com.example.portofit.data.repository.ExperienceDraft
import com.example.portofit.data.repository.ExperiencesRepository
import com.example.portofit.data.repository.PublishOutcome
import com.example.portofit.data.repository.PublishRejection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** Resultado a explicar ao utilizador depois de uma tentativa. */
sealed interface PublishMessage {
    /** Não foi publicada (nada ficou gravado). */
    data class Rejected(val reason: PublishRejection, val retryAfterSeconds: Long? = null) : PublishMessage

    /** A tentativa anterior já tinha sido aceite (409 DIFFERENT_CONTENT). */
    data class AlreadyPublished(val experienceId: String?) : PublishMessage

    /** Foi publicada e entretanto eliminada (409 EXPERIENCE_DELETED). */
    data object PublishedThenDeleted : PublishMessage

    /** A tentativa por confirmar tem mais de 6 dias: a chave já não é reutilizada (docs/experiences-api.md §5). */
    data object AttemptTooOld : PublishMessage
}

data class PublishUiState(
    val placeName: String,
    val offeringName: String,
    val text: String = "",
    val stars: Int? = null,
    val sending: Boolean = false,
    /**
     * Há uma tentativa de resultado desconhecido: pode já ter sido publicada. O conteúdo fica bloqueado;
     * só se pode repetir (mesma chave e conteúdo) ou descartar a tentativa.
     */
    val pendingAttempt: Boolean = false,
    val showValidation: Boolean = false,
    val message: PublishMessage? = null,
    val signedIn: Boolean = false,
    /** Id da experiência publicada: o ecrã regressa ao local. */
    val publishedId: String? = null,
) {
    val textProblem: ExperienceTextProblem? get() = ExperienceRules.textProblem(text)
    val textLength: Int get() = ExperienceRules.length(ExperienceRules.normalizeText(text))
    val starsMissing: Boolean get() = !ExperienceRules.validStars(stars)
    val contentLocked: Boolean get() = sending || pendingAttempt
    val canSubmit: Boolean get() = signedIn && !sending && publishedId == null
}

/**
 * Publicar uma experiência numa atividade (docs/experiences-api.md §5).
 *
 * - Uma `Idempotency-Key` por tentativa lógica, gerada no primeiro envio e guardada com o conteúdo no
 *   SavedStateHandle **antes** de enviar: sobrevive à rotação e à recriação do processo.
 * - Resultado desconhecido: chave e conteúdo ficam bloqueados; "Tentar novamente" repete exatamente o
 *   mesmo pedido. Nunca há uma nova publicação automática, nem a mesma chave com outro conteúdo.
 * - Recusa definitiva (nada gravado): a chave é descartada e o conteúdo pode ser corrigido.
 * - Chaves com mais de 6 dias não são reutilizadas (o servidor só as guarda 7 dias).
 */
class PublishExperienceViewModel(
    private val repository: ExperiencesRepository,
    private val changes: ExperienceChanges,
    private val session: StateFlow<SessionState>,
    private val savedState: SavedStateHandle,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {

    private val placeId: String = checkNotNull(savedState[PLACE_ID_ARG])
    private val offeringId: String = checkNotNull(savedState[OFFERING_ID_ARG])

    private val content = MutableStateFlow(
        PublishUiState(
            placeName = savedState[PLACE_NAME_ARG] ?: "",
            offeringName = savedState[OFFERING_NAME_ARG] ?: "",
            text = savedState[KEY_TEXT] ?: "",
            stars = savedState[KEY_STARS],
            // Restaurado depois de a app ter sido terminada a meio: o resultado é desconhecido.
            pendingAttempt = savedState.get<String>(KEY_PENDING_KEY) != null,
        ),
    )

    val uiState: StateFlow<PublishUiState> = combine(content, session) { state, current ->
        state.copy(signedIn = current is SessionState.SignedIn)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, content.value)

    fun onTextChange(text: String) {
        if (content.value.contentLocked) return
        savedState[KEY_TEXT] = text
        content.update { it.copy(text = text, message = null) }
    }

    fun onStarsChange(stars: Int) {
        if (content.value.contentLocked || stars !in 1..5) return
        savedState[KEY_STARS] = stars
        content.update { it.copy(stars = stars, message = null) }
    }

    /** "Publicar" ou, com uma tentativa por confirmar, "Tentar novamente" (mesma chave e conteúdo). */
    fun submit() {
        val state = uiState.value
        if (!state.canSubmit) return
        val pendingKey = savedState.get<String>(KEY_PENDING_KEY)
        if (pendingKey != null) {
            val createdAt = savedState.get<Long>(KEY_PENDING_CREATED_AT) ?: 0L
            if (clock() - createdAt > MAX_KEY_AGE_MILLIS) {
                content.update { it.copy(message = PublishMessage.AttemptTooOld) }
                return
            }
            send(pendingKey, pendingDraft())
            return
        }
        if (state.textProblem != null || state.starsMissing) {
            content.update { it.copy(showValidation = true) }
            return
        }
        val draft = ExperienceDraft(placeId, offeringId, ExperienceRules.normalizeText(state.text), state.stars!!)
        val key = newKey()
        // Guardado antes de enviar: se a app terminar a meio, a tentativa fica por confirmar.
        savedState[KEY_PENDING_KEY] = key
        savedState[KEY_PENDING_CREATED_AT] = clock()
        savedState[KEY_PENDING_TEXT] = draft.text
        savedState[KEY_PENDING_STARS] = draft.stars
        send(key, draft)
    }

    /** Descarta a tentativa por confirmar (depois de o utilizador confirmar que quer recomeçar). */
    fun discardAttempt() {
        if (content.value.sending) return
        clearPending()
        content.update { it.copy(pendingAttempt = false, message = null) }
    }

    fun dismissMessage() = content.update { it.copy(message = null) }

    private fun send(key: String, draft: ExperienceDraft) {
        content.update { it.copy(sending = true, message = null) }
        viewModelScope.launch {
            val outcome = repository.publish(key, draft)
            when (outcome) {
                is PublishOutcome.Published -> {
                    clearPending()
                    savedState.remove<String>(KEY_TEXT)
                    savedState.remove<Int>(KEY_STARS)
                    changes.notify(ExperienceChange(outcome.experience.id, outcome.experience.placeId, outcome.experience.authorId))
                    content.update { it.copy(sending = false, pendingAttempt = false, publishedId = outcome.experience.id) }
                }
                PublishOutcome.Unconfirmed -> content.update { it.copy(sending = false, pendingAttempt = true) }
                is PublishOutcome.Rejected -> {
                    clearPending()
                    content.update { it.copy(sending = false, pendingAttempt = false, message = PublishMessage.Rejected(outcome.reason, outcome.retryAfterSeconds)) }
                }
                is PublishOutcome.AlreadyPublished -> {
                    clearPending()
                    // A experiência existe: as listas do local e do perfil devem mostrá-la.
                    val author = (session.value as? SessionState.SignedIn)?.userId.orEmpty()
                    outcome.experienceId?.let { changes.notify(ExperienceChange(it, placeId, author)) }
                    content.update { it.copy(sending = false, pendingAttempt = false, message = PublishMessage.AlreadyPublished(outcome.experienceId)) }
                }
                PublishOutcome.PublishedThenDeleted -> {
                    clearPending()
                    content.update { it.copy(sending = false, pendingAttempt = false, message = PublishMessage.PublishedThenDeleted) }
                }
            }
        }
    }

    private fun pendingDraft() = ExperienceDraft(
        placeId,
        offeringId,
        savedState[KEY_PENDING_TEXT] ?: ExperienceRules.normalizeText(content.value.text),
        savedState[KEY_PENDING_STARS] ?: content.value.stars ?: 0,
    )

    private fun clearPending() {
        savedState.remove<String>(KEY_PENDING_KEY)
        savedState.remove<Long>(KEY_PENDING_CREATED_AT)
        savedState.remove<String>(KEY_PENDING_TEXT)
        savedState.remove<Int>(KEY_PENDING_STARS)
    }

    companion object {
        const val PLACE_ID_ARG = "placeId"
        const val OFFERING_ID_ARG = "offeringId"
        const val PLACE_NAME_ARG = "placeName"
        const val OFFERING_NAME_ARG = "offeringName"

        const val KEY_TEXT = "publish.text"
        const val KEY_STARS = "publish.stars"
        const val KEY_PENDING_KEY = "publish.pendingKey"
        const val KEY_PENDING_CREATED_AT = "publish.pendingCreatedAt"
        const val KEY_PENDING_TEXT = "publish.pendingText"
        const val KEY_PENDING_STARS = "publish.pendingStars"

        /** O servidor guarda as chaves 7 dias; a app não reutiliza chaves com mais de 6. */
        const val MAX_KEY_AGE_MILLIS = 6L * 24 * 60 * 60 * 1000

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PortoFitApplication
                PublishExperienceViewModel(
                    app.container.experiencesRepository,
                    app.container.experienceChanges,
                    app.container.sessionManager.state,
                    createSavedStateHandle(),
                )
            }
        }
    }
}
