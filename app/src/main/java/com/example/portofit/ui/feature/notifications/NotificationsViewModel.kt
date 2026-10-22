package com.example.portofit.ui.feature.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.portofit.PortoFitApplication
import com.example.portofit.data.auth.SessionState
import com.example.portofit.data.model.AppNotification
import com.example.portofit.data.repository.DataError
import com.example.portofit.data.repository.NotificationsRepository
import com.example.portofit.data.repository.SessionResult
import com.example.portofit.notifications.SystemNotifier
import com.example.portofit.ui.feature.collections.VISITOR
import com.example.portofit.ui.feature.collections.sessionKey
import com.example.portofit.ui.feature.messaging.Poller
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface NotificationListState {
    data object Loading : NotificationListState
    data class Error(val error: DataError) : NotificationListState
    data object SessionEnded : NotificationListState
    data object SessionUnverified : NotificationListState

    /**
     * Por `seq` decrescente. [oldestSeq] e [newestSeq] são os limites do que foi lido (também itens ignorados), para
     * "Carregar mais" (`before`) e para as novas (`after`) nunca saltarem nem repetirem nada; lacunas são normais.
     */
    data class Loaded(
        val items: List<AppNotification>,
        val oldestSeq: Long?,
        val newestSeq: Long,
        val hasMoreBefore: Boolean,
        val isLoadingMore: Boolean = false,
        val loadMoreError: DataError? = null,
    ) : NotificationListState
}

data class NotificationsUiState(
    /** null enquanto a sessão é lida; false = visitante (convite para entrar). */
    val signedIn: Boolean? = null,
    val list: NotificationListState = NotificationListState.Loading,
    /** Do servidor (até 100); null enquanto não se sabe. */
    val unreadCount: Int? = null,
    /** A última consulta periódica falhou (aviso discreto; a lista mantém-se). */
    val refreshFailed: Boolean = false,
    val markingAll: Boolean = false,
    /** Marcar como lida(s) falhou: as notificações continuam por ler. */
    val markFailed: Boolean = false,
)

/**
 * Caixa de notificações (docs/notifications-api.md §5, §15.1):
 * - as mais recentes primeiro, com "Carregar mais" (`before`);
 * - com o ecrã ativo ([onResumed]/[onPaused]), as novas (`after` o maior `seq` lido) de 30 em 30 s (até 60 s com erros),
 *   por páginas até ao fim; se forem demasiadas, recarrega a primeira página em vez de deixar um buraco;
 * - abrir marca como lida e retira a notificação do sistema correspondente; "marcar todas" usa o maior `seq` lido.
 *
 * Privada: ao terminar sessão ou trocar de conta, tudo é apagado logo e respostas tardias são ignoradas.
 */
