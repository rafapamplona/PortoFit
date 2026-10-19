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
import com.example.portofit.data.photo.PhotoDrafts
import com.example.portofit.data.photo.PhotoError
import com.example.portofit.data.repository.ExperienceChange
import com.example.portofit.data.repository.ExperienceChanges
import com.example.portofit.data.repository.ExperienceDraft
import com.example.portofit.data.repository.ExperiencesRepository
import com.example.portofit.data.repository.PublishOutcome
import com.example.portofit.data.repository.PublishRejection
import com.example.portofit.data.repository.UploadError
import com.example.portofit.data.repository.UploadOutcome
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

    /** O envio da fotografia falhou: a experiência **não** foi criada. */
    data class UploadFailed(val error: UploadError, val retryAfterSeconds: Long? = null) : PublishMessage

    /** A tentativa anterior já tinha sido aceite (409 DIFFERENT_CONTENT). */
    data class AlreadyPublished(val experienceId: String?) : PublishMessage

    /** Foi publicada e entretanto eliminada (409 EXPERIENCE_DELETED). */
    data object PublishedThenDeleted : PublishMessage

    /** A tentativa por confirmar tem mais de 6 dias: a chave já não é reutilizada (docs/experiences-api.md §5). */
    data object AttemptTooOld : PublishMessage
}

/** Fase do envio, para o ecrã dizer o que está a acontecer. */
enum class SendPhase { UPLOADING_PHOTO, PUBLISHING }

data class PublishUiState(
    val placeName: String,
    val offeringName: String,
    val text: String = "",
    val stars: Int? = null,
    /** null = nada a enviar; senão, a fase em curso. */
    val sendPhase: SendPhase? = null,
    /**
     * Há uma tentativa de resultado desconhecido: pode já ter sido publicada. O conteúdo (texto, estrelas e
     * fotografia) fica bloqueado; só se pode repetir (mesma chave, conteúdo e `mediaId`) ou descartar.
     */
    val pendingAttempt: Boolean = false,
    /** A tentativa por confirmar inclui uma fotografia já enviada. */
    val pendingHasPhoto: Boolean = false,
    val showValidation: Boolean = false,
    val message: PublishMessage? = null,
    val signedIn: Boolean = false,
    val photo: PhotoDraftState = PhotoDraftState.None,
    val photoError: PhotoError? = null,
    /** Id da experiência publicada: o ecrã regressa ao local. */
    val publishedId: String? = null,
) {
    val sending: Boolean get() = sendPhase != null
    val textProblem: ExperienceTextProblem? get() = ExperienceRules.textProblem(text)
    val textLength: Int get() = ExperienceRules.length(ExperienceRules.normalizeText(text))
    val starsMissing: Boolean get() = !ExperienceRules.validStars(stars)
    val contentLocked: Boolean get() = sending || pendingAttempt
    val photoBusy: Boolean get() = photo is PhotoDraftState.Preparing
    val canSubmit: Boolean get() = signedIn && !sending && !photoBusy && publishedId == null
}

/**
 * Publicar uma experiência numa atividade (docs/experiences-api.md §5 e §12).
 *
 * - Uma `Idempotency-Key` por tentativa lógica, gerada no primeiro envio e guardada com o conteúdo (incluindo o
 *   `mediaId`) no SavedStateHandle **antes** de enviar: sobrevive à rotação e à recriação do processo.
 * - Com fotografia: primeiro o envio da fotografia; só depois de sucesso o `mediaId` é usado na publicação. Uma
 *   falha do envio da fotografia não cria nada e é explicada à parte.
 * - Resultado desconhecido: chave, conteúdo e `mediaId` ficam bloqueados; "Tentar novamente" repete exatamente o
 *   mesmo pedido, sem novo envio da fotografia. Nunca há uma nova publicação automática.
 * - Recusa definitiva (nada gravado): a chave é descartada e o conteúdo pode ser corrigido; a fotografia já
 *   enviada é reaproveitada (exceto se o servidor disser que já não serve).
 * - Chaves com mais de 6 dias não são reutilizadas (o servidor só as guarda 7 dias).
 */
