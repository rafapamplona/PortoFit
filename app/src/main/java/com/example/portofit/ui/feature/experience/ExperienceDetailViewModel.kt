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
import com.example.portofit.data.model.CatalogStatus
import com.example.portofit.data.model.Experience
import com.example.portofit.data.model.ExperienceRules
import com.example.portofit.data.model.ExperienceTextProblem
import com.example.portofit.data.repository.CatalogRepository
import com.example.portofit.data.repository.DataError
import com.example.portofit.data.repository.DataResult
import com.example.portofit.data.repository.ExperienceChange
import com.example.portofit.data.repository.ExperienceChanges
import com.example.portofit.data.repository.ExperienceCounts
import com.example.portofit.data.repository.ExperiencesRepository
import com.example.portofit.data.repository.LikeError
import com.example.portofit.data.repository.LikeResult
import com.example.portofit.data.repository.MutationError
import com.example.portofit.data.repository.MutationResult
import com.example.portofit.data.repository.PhotoChange
import com.example.portofit.data.repository.UploadError
import com.example.portofit.data.repository.UploadOutcome
import com.example.portofit.data.photo.PhotoDrafts
import com.example.portofit.data.photo.PhotoError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

sealed interface ExperienceLoad {
    data object Loading : ExperienceLoad
    data class Error(val error: DataError) : ExperienceLoad
    data class Loaded(val experience: Experience) : ExperienceLoad
}

/**
 * Edição em curso: texto, estrelas e fotografia (a atividade não se altera). A fotografia nova (se houver) está no
 * [PhotoDraftController]; [removePhoto] pede para remover a atual.
 */
data class ExperienceEdit(val text: String, val stars: Int, val removePhoto: Boolean = false, val showValidation: Boolean = false) {
    val textProblem: ExperienceTextProblem? get() = ExperienceRules.textProblem(text)
    val textLength: Int get() = ExperienceRules.length(ExperienceRules.normalizeText(text))
}

/**
 * Botão de gosto, a partir do último estado **confirmado** pelo servidor (docs/experiences-api.md §13.3).
 * [UNKNOWN]: com sessão, mas `likedByMe` não veio (sessão por confirmar): mostra-se a contagem e "Atualizar", nunca
 * "Gostar" como se fosse false.
 */
enum class LikeButton { LOGIN_REQUIRED, OWN, UNKNOWN, LIKE, UNLIKE }

data class ExperienceDetailUiState(
    val load: ExperienceLoad = ExperienceLoad.Loading,
    /** Estado atual da atividade no catálogo; null se não foi possível saber (não se mostra nada). */
    val offeringStatus: CatalogStatus? = null,
    val isAuthor: Boolean = false,
    val edit: ExperienceEdit? = null,
    /** Fotografia nova escolhida na edição (preparada na app), e o seu aviso. */
    val editPhoto: PhotoDraftState = PhotoDraftState.None,
    val editPhotoError: PhotoError? = null,
    /** A enviar a fotografia nova (antes do PATCH). */
    val uploadingPhoto: Boolean = false,
    val uploadError: UploadError? = null,
    val saving: Boolean = false,
    val showDeleteConfirmation: Boolean = false,
    val deleting: Boolean = false,
    val actionError: MutationError? = null,
    /** Eliminada com sucesso: o ecrã regressa. */
    val deleted: Boolean = false,
    /** Sessão atual (para comentários: só o autor de cada um vê editar/eliminar). */
    val signedIn: Boolean = false,
    val likeButton: LikeButton = LikeButton.LOGIN_REQUIRED,
    /** Pedido de gosto em curso: o botão fica desativado (sem envios duplicados). */
    val likePending: Boolean = false,
    val likeError: LikeError? = null,
    val likeRetryAfterSeconds: Long? = null,
    /** A pedir de novo o detalhe para reconciliar o gosto com o servidor. */
    val refreshing: Boolean = false,
    val refreshFailed: Boolean = false,
) {
    val experience: Experience? get() = (load as? ExperienceLoad.Loaded)?.experience
}

