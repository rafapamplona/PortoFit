package com.example.portofit.data.remote

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/** Endpoints do catalog-service usados pela app. Parâmetros a null não são enviados. */
interface CatalogApi {

    @GET("api/v1/sports")
    suspend fun getSports(): SportsResponseDto

    @GET("api/v1/places")
    suspend fun searchPlaces(
        @Query("q") query: String?,
        @Query("sport") sportId: String?,
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): PageDto<PlaceSummaryDto>

    @GET("api/v1/places/{placeId}")
    suspend fun getPlace(@Path("placeId") placeId: String): PlaceDetailDto
}
