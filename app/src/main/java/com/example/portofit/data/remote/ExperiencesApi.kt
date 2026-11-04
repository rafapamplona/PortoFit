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
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * experiences-service (docs/experiences-api.md §4, §12, §13). Consultas públicas; o detalhe aceita um token
 * opcional (para `likedByMe`). Publicar, editar, eliminar, comentar e gostar com o access token. Parâmetros e
 * cabeçalhos a null não são enviados.
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

    /** Pesquisa pública por texto ([q]: 2 a 100 caracteres, docs/experiences-api.md §4.2). */
    @GET("api/v1/experiences")
    suspend fun search(
        @Query("q") q: String,
        @Query("sort") sort: String,
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): PageDto<ExperienceDto>

    @GET("api/v1/experiences/{experienceId}")
    suspend fun get(
        @Path("experienceId") experienceId: String,
        @Header("Authorization") authorization: String? = null,
    ): Response<ExperienceDto>

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

    // --- E3: comentários, gostos e visualizações (§13) ---

    @GET("api/v1/experiences/{experienceId}/comments")
    suspend fun comments(
        @Path("experienceId") experienceId: String,
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): PageDto<CommentDto>

    @POST("api/v1/experiences/{experienceId}/comments")
    suspend fun postComment(
        @Header("Authorization") authorization: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Path("experienceId") experienceId: String,
        @Body body: CommentBodyDto,
    ): Response<CommentDto>

    @PATCH("api/v1/comments/{commentId}")
    suspend fun editComment(
        @Header("Authorization") authorization: String,
        @Path("commentId") commentId: String,
        @Body body: CommentBodyDto,
    ): Response<CommentDto>

    @DELETE("api/v1/comments/{commentId}")
    suspend fun deleteComment(
        @Header("Authorization") authorization: String,
        @Path("commentId") commentId: String,
    ): Response<Unit>

    @PUT("api/v1/experiences/{experienceId}/like")
    suspend fun like(
        @Header("Authorization") authorization: String,
        @Path("experienceId") experienceId: String,
    ): Response<Unit>

    @DELETE("api/v1/experiences/{experienceId}/like")
    suspend fun unlike(
        @Header("Authorization") authorization: String,
        @Path("experienceId") experienceId: String,
    ): Response<Unit>

    // --- E4: feeds e dashboard (§14) ---

    /** Público; com token acrescenta `likedByMe`. */
    @GET("api/v1/feed/popular")
    suspend fun popularFeed(
        @Query("sportId") sportId: String?,
        @Query("page") page: Int,
        @Query("size") size: Int,
        @Header("Authorization") authorization: String? = null,
    ): Response<FeedPageDto>

    @GET("api/v1/feed")
    suspend fun personalFeed(
        @Header("Authorization") authorization: String,
        @Query("sportId") sportId: String?,
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): Response<FeedPageDto>

    @GET("api/v1/me/stats")
    suspend fun stats(@Header("Authorization") authorization: String, @Query("period") period: String): Response<StatsDto>

    @GET("api/v1/me/stats/experiences")
    suspend fun statsExperiences(
        @Header("Authorization") authorization: String,
        @Query("period") period: String,
        @Query("sort") sort: String,
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): Response<StatsPageDto>

    /** Com sessão, o token; sem sessão, a chave aleatória da instalação. O servidor elimina as repetições. */
    @POST("api/v1/experiences/{experienceId}/views")
    suspend fun registerView(
        @Path("experienceId") experienceId: String,
        @Header("Authorization") authorization: String?,
        @Header("X-Viewer-Key") viewerKey: String?,
    ): Response<Unit>
}

@Serializable
data class CommentDto(
    val id: String,
    val experienceId: String,
    val author: ExperienceAuthorDto,
    val text: String,
    val createdAt: String,
    val editedAt: String? = null,
)

@Serializable
data class CommentBodyDto(val text: String)

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
    // E3: ausentes num servidor anterior (ficam a 0); likedByMe ausente = relação não consultada.
    val likesCount: Long = 0,
    val commentsCount: Long = 0,
    val likedByMe: Boolean? = null,
    /** Só no feed pessoal (E4). */
    val matchedBy: List<String>? = null,
)

@Serializable
data class FeedPageDto(
    val items: List<ExperienceDto>,
    val page: Int,
    val size: Int,
    val totalItems: Long,
    val totalPages: Int,
    val mode: String,
    val fallbackReason: String? = null,
    val recentSince: String? = null,
    val generatedAt: String,
)

@Serializable
data class StatsReceivedDto(
    val likes: Long,
    val comments: Long,
    val interactions: Long,
    val views: Long,
    val viewsRule: String? = null,
    val commentsIncludeOwn: Boolean = true,
)

@Serializable
data class StatsDto(
    val period: String,
    val from: String? = null,
    val to: String,
    val generatedAt: String,
    val timezone: String? = null,
    val received: StatsReceivedDto,
    val publications: Long,
    val starsGiven: Map<String, Long>,
)

@Serializable
data class StatsExperienceDto(
    val id: String,
    val place: PlaceRefDto,
    val offering: ExperienceOfferingDto,
    val sport: SportRefDto,
    val stars: Int,
    val createdAt: String,
)

@Serializable
data class ExperienceStatsDto(val experience: StatsExperienceDto, val likes: Long, val comments: Long, val interactions: Long, val views: Long)

@Serializable
data class StatsPageDto(
    val items: List<ExperienceStatsDto>,
    val page: Int,
    val size: Int,
    val totalItems: Long,
    val totalPages: Int,
    val period: String,
    val from: String? = null,
    val to: String,
    val generatedAt: String,
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
