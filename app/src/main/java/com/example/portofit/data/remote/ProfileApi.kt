package com.example.portofit.data.remote

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.PUT

/** Perfil do próprio utilizador no users-service (docs/users-api.md §3.5–3.6). */
interface ProfileApi {

    @GET("api/v1/me")
    suspend fun me(@Header("Authorization") authorization: String): Response<ProfileDto>

    @PATCH("api/v1/me")
    suspend fun updateDescription(
        @Header("Authorization") authorization: String,
        @Body body: DescriptionUpdateDto,
    ): Response<ProfileDto>

    @PUT("api/v1/me/interests")
    suspend fun updateInterests(
        @Header("Authorization") authorization: String,
        @Body body: InterestsUpdateDto,
    ): Response<ProfileDto>
}

@Serializable
data class ProfileDto(
    val userId: String,
    val username: String,
    val email: String,
    val description: String? = null,
    val interests: List<String> = emptyList(),
)

/** `description: null` apaga a descrição (o campo é sempre enviado). */
@Serializable
data class DescriptionUpdateDto(val description: String?)

@Serializable
data class InterestsUpdateDto(val interests: List<String>)