class NotificationsViewModel(
    private val repository: NotificationsRepository,
    session: StateFlow<SessionState>,
    private val notifier: SystemNotifier,
    private val pageSize: Int = PAGE_SIZE,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NotificationsUiState())
    val uiState: StateFlow<NotificationsUiState> = _uiState.asStateFlow()

    private var account: String? = null
    private var generation = 0
    private var job: Job? = null
    private var resumed = false

    private val poller = Poller(viewModelScope, Poller.INBOX_MILLIS) { refreshNew() }

    init {
        viewModelScope.launch {
            session.map { sessionKey(it) }.filterNotNull().distinctUntilChanged().collect { key ->
                account = key.takeUnless { it == VISITOR }
                generation++
                job?.cancel()
                poller.stop()
                poller.reset()
                _uiState.value = NotificationsUiState(signedIn = account != null)
                if (account != null) load()
            }
        }
    }

    fun onResumed() {
        resumed = true
        if (account != null && _uiState.value.list is NotificationListState.Loaded) poller.start()
    }

    fun onPaused() {
        resumed = false
        poller.stop()
    }

    /** As mais recentes (também "Tentar novamente"). */
    fun load() {
        val requestAccount = account ?: return
        job?.cancel()
        poller.stop()
        val myGeneration = ++generation
        _uiState.update { it.copy(list = NotificationListState.Loading, refreshFailed = false, markFailed = false) }
        job = viewModelScope.launch {
            val result = repository.page(before = null, after = null, limit = pageSize)
            if (!current(myGeneration, requestAccount)) return@launch
            _uiState.update {
                it.copy(
                    list = when (result) {
                        is SessionResult.Success -> result.value.let { page ->
                            NotificationListState.Loaded(page.items, page.lowestSeq, page.highestSeq ?: page.lastSeq, page.hasMoreBefore)
                        }
                        is SessionResult.Failure -> NotificationListState.Error(result.error)
                        SessionResult.SessionEnded -> NotificationListState.SessionEnded
                        SessionResult.SessionUnverified -> NotificationListState.SessionUnverified
                    },
                )
            }
            if (result is SessionResult.Success) {
                refreshCount(myGeneration, requestAccount)
                if (resumed) poller.start(immediate = false)
            }
        }
    }

    fun loadMore() {
        val loaded = _uiState.value.list as? NotificationListState.Loaded ?: return
        val before = loaded.oldestSeq ?: return
        if (!loaded.hasMoreBefore || loaded.isLoadingMore) return
        val requestAccount = account ?: return
        val myGeneration = generation
        _uiState.update { it.copy(list = loaded.copy(isLoadingMore = true, loadMoreError = null)) }
        viewModelScope.launch {
            val result = repository.page(before = before, after = null, limit = pageSize)
            if (!current(myGeneration, requestAccount)) return@launch
            _uiState.update { state ->
                val now = state.list as? NotificationListState.Loaded ?: return@update state
                state.copy(
                    list = when (result) {
                        is SessionResult.Success -> now.copy(
                            items = merge(now.items, result.value.items),
                            oldestSeq = result.value.lowestSeq ?: now.oldestSeq,
                            hasMoreBefore = result.value.hasMoreBefore,
                            isLoadingMore = false,
                        )
                        is SessionResult.Failure -> now.copy(isLoadingMore = false, loadMoreError = result.error)
                        SessionResult.SessionEnded -> NotificationListState.SessionEnded
                        SessionResult.SessionUnverified -> now.copy(isLoadingMore = false, loadMoreError = DataError.NETWORK)
                    },
                )
            }
        }
    }

    /** Abrir: marca como lida (sem esperar) e retira a notificação do sistema; o ecrã abre o destino logo. */
    fun onOpened(item: AppNotification) {
        notifier.cancel(item.seq)
        if (item.read) return
        val requestAccount = account ?: return
        val myGeneration = generation
        setRead(item.id, true)
        viewModelScope.launch {
            val result = repository.markRead(item.id)
            if (!current(myGeneration, requestAccount)) return@launch
            when (result) {
                is SessionResult.Success -> _uiState.update { it.copy(unreadCount = result.value, markFailed = false) }
                else -> {
                    setRead(item.id, false)
                    _uiState.update { it.copy(markFailed = true) }
                }
            }
        }
    }

    /** Todas até ao maior `seq` lido (§5.3): as que chegarem depois continuam por ler. */
    fun markAllRead() {
        val loaded = _uiState.value.list as? NotificationListState.Loaded ?: return
        if (_uiState.value.markingAll) return
        val requestAccount = account ?: return
        val myGeneration = generation
        val upTo = loaded.newestSeq
        _uiState.update { it.copy(markingAll = true, markFailed = false) }
        viewModelScope.launch {
            val result = repository.markAllRead(upTo)
            if (!current(myGeneration, requestAccount)) return@launch
            when (result) {
                is SessionResult.Success -> {
                    notifier.cancelUpTo(upTo)
                    _uiState.update { state ->
                        val now = state.list as? NotificationListState.Loaded
                        state.copy(
                            list = now?.copy(items = now.items.map { if (it.seq <= upTo) it.copy(read = true) else it }) ?: state.list,
                            unreadCount = result.value,
                            markingAll = false,
                        )
                    }
                }
                else -> _uiState.update { it.copy(markingAll = false, markFailed = true) }
            }
        }
    }

    /** As novas desde o maior `seq` lido; com mais do que [MAX_NEW_PAGES] páginas, recarrega as mais recentes. */
    private suspend fun refreshNew(): Boolean {
        val requestAccount = account ?: return true
        val myGeneration = generation
        val start = (_uiState.value.list as? NotificationListState.Loaded)?.newestSeq ?: return true
        var after = start
        val fresh = mutableListOf<AppNotification>()
        for (page in 1..MAX_NEW_PAGES) {
            when (val result = repository.page(before = null, after = after, limit = NEW_PAGE_SIZE)) {
                is SessionResult.Success -> {
                    if (!current(myGeneration, requestAccount)) return true
                    fresh += result.value.items
                    after = maxOf(after, result.value.highestSeq ?: after)
                    if (!result.value.hasMoreAfter) {
                        _uiState.update { state ->
                            val now = state.list as? NotificationListState.Loaded ?: return@update state
                            state.copy(list = now.copy(items = merge(now.items, fresh), newestSeq = maxOf(now.newestSeq, after)), refreshFailed = false)
                        }
                        refreshCount(myGeneration, requestAccount)
                        return true
                    }
                }
                SessionResult.SessionEnded -> {
                    if (!current(myGeneration, requestAccount)) return true
                    poller.stop()
                    _uiState.update { it.copy(list = NotificationListState.SessionEnded) }
                    return true
                }
                else -> {
                    if (current(myGeneration, requestAccount)) _uiState.update { it.copy(refreshFailed = true) }
                    return false
                }
            }
        }
        // Demasiadas novas para juntar: as mais recentes de novo (o histórico continua com "Carregar mais").
        if (current(myGeneration, requestAccount)) load()
        return true
    }

    private suspend fun refreshCount(myGeneration: Int, requestAccount: String) {
        val result = repository.unreadCount()
        if (result is SessionResult.Success && current(myGeneration, requestAccount)) _uiState.update { it.copy(unreadCount = result.value) }
    }

    private fun setRead(id: String, read: Boolean) = _uiState.update { state ->
        val now = state.list as? NotificationListState.Loaded ?: return@update state
        state.copy(list = now.copy(items = now.items.map { if (it.id == id) it.copy(read = read) else it }))
    }

    private fun current(myGeneration: Int, requestAccount: String) = myGeneration == generation && requestAccount == account

    companion object {
        const val PAGE_SIZE = 20
        const val NEW_PAGE_SIZE = 50
        const val MAX_NEW_PAGES = 3

        /** Junta sem repetir (pelo id) e por `seq` decrescente. */
        fun merge(loaded: List<AppNotification>, more: List<AppNotification>): List<AppNotification> =
            (loaded + more.filter { m -> loaded.none { it.id == m.id } }).sortedByDescending { it.seq }

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PortoFitApplication
                NotificationsViewModel(app.container.notificationsRepository, app.container.sessionManager.state, app.container.systemNotifier)
            }
        }
    }
}

