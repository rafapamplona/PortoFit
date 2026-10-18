package com.example.portofit.data.repository

import com.example.portofit.data.auth.SessionManager
import com.example.portofit.data.auth.SessionState
import com.example.portofit.data.remote.DescriptionUpdateDto
import com.example.portofit.data.remote.InterestsUpdateDto
import com.example.portofit.data.remote.ProblemDto
import com.example.portofit.data.remote.ProfileApi
import com.example.portofit.data.remote.ProfileDto
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import retrofit2.Response
import java.io.IOException

/** Perfil do próprio utilizador. O email só existe aqui (nunca em perfis públicos). */
data class Profile(
    val userId: String,
    val username: String,
    val email: String,
    val description: String?,
    /** Ids de modalidade do catálogo, pela ordem guardada. */
    val interests: List<String>,
)

enum class ProfileError {
    NETWORK,
    SERVER,

    /** Sem sessão (terminou ou foi recusada): é preciso entrar de novo. */
    SESSION_ENDED,

    /** A sessão existe mas não foi possível confirmá-la agora (falha temporária). */
    SESSION_UNVERIFIED,

    /** O users-service não conseguiu validar modalidades novas (docs/users-api.md §7). */
    CATALOG_UNAVAILABLE,
    UNKNOWN_INTERESTS,
    VALIDATION,
    NOT_CONFIGURED,
    UNEXPECTED,
}

sealed interface ProfileResult {
    data class Success(val profile: Profile) : ProfileResult
    data class Failure(val error: ProfileError) : ProfileResult
}

interface ProfileRepository {
    suspend fun load(): ProfileResult
    suspend fun saveDescription(description: String?): ProfileResult
    suspend fun saveInterests(interests: List<String>): ProfileResult
}

/**
 * Pedidos autenticados com o access token do [SessionManager]. Perante um 401, pede uma renovação
 * (partilhada com outros pedidos) e repete uma única vez.
 */
class NetworkProfileRepository(
    private val api: ProfileApi,
    private val session: SessionManager,
    private val json: Json,
) : ProfileRepository {

    override suspend fun load() = authorized { api.me(it) }

    override suspend fun saveDescription(description: String?) =
        authorized { api.updateDescription(it, DescriptionUpdateDto(description)) }

    override suspend fun saveInterests(interests: List<String>) =
        authorized { api.updateInterests(it, InterestsUpdateDto(interests)) }

    private suspend fun authorized(call: suspend (String) -> Response<ProfileDto>): ProfileResult =
        try {
            val token = session.accessToken() ?: return sessionFailure()
            var response = call("Bearer $token")
            if (response.code() == 401) {
                val fresh = session.accessTokenAfterUnauthorized(token) ?: return sessionFailure()
                response = call("Bearer $fresh")
            }
            toResult(response)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            ProfileResult.Failure(ProfileError.NETWORK)
        } catch (e: IllegalArgumentException) {
            ProfileResult.Failure(ProfileError.UNEXPECTED)
        }

    private fun sessionFailure() = ProfileResult.Failure(
        if (session.state.value is SessionState.SignedOut) ProfileError.SESSION_ENDED else ProfileError.SESSION_UNVERIFIED,
    )

    private fun toResult(response: Response<ProfileDto>): ProfileResult {
        if (response.isSuccessful) {
            val body = response.body() ?: return ProfileResult.Failure(ProfileError.UNEXPECTED)
            return ProfileResult.Success(Profile(body.userId, body.username, body.email, body.description, body.interests))
        }
        val code = runCatching { response.errorBody()?.string()?.let { json.decodeFromString<ProblemDto>(it).code } }.getOrNull()
        return ProfileResult.Failure(
            when {
                code == "CATALOG_UNAVAILABLE" -> ProfileError.CATALOG_UNAVAILABLE
                code == "UNKNOWN_INTERESTS" -> ProfileError.UNKNOWN_INTERESTS
                response.code() == 401 -> ProfileError.SESSION_ENDED
                response.code() == 400 -> ProfileError.VALIDATION
                response.code() in 500..599 -> ProfileError.SERVER
                else -> ProfileError.UNEXPECTED
            },
        )
    }
}

class UnconfiguredProfileRepository : ProfileRepository {
    private val failure = ProfileResult.Failure(ProfileError.NOT_CONFIGURED)
    override suspend fun load() = failure
    override suspend fun saveDescription(description: String?) = failure
    override suspend fun saveInterests(interests: List<String>) = failure
}
