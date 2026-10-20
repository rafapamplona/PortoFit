package com.example.portofit.ui.feature.collections

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.portofit.PortoFitApplication
import com.example.portofit.data.auth.SessionState
import com.example.portofit.data.model.CollectionRules
import com.example.portofit.data.model.SavedItem
import com.example.portofit.data.model.UserCollection
import com.example.portofit.data.repository.CollectionChanges
import com.example.portofit.data.repository.CollectionError
import com.example.portofit.data.repository.CollectionResult
import com.example.portofit.data.repository.CollectionsRepository
import com.example.portofit.data.repository.DataError
import com.example.portofit.data.repository.ExperienceChanges
import com.example.portofit.data.repository.ExperienceCounts
import com.example.portofit.data.repository.SessionResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

// ------------------------------------------------------------------ As minhas listas

data class RenameState(val collectionId: String, val name: String, val saving: Boolean = false, val showValidation: Boolean = false, val error: CollectionError? = null) {
    val nameValid: Boolean get() = CollectionRules.cleanName(name) != null
}

data class CollectionsUiState(
    /** null enquanto a sessão é lida; false = visitante (convite para entrar). */
    val signedIn: Boolean? = null,
    val rename: RenameState? = null,
    val confirmDelete: UserCollection? = null,
    val deletingId: String? = null,
    /** Erro da última eliminação (a lista mantém-se). */
    val deleteError: CollectionError? = null,
)

/**
 * As minhas listas (docs/collections-api.md §5): paginadas, criar, renomear e eliminar (com confirmação). Privadas: ao
 * terminar sessão ou trocar de conta, tudo é apagado logo e respostas tardias são ignoradas.
 */
