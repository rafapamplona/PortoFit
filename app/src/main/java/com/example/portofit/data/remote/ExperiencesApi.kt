package com.example.portofit.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
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

    /** Corpo construído campo a campo ([editBody]): `mediaId` ausente mantém, `null` remove, um id substitui. */
    @PATCH("api/v1/experiences/{experienceId}")
    suspend fun edit(
        @Header("Authorization") authorization: String,
        @Path("experienceId") experienceId: String,
        @Body body: JsonObject,
    ): Response<ExperienceDto>

    /** Fotografia (E2): fica pendente no servidor até ser associada numa publicação ou edição. */
    @Multipart
    @POST("api/v1/media")
    suspend fun uploadPhoto(
        @Header("Authorization") authorization: String,
        @Part file: MultipartBody.Part,
    ): Response<MediaUploadDto>

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
    val photo: ExperiencePhotoDto? = null,
)

@Serializable
data class ExperiencePhotoDto(val mediaId: String, val url: String, val width: Int, val height: Int)

@Serializable
data class MediaUploadDto(val mediaId: String, val width: Int, val height: Int)

@Serializable
data class ExperienceAuthorDto(val userId: String, val username: String)

@Serializable
data class ExperienceOfferingDto(val id: String, val name: String)

/** `mediaId` a null não é enviado (o Json da app não codifica valores por omissão). */
@Serializable
data class PublishExperienceDto(val placeId: String, val offeringId: String, val text: String, val stars: Int, val mediaId: String? = null)
