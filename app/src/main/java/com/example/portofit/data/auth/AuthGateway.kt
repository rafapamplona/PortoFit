package com.example.portofit.data.auth

import com.example.portofit.data.remote.LoginRequestDto
import com.example.portofit.data.remote.ProblemDto
import com.example.portofit.data.remote.RefreshRequestDto
import com.example.portofit.data.remote.RegisterRequestDto
import com.example.portofit.data.remote.SessionDto
import com.example.portofit.data.remote.UsersApi
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import retrofit2.Response
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException

/** Porquê um pedido falhou sem resposta útil do servidor. */
enum class FailureKind {
    /** O pedido não chegou a sair (sem rede, ligação recusada): é seguro repetir. */
    NOT_SENT,

    /** Pode ter chegado ao servidor (timeout, ligação cortada depois do envio): resultado desconhecido. */
    AMBIGUOUS,

    /** O servidor respondeu 5xx. */
    SERVER,

    /** A app foi compilada sem URL do serviço (ex.: release nesta fase). */
    NOT_CONFIGURED,

    /** Resposta que não corresponde ao contrato. */
    UNEXPECTED,
}

sealed interface GatewayResult<out T> {
    data class Success<T>(val value: T) : GatewayResult<T>

    /** O servidor recusou o pedido (4xx) com um corpo Problem Details. */
    data class Rejected(
        val status: Int,
        val code: String?,
        val fields: List<String>,
        val retryAfterSeconds: Long?,
    ) : GatewayResult<Nothing>

    data class Failure(val kind: FailureKind) : GatewayResult<Nothing>
}

/** Chamadas de sessão ao users-service. As implementações nunca registam credenciais nem tokens. */
interface AuthGateway {
    suspend fun register(username: String, email: String, password: String): GatewayResult<SessionDto>
    suspend fun login(email: String, password: String): GatewayResult<SessionDto>
    suspend fun refresh(refreshToken: String): GatewayResult<SessionDto>
    suspend fun logout(refreshToken: String): GatewayResult<Unit>
}

class RetrofitAuthGateway(private val api: UsersApi, private val json: Json) : AuthGateway {

    override suspend fun register(username: String, email: String, password: String) =
        call { api.register(RegisterRequestDto(username, email, password)) }

    override suspend fun login(email: String, password: String) = call { api.login(LoginRequestDto(email, password)) }

    override suspend fun refresh(refreshToken: String) = call { api.refresh(RefreshRequestDto(refreshToken)) }

    override suspend fun logout(refreshToken: String) = call { api.logout(RefreshRequestDto(refreshToken)) }

    private suspend fun <T> call(block: suspend () -> Response<T>): GatewayResult<T> =
        try {
            val response = block()
            when {
                response.isSuccessful -> {
                    @Suppress("UNCHECKED_CAST")
                    val body = response.body() ?: (Unit as T)
                    GatewayResult.Success(body)
                }
                response.code() in 500..599 -> GatewayResult.Failure(FailureKind.SERVER)
                else -> rejected(response)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            GatewayResult.Failure(classify(e))
        } catch (e: IllegalArgumentException) {
            // kotlinx.serialization: corpo de sucesso que não corresponde ao contrato.
            GatewayResult.Failure(FailureKind.UNEXPECTED)
        }

    private fun rejected(response: Response<*>): GatewayResult.Rejected {
        val problem = runCatching { response.errorBody()?.string()?.let { json.decodeFromString<ProblemDto>(it) } }.getOrNull()
        return GatewayResult.Rejected(
            status = response.code(),
            code = problem?.code,
            fields = problem?.errors?.map { it.field }.orEmpty(),
            retryAfterSeconds = response.headers()["Retry-After"]?.toLongOrNull(),
        )
    }

    companion object {
        /**
         * Só é seguro dizer que o pedido não foi enviado quando a ligação nem chegou a abrir.
         * Qualquer outra falha de E/S (timeout, ligação cortada) é tratada como ambígua.
         */
        fun classify(e: IOException): FailureKind = when (e) {
            is UnknownHostException, is ConnectException, is NoRouteToHostException -> FailureKind.NOT_SENT
            else -> FailureKind.AMBIGUOUS
        }
    }
}

class UnconfiguredAuthGateway : AuthGateway {
    private val failure = GatewayResult.Failure(FailureKind.NOT_CONFIGURED)
    override suspend fun register(username: String, email: String, password: String) = failure
    override suspend fun login(email: String, password: String) = failure
    override suspend fun refresh(refreshToken: String) = failure
    override suspend fun logout(refreshToken: String) = failure
}
