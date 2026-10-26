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
        // Proximidade (docs/catalog-api.md §3.2): as três em conjunto, ou nenhuma.
        @Query("lat") latitude: String?,
        @Query("lng") longitude: String?,
        @Query("radiusKm") radiusKm: String?,
        @Query("sort") sort: String?,
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): PageDto<PlaceSummaryDto>

    @GET("api/v1/places/{placeId}")
    suspend fun getPlace(@Path("placeId") placeId: String): PlaceDetailDto

    /** Sem avaliação: só o estado e o local, para o detalhe de uma experiência. */
    @GET("api/v1/offerings/{offeringId}?includeRating=false")
    suspend fun getOffering(@Path("offeringId") offeringId: String): OfferingDetailDto
}