/**
 * Detalhe de uma experiência (pública). Só o autor (sessão com o mesmo `userId`) vê "Editar" e "Eliminar";
 * o servidor decide sempre a autorização (403 para outra pessoa). A edição fica no SavedStateHandle.
 *
 * E3: gosto com o último estado confirmado (sem envios duplicados; um resultado desconhecido é reconciliado com
 * "Atualizar"), comentários num [CommentsController] próprio e uma visualização registada uma vez por abertura.
 * Entrar ou sair pede de novo o detalhe, mas nunca envia um gosto ou comentário sozinho.
 */
class ExperienceDetailViewModel(
    private val repository: ExperiencesRepository,
    private val catalog: CatalogRepository,
    private val changes: ExperienceChanges,
    private val session: StateFlow<SessionState>,
    private val savedState: SavedStateHandle,
    drafts: PhotoDrafts,
    clock: () -> Long = System::currentTimeMillis,
    newKey: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {

    private val experienceId: String = checkNotNull(savedState[EXPERIENCE_ID_ARG])

    /** Fotografia nova da edição (só nomes de ficheiros da app no SavedStateHandle). */
    val photos = PhotoDraftController(viewModelScope, drafts, savedState, "experience.edit")

    private val content = MutableStateFlow(
        ExperienceDetailUiState(
            edit = savedState.get<String>(KEY_EDIT_TEXT)?.let {
                ExperienceEdit(it, savedState[KEY_EDIT_STARS] ?: 1, removePhoto = savedState[KEY_EDIT_REMOVE_PHOTO] ?: false)
            },
        ),
    )

    val uiState: StateFlow<ExperienceDetailUiState> = combine(content, session, photos.state, photos.error) { state, current, photo, photoError ->
        val author = state.experience?.authorId
        val isAuthor = author != null && current is SessionState.SignedIn && current.userId == author
        state.copy(
            isAuthor = isAuthor,
            editPhoto = photo,
            editPhotoError = photoError,
            signedIn = current is SessionState.SignedIn,
            likeButton = when {
                current !is SessionState.SignedIn -> LikeButton.LOGIN_REQUIRED
                isAuthor -> LikeButton.OWN
                else -> when (state.experience?.likedByMe) {
                    true -> LikeButton.UNLIKE
                    false -> LikeButton.LIKE
                    null -> LikeButton.UNKNOWN
                }
            },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, content.value)

    /** Comentários: carregam e falham sem afetar o conteúdo da experiência. */
    val comments = CommentsController(
        scope = viewModelScope,
        repository = repository,
        savedState = savedState,
        experienceId = experienceId,
        currentUserId = { (session.value as? SessionState.SignedIn)?.userId },
        onChanged = { refresh(clearLikeError = false) },
        onExperienceGone = ::markGone,
        clock = clock,
        newKey = newKey,
    )

    /** Leituras silenciosas do detalhe: só a mais recente conta. */
    private var refreshGeneration = 0

    private val busy: Boolean get() = content.value.saving || content.value.uploadingPhoto

    init {
        load()
        comments.load()
        addCloseable { photos.clearFiles() }
        // Entrar ou sair (ex.: regresso do login): pede de novo o detalhe para saber likedByMe. Nada é enviado.
        viewModelScope.launch {
            session.map { (it as? SessionState.SignedIn)?.userId }.distinctUntilChanged().drop(1).collect {
                if (content.value.experience != null) refresh(clearLikeError = true)
            }
        }
    }

    fun retry() = load()

    // --- Gostos (docs/experiences-api.md §13.2) ---

    /** Gostar ou retirar, a partir do último estado confirmado. Visitantes vão para o login (no ecrã). */
    fun toggleLike() {
        val state = uiState.value
        val experience = state.experience ?: return
        val liked = experience.likedByMe ?: return
        if (state.likePending || state.likeButton !in setOf(LikeButton.LIKE, LikeButton.UNLIKE)) return
        val target = !liked
        content.update { it.copy(likePending = true, likeError = null, likeRetryAfterSeconds = null, refreshFailed = false) }
        viewModelScope.launch {
            when (val result = repository.setLiked(experienceId, target)) {
                LikeResult.Confirmed -> {
                    // Estado confirmado; a contagem exata vem da leitura seguinte.
                    refreshGeneration++
                    content.update { current ->
                        val shown = current.experience
                        if (shown == null) {
                            current.copy(likePending = false)
                        } else {
                            val delta = if (shown.likedByMe == target) 0 else if (target) 1 else -1
                            current.copy(
                                likePending = false,
                                load = ExperienceLoad.Loaded(shown.copy(likedByMe = target, likesCount = (shown.likesCount + delta).coerceAtLeast(0))),
                            )
                        }
                    }
                    content.value.experience?.let(::notifyCounts)
                    refresh(clearLikeError = false)
                }
                is LikeResult.Failed -> {
                    content.update { it.copy(likePending = false, likeError = result.error, likeRetryAfterSeconds = result.retryAfterSeconds) }
                    if (result.error == LikeError.EXPERIENCE_GONE) markGone()
                }
            }
        }
    }

    /** "Atualizar": pede o detalhe ao servidor (reconcilia o gosto depois de um resultado desconhecido). */
    fun refreshLike() = refresh(clearLikeError = true)

    private fun refresh(clearLikeError: Boolean) {
        val myGeneration = ++refreshGeneration
        content.update { it.copy(refreshing = true, refreshFailed = false) }
        viewModelScope.launch {
            val result = repository.get(experienceId)
            if (myGeneration != refreshGeneration) return@launch
            when (result) {
                is DataResult.Success -> {
                    content.update {
                        it.copy(
                            load = ExperienceLoad.Loaded(result.value),
                            refreshing = false,
                            likeError = if (clearLikeError) null else it.likeError,
                        )
                    }
                    notifyCounts(result.value)
                }
                is DataResult.Failure ->
                    if (result.error == DataError.NOT_FOUND) {
                        markGone()
                    } else {
                        // O último estado confirmado continua visível.
                        content.update { it.copy(refreshing = false, refreshFailed = true) }
                    }
            }
        }
    }

    /** A experiência foi eliminada: ações deixam de estar disponíveis e as listas são atualizadas. */
    private fun markGone() {
        val experience = content.value.experience
        refreshGeneration++
        content.update { it.copy(load = ExperienceLoad.Error(DataError.NOT_FOUND), edit = null, refreshing = false, likePending = false, likeError = null) }
        experience?.let { changes.notify(ExperienceChange(it.id, it.placeId, it.authorId)) }
    }

    private fun notifyCounts(experience: Experience) =
        changes.notifyCounts(ExperienceCounts(experience.id, experience.likesCount, experience.commentsCount))

    // --- Visualização (docs/experiences-api.md §13.5) ---

    /**
     * Uma vez por abertura do detalhe (marcada no SavedStateHandle antes do pedido: nem a rotação, nem a recriação,
     * nem uma falha a repetem). O autor não contribui. Não bloqueia o ecrã e uma falha não é mostrada.
     */
    private fun registerViewOnce(experience: Experience) {
        if (savedState.get<Boolean>(KEY_VIEW_REGISTERED) == true) return
        savedState[KEY_VIEW_REGISTERED] = true
        viewModelScope.launch {
            val current = session.first { it !is SessionState.Loading }
            if (current is SessionState.SignedIn && current.userId == experience.authorId) return@launch
            repository.registerView(experience.id)
        }
    }

    fun startEdit() {
        val experience = uiState.value.experience ?: return
        if (!uiState.value.isAuthor) return
        setEdit(ExperienceEdit(experience.text, experience.stars))
    }

    fun onEditText(text: String) {
        val edit = content.value.edit ?: return
        if (busy) return
        setEdit(edit.copy(text = text))
    }

    fun onEditStars(stars: Int) {
        val edit = content.value.edit ?: return
        if (busy || stars !in 1..5) return
        setEdit(edit.copy(stars = stars))
    }

    // --- Fotografia na edição ---

    fun startCapture(): String? = if (content.value.edit == null || busy) null else photos.startCapture()

    fun onCaptureResult(taken: Boolean) {
        if (taken) forgetUploadedPhoto()
        photos.onCaptureResult(taken)
        if (taken) content.value.edit?.let { setEdit(it.copy(removePhoto = false)) }
    }

    fun onCaptureUnavailable() = photos.onCaptureUnavailable()

    fun onPicked(uri: String?) {
        val edit = content.value.edit ?: return
        if (busy || uri == null) return
        forgetUploadedPhoto()
        photos.onPicked(uri)
        setEdit(edit.copy(removePhoto = false))
    }

    /** Com uma fotografia nova escolhida, descarta-a (fica a atual); senão, pede para remover a atual. */
    fun removePhoto() {
        val edit = content.value.edit ?: return
        if (busy) return
        if (photos.readyName != null || photos.state.value == PhotoDraftState.Missing) {
            forgetUploadedPhoto()
            photos.remove()
        } else {
            setEdit(edit.copy(removePhoto = true))
        }
        content.update { it.copy(uploadError = null) }
    }

    /** Desfaz "remover": a fotografia atual mantém-se. */
    fun keepCurrentPhoto() {
        val edit = content.value.edit ?: return
        if (busy) return
        setEdit(edit.copy(removePhoto = false))
    }

    fun dismissPhotoError() = photos.dismissError()

    fun cancelEdit() {
        if (busy) return
        setEdit(null)
        forgetUploadedPhoto()
        photos.clearFiles()
        content.update { it.copy(actionError = null, uploadError = null) }
    }

    fun saveEdit() {
        val state = uiState.value
        val edit = state.edit ?: return
        val experience = state.experience ?: return
        if (busy || !state.isAuthor || photos.busy) return
        if (edit.textProblem != null || state.editPhoto == PhotoDraftState.Missing) {
            setEdit(edit.copy(showValidation = true))
            return
        }
        val text = ExperienceRules.normalizeText(edit.text)
        // Só o que mudou; sem alterações não é preciso pedido.
        val newText = text.takeIf { it != experience.text }
        val newStars = edit.stars.takeIf { it != experience.stars }
        val newPhotoName = photos.readyName
        val removing = newPhotoName == null && edit.removePhoto && experience.photo != null
        if (newText == null && newStars == null && newPhotoName == null && !removing) {
            setEdit(null)
            return
        }
        content.update { it.copy(actionError = null, uploadError = null) }
        viewModelScope.launch {
            // A fotografia nova é enviada primeiro; só depois de sucesso o mediaId entra no PATCH.
            val photoChange: PhotoChange = when {
                newPhotoName != null -> uploadedMediaId(newPhotoName)?.let { PhotoChange.Replace(it) } ?: return@launch
                removing -> PhotoChange.Remove
                else -> PhotoChange.Keep
            }
            content.update { it.copy(saving = true) }
            when (val result = repository.edit(experienceId, newText, newStars, photoChange)) {
                is MutationResult.Success -> {
                    savedState.remove<String>(KEY_EDIT_TEXT)
                    savedState.remove<Int>(KEY_EDIT_STARS)
                    savedState.remove<Boolean>(KEY_EDIT_REMOVE_PHOTO)
                    forgetUploadedPhoto()
                    photos.clearFiles()
                    changes.notify(ExperienceChange(result.value.id, result.value.placeId, result.value.authorId))
                    content.update { it.copy(load = ExperienceLoad.Loaded(result.value), edit = null, saving = false) }
                }
                is MutationResult.Failure -> {
                    // O PATCH é idempotente: repetir é seguro. Uma fotografia que já não serve é enviada de novo.
                    if (result.error == MutationError.MEDIA_NOT_USABLE) forgetUploadedPhoto()
                    content.update { it.copy(saving = false, actionError = result.error) }
                }
            }
        }
    }

    /** mediaId da fotografia nova: reaproveitado se já foi enviada; senão envia-a. null = falhou (mensagem). */
    private suspend fun uploadedMediaId(photoName: String): String? {
        savedState.get<String>(KEY_EDIT_MEDIA_ID)?.takeIf { savedState.get<String>(KEY_EDIT_MEDIA_FOR) == photoName }?.let { return it }
        val file = if (photos.markMissingIfGone()) null else photos.readyFile()
        if (file == null) return null
        content.update { it.copy(uploadingPhoto = true) }
        val upload = repository.uploadPhoto(file)
        content.update { it.copy(uploadingPhoto = false) }
        return when (upload) {
            is UploadOutcome.Uploaded -> {
                savedState[KEY_EDIT_MEDIA_ID] = upload.mediaId
                savedState[KEY_EDIT_MEDIA_FOR] = photoName
                upload.mediaId
            }
            is UploadOutcome.Failed -> {
                content.update { it.copy(uploadError = upload.error) }
                null
            }
        }
    }

    private fun forgetUploadedPhoto() {
        savedState.remove<String>(KEY_EDIT_MEDIA_ID)
        savedState.remove<String>(KEY_EDIT_MEDIA_FOR)
    }

    fun requestDelete() {
        if (uiState.value.isAuthor && !content.value.deleting) content.update { it.copy(showDeleteConfirmation = true) }
    }

    fun dismissDelete() = content.update { it.copy(showDeleteConfirmation = false) }

    fun confirmDelete() {
        val experience = uiState.value.experience ?: return
        if (content.value.deleting || !uiState.value.isAuthor) return
        content.update { it.copy(showDeleteConfirmation = false, deleting = true, actionError = null) }
        viewModelScope.launch {
            when (val result = repository.delete(experienceId)) {
                is MutationResult.Success -> {
                    changes.notify(ExperienceChange(experience.id, experience.placeId, experience.authorId))
                    content.update { it.copy(deleting = false, deleted = true) }
                }
                is MutationResult.Failure -> content.update { it.copy(deleting = false, actionError = result.error) }
            }
        }
    }

    private fun setEdit(edit: ExperienceEdit?) {
        if (edit == null) {
            savedState.remove<String>(KEY_EDIT_TEXT)
            savedState.remove<Int>(KEY_EDIT_STARS)
            savedState.remove<Boolean>(KEY_EDIT_REMOVE_PHOTO)
        } else {
            savedState[KEY_EDIT_TEXT] = edit.text
            savedState[KEY_EDIT_STARS] = edit.stars
            savedState[KEY_EDIT_REMOVE_PHOTO] = edit.removePhoto
        }
        content.update { it.copy(edit = edit) }
    }

    private fun load() {
        refreshGeneration++
        content.update { it.copy(load = ExperienceLoad.Loading) }
        viewModelScope.launch {
            when (val result = repository.get(experienceId)) {
                is DataResult.Success -> {
                    content.update { it.copy(load = ExperienceLoad.Loaded(result.value)) }
                    notifyCounts(result.value)
                    registerViewOnce(result.value)
                    // Estado atual da atividade (complemento): uma falha não afeta o detalhe.
                    val offering = catalog.getOffering(result.value.offeringId)
                    content.update { it.copy(offeringStatus = (offering as? DataResult.Success)?.value?.status) }
                }
                is DataResult.Failure -> content.update { it.copy(load = ExperienceLoad.Error(result.error)) }
            }
        }
    }

    companion object {
        const val EXPERIENCE_ID_ARG = "experienceId"
        const val KEY_EDIT_TEXT = "experience.editText"
        const val KEY_EDIT_STARS = "experience.editStars"
        const val KEY_EDIT_REMOVE_PHOTO = "experience.editRemovePhoto"
        const val KEY_EDIT_MEDIA_ID = "experience.editMediaId"
        const val KEY_EDIT_MEDIA_FOR = "experience.editMediaFor"
        const val KEY_VIEW_REGISTERED = "experience.viewRegistered"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PortoFitApplication
                ExperienceDetailViewModel(
                    app.container.experiencesRepository,
                    app.container.catalogRepository,
                    app.container.experienceChanges,
                    app.container.sessionManager.state,
                    createSavedStateHandle(),
                    app.container.photoDrafts,
                )
            }
        }
    }
}
