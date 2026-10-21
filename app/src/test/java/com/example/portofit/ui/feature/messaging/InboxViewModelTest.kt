package com.example.portofit.ui.feature.messaging

import com.example.portofit.data.auth.SessionState
import com.example.portofit.data.model.InboxPage
import com.example.portofit.data.repository.DataError
import com.example.portofit.data.repository.SessionResult
import com.example.portofit.testing.FakeMessagingRepository
import com.example.portofit.testing.FakeMessagingRepository.Companion.ME
import com.example.portofit.testing.FakeMessagingRepository.Companion.conversationOf
import com.example.portofit.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InboxViewModelTest {

    private val scheduler = TestCoroutineScheduler()

    @get:Rule
    val mainDispatcher = MainDispatcherRule(UnconfinedTestDispatcher(scheduler))

    private val repository = FakeMessagingRepository()
    private val session = MutableStateFlow<SessionState>(SessionState.SignedIn(ME, "ana"))

    private fun advance(millis: Long) {
        scheduler.advanceTimeBy(millis)
        scheduler.runCurrent()
    }

    private fun ids(vm: InboxViewModel) = (vm.uiState.value.list as InboxListState.Loaded).items.map { it.id }

    @Test
    fun `visitante ve o convite e nao ha pedidos`() {
        session.value = SessionState.SignedOut()
        val vm = InboxViewModel(repository, session)
        vm.onResumed()
        advance(120_000)
        assertEquals(false, vm.uiState.value.signedIn)
        assertTrue(repository.inboxCalls.isEmpty())
    }

    @Test
    fun `paginada pelo cursor do servidor sem repetir conversas que subiram`() {
        repository.onInbox = { cursor ->
            SessionResult.Success(
                if (cursor == null) InboxPage(listOf(conversationOf("c1", lastAt = 30), conversationOf("c2", lastAt = 20)), "n1")
                // c1 voltou a aparecer (recebeu uma mensagem entre os dois pedidos): não se repete.
                else InboxPage(listOf(conversationOf("c1", lastAt = 40), conversationOf("c3", lastAt = 10)), null),
            )
        }
        val vm = InboxViewModel(repository, session, pageSize = 2)
        vm.loadMore()
        assertEquals(listOf("c1", "c2", "c3"), ids(vm))
        assertNull((vm.uiState.value.list as InboxListState.Loaded).nextCursor)
        assertEquals(listOf(null, "n1"), repository.inboxCalls)
    }

    @Test
    fun `consulta de 30 s so com o ecra ativo, junta pelo id e reordena, com recuo nos erros`() {
        repository.onInbox = { SessionResult.Success(InboxPage(listOf(conversationOf("c1", lastAt = 30), conversationOf("c2", lastAt = 20)), null)) }
        val vm = InboxViewModel(repository, session)
        advance(120_000)
        assertEquals("sem o ecrã ativo, só o carregamento", 1, repository.inboxCalls.size)
        vm.onResumed()
        assertEquals(2, repository.inboxCalls.size)

        // c2 recebeu uma mensagem: sobe para o topo; uma conversa nova entra.
        repository.onInbox = {
            SessionResult.Success(InboxPage(listOf(conversationOf("c2", lastAt = 50, unread = 3), conversationOf("c9", lastAt = 40), conversationOf("c1", lastAt = 30)), null))
        }
        advance(30_000)
        assertEquals(listOf("c2", "c9", "c1"), ids(vm))
        assertEquals(3, (vm.uiState.value.list as InboxListState.Loaded).items.first().unreadCount)

        repository.onInbox = { SessionResult.Failure(DataError.NETWORK) }
        advance(30_000)
        assertTrue(vm.uiState.value.refreshFailed)
        assertEquals("a lista mantém-se", listOf("c2", "c9", "c1"), ids(vm))
        val failedAt = repository.inboxCalls.size
        advance(59_000)
        assertEquals(failedAt, repository.inboxCalls.size)
        advance(1_000)
        assertEquals("60 s depois de um erro", failedAt + 1, repository.inboxCalls.size)

        vm.onPaused()
        advance(600_000)
        assertEquals(failedAt + 1, repository.inboxCalls.size)
    }

    @Test
    fun `terminar sessao apaga a caixa, para a consulta e ignora respostas antigas`() {
        val vm = InboxViewModel(repository, session)
        vm.onResumed()
        val gate = CompletableDeferred<Unit>()
        repository.onInbox = { gate.await(); SessionResult.Success(InboxPage(listOf(conversationOf("c-antiga")), null)) }
        advance(30_000)
        session.value = SessionState.SignedOut()
        gate.complete(Unit)
        scheduler.runCurrent()
        assertEquals(InboxUiState(signedIn = false), vm.uiState.value)
        val calls = repository.inboxCalls.size
        advance(600_000)
        assertEquals(calls, repository.inboxCalls.size)

        repository.onInbox = { SessionResult.Success(InboxPage(listOf(conversationOf("c-bea")), null)) }
        session.value = SessionState.SignedIn("bea-id", "bea")
        assertEquals(listOf("c-bea"), ids(vm))
        assertEquals("bea-id", vm.uiState.value.myUserId)
    }
}
