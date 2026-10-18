package com.example.portofit.data.remote

import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/** Endpoints públicos de utilizadores (docs/users-api.md §3.7–3.8). Não enviam token. */
interface PeopleApi {

    @GET("api/v1/users")
    suspend fun search(
        @Query("q") query: String,
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): PageDto<UserSummaryDto>

    @GET("api/v1/users/{userId}")
    suspend fun profile(@Path("userId") userId: String): PublicProfileDto
}

@Serializable
data class UserSummaryDto(
    val userId: String,
    val username: String,
    val description: String? = null,
)

/**
 * Perfil público. Só os campos públicos existem aqui: mesmo que uma resposta trouxesse outros
 * (ex.: email), seriam ignorados na leitura.
 */
@Serializable
data class PublicProfileDto(
    val userId: String,
    val username: String,
    val description: String? = null,
    val interests: List<String> = emptyList(),
)
