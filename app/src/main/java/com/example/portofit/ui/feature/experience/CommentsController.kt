package com.example.portofit.ui.feature.experience

import androidx.lifecycle.SavedStateHandle
import com.example.portofit.data.model.Comment
import com.example.portofit.data.model.ExperienceRules
import com.example.portofit.data.model.ExperienceTextProblem
import com.example.portofit.data.repository.CommentOutcome
import com.example.portofit.data.repository.CommentRejection
import com.example.portofit.data.repository.DataError
import com.example.portofit.data.repository.DataResult
import com.example.portofit.data.repository.ExperiencesRepository
import com.example.portofit.data.repository.MutationError
import com.example.portofit.data.repository.MutationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface CommentListState {
    data object Loading : CommentListState
    data class Error(val error: DataError) : CommentListState
    data class Loaded(
        val items: List<Comment>,
        val page: Int,
        val totalItems: Long,
        val hasMore: Boolean,
        val isLoadingMore: Boolean = false,
        val loadMoreError: DataError? = null,
    ) : CommentListState
}

/** Resultado a explicar depois de publicar um comentário. */
sealed interface CommentMessage {
    data object Posted : CommentMessage

    /** Não foi publicado (nada ficou gravado); o texto mantém-se. */
    data class Rejected(val reason: CommentRejection, val retryAfterSeconds: Long? = null) : CommentMessage

    /** A tentativa já tinha sido aceite com outro texto (409 DIFFERENT_CONTENT). */
    data object AlreadyPosted : CommentMessage

    /** Foi publicado e entretanto eliminado (409 COMMENT_DELETED). */
    data object PostedThenDeleted : CommentMessage

    /** A tentativa por confirmar tem mais de 6 dias: a chave já não é reutilizada. */
    data object AttemptTooOld : CommentMessage
}

data class CommentComposer(
    val text: String = "",
    val showValidation: Boolean = false,
    val sending: Boolean = false,
    /** Tentativa de resultado desconhecido: o texto fica bloqueado; só se pode repetir (mesma chave) ou descartar. */
    val pendingAttempt: Boolean = false,
    val message: CommentMessage? = null,
) {
    val textProblem: ExperienceTextProblem? get() = ExperienceRules.textProblem(text, ExperienceRules.MAX_COMMENT_LENGTH)
    val textLength: Int get() = ExperienceRules.length(ExperienceRules.normalizeText(text))
    val locked: Boolean get() = sending || pendingAttempt
}

/** Edição de um comentário próprio (só um de cada vez). */
data class CommentEdit(
    val commentId: String,
    val text: String,
    val showValidation: Boolean = false,
    val saving: Boolean = false,
    val error: MutationError? = null,
) {
    val textProblem: ExperienceTextProblem? get() = ExperienceRules.textProblem(text, ExperienceRules.MAX_COMMENT_LENGTH)
    val textLength: Int get() = ExperienceRules.length(ExperienceRules.normalizeText(text))
}

data class CommentsUiState(
    val list: CommentListState = CommentListState.Loading,
    val composer: CommentComposer = CommentComposer(),
    val edit: CommentEdit? = null,
    /** Comentário cuja eliminação aguarda confirmação. */
    val confirmDeleteId: String? = null,
    val deletingId: String? = null,
    val deleteError: MutationError? = null,
)

/**
 * Comentários do detalhe (docs/experiences-api.md §13.1), com estados próprios: uma falha aqui nunca esconde o
 * texto nem a fotografia da experiência.
 *
 * - Lista paginada, mais antigos primeiro; cada carregamento tem uma geração (respostas antigas são ignoradas).
 * - Publicar: uma `Idempotency-Key` por tentativa lógica, guardada com o texto no SavedStateHandle **antes** de
 *   enviar. Resultado desconhecido → texto e chave bloqueados; "Tentar novamente" repete o mesmo pedido. Nunca há
 *   um envio automático. Recusa definitiva → chave descartada, texto mantido para corrigir.
 * - Editar e eliminar só para o autor do comentário (o servidor decide sempre: 403 para outra pessoa).
 */