class CollectionsViewModel(
    private val repository: CollectionsRepository,
    private val changes: CollectionChanges,
    session: StateFlow<SessionState>,
    savedState: SavedStateHandle,
    clock: () -> Long = System::currentTimeMillis,
    newKey: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {

    private val loader = CollectionListLoader(viewModelScope, repository)
    val lists: StateFlow<CollectionsListState> = loader.state

    val creator = CollectionCreator(
        viewModelScope, repository, savedState, "collections.create", clock, newKey,
        onCreated = { created ->
            loader.prepend(created)
            changes.notify(created.id)
        },
        onStale = { loader.load() },
    )

    private val _uiState = MutableStateFlow(CollectionsUiState())
    val uiState: StateFlow<CollectionsUiState> = _uiState.asStateFlow()

    private var account: String? = null
    private var actionGeneration = 0

    init {
        viewModelScope.launch {
            session.map { sessionKey(it) }.filterNotNull().distinctUntilChanged().collect { key ->
                val previous = account
                account = key.takeUnless { it == VISITOR }
                actionGeneration++
                loader.clear()
                // A tentativa de criação pertencia à conta anterior.
                if (previous != null && previous != account) creator.reset()
                _uiState.value = CollectionsUiState(signedIn = account != null)
                if (account != null) loader.load()
            }
        }
        // Uma lista alterada noutro ecrã (itens guardados ou removidos): atualiza-a sem recarregar as páginas.
        viewModelScope.launch {
            changes.events.collect { id ->
                val requestAccount = account ?: return@collect
                if (!loader.contains(id)) return@collect
                when (val result = repository.get(id)) {
                    is SessionResult.Success -> if (requestAccount == account) loader.replace(result.value)
                    is SessionResult.Failure -> if (result.error == DataError.NOT_FOUND && requestAccount == account) loader.remove(id)
                    else -> Unit
                }
            }
        }
    }

    fun retry() = loader.load()

    fun loadMore() = loader.loadMore()

    // --- Renomear ---

    fun startRename(collection: UserCollection) = _uiState.update { it.copy(rename = RenameState(collection.id, collection.name)) }

    fun onRenameText(name: String) = _uiState.update { state ->
        state.rename?.takeUnless { it.saving }?.let { state.copy(rename = it.copy(name = name, error = null)) } ?: state
    }

    fun cancelRename() = _uiState.update { if (it.rename?.saving == true) it else it.copy(rename = null) }

    fun saveRename() {
        val rename = _uiState.value.rename ?: return
        if (rename.saving) return
        val name = CollectionRules.cleanName(rename.name)
        if (name == null) {
            _uiState.update { it.copy(rename = rename.copy(showValidation = true)) }
            return
        }
        val requestAccount = account ?: return
        val myGeneration = actionGeneration
        _uiState.update { it.copy(rename = rename.copy(saving = true, error = null)) }
        viewModelScope.launch {
            val result = repository.rename(rename.collectionId, name)
            if (myGeneration != actionGeneration || requestAccount != account) return@launch
            when (result) {
                is CollectionResult.Success -> {
                    loader.replace(result.value)
                    changes.notify(result.value.id)
                    _uiState.update { it.copy(rename = null) }
                }
                is CollectionResult.Failure -> {
                    if (result.error == CollectionError.COLLECTION_GONE) loader.remove(rename.collectionId)
                    _uiState.update { state ->
                        // O nome escrito mantém-se para corrigir ou repetir (o PATCH é idempotente).
                        if (result.error == CollectionError.COLLECTION_GONE) state.copy(rename = null, deleteError = result.error)
                        else state.copy(rename = state.rename?.copy(saving = false, error = result.error))
                    }
                }
            }
        }
    }

    // --- Eliminar (com confirmação; as experiências não são eliminadas) ---

    fun requestDelete(collection: UserCollection) {
        if (_uiState.value.deletingId == null) _uiState.update { it.copy(confirmDelete = collection, deleteError = null) }
    }

    fun dismissDelete() = _uiState.update { it.copy(confirmDelete = null) }

    fun confirmDelete() {
        val target = _uiState.value.confirmDelete ?: return
        if (_uiState.value.deletingId != null) return
        val requestAccount = account ?: return
        val myGeneration = actionGeneration
        _uiState.update { it.copy(confirmDelete = null, deletingId = target.id, deleteError = null) }
        viewModelScope.launch {
            val result = repository.delete(target.id)
            if (myGeneration != actionGeneration || requestAccount != account) return@launch
            val gone = result is CollectionResult.Success || (result as CollectionResult.Failure).error == CollectionError.COLLECTION_GONE
            if (gone) {
                loader.remove(target.id)
                changes.notify(target.id)
                _uiState.update { it.copy(deletingId = null) }
            } else {
                // Falha temporária: a lista continua (nada é apagado na app sem confirmação do servidor).
                _uiState.update { it.copy(deletingId = null, deleteError = (result as CollectionResult.Failure).error) }
            }
        }
    }

    fun dismissDeleteError() = _uiState.update { it.copy(deleteError = null) }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PortoFitApplication
                CollectionsViewModel(
                    app.container.collectionsRepository,
                    app.container.collectionChanges,
                    app.container.sessionManager.state,
                    createSavedStateHandle(),
                )
            }
        }
    }
}

// ------------------------------------------------------------------ Detalhe de uma lista

sealed interface CollectionHeaderState {
    data object Loading : CollectionHeaderState
    data class Loaded(val collection: UserCollection) : CollectionHeaderState

    /** A lista não existe (eliminada noutro aparelho, ou não é desta conta). */
    data object Gone : CollectionHeaderState
    data class Error(val error: DataError) : CollectionHeaderState
}

sealed interface SavedItemsState {
    data object Loading : SavedItemsState
    data class Error(val error: DataError) : SavedItemsState
    data object SessionEnded : SavedItemsState
    data object SessionUnverified : SavedItemsState
    data class Loaded(
        val items: List<SavedItem>,
        val page: Int,
        val totalItems: Long,
        val hasMore: Boolean,
        /** false se alguma página veio sem o serviço das experiências: itens UNKNOWN, não eliminados. */
        val experiencesAvailable: Boolean,
        val isLoadingMore: Boolean = false,
        val loadMoreError: DataError? = null,
    ) : SavedItemsState
}

