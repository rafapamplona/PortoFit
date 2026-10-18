package com.example.portofit.data.auth

import com.example.portofit.data.remote.SessionDto
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/** Estado da sessão visto pela interface. */
sealed interface SessionState {
    /** A ler a sessão guardada, ao abrir a app. */
    data object Loading : SessionState

    data class SignedOut(val notice: SessionNotice? = null) : SessionState

    data class SignedIn(val userId: String, val username: String, val status: SessionStatus = SessionStatus.ACTIVE) : SessionState
}

enum class SessionStatus {
    ACTIVE,

    /** A confirmar a sessão guardada junto do servidor. */
    VERIFYING,

    /** Falha temporária (rede, servidor, limite de tentativas): a sessão mantém-se; "Tentar novamente". */
    TEMPORARILY_UNVERIFIED,

    /** Renovação de resultado desconhecido: não se repete sozinha; tentar de novo pode pedir novo login. */
    UNCONFIRMED,
}

enum class SessionNotice {
    /** O servidor recusou a sessão (expirada, revogada ou reutilizada) ou não foi possível lê-la. */
    SESSION_ENDED,

    /** Logout local feito, mas o servidor não foi avisado (docs/users-api.md §6.5). */
    LOGGED_OUT_SERVER_UNREACHABLE,
}

/** Resultado de login ou registo, para o formulário. */
sealed interface AuthOutcome {
    data object Success : AuthOutcome
    data object InvalidCredentials : AuthOutcome
    data class TooManyAttempts(val retryAfterSeconds: Long?) : AuthOutcome
    data class FieldErrors(val fields: Map<String, FieldProblem>) : AuthOutcome
    data object NetworkUnavailable : AuthOutcome
    data object ServerUnavailable : AuthOutcome
    data object NotConfigured : AuthOutcome
    data object Unexpected : AuthOutcome
}

/**
 * Sessão da app (docs/users-api.md §8.3).
 *
 * - Access token só em memória; refresh token cifrado em [store].
 * - Uma única renovação de cada vez: quem chega enquanto outra decorre espera e reutiliza o resultado.
 * - Depois de uma renovação de resultado desconhecido não há novas tentativas automáticas.
 * - Falhas temporárias nunca apagam a sessão guardada.
 */