class PublishExperienceViewModel(
    private val repository: ExperiencesRepository,
    private val changes: ExperienceChanges,
    private val session: StateFlow<SessionState>,
    private val savedState: SavedStateHandle,
    drafts: PhotoDrafts,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {

    private val placeId: String = checkNotNull(savedState[PLACE_ID_ARG])
    private val offeringId: String = checkNotNull(savedState[OFFERING_ID_ARG])

    /** Fotografia do rascunho (só nomes de ficheiros da app no SavedStateHandle). */
    val photos = PhotoDraftController(viewModelScope, drafts, savedState, "publish")

    private val content = MutableStateFlow(
        PublishUiState(
            placeName = savedState[PLACE_NAME_ARG] ?: "",
            offeringName = savedState[OFFERING_NAME_ARG] ?: "",
            text = savedState[KEY_TEXT] ?: "",
            stars = savedState[KEY_STARS],
            // Restaurado depois de a app ter sido terminada a meio: o resultado é desconhecido.
            pendingAttempt = savedState.get<String>(KEY_PENDING_KEY) != null,
            pendingHasPhoto = savedState.get<String>(KEY_PENDING_MEDIA_ID) != null,
        ),
    )

    val uiState: StateFlow<PublishUiState> = combine(content, session, photos.state, photos.error) { state, current, photo, photoError ->
        state.copy(signedIn = current is SessionState.SignedIn, photo = photo, photoError = photoError)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, content.value)

    init {
        // Ao sair do ecrã (não ao rodar), os ficheiros temporários deste rascunho já não são precisos.
        addCloseable { photos.clearFiles() }
    }

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

    // --- Fotografia (bloqueada durante o envio e numa tentativa por confirmar) ---

    fun startCapture(): String? = if (content.value.contentLocked) null else photos.startCapture()

    fun onCaptureResult(taken: Boolean) {
        // Cancelada: a fotografia já escolhida (e já enviada) mantém-se.
        if (taken) forgetUploadedPhoto()
        photos.onCaptureResult(taken)
    }

    fun onCaptureUnavailable() = photos.onCaptureUnavailable()

    fun onPicked(uri: String?) {
        if (content.value.contentLocked || uri == null) return
        forgetUploadedPhoto()
        photos.onPicked(uri)
    }

    fun removePhoto() {
        if (content.value.contentLocked) return
        forgetUploadedPhoto()
        photos.remove()
        // "Publicar sem fotografia" depois de um envio falhado: a mensagem desse envio deixa de se aplicar.
        content.update { if (it.message is PublishMessage.UploadFailed) it.copy(message = null) else it }
    }

    fun dismissPhotoError() = photos.dismissError()

    /** "Publicar" ou, com uma tentativa por confirmar, "Tentar novamente" (mesma chave, conteúdo e `mediaId`). */
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
        if (state.textProblem != null || state.starsMissing || state.photo == PhotoDraftState.Missing) {
            content.update { it.copy(showValidation = true) }
            return
        }
        val text = ExperienceRules.normalizeText(state.text)
        val stars = state.stars!!
        val photoName = photos.readyName
        if (photoName == null) {
            startAttempt(ExperienceDraft(placeId, offeringId, text, stars))
            return
        }
        val uploaded = savedState.get<String>(KEY_MEDIA_ID)?.takeIf { savedState.get<String>(KEY_MEDIA_FOR) == photoName }
        if (uploaded != null) {
            startAttempt(ExperienceDraft(placeId, offeringId, text, stars, uploaded))
            return
        }
        // Primeiro confirmar que o ficheiro ainda existe (senão passa a "fotografia em falta", com aviso).
        val file = if (photos.markMissingIfGone()) null else photos.readyFile()
        if (file == null) {
            content.update { it.copy(showValidation = true) }
            return
        }
        content.update { it.copy(sendPhase = SendPhase.UPLOADING_PHOTO, message = null) }
        viewModelScope.launch {
            when (val upload = repository.uploadPhoto(file)) {
                is UploadOutcome.Uploaded -> {
                    savedState[KEY_MEDIA_ID] = upload.mediaId
                    savedState[KEY_MEDIA_FOR] = photoName
                    startAttempt(ExperienceDraft(placeId, offeringId, text, stars, upload.mediaId))
                }
                is UploadOutcome.Failed -> content.update {
                    it.copy(sendPhase = null, message = PublishMessage.UploadFailed(upload.error, upload.retryAfterSeconds))
                }
            }
        }
    }

    /** Descarta a tentativa por confirmar (depois de o utilizador confirmar que quer recomeçar). */
    fun discardAttempt() {
        if (content.value.sending) return
        clearPending()
        // A tentativa anterior pode ter associado a fotografia: na próxima, é enviada de novo.
        forgetUploadedPhoto()
        content.update { it.copy(pendingAttempt = false, pendingHasPhoto = false, message = null) }
    }

    fun dismissMessage() = content.update { it.copy(message = null) }

    /** Nova tentativa lógica: chave e conteúdo (com o `mediaId`) guardados antes de enviar. */
    private fun startAttempt(draft: ExperienceDraft) {
        val key = newKey()
        savedState[KEY_PENDING_KEY] = key
        savedState[KEY_PENDING_CREATED_AT] = clock()
        savedState[KEY_PENDING_TEXT] = draft.text
        savedState[KEY_PENDING_STARS] = draft.stars
        draft.mediaId?.let { savedState[KEY_PENDING_MEDIA_ID] = it } ?: savedState.remove<String>(KEY_PENDING_MEDIA_ID)
        send(key, draft)
    }

    private fun send(key: String, draft: ExperienceDraft) {
        content.update { it.copy(sendPhase = SendPhase.PUBLISHING, message = null) }
        viewModelScope.launch {
            when (val outcome = repository.publish(key, draft)) {
                is PublishOutcome.Published -> {
                    clearPending()
                    forgetUploadedPhoto()
                    photos.clearFiles()
                    savedState.remove<String>(KEY_TEXT)
                    savedState.remove<Int>(KEY_STARS)
                    changes.notify(ExperienceChange(outcome.experience.id, outcome.experience.placeId, outcome.experience.authorId))
                    content.update { it.copy(sendPhase = null, pendingAttempt = false, pendingHasPhoto = false, publishedId = outcome.experience.id) }
                }
                PublishOutcome.Unconfirmed -> content.update {
                    it.copy(sendPhase = null, pendingAttempt = true, pendingHasPhoto = draft.mediaId != null)
                }
                is PublishOutcome.Rejected -> {
                    clearPending()
                    // A fotografia enviada continua pendente no servidor e é reaproveitada, salvo se já não servir.
                    if (outcome.reason == PublishRejection.MEDIA_NOT_USABLE) forgetUploadedPhoto()
                    content.update {
                        it.copy(sendPhase = null, pendingAttempt = false, pendingHasPhoto = false, message = PublishMessage.Rejected(outcome.reason, outcome.retryAfterSeconds))
                    }
                }
                is PublishOutcome.AlreadyPublished -> {
                    clearPending()
                    forgetUploadedPhoto()
                    // A experiência existe: as listas do local e do perfil devem mostrá-la.
                    val author = (session.value as? SessionState.SignedIn)?.userId.orEmpty()
                    outcome.experienceId?.let { changes.notify(ExperienceChange(it, placeId, author)) }
                    content.update { it.copy(sendPhase = null, pendingAttempt = false, pendingHasPhoto = false, message = PublishMessage.AlreadyPublished(outcome.experienceId)) }
                }
                PublishOutcome.PublishedThenDeleted -> {
                    clearPending()
                    forgetUploadedPhoto()
                    content.update { it.copy(sendPhase = null, pendingAttempt = false, pendingHasPhoto = false, message = PublishMessage.PublishedThenDeleted) }
                }
            }
        }
    }

    private fun pendingDraft() = ExperienceDraft(
        placeId,
        offeringId,
        savedState[KEY_PENDING_TEXT] ?: ExperienceRules.normalizeText(content.value.text),
        savedState[KEY_PENDING_STARS] ?: content.value.stars ?: 0,
        savedState[KEY_PENDING_MEDIA_ID],
    )

    private fun forgetUploadedPhoto() {
        savedState.remove<String>(KEY_MEDIA_ID)
        savedState.remove<String>(KEY_MEDIA_FOR)
    }

    private fun clearPending() {
        savedState.remove<String>(KEY_PENDING_KEY)
        savedState.remove<Long>(KEY_PENDING_CREATED_AT)
        savedState.remove<String>(KEY_PENDING_TEXT)
        savedState.remove<Int>(KEY_PENDING_STARS)
        savedState.remove<String>(KEY_PENDING_MEDIA_ID)
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
        const val KEY_PENDING_MEDIA_ID = "publish.pendingMediaId"

        /** Fotografia já enviada ([KEY_MEDIA_ID]) e o rascunho a que pertence ([KEY_MEDIA_FOR]). */
        const val KEY_MEDIA_ID = "publish.mediaId"
        const val KEY_MEDIA_FOR = "publish.mediaFor"

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
                    app.container.photoDrafts,
                )
            }
        }
    }
}
