package com.example.portofit.data.repository

import com.example.portofit.data.auth.SessionManager
import com.example.portofit.data.auth.SessionState
import com.example.portofit.data.model.Page
import com.example.portofit.data.remote.PeopleApi
import com.example.portofit.data.remote.ProblemDto
import com.example.portofit.data.remote.PublicProfileDto
import com.example.portofit.data.remote.toModel
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import retrofit2.Response
import java.io.IOException

/** Pessoa num resultado de pesquisa ou numa lista de seguidores/seguidos. */
data class UserSummary(
    val userId: String,
    val username: String,
    val description: String?,
)

/** Perfil público: nunca tem email nem outros dados privados, mesmo quando é o próprio utilizador. */
data class PublicProfile(
    val userId: String,
    val username: String,
    val description: String?,
    val interests: List<String>,
    val followersCount: Long = 0,
    val followingCount: Long = 0,
    /**
     * true/false só quando o servidor confirmou a relação para a sessão atual; null quando não foi
     * consultada (visitante, próprio perfil ou sessão que não foi possível confirmar).
     */
    val followedByMe: Boolean? = null,
)

enum class FollowError { NETWORK, SERVER, SESSION_ENDED, SESSION_UNVERIFIED, NOT_FOUND, CANNOT_FOLLOW_SELF, NOT_CONFIGURED, UNEXPECTED }

sealed interface FollowResult {
    data object Success : FollowResult
    data class Failure(val error: FollowError) : FollowResult
}

interface PeopleRepository {
    suspend fun search(query: String, page: Int, pageSize: Int): DataResult<Page<UserSummary>>
    suspend fun profile(userId: String): DataResult<PublicProfile>
    suspend fun followers(userId: String, page: Int, pageSize: Int): DataResult<Page<UserSummary>>
    suspend fun following(userId: String, page: Int, pageSize: Int): DataResult<Page<UserSummary>>

    /** Seguir (true) ou deixar de seguir (false); idempotente no servidor. */
    suspend fun setFollowing(userId: String, follow: Boolean): FollowResult
}

/**
 * Pesquisa, perfis e listas são públicos. Com sessão, o perfil é pedido com o access token para obter
 * `followedByMe`; sem token disponível é pedido como visitante (a relação fica por confirmar).
 */
class NetworkPeopleRepository(
    private val api: PeopleApi,
    private val session: SessionManager,
    private val json: Json,
) : PeopleRepository {

    override suspend fun search(query: String, page: Int, pageSize: Int) = apiCall {
        api.search(query, page, pageSize).toModel { UserSummary(it.userId, it.username, it.description) }
    }

    override suspend fun followers(userId: String, page: Int, pageSize: Int) = apiCall {
        api.followers(userId, page, pageSize).toModel { UserSummary(it.userId, it.username, it.description) }
    }

    override suspend fun following(userId: String, page: Int, pageSize: Int) = apiCall {
        api.following(userId, page, pageSize).toModel { UserSummary(it.userId, it.username, it.description) }
    }

    override suspend fun profile(userId: String): DataResult<PublicProfile> = apiCall {
        val token = if (session.state.value is SessionState.SignedIn) session.accessToken() else null
        var response = api.profile(userId, token?.let { "Bearer $it" })
        if (response.code() == 401 && token != null) {
            val fresh = session.accessTokenAfterUnauthorized(token)
            response = api.profile(userId, fresh?.let { "Bearer $it" })
        }
        response.bodyOrThrow().toModel()
    }

    override suspend fun setFollowing(userId: String, follow: Boolean): FollowResult =
        try {
            val token = session.accessToken() ?: return sessionFailure()
            val call: suspend (String) -> Response<Unit> = { t ->
                if (follow) api.follow(userId, "Bearer $t") else api.unfollow(userId, "Bearer $t")
            }
            var response = call(token)
            if (response.code() == 401) {
                val fresh = session.accessTokenAfterUnauthorized(token) ?: return sessionFailure()
                response = call(fresh)
            }
            toFollowResult(response)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            FollowResult.Failure(FollowError.NETWORK)
        }

    private fun sessionFailure() = FollowResult.Failure(
        if (session.state.value is SessionState.SignedOut) FollowError.SESSION_ENDED else FollowError.SESSION_UNVERIFIED,
    )

    private fun toFollowResult(response: Response<Unit>): FollowResult {
        if (response.isSuccessful) return FollowResult.Success
        val code = runCatching { response.errorBody()?.string()?.let { json.decodeFromString<ProblemDto>(it).code } }.getOrNull()
        return FollowResult.Failure(
            when {
                code == "CANNOT_FOLLOW_SELF" -> FollowError.CANNOT_FOLLOW_SELF
                response.code() == 401 -> FollowError.SESSION_ENDED
                response.code() == 404 -> FollowError.NOT_FOUND
                response.code() in 500..599 -> FollowError.SERVER
                else -> FollowError.UNEXPECTED
            },
        )
    }

    private fun PublicProfileDto.toModel() =
        PublicProfile(userId, username, description, interests, followersCount, followingCount, followedByMe)

    /** Para o apiCall classificar erros HTTP como nas restantes chamadas públicas. */
    private fun <T> Response<T>.bodyOrThrow(): T = if (isSuccessful) body()!! else throw retrofit2.HttpException(this)
}

class UnconfiguredPeopleRepository : PeopleRepository {
    private val failure = DataResult.Failure(DataError.NOT_CONFIGURED)
    override suspend fun search(query: String, page: Int, pageSize: Int) = failure
    override suspend fun profile(userId: String) = failure
    override suspend fun followers(userId: String, page: Int, pageSize: Int) = failure
    override suspend fun following(userId: String, page: Int, pageSize: Int) = failure
    override suspend fun setFollowing(userId: String, follow: Boolean) = FollowResult.Failure(FollowError.NOT_CONFIGURED)
}
