package com.example.portofit.ui.feature.auth

import androidx.lifecycle.SavedStateHandle
import com.example.portofit.data.auth.FailureKind
import com.example.portofit.data.auth.FieldProblem
import com.example.portofit.data.auth.GatewayResult
import com.example.portofit.data.auth.SessionManager
import com.example.portofit.data.auth.SessionState
import com.example.portofit.data.remote.SessionDto
import com.example.portofit.testing.FakeAuthGateway
import com.example.portofit.testing.FakeAuthGateway.Companion.session
import com.example.portofit.testing.InMemorySessionStore
import com.example.portofit.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelsTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val gateway = FakeAuthGateway()
    private val store = InMemorySessionStore()
    private val session = SessionManager(gateway, store, CoroutineScope(Dispatchers.Unconfined), io = UnconfinedTestDispatcher())

    private fun login(saved: SavedStateHandle = SavedStateHandle()) = LoginViewModel(session, saved)
    private fun register() = RegisterViewModel(session, SavedStateHandle())

    // --- Login ---

    @Test
    fun `login valida os campos antes de enviar`() {
        val vm = login()
        vm.onEmailChange("sem-arroba")
        vm.submit()
        assertEquals(FieldProblem.INVALID_FORMAT, vm.uiState.value.emailProblem)
        assertEquals(FieldProblem.REQUIRED, vm.uiState.value.passwordProblem)
        assertEquals(0, gateway.loginCalls)
    }

    @Test
    fun `envios repetidos durante o pedido sao ignorados`() {
        val slow = CompletableDeferred<GatewayResult<SessionDto>>()
        gateway.onLogin = { _, _ -> slow.await() }
        val vm = login()
        vm.onEmailChange(" Ana@Example.org ")
        vm.onPasswordChange("uma frase longa")

        vm.submit()
        assertTrue(vm.uiState.value.isSubmitting)
        vm.submit()
        vm.submit()
        assertEquals(1, gateway.loginCalls)

        slow.complete(GatewayResult.Success(session("r", "a")))
        assertTrue(vm.uiState.value.completed)
        assertFalse(vm.uiState.value.isSubmitting)
        assertEquals("", vm.uiState.value.password) // palavra-passe não fica no estado
        assertEquals(SessionState.SignedIn("user-1", "ana"), session.state.value)
    }

    @Test
    fun `credenciais invalidas e falhas de rede dao erros distintos`() {
        gateway.onLogin = { _, _ -> GatewayResult.Rejected(401, "INVALID_CREDENTIALS", emptyList(), null) }
        val vm = login()
        vm.onEmailChange("ana@example.org")
        vm.onPasswordChange("errada-errada")
        vm.submit()
        assertEquals(FormError.INVALID_CREDENTIALS, vm.uiState.value.formError)
        assertFalse(vm.uiState.value.isSubmitting)

        gateway.onLogin = { _, _ -> GatewayResult.Failure(FailureKind.NOT_SENT) }
        vm.submit()
        assertEquals(FormError.NETWORK, vm.uiState.value.formError)

        gateway.onLogin = { _, _ -> GatewayResult.Rejected(429, "TOO_MANY_ATTEMPTS", emptyList(), 601) }
        vm.submit()
        assertEquals(FormError.TOO_MANY_ATTEMPTS, vm.uiState.value.formError)
        assertEquals(11L, vm.uiState.value.retryAfterMinutes)
        assertNull(store.stored)
    }

    @Test
    fun `o email sobrevive a recriacao mas a palavra-passe nao`() {
        val saved = SavedStateHandle()
        login(saved).apply {
            onEmailChange("ana@example.org")
            onPasswordChange("segredo-segredo")
        }
        val recreated = login(saved)
        assertEquals("ana@example.org", recreated.uiState.value.email)
        assertEquals("", recreated.uiState.value.password)
        assertFalse(saved.keys().any { it.contains("password", ignoreCase = true) })
    }

    // --- Registo ---

    @Test
    fun `registo valida como o contrato`() {
        val vm = register()
        vm.onUsernameChange("demo_ana")
        vm.onEmailChange("ana@example.org")
        vm.onPasswordChange("ã".repeat(40)) // 40 caracteres, 80 bytes
        vm.submit()
        assertEquals(FieldProblem.RESERVED, vm.uiState.value.usernameProblem)
        assertEquals(FieldProblem.TOO_SHORT_OR_LONG, vm.uiState.value.passwordProblem)
        assertEquals(0, gateway.registerCalls)

        vm.onUsernameChange("A")
        vm.onPasswordChange("ana@example.org")
        vm.submit()
        assertEquals(FieldProblem.INVALID_FORMAT, vm.uiState.value.usernameProblem)
        assertEquals(FieldProblem.SAME_AS_IDENTITY, vm.uiState.value.passwordProblem)
    }

    @Test
    fun `registo normaliza e mostra conflitos do servidor nos campos`() {
        gateway.onRegister = { _, _, _ -> GatewayResult.Rejected(409, "CONFLICT", listOf("username"), null) }
        val vm = register()
        vm.onUsernameChange("  Ana.Silva ")
        vm.onEmailChange("Ana@Example.org")
        vm.onPasswordChange("uma frase longa")
        vm.submit()
        assertEquals(FieldProblem.ALREADY_USED, vm.uiState.value.usernameProblem)
        assertNull(vm.uiState.value.emailProblem)

        var sent: Triple<String, String, String>? = null
        gateway.onRegister = { u, e, p -> sent = Triple(u, e, p); GatewayResult.Success(session("r", "a", username = u)) }
        vm.onUsernameChange("ana.silva2")
        vm.submit()
        assertEquals("ana.silva2", sent?.first)
        assertEquals("ana@example.org", sent?.second)
        assertTrue(vm.uiState.value.completed)
        assertEquals(SessionState.SignedIn("user-1", "ana.silva2"), session.state.value)
    }
}
