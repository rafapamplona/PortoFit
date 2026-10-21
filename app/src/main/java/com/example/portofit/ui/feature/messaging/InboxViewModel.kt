package com.example.portofit.ui.feature.messaging

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.portofit.PortoFitApplication
import com.example.portofit.data.auth.SessionState
import com.example.portofit.data.model.Conversation
import com.example.portofit.data.repository.DataError
import com.example.portofit.data.repository.MessagingRepository
import com.example.portofit.data.repository.SessionResult
import com.example.portofit.ui.feature.collections.VISITOR
import com.example.portofit.ui.feature.collections.sessionKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface InboxListState {
    data object Loading : InboxListState
    data class Error(val error: DataError) : InboxListState
    data object SessionEnded : InboxListState
    data object SessionUnverified : InboxListState
    data class Loaded(
        val items: List<Conversation>,
        val nextCursor: String?,
        val isLoadingMore: Boolean = false,
        val loadMoreError: DataError? = null,
    ) : InboxListState
}

data class InboxUiState(
    /** null enquanto a sessão é lida; false = visitante (convite para entrar). */
    val signedIn: Boolean? = null,
    /** Id do próprio, para mostrar "Tu:" na última mensagem. */
    val myUserId: String? = null,
    val list: InboxListState = InboxListState.Loading,
    /** A última consulta periódica falhou (aviso discreto; a lista mantém-se). */
    val refreshFailed: Boolean = false,
)

/**
 * Caixa de conversas (docs/messaging-api.md §5.2): só as com mensagens, a mais recente primeiro, paginada pelo cursor do
 * servidor. Com o ecrã ativo ([onResumed]/[onPaused]), a primeira página é pedida de 30 em 30 s (até 60 s com erros) e
 * junta-se ao que está carregado pelo id, sem duplicar nem perder as páginas seguintes.
 *
 * Privada: ao terminar sessão ou trocar de conta, tudo é apagado logo, a consulta para e respostas tardias são ignoradas.
 */
class InboxViewModel(
    private val repository: MessagingRepository,
    session: StateFlow<SessionState>,
    private val pageSize: Int = PAGE_SIZE,
) : ViewModel() {

    private val _uiState = MutableStateFlow(InboxUiState())
    val uiState: StateFlow<InboxUiState> = _uiState.asStateFlow()

    private var account: String? = null
    private var generation = 0
    private var job: Job? = null
    private var resumed = false

    private val poller = Poller(viewModelScope, Poller.INBOX_MILLIS) { refreshFirstPage() }

    init {
        viewModelScope.launch {
            session.map { sessionKey(it) }.filterNotNull().distinctUntilChanged().collect { key ->
                account = key.takeUnless { it == VISITOR }
                generation++
                job?.cancel()
                poller.stop()
                poller.reset()
                _uiState.value = InboxUiState(signedIn = account != null, myUserId = account)
                if (account != null) load()
            }
        }
    }

    fun onResumed() {
        resumed = true
        if (account != null && _uiState.value.list is InboxListState.Loaded) poller.start()
    }

    fun onPaused() {
        resumed = false
        poller.stop()
    }

    /** Primeira página (também "Tentar novamente"). */
    fun load() {
        val requestAccount = account ?: return
        job?.cancel()
        poller.stop()
        val myGeneration = ++generation
        _uiState.update { it.copy(list = InboxListState.Loading, refreshFailed = false) }
        job = viewModelScope.launch {
            val result = repository.inbox(null, pageSize)
            if (!current(myGeneration, requestAccount)) return@launch
            _uiState.update {
                it.copy(
                    list = when (result) {
                        is SessionResult.Success -> InboxListState.Loaded(result.value.items, result.value.nextCursor)
                        is SessionResult.Failure -> InboxListState.Error(result.error)
                        SessionResult.SessionEnded -> InboxListState.SessionEnded
                        SessionResult.SessionUnverified -> InboxListState.SessionUnverified
                    },
                )
            }
            if (result is SessionResult.Success && resumed) poller.start(immediate = false)
        }
    }

    fun loadMore() {
        val loaded = _uiState.value.list as? InboxListState.Loaded ?: return
        val cursor = loaded.nextCursor ?: return
        if (loaded.isLoadingMore) return
        val requestAccount = account ?: return
        val myGeneration = generation
        _uiState.update { it.copy(list = loaded.copy(isLoadingMore = true, loadMoreError = null)) }
        viewModelScope.launch {
            val result = repository.inbox(cursor, pageSize)
            if (!current(myGeneration, requestAccount)) return@launch
            _uiState.update { state ->
                val now = state.list as? InboxListState.Loaded ?: return@update state
                state.copy(
                    list = when (result) {
                        // Uma conversa que subiu entretanto para o topo já está carregada: não se repete.
                        is SessionResult.Success -> now.copy(
                            items = now.items + result.value.items.filter { fresh -> now.items.none { it.id == fresh.id } },
                            nextCursor = result.value.nextCursor,
                            isLoadingMore = false,
                        )
                        is SessionResult.Failure -> now.copy(isLoadingMore = false, loadMoreError = result.error)
                        SessionResult.SessionEnded -> InboxListState.SessionEnded
                        SessionResult.SessionUnverified -> now.copy(isLoadingMore = false, loadMoreError = DataError.NETWORK)
                    },
                )
            }
        }
    }

    /** Consulta periódica: a primeira página substitui as mesmas conversas e reordena; as outras mantêm-se. */
    private suspend fun refreshFirstPage(): Boolean {
        val requestAccount = account ?: return true
        val myGeneration = generation
        val result = repository.inbox(null, pageSize)
        if (!current(myGeneration, requestAccount)) return true
        return when (result) {
            is SessionResult.Success -> {
                _uiState.update { state ->
                    val loaded = state.list as? InboxListState.Loaded ?: return@update state
                    val fresh = result.value.items.associateBy { it.id }
                    val merged = (loaded.items.map { fresh[it.id] ?: it } + result.value.items.filter { item -> loaded.items.none { it.id == item.id } })
                        .sortedWith(compareByDescending<Conversation> { it.lastMessage?.createdAt }.thenByDescending { it.id })
                    state.copy(list = loaded.copy(items = merged), refreshFailed = false)
                }
                true
            }
            SessionResult.SessionEnded -> {
                poller.stop()
                _uiState.update { it.copy(list = InboxListState.SessionEnded) }
                true
            }
            else -> {
                _uiState.update { it.copy(refreshFailed = true) }
                false
            }
        }
    }

    private fun current(myGeneration: Int, requestAccount: String) = myGeneration == generation && requestAccount == account

    companion object {
        const val PAGE_SIZE = 20

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PortoFitApplication
                InboxViewModel(app.container.messagingRepository, app.container.sessionManager.state)
            }
        }
    }
}