/**
 * Contagem por ler no menu da conta (§15.1): pedida quando o ecrã volta a ficar visível ([refresh]); sem consulta
 * contínua. Com outra conta ou sem sessão, volta logo a null e respostas tardias são ignoradas.
 */
class UnreadCountViewModel(
    private val repository: NotificationsRepository,
    session: StateFlow<SessionState>,
) : ViewModel() {

    private val _count = MutableStateFlow<Int?>(null)
    val count: StateFlow<Int?> = _count.asStateFlow()

    private var account: String? = null
    private var generation = 0
    private var job: Job? = null

    init {
        viewModelScope.launch {
            session.map { sessionKey(it) }.filterNotNull().distinctUntilChanged().collect { key ->
                account = key.takeUnless { it == VISITOR }
                generation++
                job?.cancel()
                _count.value = null
                refresh()
            }
        }
    }

    fun refresh() {
        val requestAccount = account ?: return
        val myGeneration = generation
        job?.cancel()
        job = viewModelScope.launch {
            val result = repository.unreadCount()
            if (myGeneration != generation || requestAccount != account) return@launch
            // Uma falha não mostra um número antigo como se fosse atual.
            _count.value = (result as? SessionResult.Success)?.value
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PortoFitApplication
                UnreadCountViewModel(app.container.notificationsRepository, app.container.sessionManager.state)
            }
        }
    }
}