data class CollectionDetailUiState(
    val signedIn: Boolean? = null,
    val header: CollectionHeaderState = CollectionHeaderState.Loading,
    val items: SavedItemsState = SavedItemsState.Loading,
    /** Itens a ser removidos (sem envios duplicados). */
    val removing: Set<String> = emptySet(),
    /** Erros de remoção por item (o item mantém-se). */
    val removeErrors: Map<String, CollectionError> = emptyMap(),
)

/**
 * Itens de uma lista (docs/collections-api.md §6.3): disponíveis com o cartão da experiência, eliminados sem conteúdo e
 * desconhecidos (serviço indisponível) com nova tentativa; todos removíveis. Uma falha nunca apaga a lista nem marca
 * itens como eliminados.
 */
class CollectionDetailViewModel(
    private val repository: CollectionsRepository,
    private val changes: CollectionChanges,
    experienceChanges: ExperienceChanges,
    session: StateFlow<SessionState>,
    savedState: SavedStateHandle,
    private val pageSize: Int = CollectionListLoader.PAGE_SIZE,
) : ViewModel() {

    private val collectionId: String = checkNotNull(savedState[COLLECTION_ID_ARG])

    private val _uiState = MutableStateFlow(CollectionDetailUiState())
    val uiState: StateFlow<CollectionDetailUiState> = _uiState.asStateFlow()

    private var account: String? = null
    private var generation = 0
    private var job: Job? = null

    init {
        viewModelScope.launch {
            session.map { sessionKey(it) }.filterNotNull().distinctUntilChanged().collect { key ->
                account = key.takeUnless { it == VISITOR }
                generation++
                job?.cancel()
                _uiState.value = CollectionDetailUiState(signedIn = account != null)
                if (account != null) refresh()
            }
        }
        viewModelScope.launch { experienceChanges.counts.collect(::applyCounts) }
    }

    /** "Tentar novamente": cabeçalho e primeira página (também para itens UNKNOWN). */
    fun refresh() {
        val requestAccount = account ?: return
        job?.cancel()
        val myGeneration = ++generation
        _uiState.update { it.copy(items = SavedItemsState.Loading, removeErrors = emptyMap()) }
        loadHeader(requestAccount, myGeneration)
        job = viewModelScope.launch {
            val result = repository.items(collectionId, 0, pageSize)
            if (!current(myGeneration, requestAccount)) return@launch
            _uiState.update { state ->
                when (result) {
                    is SessionResult.Success -> state.copy(
                        items = SavedItemsState.Loaded(
                            result.value.page.items, result.value.page.page, result.value.page.totalItems, result.value.page.hasMore, result.value.experiencesAvailable,
                        ),
                    )
                    is SessionResult.Failure ->
                        if (result.error == DataError.NOT_FOUND) state.copy(header = CollectionHeaderState.Gone, items = SavedItemsState.Loaded(emptyList(), 0, 0, false, true))
                        else state.copy(items = SavedItemsState.Error(result.error))
                    SessionResult.SessionEnded -> state.copy(items = SavedItemsState.SessionEnded)
                    SessionResult.SessionUnverified -> state.copy(items = SavedItemsState.SessionUnverified)
                }
            }
        }
    }

    fun loadMore() {
        val current = _uiState.value.items as? SavedItemsState.Loaded ?: return
        if (!current.hasMore || current.isLoadingMore) return
        val requestAccount = account ?: return
        val myGeneration = generation
        _uiState.update { it.copy(items = current.copy(isLoadingMore = true, loadMoreError = null)) }
        job = viewModelScope.launch {
            val result = repository.items(collectionId, current.page + 1, pageSize)
            if (!current(myGeneration, requestAccount)) return@launch
            _uiState.update { state ->
                val loaded = state.items as? SavedItemsState.Loaded ?: return@update state
                state.copy(
                    items = when (result) {
                        is SessionResult.Success -> loaded.copy(
                            items = (loaded.items + result.value.page.items).distinctBy { it.experienceId },
                            page = result.value.page.page,
                            totalItems = result.value.page.totalItems,
                            hasMore = result.value.page.hasMore,
                            experiencesAvailable = loaded.experiencesAvailable && result.value.experiencesAvailable,
                            isLoadingMore = false,
                        )
                        is SessionResult.Failure -> loaded.copy(isLoadingMore = false, loadMoreError = result.error)
                        SessionResult.SessionEnded -> SavedItemsState.SessionEnded
                        SessionResult.SessionUnverified -> loaded.copy(isLoadingMore = false, loadMoreError = DataError.NETWORK)
                    },
                )
            }
        }
    }

    /** Remover (idempotente): disponível, eliminado ou desconhecido. Um resultado desconhecido mantém o item. */
    fun remove(experienceId: String) {
        if (experienceId in _uiState.value.removing) return
        val requestAccount = account ?: return
        val myGeneration = generation
        _uiState.update { it.copy(removing = it.removing + experienceId, removeErrors = it.removeErrors - experienceId) }
        viewModelScope.launch {
            val result = repository.remove(collectionId, experienceId)
            if (!current(myGeneration, requestAccount)) return@launch
            when {
                result is CollectionResult.Success -> {
                    _uiState.update { state ->
                        val loaded = state.items as? SavedItemsState.Loaded
                        state.copy(
                            removing = state.removing - experienceId,
                            items = loaded?.let { l ->
                                if (l.items.none { it.experienceId == experienceId }) l
                                else l.copy(items = l.items.filterNot { it.experienceId == experienceId }, totalItems = (l.totalItems - 1).coerceAtLeast(0))
                            } ?: state.items,
                        )
                    }
                    changes.notify(collectionId)
                    loadHeader(requestAccount, myGeneration)
                }
                (result as CollectionResult.Failure).error == CollectionError.COLLECTION_GONE ->
                    _uiState.update { it.copy(removing = it.removing - experienceId, header = CollectionHeaderState.Gone) }
                else -> _uiState.update { it.copy(removing = it.removing - experienceId, removeErrors = it.removeErrors + (experienceId to result.error)) }
            }
        }
    }

    private fun loadHeader(requestAccount: String, myGeneration: Int) {
        viewModelScope.launch {
            val result = repository.get(collectionId)
            if (!current(myGeneration, requestAccount)) return@launch
            _uiState.update { state ->
                when (result) {
                    is SessionResult.Success -> state.copy(header = CollectionHeaderState.Loaded(result.value))
                    is SessionResult.Failure -> state.copy(header = if (result.error == DataError.NOT_FOUND) CollectionHeaderState.Gone else CollectionHeaderState.Error(result.error))
                    else -> state
                }
            }
        }
    }

    private fun current(myGeneration: Int, requestAccount: String) = myGeneration == generation && requestAccount == account

    private fun applyCounts(counts: ExperienceCounts) = _uiState.update { state ->
        val loaded = state.items as? SavedItemsState.Loaded ?: return@update state
        if (loaded.items.none { it.experienceId == counts.experienceId }) return@update state
        state.copy(
            items = loaded.copy(
                items = loaded.items.map { item ->
                    if (item.experienceId == counts.experienceId && item.experience != null) {
                        item.copy(experience = item.experience.copy(likesCount = counts.likesCount, commentsCount = counts.commentsCount))
                    } else {
                        item
                    }
                },
            ),
        )
    }

    companion object {
        const val COLLECTION_ID_ARG = "collectionId"
        const val COLLECTION_NAME_ARG = "collectionName"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PortoFitApplication
                CollectionDetailViewModel(
                    app.container.collectionsRepository,
                    app.container.collectionChanges,
                    app.container.experienceChanges,
                    app.container.sessionManager.state,
                    createSavedStateHandle(),
                )
            }
        }
    }
}

