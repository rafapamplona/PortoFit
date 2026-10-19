package com.example.portofit.data.remote

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Pessoas no users-service (docs/users-api.md §3.7–3.10). Pesquisa, perfil e listas são públicos; o
 * perfil aceita um token opcional (para `followedByMe`); seguir exige token. Um cabeçalho null não é enviado.
 */
interface PeopleApi {

    @GET("api/v1/users")
    suspend fun search(
        @Query("q") query: String,
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): PageDto<UserSummaryDto>

    @GET("api/v1/users/{userId}")
    suspend fun profile(
        @Path("userId") userId: String,
        @Header("Authorization") authorization: String? = null,
    ): Response<PublicProfileDto>

    @GET("api/v1/users/{userId}/followers")
    suspend fun followers(@Path("userId") userId: String, @Query("page") page: Int, @Query("size") size: Int): PageDto<FollowEntryDto>

    @GET("api/v1/users/{userId}/following")
    suspend fun following(@Path("userId") userId: String, @Query("page") page: Int, @Query("size") size: Int): PageDto<FollowEntryDto>

    @PUT("api/v1/users/{userId}/follow")
    suspend fun follow(@Path("userId") userId: String, @Header("Authorization") authorization: String): Response<Unit>

    @DELETE("api/v1/users/{userId}/follow")
    suspend fun unfollow(@Path("userId") userId: String, @Header("Authorization") authorization: String): Response<Unit>
}

@Serializable
data class FollowEntryDto(
    val userId: String,
    val username: String,
    val description: String? = null,
)

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
    val followersCount: Long = 0,
    val followingCount: Long = 0,
    /** Ausente para visitantes e no próprio perfil: a relação não foi consultada. */
    val followedByMe: Boolean? = null,
)
