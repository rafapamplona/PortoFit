package com.example.portofit.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/** collections-service (docs/collections-api.md §4). Tudo com o access token: as listas são privadas. */
interface CollectionsApi {

    @GET("api/v1/collections")
    suspend fun list(
        @Header("Authorization") authorization: String,
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): Response<PageDto<CollectionDto>>

    @POST("api/v1/collections")
    suspend fun create(
        @Header("Authorization") authorization: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: CollectionNameDto,
    ): Response<CollectionDto>

    @GET("api/v1/collections/{collectionId}")
    suspend fun get(@Header("Authorization") authorization: String, @Path("collectionId") collectionId: String): Response<CollectionDto>

    @PATCH("api/v1/collections/{collectionId}")
    suspend fun rename(
        @Header("Authorization") authorization: String,
        @Path("collectionId") collectionId: String,
        @Body body: CollectionNameDto,
    ): Response<CollectionDto>

    @DELETE("api/v1/collections/{collectionId}")
    suspend fun delete(@Header("Authorization") authorization: String, @Path("collectionId") collectionId: String): Response<Unit>

    @GET("api/v1/collections/{collectionId}/items")
    suspend fun items(
        @Header("Authorization") authorization: String,
        @Path("collectionId") collectionId: String,
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): Response<CollectionItemsDto>

    @PUT("api/v1/collections/{collectionId}/items/{experienceId}")
    suspend fun addItem(
        @Header("Authorization") authorization: String,
        @Path("collectionId") collectionId: String,
        @Path("experienceId") experienceId: String,
    ): Response<Unit>

    @DELETE("api/v1/collections/{collectionId}/items/{experienceId}")
    suspend fun removeItem(
        @Header("Authorization") authorization: String,
        @Path("collectionId") collectionId: String,
        @Path("experienceId") experienceId: String,
    ): Response<Unit>
}

@Serializable
data class CollectionDto(val id: String, val name: String, val itemCount: Long, val createdAt: String, val updatedAt: String)

@Serializable
data class CollectionNameDto(val name: String)

/** `experience` fica como JSON: só é convertido quando o item está AVAILABLE (§6.3). */
@Serializable
data class CollectionItemDto(val experienceId: String, val addedAt: String, val availability: String, val experience: JsonObject? = null)

@Serializable
data class CollectionItemsDto(
    val items: List<CollectionItemDto>,
    val page: Int,
    val size: Int,
    val totalItems: Long,
    val totalPages: Int,
    val experiencesStatus: String,
)