// ------------------------------------------------------------------ Guardar numa lista

sealed interface SaveState {
    data object Saving : SaveState
    data object Saved : SaveState
    data class Failed(val error: CollectionError) : SaveState
}

data class SaveToCollectionUiState(
    val signedIn: Boolean? = null,
    /** Estado de guardar por lista (idle = ausente). */
    val saves: Map<String, SaveState> = emptyMap(),
    /** A experiência já não existe: não é possível guardá-la em nenhuma lista. */
    val experienceGone: Boolean = false,
)

/**
 * Seletor "Guardar numa lista" (docs/collections-api.md §6.1): listas paginadas, criar uma nova, e guardar com um toque
 * (idempotente, sem envios duplicados). Nada é guardado sozinho, nem depois de entrar.
 */
class SaveToCollectionViewModel(
    private val repository: CollectionsRepository,
    private val changes: CollectionChanges,
    session: StateFlow<SessionState>,
    savedState: SavedStateHandle,
    clock: () -> Long = System::currentTimeMillis,
    newKey: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {

    private val experienceId: String = checkNotNull(savedState[EXPERIENCE_ID_ARG])

    private val loader = CollectionListLoader(viewModelScope, repository)
    val lists: StateFlow<CollectionsListState> = loader.state

    val creator = CollectionCreator(
        viewModelScope, repository, savedState, "save.create", clock, newKey,
        onCreated = { created ->
            // A lista nova aparece no topo; guardar nela exige um toque.
            loader.prepend(created)
            changes.notify(created.id)
        },
        onStale = { loader.load() },
    )

    private val _uiState = MutableStateFlow(SaveToCollectionUiState())
    val uiState: StateFlow<SaveToCollectionUiState> = _uiState.asStateFlow()

    private var account: String? = null
    private var generation = 0

    init {
        viewModelScope.launch {
            session.map { sessionKey(it) }.filterNotNull().distinctUntilChanged().collect { key ->
                val previous = account
                account = key.takeUnless { it == VISITOR }
                generation++
                loader.clear()
                if (previous != null && previous != account) creator.reset()
                _uiState.value = SaveToCollectionUiState(signedIn = account != null)
                if (account != null) loader.load()
            }
        }
    }

    fun retry() = loader.load()

    fun loadMore() = loader.loadMore()

    fun save(collectionId: String) {
        if (_uiState.value.saves[collectionId] == SaveState.Saving || _uiState.value.experienceGone) return
        val requestAccount = account ?: return
        val myGeneration = generation
        _uiState.update { it.copy(saves = it.saves + (collectionId to SaveState.Saving)) }
        viewModelScope.launch {
            val result = repository.add(collectionId, experienceId)
            if (myGeneration != generation || requestAccount != account) return@launch
            when (result) {
                is CollectionResult.Success -> {
                    _uiState.update { it.copy(saves = it.saves + (collectionId to SaveState.Saved)) }
                    changes.notify(collectionId)
                }
                is CollectionResult.Failure -> {
                    if (result.error == CollectionError.COLLECTION_GONE) loader.remove(collectionId)
                    _uiState.update {
                        it.copy(
                            saves = if (result.error == CollectionError.COLLECTION_GONE) it.saves - collectionId else it.saves + (collectionId to SaveState.Failed(result.error)),
                            experienceGone = it.experienceGone || result.error == CollectionError.EXPERIENCE_GONE,
                        )
                    }
                }
            }
        }
    }

    companion object {
        const val EXPERIENCE_ID_ARG = "experienceId"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PortoFitApplication
                SaveToCollectionViewModel(
                    app.container.collectionsRepository,
                    app.container.collectionChanges,
                    app.container.sessionManager.state,
                    createSavedStateHandle(),
                )
            }
        }
    }
}
