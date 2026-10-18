package com.example.portofit.data.repository

import com.example.portofit.data.model.Page
import com.example.portofit.data.remote.PeopleApi
import com.example.portofit.data.remote.toModel

/** Pessoa num resultado de pesquisa. */
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
)

/** Pesquisa pública de pessoas e perfis públicos (sem sessão). */
interface PeopleRepository {
    suspend fun search(query: String, page: Int, pageSize: Int): DataResult<Page<UserSummary>>
    suspend fun profile(userId: String): DataResult<PublicProfile>
}

class NetworkPeopleRepository(private val api: PeopleApi) : PeopleRepository {

    override suspend fun search(query: String, page: Int, pageSize: Int) = apiCall {
        api.search(query, page, pageSize).toModel { UserSummary(it.userId, it.username, it.description) }
    }

    override suspend fun profile(userId: String) = apiCall {
        api.profile(userId).let { PublicProfile(it.userId, it.username, it.description, it.interests) }
    }
}

class UnconfiguredPeopleRepository : PeopleRepository {
    private val failure = DataResult.Failure(DataError.NOT_CONFIGURED)
    override suspend fun search(query: String, page: Int, pageSize: Int) = failure
    override suspend fun profile(userId: String) = failure
}
