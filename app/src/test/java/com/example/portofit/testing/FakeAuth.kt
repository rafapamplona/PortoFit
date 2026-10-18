package com.example.portofit.testing

import com.example.portofit.data.auth.AuthGateway
import com.example.portofit.data.auth.GatewayResult
import com.example.portofit.data.auth.SecretCipher
import com.example.portofit.data.auth.SessionStore
import com.example.portofit.data.auth.StoredSession
import com.example.portofit.data.auth.StoredSessionResult
import com.example.portofit.data.remote.SessionDto
import com.example.portofit.data.remote.SessionUserDto

/** users-service falso: cada resposta é decidida por uma função (que pode suspender). */
class FakeAuthGateway : AuthGateway {
    val refreshCalls = mutableListOf<String>()
    val logoutCalls = mutableListOf<String>()
    var loginCalls = 0
    var registerCalls = 0

    var onLogin: suspend (String, String) -> GatewayResult<SessionDto> = { _, _ -> GatewayResult.Success(session("r-login", "a-login")) }
    var onRegister: suspend (String, String, String) -> GatewayResult<SessionDto> = { u, _, _ ->
        GatewayResult.Success(session("r-register", "a-register", username = u))
    }
    /** Por omissão, a n-ésima renovação (a contar de 0) devolve "r-n" e "a-n". */
    var onRefresh: suspend (String) -> GatewayResult<SessionDto> = {
        val n = refreshCalls.size - 1
        GatewayResult.Success(session("r-$n", "a-$n", withUser = false))
    }
    var onLogout: suspend (String) -> GatewayResult<Unit> = { GatewayResult.Success(Unit) }

    override suspend fun login(email: String, password: String): GatewayResult<SessionDto> {
        loginCalls++
        return onLogin(email, password)
    }

    override suspend fun register(username: String, email: String, password: String): GatewayResult<SessionDto> {
        registerCalls++
        return onRegister(username, email, password)
    }

    override suspend fun refresh(refreshToken: String): GatewayResult<SessionDto> {
        refreshCalls += refreshToken
        return onRefresh(refreshToken)
    }

    override suspend fun logout(refreshToken: String): GatewayResult<Unit> {
        logoutCalls += refreshToken
        return onLogout(refreshToken)
    }

    companion object {
        fun session(refresh: String, access: String, username: String = "ana", withUser: Boolean = true, accessTtl: Long = 900) = SessionDto(
            tokenType = "Bearer",
            accessToken = access,
            accessTokenExpiresIn = accessTtl,
            refreshToken = refresh,
            refreshTokenExpiresIn = 2_592_000,
            user = if (withUser) SessionUserDto("user-1", username) else null,
        )
    }
}

/** Armazenamento em memória com o mesmo contrato do ficheiro cifrado. */
class InMemorySessionStore(var stored: StoredSession? = null, var unreadable: Boolean = false) : SessionStore {
    var saves = 0
    override fun load(): StoredSessionResult = when {
        unreadable -> StoredSessionResult.Unreadable.also { unreadable = false }
        stored != null -> StoredSessionResult.Found(stored!!)
        else -> StoredSessionResult.None
    }
    override fun save(session: StoredSession) {
        saves++
        stored = session
    }
    override fun clear() {
        stored = null
    }
}

/** Cifra falsa reversível (só para testar o ficheiro sem Android Keystore). */
class FakeCipher : SecretCipher {
    override fun encrypt(plaintext: ByteArray) = byteArrayOf(9) + plaintext.map { (it.toInt() xor 0x5A).toByte() }
    override fun decrypt(ciphertext: ByteArray): ByteArray {
        require(ciphertext.firstOrNull() == 9.toByte()) { "formato desconhecido" }
        return ciphertext.drop(1).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }
}