class CommentsController(
    private val scope: CoroutineScope,
    private val repository: ExperiencesRepository,
    private val savedState: SavedStateHandle,
    private val experienceId: String,
    /** userId da sessão, ou null (visitante). */
    private val currentUserId: () -> String?,
    /** Os comentários mudaram: o detalhe pede as contagens atualizadas. */
    private val onChanged: () -> Unit,
    /** O servidor indicou que a experiência já não existe. */
    private val onExperienceGone: () -> Unit,
    private val clock: () -> Long,
    private val newKey: () -> String,
    private val pageSize: Int = PAGE_SIZE,
) {
    private val _state = MutableStateFlow(
        CommentsUiState(
            composer = CommentComposer(text = savedState[KEY_TEXT] ?: "", pendingAttempt = savedState.get<String>(KEY_PENDING_KEY) != null),
            edit = savedState.get<String>(KEY_EDIT_ID)?.let { CommentEdit(it, savedState[KEY_EDIT_TEXT] ?: "") },
        ),
    )
    val state: StateFlow<CommentsUiState> = _state.asStateFlow()

    private var generation = 0
    private var job: Job? = null

    // --- Lista ---

    /** Primeira página; [silent] mantém a lista atual visível até chegar a resposta (e se ela falhar). */
    fun load(silent: Boolean = false) {
        job?.cancel()
        val myGeneration = ++generation
        val previous = _state.value.list as? CommentListState.Loaded
        if (!silent || previous == null) _state.update { it.copy(list = CommentListState.Loading) }
        job = scope.launch {
            val result = repository.comments(experienceId, 0, pageSize)
            if (myGeneration != generation) return@launch
            when (result) {
                is DataResult.Success -> _state.update {
                    it.copy(list = CommentListState.Loaded(result.value.items, result.value.page, result.value.totalItems, result.value.hasMore))
                }
                is DataResult.Failure -> {
                    if (result.error == DataError.NOT_FOUND) onExperienceGone()
                    if (!silent || previous == null) _state.update { it.copy(list = CommentListState.Error(result.error)) }
                }
            }
        }
    }

    fun loadMore() {
        val current = _state.value.list as? CommentListState.Loaded ?: return
        if (!current.hasMore || current.isLoadingMore) return
        val myGeneration = generation
        setList(current.copy(isLoadingMore = true, loadMoreError = null))
        job = scope.launch {
            val result = repository.comments(experienceId, current.page + 1, pageSize)
            if (myGeneration != generation) return@launch
            _state.update { state ->
                val loaded = state.list as? CommentListState.Loaded ?: return@update state
                state.copy(
                    list = when (result) {
                        // Sem repetições pelo id (paginação por offset).
                        is DataResult.Success -> loaded.copy(
                            items = (loaded.items + result.value.items).distinctBy { it.id },
                            page = result.value.page,
                            totalItems = result.value.totalItems,
                            hasMore = result.value.hasMore,
                            isLoadingMore = false,
                        )
                        is DataResult.Failure -> loaded.copy(isLoadingMore = false, loadMoreError = result.error)
                    },
                )
            }
            if (result is DataResult.Failure && result.error == DataError.NOT_FOUND) onExperienceGone()
        }
    }

    // --- Publicar ---

    fun onTextChange(text: String) {
        if (_state.value.composer.locked) return
        savedState[KEY_TEXT] = text
        updateComposer { it.copy(text = text, message = null) }
    }

    /** "Publicar" ou, com uma tentativa por confirmar, "Tentar novamente" (mesma chave e texto). Exige sessão. */
    fun submit() {
        val composer = _state.value.composer
        if (composer.sending || currentUserId() == null) return
        val pendingKey = savedState.get<String>(KEY_PENDING_KEY)
        if (pendingKey != null) {
            val createdAt = savedState.get<Long>(KEY_PENDING_CREATED_AT) ?: 0L
            if (clock() - createdAt > MAX_KEY_AGE_MILLIS) {
                updateComposer { it.copy(message = CommentMessage.AttemptTooOld) }
                return
            }
            send(pendingKey, savedState[KEY_PENDING_TEXT] ?: ExperienceRules.normalizeText(composer.text))
            return
        }
        if (composer.textProblem != null) {
            updateComposer { it.copy(showValidation = true) }
            return
        }
        val text = ExperienceRules.normalizeText(composer.text)
        val key = newKey()
        savedState[KEY_PENDING_KEY] = key
        savedState[KEY_PENDING_CREATED_AT] = clock()
        savedState[KEY_PENDING_TEXT] = text
        send(key, text)
    }

    /** Descarta a tentativa por confirmar (depois de o utilizador confirmar): o texto volta a poder ser editado. */
    fun discardAttempt() {
        if (_state.value.composer.sending) return
        clearPending()
        updateComposer { it.copy(pendingAttempt = false, message = null) }
    }

    fun dismissMessage() = updateComposer { it.copy(message = null) }

    private fun send(key: String, text: String) {
        updateComposer { it.copy(sending = true, message = null) }
        scope.launch {
            when (val outcome = repository.postComment(key, experienceId, text)) {
                is CommentOutcome.Posted -> {
                    clearPending()
                    savedState.remove<String>(KEY_TEXT)
                    updateComposer { CommentComposer(message = CommentMessage.Posted) }
                    load(silent = true)
                    onChanged()
                }
                CommentOutcome.Unconfirmed -> updateComposer { it.copy(sending = false, pendingAttempt = true) }
                is CommentOutcome.Rejected -> {
                    clearPending()
                    updateComposer { it.copy(sending = false, pendingAttempt = false, message = CommentMessage.Rejected(outcome.reason, outcome.retryAfterSeconds)) }
                    if (outcome.reason == CommentRejection.EXPERIENCE_GONE) onExperienceGone()
                }
                is CommentOutcome.AlreadyPosted -> {
                    clearPending()
                    updateComposer { it.copy(sending = false, pendingAttempt = false, message = CommentMessage.AlreadyPosted) }
                    load(silent = true)
                    onChanged()
                }
                CommentOutcome.PostedThenDeleted -> {
                    clearPending()
                    updateComposer { it.copy(sending = false, pendingAttempt = false, message = CommentMessage.PostedThenDeleted) }
                }
            }
        }
    }

    // --- Editar (só o autor do comentário) ---

    fun canModify(comment: Comment): Boolean = comment.authorId == currentUserId()

    fun startEdit(comment: Comment) {
        if (!canModify(comment) || _state.value.edit?.saving == true) return
        setEdit(CommentEdit(comment.id, comment.text))
    }

    fun onEditText(text: String) {
        val edit = _state.value.edit ?: return
        if (edit.saving) return
        setEdit(edit.copy(text = text, error = null))
    }

    fun cancelEdit() {
        if (_state.value.edit?.saving == true) return
        setEdit(null)
    }

    fun saveEdit() {
        val edit = _state.value.edit ?: return
        if (edit.saving) return
        if (edit.textProblem != null) {
            setEdit(edit.copy(showValidation = true))
            return
        }
        val text = ExperienceRules.normalizeText(edit.text)
        val original = (_state.value.list as? CommentListState.Loaded)?.items?.firstOrNull { it.id == edit.commentId }
        if (original != null && original.text == text) {
            setEdit(null)
            return
        }
        setEdit(edit.copy(saving = true, error = null))
        scope.launch {
            when (val result = repository.editComment(edit.commentId, text)) {
                is MutationResult.Success -> {
                    replaceItem(result.value)
                    setEdit(null)
                }
                is MutationResult.Failure -> {
                    if (result.error == MutationError.NOT_FOUND) {
                        // Já não existe (eliminado noutro sítio, ou a experiência foi eliminada).
                        setEdit(null)
                        _state.update { it.copy(deleteError = MutationError.NOT_FOUND) }
                        removeItem(edit.commentId)
                        load(silent = true)
                        onChanged()
                    } else {
                        // O PATCH é idempotente: repetir é seguro; o texto editado mantém-se.
                        _state.value.edit?.let { setEdit(it.copy(saving = false, error = result.error)) }
                    }
                }
            }
        }
    }

    // --- Eliminar (só o autor do comentário, com confirmação) ---

    fun requestDelete(comment: Comment) {
        if (canModify(comment) && _state.value.deletingId == null) _state.update { it.copy(confirmDeleteId = comment.id, deleteError = null) }
    }

    fun dismissDelete() = _state.update { it.copy(confirmDeleteId = null) }

    fun confirmDelete() {
        val id = _state.value.confirmDeleteId ?: return
        if (_state.value.deletingId != null) return
        _state.update { it.copy(confirmDeleteId = null, deletingId = id, deleteError = null) }
        scope.launch {
            val result = repository.deleteComment(id)
            _state.update { it.copy(deletingId = null) }
            when {
                result is MutationResult.Success || (result as MutationResult.Failure).error == MutationError.NOT_FOUND -> {
                    removeItem(id)
                    if (_state.value.edit?.commentId == id) setEdit(null)
                    load(silent = true)
                    onChanged()
                }
                else -> _state.update { it.copy(deleteError = result.error) }
            }
        }
    }

    fun dismissDeleteError() = _state.update { it.copy(deleteError = null) }

    private fun replaceItem(comment: Comment) = updateLoaded { it.copy(items = it.items.map { item -> if (item.id == comment.id) comment else item }) }

    private fun removeItem(id: String) = updateLoaded { loaded ->
        if (loaded.items.none { it.id == id }) loaded else loaded.copy(items = loaded.items.filterNot { it.id == id }, totalItems = (loaded.totalItems - 1).coerceAtLeast(0))
    }

    private fun updateLoaded(transform: (CommentListState.Loaded) -> CommentListState.Loaded) =
        _state.update { state -> (state.list as? CommentListState.Loaded)?.let { state.copy(list = transform(it)) } ?: state }

    private fun setList(list: CommentListState) = _state.update { it.copy(list = list) }

    private fun updateComposer(transform: (CommentComposer) -> CommentComposer) = _state.update { it.copy(composer = transform(it.composer)) }

    private fun setEdit(edit: CommentEdit?) {
        if (edit == null) {
            savedState.remove<String>(KEY_EDIT_ID)
            savedState.remove<String>(KEY_EDIT_TEXT)
        } else {
            savedState[KEY_EDIT_ID] = edit.commentId
            savedState[KEY_EDIT_TEXT] = edit.text
        }
        _state.update { it.copy(edit = edit) }
    }

    private fun clearPending() {
        savedState.remove<String>(KEY_PENDING_KEY)
        savedState.remove<Long>(KEY_PENDING_CREATED_AT)
        savedState.remove<String>(KEY_PENDING_TEXT)
    }

    companion object {
        const val PAGE_SIZE = 20
        const val KEY_TEXT = "comment.text"
        const val KEY_PENDING_KEY = "comment.pendingKey"
        const val KEY_PENDING_CREATED_AT = "comment.pendingCreatedAt"
        const val KEY_PENDING_TEXT = "comment.pendingText"
        const val KEY_EDIT_ID = "comment.editId"
        const val KEY_EDIT_TEXT = "comment.editText"

        /** O servidor guarda as chaves 7 dias; a app não reutiliza chaves com mais de 6. */
        const val MAX_KEY_AGE_MILLIS = PublishExperienceViewModel.MAX_KEY_AGE_MILLIS
    }
}
