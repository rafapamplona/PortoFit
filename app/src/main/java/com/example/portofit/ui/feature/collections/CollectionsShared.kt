package com.example.portofit.ui.feature.collections

import androidx.lifecycle.SavedStateHandle
import com.example.portofit.data.auth.SessionState
import com.example.portofit.data.model.CollectionRules
import com.example.portofit.data.model.UserCollection
import com.example.portofit.data.repository.CollectionError
import com.example.portofit.data.repository.CollectionsRepository
import com.example.portofit.data.repository.CreateCollectionOutcome
import com.example.portofit.data.repository.DataError
import com.example.portofit.data.repository.SessionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Chave da conta em sessão: null enquanto a sessão é lida; [VISITOR] sem sessão; senão o `userId`. */
internal const val VISITOR = "\u0000visitante"

internal fun sessionKey(state: SessionState): String? = when (state) {
    SessionState.Loading -> null
    is SessionState.SignedOut -> VISITOR
    is SessionState.SignedIn -> state.userId
}

sealed interface CollectionsListState {
    data object Loading : CollectionsListState
    data class Error(val error: DataError) : CollectionsListState
    data object SessionEnded : CollectionsListState
    data object SessionUnverified : CollectionsListState
    data class Loaded(
        val items: List<UserCollection>,
        val page: Int,
        val totalItems: Long,
        val hasMore: Boolean,
        val isLoadingMore: Boolean = false,
        val loadMoreError: DataError? = null,
    ) : CollectionsListState
}

/**
 * As minhas listas, paginadas (mais recentes primeiro), dentro de um ViewModel. Cada carregamento tem uma geração:
 * respostas antigas (incluindo de outra conta, depois de [clear]) são ignoradas.
 */
class CollectionListLoader(private val scope: CoroutineScope, private val repository: CollectionsRepository, private val pageSize: Int = PAGE_SIZE) {
    private val _state = MutableStateFlow<CollectionsListState>(CollectionsListState.Loading)
    val state: StateFlow<CollectionsListState> = _state.asStateFlow()

    private var generation = 0
    private var job: Job? = null

    fun load() {
        job?.cancel()
        val myGeneration = ++generation
        _state.value = CollectionsListState.Loading
        job = scope.launch {
            val result = repository.list(0, pageSize)
            if (myGeneration != generation) return@launch
            _state.value = when (result) {
                is SessionResult.Success -> CollectionsListState.Loaded(result.value.items, result.value.page, result.value.totalItems, result.value.hasMore)
                is SessionResult.Failure -> CollectionsListState.Error(result.error)
                SessionResult.SessionEnded -> CollectionsListState.SessionEnded
                SessionResult.SessionUnverified -> CollectionsListState.SessionUnverified
            }
        }
    }

    fun loadMore() {
        val current = _state.value as? CollectionsListState.Loaded ?: return
        if (!current.hasMore || current.isLoadingMore) return
        val myGeneration = generation
        _state.value = current.copy(isLoadingMore = true, loadMoreError = null)
        job = scope.launch {
            val result = repository.list(current.page + 1, pageSize)
            if (myGeneration != generation) return@launch
            _state.update { state ->
                val loaded = state as? CollectionsListState.Loaded ?: return@update state
                when (result) {
                    is SessionResult.Success -> loaded.copy(
                        items = (loaded.items + result.value.items).distinctBy { it.id },
                        page = result.value.page,
                        totalItems = result.value.totalItems,
                        hasMore = result.value.hasMore,
                        isLoadingMore = false,
                    )
                    is SessionResult.Failure -> loaded.copy(isLoadingMore = false, loadMoreError = result.error)
                    SessionResult.SessionEnded -> CollectionsListState.SessionEnded
                    SessionResult.SessionUnverified -> loaded.copy(isLoadingMore = false, loadMoreError = DataError.NETWORK)
                }
            }
        }
    }

    /** Sem dados (visitante ou troca de conta): nada da conta anterior fica visível, nem chega depois. */
    fun clear() {
        job?.cancel()
        generation++
        _state.value = CollectionsListState.Loading
    }

    /** Lista criada: entra no topo (mais recentes primeiro), sem recarregar. */
    fun prepend(collection: UserCollection) = updateLoaded { loaded ->
        if (loaded.items.any { it.id == collection.id }) loaded else loaded.copy(items = listOf(collection) + loaded.items, totalItems = loaded.totalItems + 1)
    }

    fun replace(collection: UserCollection) = updateLoaded { loaded -> loaded.copy(items = loaded.items.map { if (it.id == collection.id) collection else it }) }

    fun remove(collectionId: String) = updateLoaded { loaded ->
        if (loaded.items.none { it.id == collectionId }) loaded else loaded.copy(items = loaded.items.filterNot { it.id == collectionId }, totalItems = (loaded.totalItems - 1).coerceAtLeast(0))
    }

    fun contains(collectionId: String): Boolean = (_state.value as? CollectionsListState.Loaded)?.items?.any { it.id == collectionId } == true

    private fun updateLoaded(transform: (CollectionsListState.Loaded) -> CollectionsListState.Loaded) =
        _state.update { (it as? CollectionsListState.Loaded)?.let(transform) ?: it }

    companion object {
        const val PAGE_SIZE = 20
    }
}

/** Mensagem depois de uma tentativa de criar uma lista. */
sealed interface CreatorMessage {
    data class Rejected(val error: CollectionError) : CreatorMessage
    data object AlreadyCreated : CreatorMessage
    data object CreatedThenDeleted : CreatorMessage
    data object AttemptTooOld : CreatorMessage
}

