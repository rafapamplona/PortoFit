package com.example.portofit.notifications

import com.example.portofit.data.auth.SessionState
import com.example.portofit.data.model.AppNotification
import com.example.portofit.data.model.NotificationType
import com.example.portofit.data.model.NotificationsPage
import com.example.portofit.data.repository.DataError
import com.example.portofit.data.repository.SessionResult
import com.example.portofit.testing.FakeNotificationsRepository
import com.example.portofit.testing.FakeNotificationsRepository.Companion.NOW
import com.example.portofit.testing.FakeNotificationsRepository.Companion.notificationOf
import com.example.portofit.testing.FakeNotificationsRepository.Companion.serve
import com.example.portofit.testing.FakeSystemNotifier
import com.example.portofit.testing.FakeWorkScheduler
import com.example.portofit.testing.InMemoryCursorStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Consulta em segundo plano (docs/notifications-api.md §15.3), testada como classe com repositório e sessão falsos. */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationPollerTest {

    private val session = MutableStateFlow<SessionState>(SessionState.SignedIn(ANA, "ana"))
    private val repository = FakeNotificationsRepository()
    private val notifier = FakeSystemNotifier()
    private val scheduler = FakeWorkScheduler()
    private val cursors = InMemoryCursorStore()
    private var server = mutableListOf<AppNotification>()

    init {
        repository.onPage = serve({ server })
    }

    /** Coordenador a seguir a sessão (como na app) e o poller com páginas pequenas, para testar os limites. */
    private fun TestScope.poller(pageSize: Int = 50, maxPages: Int = 3): NotificationPoller {
        val coordinator = NotificationsCoordinator(session, scheduler, notifier, cursors, backgroundScope, clock = { BASELINE })
        return NotificationPoller(repository, session, coordinator, cursors, notifier, clock = { BASELINE }, pageSize = pageSize, maxPages = maxPages)
    }

    @Test
    fun `primeira consulta comeca antes do inicio da conta e mostra so as novas nao lidas`() = runTest(UnconfinedTestDispatcher()) {
        server = mutableListOf(
            notificationOf(1, createdAt = NOW.minusSeconds(7200)), // anterior: fica só na caixa
            notificationOf(2, read = true), // lida noutro aparelho
            notificationOf(3),
            notificationOf(5, type = NotificationType.MESSAGE_RECEIVED), // lacuna no 4: normal
        )
        assertEquals(PollOutcome.DONE, poller().poll(ANA))
        assertEquals(listOf(FakeSystemNotifier.Shown(ANA, listOf(3L, 5L), 0)), notifier.shown)
        assertEquals(5L, cursors.get(ANA)?.seq)
        assertEquals(listOf(null, 1L), repository.pageCalls.map { it.after })
    }

    @Test
    fun `repetir nao mostra de novo e as seguintes sim`() = runTest(UnconfinedTestDispatcher()) {
        server = mutableListOf(notificationOf(1), notificationOf(2))
        val poller = poller()
        poller.poll(ANA)
        poller.poll(ANA)
        assertEquals(1, notifier.shown.size)
        server += notificationOf(3)
        poller.poll(ANA)
        assertEquals(listOf(3L), notifier.shown.last().seqs)
        assertEquals(listOf(2L, 2L), repository.pageCalls.drop(2).map { it.after }.take(2))
    }

    @Test
    fun `execucoes sobrepostas nao mostram a mesma notificacao duas vezes`() = runTest(UnconfinedTestDispatcher()) {
        server = mutableListOf(notificationOf(1), notificationOf(2))
        val gate = CompletableDeferred<Unit>()
        val serving = serve({ server })
        repository.onPage = { call -> gate.await(); serving(call) }
        val poller = poller()
        val first = async { poller.poll(ANA) }
        val second = async { poller.poll(ANA) }
        gate.complete(Unit)
        assertEquals(listOf(PollOutcome.DONE, PollOutcome.DONE), listOf(first.await(), second.await()))
        assertEquals(listOf(1L, 2L), notifier.shown.flatMap { it.seqs })
    }

    @Test
    fun `falha temporaria repete sem avancar o cursor nem mostrar nada`() = runTest(UnconfinedTestDispatcher()) {
        server = mutableListOf(notificationOf(1))
        val poller = poller()
        poller.poll(ANA)
        server += notificationOf(2)
        repository.onPage = { SessionResult.Failure(DataError.SERVER) }
        assertEquals(PollOutcome.RETRY, poller.poll(ANA))
        assertEquals(1L, cursors.get(ANA)?.seq)
        assertEquals(1, notifier.shown.size)
        // Uma falha na segunda página também não mostra a primeira.
        server += (3L..6L).map { notificationOf(it) }
        val serving = serve({ server })
        repository.onPage = { call -> if (call.after == 3L) SessionResult.Failure(DataError.NETWORK) else serving(call) }
        val small = poller(pageSize = 2)
        assertEquals(PollOutcome.RETRY, small.poll(ANA))
        assertEquals(1L, cursors.get(ANA)?.seq)
        assertEquals(1, notifier.shown.size)
    }

    @Test
    fun `sem sessao, outra conta ou sessao por confirmar nao faz nada`() = runTest(UnconfinedTestDispatcher()) {
        server = mutableListOf(notificationOf(1))
        val poller = poller()
        assertEquals(PollOutcome.SKIPPED, poller.poll(BRUNO))
        session.value = SessionState.SignedOut()
        assertEquals(PollOutcome.SKIPPED, poller.poll(ANA))
        assertTrue(repository.pageCalls.isEmpty())
        // Renovação ambígua: o repositório não chega a pedir (sem token) e o worker não tenta renovar.
        session.value = SessionState.SignedIn(ANA, "ana")
        repository.onPage = { SessionResult.SessionUnverified }
        assertEquals(PollOutcome.SKIPPED, poller.poll(ANA))
        assertNull(cursors.get(ANA)?.seq)
        assertTrue(notifier.shown.isEmpty())
    }

    @Test
    fun `espera que a sessao guardada seja lida antes de decidir`() = runTest(UnconfinedTestDispatcher()) {
        session.value = SessionState.Loading
        server = mutableListOf(notificationOf(1))
        val poller = poller()
        val run = async { poller.poll(ANA) }
        assertTrue(repository.pageCalls.isEmpty())
        session.value = SessionState.SignedIn(ANA, "ana")
        assertEquals(PollOutcome.DONE, run.await())
        assertEquals(listOf(1L), notifier.shown.single().seqs)
    }

    @Test
    fun `no maximo cinco individuais e um resumo das restantes`() = runTest(UnconfinedTestDispatcher()) {
        server = (1L..8L).map { notificationOf(it) }.toMutableList()
        poller().poll(ANA)
        assertEquals(FakeSystemNotifier.Shown(ANA, listOf(4L, 5L, 6L, 7L, 8L), 3), notifier.shown.single())
    }

    @Test
    fun `ate tres paginas por execucao e as restantes na seguinte, sem saltar`() = runTest(UnconfinedTestDispatcher()) {
        server = (1L..10L).map { notificationOf(it) }.toMutableList()
        val poller = poller(pageSize = 2, maxPages = 3)
        poller.poll(ANA)
        assertEquals(6L, cursors.get(ANA)?.seq)
        assertEquals(FakeSystemNotifier.Shown(ANA, listOf(2L, 3L, 4L, 5L, 6L), 1), notifier.shown.last())
        poller.poll(ANA)
        assertEquals(10L, cursors.get(ANA)?.seq)
        assertEquals(listOf(7L, 8L, 9L, 10L), notifier.shown.last().seqs)
    }

    @Test
    fun `lacunas da retencao e tipos desconhecidos nunca param o cursor`() = runTest(UnconfinedTestDispatcher()) {
        server = mutableListOf(notificationOf(1), notificationOf(2))
        val poller = poller()
        poller.poll(ANA)
        // A retenção apagou 3–9; o 12 é de um tipo desta versão desconhecido (ignorado pelo repositório).
        server = mutableListOf(notificationOf(10), notificationOf(11))
        repository.onPage = { call ->
            assertEquals(2L, call.after)
            SessionResult.Success(NotificationsPage(server, lastSeq = 12, hasMoreBefore = false, hasMoreAfter = false, lowestSeq = 10, highestSeq = 12))
        }
        poller.poll(ANA)
        assertEquals(listOf(10L, 11L), notifier.shown.last().seqs)
        assertEquals(12L, cursors.get(ANA)?.seq)
    }

    @Test
    fun `resposta tardia depois de trocar de conta nao mostra nem guarda nada`() = runTest(UnconfinedTestDispatcher()) {
        server = mutableListOf(notificationOf(1))
        val gate = CompletableDeferred<Unit>()
        val serving = serve({ server })
        repository.onPage = { call -> gate.await(); serving(call) }
        val run = async { poller().poll(ANA) }
        session.value = SessionState.SignedIn(BRUNO, "bruno")
        gate.complete(Unit)
        assertEquals(PollOutcome.SKIPPED, run.await())
        assertTrue(notifier.shown.isEmpty())
        assertNull(cursors.get(ANA))
    }

    @Test
    fun `outra conta nunca usa o cursor da anterior`() = runTest(UnconfinedTestDispatcher()) {
        server = mutableListOf(notificationOf(1), notificationOf(2))
        val poller = poller()
        poller.poll(ANA)
        assertEquals(2L, cursors.get(ANA)?.seq)
        session.value = SessionState.SignedIn(BRUNO, "bruno")
        repository.pageCalls.clear()
        poller.poll(BRUNO)
        assertNull(cursors.get(ANA))
        assertEquals(null, repository.pageCalls.first().after) // começa do início da conta, não do 2
        assertEquals(BRUNO, notifier.shown.last().account)
    }

    // --- Coordenador por conta (§15.2, §15.6) ---

    @Test
    fun `trabalho agendado so com sessao e notificacoes permitidas`() = runTest(UnconfinedTestDispatcher()) {
        notifier.allowed = false
        val coordinator = NotificationsCoordinator(session, scheduler, notifier, cursors, backgroundScope, clock = { BASELINE })
        assertNull(scheduler.scheduled)
        assertNull(cursors.get(ANA))
        notifier.allowed = true
        coordinator.sync()
        assertEquals(ANA, scheduler.scheduled)
        assertEquals(BASELINE, cursors.get(ANA)?.baselineMillis)
        // Permissão retirada nas definições: ao voltar à app, o trabalho é cancelado (a caixa continua).
        notifier.allowed = false
        coordinator.sync()
        assertNull(scheduler.scheduled)
    }

    @Test
    fun `terminar sessao cancela o trabalho, retira as notificacoes e apaga o cursor`() = runTest(UnconfinedTestDispatcher()) {
        server = mutableListOf(notificationOf(1))
        poller().poll(ANA)
        assertEquals(setOf(1L), notifier.visible)
        session.value = SessionState.SignedOut()
        assertNull(scheduler.scheduled)
        assertTrue(notifier.visible.isEmpty())
        assertTrue(cursors.cursors.isEmpty())
        // Entrar com outra conta: só o trabalho da nova.
        session.value = SessionState.SignedIn(BRUNO, "bruno")
        assertEquals(BRUNO, scheduler.scheduled)
        assertEquals(setOf(BRUNO), cursors.cursors.keys)
    }

    @Test
    fun `reabrir a app com a mesma sessao mantem o cursor e apaga restos de outras contas`() = runTest(UnconfinedTestDispatcher()) {
        cursors.cursors[ANA] = AccountCursor(7, BASELINE)
        cursors.cursors[BRUNO] = AccountCursor(3, BASELINE)
        session.value = SessionState.Loading
        NotificationsCoordinator(session, scheduler, notifier, cursors, backgroundScope, clock = { BASELINE + 1 })
        assertNull(scheduler.scheduled)
        session.value = SessionState.SignedIn(ANA, "ana", status = com.example.portofit.data.auth.SessionStatus.VERIFYING)
        assertEquals(mapOf(ANA to AccountCursor(7, BASELINE)), cursors.cursors)
        assertEquals(0, notifier.cancelAllCalls)
        assertEquals(ANA, scheduler.scheduled)
        assertFalse(scheduler.history.contains("schedule:$BRUNO"))
    }

    private companion object {
        const val ANA = "ana-id"
        const val BRUNO = "bruno-id"
        val BASELINE = NOW.minusSeconds(3600).toEpochMilli()
    }
}
