package com.example.portofit.data.remote

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/** Endpoints de sessão do users-service (docs/users-api.md §3.1–3.4). */
interface UsersApi {

    @POST("api/v1/auth/register")
    suspend fun register(@Body body: RegisterRequestDto): Response<SessionDto>

    @POST("api/v1/auth/login")
    suspend fun login(@Body body: LoginRequestDto): Response<SessionDto>

    @POST("api/v1/auth/refresh")
    suspend fun refresh(@Body body: RefreshRequestDto): Response<SessionDto>

    @POST("api/v1/auth/logout")
    suspend fun logout(@Body body: RefreshRequestDto): Response<Unit>
}

// Os toString() destes DTOs não incluem palavras-passe nem tokens, para nunca irem parar a logs.

@Serializable
data class RegisterRequestDto(val username: String, val email: String, val password: String) {
    override fun toString() = "RegisterRequestDto(username=$username)"
}

@Serializable
data class LoginRequestDto(val email: String, val password: String) {
    override fun toString() = "LoginRequestDto()"
}

@Serializable
data class RefreshRequestDto(val refreshToken: String) {
    override fun toString() = "RefreshRequestDto()"
}

@Serializable
data class SessionDto(
    val tokenType: String,
    val accessToken: String,
    val accessTokenExpiresIn: Long,
    val refreshToken: String,
    val refreshTokenExpiresIn: Long,
    /** Presente no registo e no login; ausente na renovação. */
    val user: SessionUserDto? = null,
) {
    override fun toString() = "SessionDto(user=${user?.userId})"
}

@Serializable
data class SessionUserDto(
    val userId: String,
    val username: String,
)

/** Corpo de erro (Problem Details com `code` e `errors`). */
@Serializable
data class ProblemDto(
    val code: String? = null,
    val errors: List<ProblemFieldDto> = emptyList(),
    /** experiences-service, 409 IDEMPOTENCY_CONFLICT: DIFFERENT_CONTENT ou EXPERIENCE_DELETED. */
    val reason: String? = null,
    val experienceId: String? = null,
)

@Serializable
data class ProblemFieldDto(val field: String, val message: String = "")
