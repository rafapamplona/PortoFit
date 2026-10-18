package com.example.portofit.data.auth

import com.example.portofit.data.remote.SessionDto
import com.example.portofit.testing.FakeAuthGateway
import com.example.portofit.testing.FakeAuthGateway.Companion.session
import com.example.portofit.testing.InMemorySessionStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionManagerTest {

    private val gateway = FakeAuthGateway()
    private val stored = StoredSession("user-1", "ana", "r-guardado")
    private var now = 1_000_000L

    /** O âmbito do teste executa o trabalho lançado pelo SessionManager (start, logout, retry) e espera por ele. */
    private fun TestScope.manager(store: InMemorySessionStore): SessionManager {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return SessionManager(gateway, store, this, io = dispatcher, clock = { now })
    }

    // --- Login, registo ---

    @Test
    fun `login guarda o refresh token e mostra o username`() = runTest {
        val store = InMemorySessionStore()
        val session = manager(store)

        assertEquals(AuthOutcome.Success, session.login("ana@example.org", "uma frase longa"))

        assertEquals(SessionState.SignedIn("user-1", "ana"), session.state.value)
        assertEquals("r-login", store.stored?.refreshToken)
        assertEquals("a-login", session.accessToken()) // em memória, sem renovar
        assertEquals(0, gateway.refreshCalls.size)
    }

    @Test
    fun `login recusado nao guarda nada`() = runTest {
        val store = InMemorySessionStore()
        gateway.onLogin = { _, _ -> GatewayResult.Rejected(401, "INVALID_CREDENTIALS", emptyList(), null) }
        val session = manager(store)

        assertEquals(AuthOutcome.InvalidCredentials, session.login("a@b.pt", "x"))
        assertNull(store.stored)
    }

    @Test
    fun `registo com username e email repetidos indica os campos`() = runTest {
        gateway.onRegister = { _, _, _ -> GatewayResult.Rejected(409, "CONFLICT", listOf("username", "email"), null) }
        val outcome = manager(InMemorySessionStore()).register("ana", "a@b.pt", "uma frase longa")
        assertEquals(AuthOutcome.FieldErrors(mapOf("username" to FieldProblem.ALREADY_USED, "email" to FieldProblem.ALREADY_USED)), outcome)
    }

    @Test
    fun `limite de tentativas devolve a espera`() = runTest {
        gateway.onLogin = { _, _ -> GatewayResult.Rejected(429, "TOO_MANY_ATTEMPTS", emptyList(), 600) }
        assertEquals(AuthOutcome.TooManyAttempts(600), manager(InMemorySessionStore()).login("a@b.pt", "x"))
    }

    // --- Reabrir a app ---

    @Test
    fun `ao abrir recupera a sessao com o refresh token e guarda o novo antes de a mostrar ativa`() = runTest {
        val store = InMemorySessionStore(stored)
        val session = manager(store)

        session.start()
        advanceUntilIdle()

        assertEquals(listOf("r-guardado"), gateway.refreshCalls)
        assertEquals("r-0", store.stored?.refreshToken)
        assertEquals(SessionState.SignedIn("user-1", "ana", SessionStatus.ACTIVE), session.state.value)
    }

    @Test
    fun `sem sessao guardada fica sem sessao e nao contacta o servidor`() = runTest {
        val session = manager(InMemorySessionStore())
        session.start()
        advanceUntilIdle()
        assertEquals(SessionState.SignedOut(), session.state.value)
        assertEquals(0, gateway.refreshCalls.size)
    }

    @Test
    fun `sessao guardada ilegivel termina a sessao com aviso`() = runTest {
        val session = manager(InMemorySessionStore(unreadable = true))
        session.start()
        advanceUntilIdle()
        assertEquals(SessionState.SignedOut(SessionNotice.SESSION_ENDED), session.state.value)
    }

    // --- Renovação ---

    @Test
    fun `pedidos simultaneos partilham uma unica renovacao`() = runTest {
        val slow = CompletableDeferred<GatewayResult<SessionDto>>()
        gateway.onRefresh = { slow.await() }
        val session = manager(InMemorySessionStore(stored))

        val requests = (1..5).map { async { session.accessToken() } }
        advanceUntilIdle()
        slow.complete(GatewayResult.Success(session("r-novo", "a-novo", withUser = false)))
        val tokens = requests.map { it.await() }

        assertEquals(1, gateway.refreshCalls.size)
        assertEquals(List(5) { "a-novo" }, tokens)
    }

    @Test
    fun `access token expirado e renovado`() = runTest {
        val store = InMemorySessionStore()
        val session = manager(store)
        session.login("ana@example.org", "uma frase longa")

        now += 900_000 // 15 min depois
        assertEquals("a-0", session.accessToken())
        assertEquals(listOf("r-login"), gateway.refreshCalls)
    }

    @Test
    fun `refresh recusado apaga a sessao e pede novo login`() = runTest {
        val store = InMemorySessionStore(stored)
        gateway.onRefresh = { GatewayResult.Rejected(401, "INVALID_REFRESH_TOKEN", emptyList(), null) }
        val session = manager(store)

        session.start()
        advanceUntilIdle()

        assertNull(store.stored)
        assertEquals(SessionState.SignedOut(SessionNotice.SESSION_ENDED), session.state.value)
    }

    @Test
    fun `falha temporaria mantem a sessao guardada e permite nova tentativa`() = runTest {
        val store = InMemorySessionStore(stored)
        gateway.onRefresh = { GatewayResult.Failure(FailureKind.NOT_SENT) }
        val session = manager(store)

        session.start()
        advanceUntilIdle()
        assertEquals(SessionState.SignedIn("user-1", "ana", SessionStatus.TEMPORARILY_UNVERIFIED), session.state.value)
        assertEquals(stored, store.stored)

        // Servidor em baixo (5xx) também não apaga nada.
        gateway.onRefresh = { GatewayResult.Failure(FailureKind.SERVER) }
        assertNull(session.accessToken())
        assertEquals(stored, store.stored)
        assertEquals(2, gateway.refreshCalls.size)

        gateway.onRefresh = { GatewayResult.Success(session("r-novo", "a-novo", withUser = false)) }
        assertEquals("a-novo", session.accessToken())
        assertEquals(SessionStatus.ACTIVE, (session.state.value as SessionState.SignedIn).status)
    }

    @Test
    fun `falha ambigua nao e repetida automaticamente, so a pedido do utilizador`() = runTest {
        val store = InMemorySessionStore(stored)
        gateway.onRefresh = { GatewayResult.Failure(FailureKind.AMBIGUOUS) }
        val session = manager(store)

        session.start()
        advanceUntilIdle()
        assertEquals(SessionStatus.UNCONFIRMED, (session.state.value as SessionState.SignedIn).status)
        assertEquals(stored, store.stored) // sessão local mantém-se

        // Pedidos automáticos não reenviam o token, que o servidor pode já ter consumido.
        assertNull(session.accessToken())
        assertNull(session.accessTokenAfterUnauthorized("qualquer"))
        assertEquals(1, gateway.refreshCalls.size)

        // "Tentar novamente": o servidor diz que o token já foi usado → novo login.
        gateway.onRefresh = { GatewayResult.Rejected(401, "INVALID_REFRESH_TOKEN", emptyList(), null) }
        session.retry()
        advanceUntilIdle()
        assertEquals(2, gateway.refreshCalls.size)
        assertEquals(SessionState.SignedOut(SessionNotice.SESSION_ENDED), session.state.value)
    }

    // --- Logout ---

    @Test
    fun `logout limpa a sessao local e avisa o servidor`() = runTest {
        val store = InMemorySessionStore()
        val session = manager(store)
        session.login("ana@example.org", "uma frase longa")

        session.logout()
        assertEquals(SessionState.SignedOut(), session.state.value) // imediato
        advanceUntilIdle()

        assertNull(store.stored)
        assertEquals(listOf("r-login"), gateway.logoutCalls)
        assertEquals(SessionState.SignedOut(), session.state.value)
        assertNull(session.accessToken())
    }

    @Test
    fun `logout sem servidor limpa a sessao local e mostra a limitacao`() = runTest {
        val store = InMemorySessionStore()
        gateway.onLogout = { GatewayResult.Failure(FailureKind.NOT_SENT) }
        val session = manager(store)
        session.login("ana@example.org", "uma frase longa")

        session.logout()
        advanceUntilIdle()

        assertNull(store.stored)
        assertEquals(SessionState.SignedOut(SessionNotice.LOGGED_OUT_SERVER_UNREACHABLE), session.state.value)
        session.dismissNotice()
        assertEquals(SessionState.SignedOut(), session.state.value)
    }

    @Test
    fun `renovacao que termina depois do logout nao ressuscita a sessao`() = runTest {
        val slow = CompletableDeferred<GatewayResult<SessionDto>>()
        gateway.onRefresh = { slow.await() }
        val store = InMemorySessionStore(stored)
        val session = manager(store)

        session.start()
        advanceUntilIdle() // renovação em curso
        session.logout()
        advanceUntilIdle()
        slow.complete(GatewayResult.Success(session("r-tardio", "a-tardio", withUser = false)))
        advanceUntilIdle()

        assertNull(store.stored)
        assertEquals(SessionState.SignedOut(), session.state.value)
        assertEquals(listOf("r-guardado", "r-tardio"), gateway.logoutCalls) // o token tardio também é revogado
    }
}
