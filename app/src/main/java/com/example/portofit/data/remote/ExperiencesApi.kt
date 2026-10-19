package com.example.portofit.data.remote

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * experiences-service, etapa E1 (docs/experiences-api.md §4). Consultas públicas (sem token); publicar,
 * editar e eliminar com o access token. Parâmetros a null não são enviados.
 */
interface ExperiencesApi {

    @GET("api/v1/experiences")
    suspend fun list(
        @Query("placeId") placeId: String?,
        @Query("authorId") authorId: String?,
        @Query("sort") sort: String,
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): PageDto<ExperienceDto>

    @GET("api/v1/experiences/{experienceId}")
    suspend fun get(@Path("experienceId") experienceId: String): ExperienceDto

    @POST("api/v1/experiences")
    suspend fun publish(
        @Header("Authorization") authorization: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: PublishExperienceDto,
    ): Response<ExperienceDto>

    @PATCH("api/v1/experiences/{experienceId}")
    suspend fun edit(
        @Header("Authorization") authorization: String,
        @Path("experienceId") experienceId: String,
        @Body body: EditExperienceDto,
    ): Response<ExperienceDto>

    @DELETE("api/v1/experiences/{experienceId}")
    suspend fun delete(
        @Header("Authorization") authorization: String,
        @Path("experienceId") experienceId: String,
    ): Response<Unit>
}

@Serializable
data class ExperienceDto(
    val id: String,
    val author: ExperienceAuthorDto,
    val place: PlaceRefDto,
    val offering: ExperienceOfferingDto,
    val sport: SportRefDto,
    val text: String,
    val stars: Int,
    val createdAt: String,
    val editedAt: String? = null,
)

@Serializable
data class ExperienceAuthorDto(val userId: String, val username: String)

@Serializable
data class ExperienceOfferingDto(val id: String, val name: String)

@Serializable
data class PublishExperienceDto(val placeId: String, val offeringId: String, val text: String, val stars: Int)

/** Campos a null não são enviados (o Json da app não codifica valores por omissão). */
@Serializable
data class EditExperienceDto(val text: String? = null, val stars: Int? = null)