data class CreatorState(
    val open: Boolean = false,
    val name: String = "",
    val showValidation: Boolean = false,
    val sending: Boolean = false,
    /** Tentativa de resultado desconhecido: nome e chave bloqueados; só repetir (mesma chave) ou descartar. */
    val pendingAttempt: Boolean = false,
    val message: CreatorMessage? = null,
) {
    val nameValid: Boolean get() = CollectionRules.cleanName(name) != null
    val nameLength: Int get() = CollectionRules.length(name)
    val locked: Boolean get() = sending || pendingAttempt
}

/**
 * Criar uma lista com nome personalizado (docs/collections-api.md §5.2): uma `Idempotency-Key` por tentativa lógica,
 * guardada com o nome no SavedStateHandle **antes** de enviar (sobrevive à rotação e à recriação). Resultado desconhecido
 * → nome e chave bloqueados; "Tentar novamente" repete o mesmo pedido; nunca há um envio automático nem a mesma chave
 * com outro nome. Recusa definitiva → chave descartada, nome mantido.
 */
class CollectionCreator(
    private val scope: CoroutineScope,
    private val repository: CollectionsRepository,
    private val savedState: SavedStateHandle,
    private val prefix: String,
    private val clock: () -> Long,
    private val newKey: () -> String,
    private val onCreated: (UserCollection) -> Unit,
    /** A chave já tinha criado outra lista: as listas devem ser pedidas de novo. */
    private val onStale: () -> Unit,
) {
    private val keyName = "$prefix.pendingKey"
    private val keyCreatedAt = "$prefix.pendingCreatedAt"
    private val keyPendingName = "$prefix.pendingName"
    private val keyDraft = "$prefix.draftName"
    private val keyOpen = "$prefix.open"

    private val _state = MutableStateFlow(
        CreatorState(
            open = savedState[keyOpen] ?: false,
            name = savedState[keyDraft] ?: "",
            pendingAttempt = savedState.get<String>(keyName) != null,
        ),
    )
    val state: StateFlow<CreatorState> = _state.asStateFlow()

    fun open() {
        savedState[keyOpen] = true
        _state.update { it.copy(open = true) }
    }

    /** Fecha o diálogo; uma tentativa por confirmar continua guardada (reaparece ao abrir). */
    fun close() {
        if (_state.value.sending) return
        savedState[keyOpen] = false
        _state.update { it.copy(open = false, message = null, showValidation = false) }
    }

    fun onNameChange(name: String) {
        if (_state.value.locked) return
        savedState[keyDraft] = name
        _state.update { it.copy(name = name, message = null) }
    }

    fun submit() {
        val state = _state.value
        if (state.sending) return
        val pendingKey = savedState.get<String>(keyName)
        if (pendingKey != null) {
            if (clock() - (savedState.get<Long>(keyCreatedAt) ?: 0L) > MAX_KEY_AGE_MILLIS) {
                _state.update { it.copy(message = CreatorMessage.AttemptTooOld) }
                return
            }
            send(pendingKey, savedState[keyPendingName] ?: CollectionRules.cleanName(state.name).orEmpty())
            return
        }
        val name = CollectionRules.cleanName(state.name)
        if (name == null) {
            _state.update { it.copy(showValidation = true) }
            return
        }
        val key = newKey()
        savedState[keyName] = key
        savedState[keyCreatedAt] = clock()
        savedState[keyPendingName] = name
        send(key, name)
    }

    /** Descarta a tentativa por confirmar (depois de o utilizador confirmar): o nome volta a poder ser editado. */
    fun discardAttempt() {
        if (_state.value.sending) return
        clearPending()
        _state.update { it.copy(pendingAttempt = false, message = null) }
    }

    /** Troca de conta ou fim de sessão: a tentativa e o nome pertenciam à conta anterior. */
    fun reset() {
        clearPending()
        savedState.remove<String>(keyDraft)
        savedState[keyOpen] = false
        _state.value = CreatorState()
    }

    private fun send(key: String, name: String) {
        _state.update { it.copy(sending = true, message = null) }
        scope.launch {
            when (val outcome = repository.create(key, name)) {
                is CreateCollectionOutcome.Created -> {
                    clearPending()
                    savedState.remove<String>(keyDraft)
                    savedState[keyOpen] = false
                    _state.value = CreatorState()
                    onCreated(outcome.collection)
                }
                CreateCollectionOutcome.Unconfirmed -> _state.update { it.copy(sending = false, pendingAttempt = true) }
                is CreateCollectionOutcome.Rejected -> {
                    clearPending()
                    _state.update { it.copy(sending = false, pendingAttempt = false, message = CreatorMessage.Rejected(outcome.error)) }
                }
                is CreateCollectionOutcome.AlreadyCreated -> {
                    clearPending()
                    _state.update { it.copy(sending = false, pendingAttempt = false, message = CreatorMessage.AlreadyCreated) }
                    onStale()
                }
                CreateCollectionOutcome.CreatedThenDeleted -> {
                    clearPending()
                    _state.update { it.copy(sending = false, pendingAttempt = false, message = CreatorMessage.CreatedThenDeleted) }
                }
            }
        }
    }

    private fun clearPending() {
        savedState.remove<String>(keyName)
        savedState.remove<Long>(keyCreatedAt)
        savedState.remove<String>(keyPendingName)
    }

    companion object {
        /** O servidor guarda as chaves 7 dias; a app não reutiliza chaves com mais de 6. */
        const val MAX_KEY_AGE_MILLIS = 6L * 24 * 60 * 60 * 1000
    }
}
