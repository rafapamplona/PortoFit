package com.example.portofit.ui.feature.notifications

import com.example.portofit.R
import com.example.portofit.data.auth.SessionState
import com.example.portofit.data.model.AppNotification
import com.example.portofit.data.model.NotificationTarget
import com.example.portofit.data.model.NotificationType
import com.example.portofit.data.repository.DataError
import com.example.portofit.data.repository.SessionResult
import com.example.portofit.notifications.NotificationTap
import com.example.portofit.notifications.TapAction
import com.example.portofit.notifications.notificationText
import com.example.portofit.notifications.resolveTap
import com.example.portofit.testing.FakeNotificationsRepository
import com.example.portofit.testing.FakeNotificationsRepository.Companion.notificationOf
import com.example.portofit.testing.FakeNotificationsRepository.Companion.serve
import com.example.portofit.testing.FakeSystemNotifier
import com.example.portofit.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationsViewModelTest {

    private val scheduler = TestCoroutineScheduler()

    @get:Rule
    val mainDispatcher = MainDispatcherRule(UnconfinedTestDispatcher(scheduler))

    private val repository = FakeNotificationsRepository()
    private val notifier = FakeSystemNotifier()
    private val session = MutableStateFlow<SessionState>(SessionState.SignedIn("ana-id", "ana"))
    private var server = mutableListOf<AppNotification>()

    init {
        repository.onPage = serve({ server })
        repository.onUnread = { SessionResult.Success(server.count { !it.read }) }
    }

    private fun advance(millis: Long) {
        scheduler.advanceTimeBy(millis)
        scheduler.runCurrent()
    }

    private fun loaded(vm: NotificationsViewModel) = vm.uiState.value.list as NotificationListState.Loaded

    private fun seqs(vm: NotificationsViewModel) = loaded(vm).items.map { it.seq }

    @Test
    fun `visitante ve o convite e nao ha pedidos`() {
        session.value = SessionState.SignedOut()
        val vm = NotificationsViewModel(repository, session, notifier)
        vm.onResumed()
        advance(120_000)
        assertEquals(false, vm.uiState.value.signedIn)
        assertTrue(repository.pageCalls.isEmpty())
        assertEquals(0, repository.unreadCalls)
    }

    @Test
    fun `mais recentes primeiro e carregar mais pelo seq, com lacunas`() {
        server = (listOf(1L, 2L, 3L, 7L, 8L, 9L)).map { notificationOf(it) }.toMutableList()
        val vm = NotificationsViewModel(repository, session, notifier, pageSize = 3)
        assertEquals(listOf(9L, 8L, 7L), seqs(vm))
        assertEquals(6, vm.uiState.value.unreadCount)
        assertTrue(loaded(vm).hasMoreBefore)
        vm.loadMore()
        assertEquals(listOf(9L, 8L, 7L, 3L, 2L, 1L), seqs(vm))
        assertEquals(7L, repository.pageCalls.last().before)
        assertFalse(loaded(vm).hasMoreBefore)
    }

    @Test
    fun `consulta so com o ecra ativo e junta as novas sem saltar nem repetir`() {
        server = mutableListOf(notificationOf(1), notificationOf(2))
        val vm = NotificationsViewModel(repository, session, notifier)
        server += (3L..5L).map { notificationOf(it) }
        advance(120_000)
        assertEquals(1, repository.pageCalls.size) // em pausa: nenhuma consulta
        vm.onResumed()
        advance(1)
        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), seqs(vm))
        assertEquals(2L, repository.pageCalls.last().after)
        vm.onPaused()
        server += notificationOf(6)
        advance(120_000)
        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), seqs(vm))
    }

    @Test
    fun `demasiadas novas para juntar recarrega as mais recentes`() {
        server = mutableListOf(notificationOf(1))
        val vm = NotificationsViewModel(repository, session, notifier, pageSize = 20)
        server += (2L..200L).map { notificationOf(it) }
        vm.onResumed()
        advance(1)
        assertEquals((200L downTo 181L).toList(), seqs(vm))
        assertTrue(loaded(vm).hasMoreBefore)
    }

    @Test
    fun `abrir marca como lida e retira a notificacao do sistema, e uma falha volta a por ler`() {
        server = mutableListOf(notificationOf(1), notificationOf(2))
        notifier.visible += listOf(1L, 2L)
        val vm = NotificationsViewModel(repository, session, notifier)
        repository.onMarkRead = { SessionResult.Success(1) }
        vm.onOpened(loaded(vm).items.first { it.seq == 2L })
        assertEquals(listOf("n2"), repository.markReadCalls)
        assertTrue(loaded(vm).items.first { it.seq == 2L }.read)
        assertEquals(1, vm.uiState.value.unreadCount)
        assertEquals(setOf(1L), notifier.visible)
        // Falha: continua por ler e o ecrã diz porquê.
        repository.onMarkRead = { SessionResult.Failure(DataError.NETWORK) }
        vm.onOpened(loaded(vm).items.first { it.seq == 1L })
        assertFalse(loaded(vm).items.first { it.seq == 1L }.read)
        assertTrue(vm.uiState.value.markFailed)
        // Uma já lida não volta a pedir ao servidor.
        vm.onOpened(loaded(vm).items.first { it.seq == 2L })
        assertEquals(listOf("n2", "n1"), repository.markReadCalls)
    }

    @Test
    fun `marcar todas usa o maior seq lido e nunca as que chegam depois`() {
        server = mutableListOf(notificationOf(1), notificationOf(4))
        notifier.visible += listOf(1L, 4L, 6L)
        val vm = NotificationsViewModel(repository, session, notifier)
        repository.onMarkAll = { SessionResult.Success(1) }
        vm.markAllRead()
        assertEquals(listOf(4L), repository.markAllCalls)
        assertTrue(loaded(vm).items.all { it.read })
        assertEquals(1, vm.uiState.value.unreadCount)
        assertEquals(setOf(6L), notifier.visible) // a 6 chegou depois do que foi lido
    }

    @Test
    fun `trocar de conta apaga a caixa e ignora a resposta tardia da anterior`() {
        val gate = CompletableDeferred<Unit>()
        server = mutableListOf(notificationOf(1, actor = "da-ana"))
        val serving = serve({ server })
        repository.onPage = { call -> gate.await(); serving(call) }
        val vm = NotificationsViewModel(repository, session, notifier)
        session.value = SessionState.SignedIn("bruno-id", "bruno")
        assertEquals(NotificationListState.Loading, vm.uiState.value.list)
        server = mutableListOf(notificationOf(5, actor = "do-bruno"))
        gate.complete(Unit)
        assertEquals(listOf("do-bruno"), loaded(vm).items.map { it.actorUsername })
        session.value = SessionState.SignedOut()
        assertEquals(NotificationsUiState(signedIn = false), vm.uiState.value)
    }

    @Test
    fun `sessao terminada e servico em baixo com nova tentativa`() {
        repository.onPage = { SessionResult.Failure(DataError.NETWORK) }
        val vm = NotificationsViewModel(repository, session, notifier)
        assertEquals(NotificationListState.Error(DataError.NETWORK), vm.uiState.value.list)
        repository.onPage = serve({ server })
        vm.load()
        assertTrue(vm.uiState.value.list is NotificationListState.Loaded)
        repository.onPage = { SessionResult.SessionEnded }
        vm.load()
        assertEquals(NotificationListState.SessionEnded, vm.uiState.value.list)
    }

    @Test
    fun `contagem do menu por conta, sem mostrar um numero antigo`() {
        repository.onUnread = { SessionResult.Success(4) }
        val vm = UnreadCountViewModel(repository, session)
        assertEquals(4, vm.count.value)
        val gate = CompletableDeferred<Unit>()
        repository.onUnread = { gate.await(); SessionResult.Success(9) }
        vm.refresh()
        session.value = SessionState.SignedIn("bruno-id", "bruno")
        assertNull(vm.count.value)
        gate.complete(Unit)
        assertEquals(9, vm.count.value) // o pedido do bruno, não o tardio da ana
        repository.onUnread = { SessionResult.Failure(DataError.SERVER) }
        vm.refresh()
        assertNull(vm.count.value)
        session.value = SessionState.SignedOut()
        assertNull(vm.count.value)
    }

    // --- Toque numa notificação do sistema e textos (§9, §15.6) ---

    @Test
    fun `o toque so abre o conteudo para a conta destinataria`() {
        val tap = NotificationTap("ana-id", "n3", 3, NotificationTarget.Conversation("c3", "rui"))
        assertEquals(TapAction.OpenTarget("n3", 3, NotificationTarget.Conversation("c3", "rui")), resolveTap(tap, SessionState.SignedIn("ana-id", "ana")))
        assertEquals(TapAction.OpenInbox, resolveTap(tap, SessionState.SignedIn("bruno-id", "bruno")))
        assertEquals(TapAction.OpenInbox, resolveTap(tap, SessionState.SignedOut()))
        assertEquals(TapAction.OpenInbox, resolveTap(NotificationTap("ana-id"), SessionState.SignedIn("ana-id", "ana"))) // resumo
    }

    @Test
    fun `texto por tipo sem conteudo privado`() {
        assertEquals(R.string.notification_text_message, notificationText(notificationOf(1, NotificationType.MESSAGE_RECEIVED)))
        assertEquals(R.string.notification_text_shared, notificationText(notificationOf(1, NotificationType.MESSAGE_RECEIVED).copy(shared = true)))
        assertEquals(R.string.notification_text_comment, notificationText(notificationOf(1, NotificationType.COMMENT_CREATED)))
        assertEquals(R.string.notification_text_follow, notificationText(notificationOf(1, NotificationType.USER_FOLLOWED)))
        assertEquals(R.string.notification_text_published, notificationText(notificationOf(1, NotificationType.EXPERIENCE_PUBLISHED)))
    }
}