class SessionManager(
    private val gateway: AuthGateway,
    private val store: SessionStore,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private class AccessToken(val value: String, val expiresAtMillis: Long)

    private val _state = MutableStateFlow<SessionState>(SessionState.Loading)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    @Volatile
    private var access: AccessToken? = null

    /** Muda a cada login, registo ou logout: resultados atrasados de sessões anteriores são descartados. */
    private val generation = AtomicInteger()

    /** Verdadeiro depois de uma renovação ambígua: só uma ação do utilizador volta a tentar. */
    @Volatile
    private var awaitingUserRetry = false

    private val refreshMutex = Mutex()

    /** Ao abrir a app: lê a sessão guardada e confirma-a com uma renovação. Não bloqueia o resto da app. */
    fun start() {
        scope.launch {
            when (val stored = withContext(io) { store.load() }) {
                StoredSessionResult.None -> _state.value = SessionState.SignedOut()
                StoredSessionResult.Unreadable -> _state.value = SessionState.SignedOut(SessionNotice.SESSION_ENDED)
                is StoredSessionResult.Found -> {
                    _state.value = SessionState.SignedIn(stored.session.userId, stored.session.username, SessionStatus.VERIFYING)
                    refresh(staleToken = null, userInitiated = false)
                }
            }
        }
    }

    suspend fun login(email: String, password: String): AuthOutcome = authenticate { gateway.login(email, password) }

    suspend fun register(username: String, email: String, password: String): AuthOutcome =
        authenticate { gateway.register(username, email, password) }

    /** Access token válido para um pedido autenticado, renovando se estiver a expirar. */
    suspend fun accessToken(): String? {
        access?.takeIf { !it.expiringSoon() }?.let { return it.value }
        return refresh(staleToken = access?.value, userInitiated = false)
    }

    /** Depois de um 401 a um pedido feito com [rejectedToken]. */
    suspend fun accessTokenAfterUnauthorized(rejectedToken: String): String? = refresh(rejectedToken, userInitiated = false)

    /** "Tentar novamente" pedido pelo utilizador (é a única forma de repetir após uma falha ambígua). */
    fun retry() {
        scope.launch { refresh(staleToken = access?.value, userInitiated = true) }
    }

    /**
     * Termina a sessão neste aparelho de imediato e avisa o servidor (best effort). Se o servidor não
     * responder, a sessão local fica terminada na mesma e é mostrado o aviso correspondente.
     */
    fun logout() {
        val myGeneration = generation.incrementAndGet()
        access = null
        awaitingUserRetry = false
        _state.value = SessionState.SignedOut()
        scope.launch {
            val stored = withContext(io) { store.load().also { store.clear() } }
            val token = (stored as? StoredSessionResult.Found)?.session?.refreshToken ?: return@launch
            if (gateway.logout(token) !is GatewayResult.Success && myGeneration == generation.get()) {
                _state.value = SessionState.SignedOut(SessionNotice.LOGGED_OUT_SERVER_UNREACHABLE)
            }
        }
    }

    fun dismissNotice() {
        _state.update { if (it is SessionState.SignedOut) SessionState.SignedOut() else it }
    }

    private suspend fun authenticate(call: suspend () -> GatewayResult<SessionDto>): AuthOutcome {
        val myGeneration = generation.get()
        return when (val result = call()) {
            is GatewayResult.Success -> {
                val user = result.value.user ?: return AuthOutcome.Unexpected
                withContext(NonCancellable) {
                    if (myGeneration != generation.get()) return@withContext
                    val session = StoredSession(user.userId, user.username, result.value.refreshToken)
                    withContext(io) { store.save(session) } // guardado antes de ficar visível
                    generation.incrementAndGet()
                    access = AccessToken(result.value.accessToken, expiresAt(result.value.accessTokenExpiresIn))
                    awaitingUserRetry = false
                    _state.value = SessionState.SignedIn(user.userId, user.username)
                }
                AuthOutcome.Success
            }
            is GatewayResult.Rejected -> when {
                result.code == "INVALID_CREDENTIALS" -> AuthOutcome.InvalidCredentials
                result.status == 429 -> AuthOutcome.TooManyAttempts(result.retryAfterSeconds)
                result.status == 409 -> AuthOutcome.FieldErrors(result.fields.associateWith { FieldProblem.ALREADY_USED })
                result.status == 400 && result.fields.isNotEmpty() ->
                    AuthOutcome.FieldErrors(result.fields.associateWith { FieldProblem.INVALID_FORMAT })
                else -> AuthOutcome.Unexpected
            }
            is GatewayResult.Failure -> when (result.kind) {
                FailureKind.NOT_SENT, FailureKind.AMBIGUOUS -> AuthOutcome.NetworkUnavailable
                FailureKind.SERVER -> AuthOutcome.ServerUnavailable
                FailureKind.NOT_CONFIGURED -> AuthOutcome.NotConfigured
                FailureKind.UNEXPECTED -> AuthOutcome.Unexpected
            }
        }
    }

    private suspend fun refresh(staleToken: String?, userInitiated: Boolean): String? = refreshMutex.withLock {
        // Outro pedido já renovou enquanto este esperava: reutiliza o resultado.
        access?.let { if (it.value != staleToken && !it.expiringSoon()) return it.value }
        if (awaitingUserRetry && !userInitiated) return null

        val stored = (withContext(io) { store.load() } as? StoredSessionResult.Found)?.session
        if (stored == null) {
            if (_state.value is SessionState.SignedIn) endSession(SessionNotice.SESSION_ENDED)
            return null
        }
        val myGeneration = generation.get()
        setStatus(SessionStatus.VERIFYING)

        when (val result = gateway.refresh(stored.refreshToken)) {
            is GatewayResult.Success -> withContext(NonCancellable) {
                if (myGeneration != generation.get()) {
                    // Logout ou nova sessão entretanto: não ressuscitar esta; avisar o servidor.
                    gateway.logout(result.value.refreshToken)
                    return@withContext null
                }
                withContext(io) { store.save(stored.copy(refreshToken = result.value.refreshToken)) }
                access = AccessToken(result.value.accessToken, expiresAt(result.value.accessTokenExpiresIn))
                awaitingUserRetry = false
                setStatus(SessionStatus.ACTIVE)
                result.value.accessToken
            }
            is GatewayResult.Rejected -> {
                if (result.status == 401 && myGeneration == generation.get()) {
                    endSession(SessionNotice.SESSION_ENDED)
                } else {
                    setStatus(SessionStatus.TEMPORARILY_UNVERIFIED) // 429 ou outro erro do pedido
                }
                null
            }
            is GatewayResult.Failure -> {
                if (result.kind == FailureKind.AMBIGUOUS) {
                    awaitingUserRetry = true
                    setStatus(SessionStatus.UNCONFIRMED)
                } else {
                    setStatus(SessionStatus.TEMPORARILY_UNVERIFIED)
                }
                null
            }
        }
    }

    private suspend fun endSession(notice: SessionNotice) {
        generation.incrementAndGet()
        access = null
        awaitingUserRetry = false
        withContext(io) { store.clear() }
        _state.value = SessionState.SignedOut(notice)
    }

    private fun setStatus(status: SessionStatus) {
        _state.update { if (it is SessionState.SignedIn) it.copy(status = status) else it }
    }

    private fun expiresAt(expiresInSeconds: Long) = clock() + expiresInSeconds * 1000

    /** Renova com 30 s de antecedência, para o token não expirar a meio de um pedido. */
    private fun AccessToken.expiringSoon() = clock() >= expiresAtMillis - 30_000
}
