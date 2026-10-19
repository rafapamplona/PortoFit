package com.example.portofit.data.repository

import com.example.portofit.data.auth.SessionManager
import com.example.portofit.data.remote.FollowEntryDto
import com.example.portofit.data.remote.PageDto
import com.example.portofit.data.remote.PeopleApi
import com.example.portofit.data.remote.PublicProfileDto
import com.example.portofit.data.remote.UserSummaryDto
import com.example.portofit.testing.FakeAuthGateway
import com.example.portofit.testing.InMemorySessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkPeopleRepositoryTest {

    private val gateway = FakeAuthGateway()
    private val session = SessionManager(gateway, InMemorySessionStore(), CoroutineScope(Dispatchers.Unconfined), io = UnconfinedTestDispatcher())

    private class FakeApi : PeopleApi {
        /** (pedido, cabeçalho Authorization) por ordem; null = sem cabeçalho. */
        val calls = mutableListOf<Pair<String, String?>>()
        var profileResponses = ArrayDeque<() -> Response<PublicProfileDto>>()
        var followResponses = ArrayDeque<() -> Response<Unit>>()

        override suspend fun search(query: String, page: Int, size: Int) = PageDto<UserSummaryDto>(emptyList(), page, size, 0, 0)
        override suspend fun profile(userId: String, authorization: String?): Response<PublicProfileDto> {
            calls += "profile" to authorization
            return profileResponses.removeFirst()()
        }
        override suspend fun followers(userId: String, page: Int, size: Int): PageDto<FollowEntryDto> {
            calls += "followers" to null
            return PageDto(listOf(FollowEntryDto("u-2", "rui", null)), page, size, 1, 1)
        }
        override suspend fun following(userId: String, page: Int, size: Int) = PageDto<FollowEntryDto>(emptyList(), page, size, 0, 0)
        override suspend fun follow(userId: String, authorization: String): Response<Unit> {
            calls += "follow" to authorization
            return followResponses.removeFirst()()
        }
        override suspend fun unfollow(userId: String, authorization: String): Response<Unit> {
            calls += "unfollow" to authorization
            return followResponses.removeFirst()()
        }
    }

    private val api = FakeApi()
    private val repository = NetworkPeopleRepository(api, session, Json { ignoreUnknownKeys = true })
    private val dto = PublicProfileDto("user-9", "ana", null, emptyList(), followersCount = 3, followingCount = 1, followedByMe = true)

    private fun <T> error(status: Int, body: String = "{}") =
        Response.error<T>(status, body.toResponseBody("application/problem+json".toMediaType()))

    @Test
    fun `visitante pede o perfil sem token e sem relacao`() = runTest {
        session.start()
        api.profileResponses = ArrayDeque(listOf({ Response.success(dto.copy(followedByMe = null)) }))

        val result = repository.profile("user-9") as DataResult.Success

        assertEquals(listOf("profile" to null), api.calls)
        assertEquals(null, result.value.followedByMe)
        assertEquals(3L, result.value.followersCount)
    }

    @Test
    fun `com sessao envia o token e um 401 renova uma vez`() = runTest {
        session.login("r@example.org", "uma frase longa")
        api.profileResponses = ArrayDeque(listOf({ error(401) }, { Response.success(dto) }))

        val result = repository.profile("user-9") as DataResult.Success

        assertEquals(listOf("profile" to "Bearer a-login", "profile" to "Bearer a-0"), api.calls)
        assertEquals(true, result.value.followedByMe)
        assertEquals(1, gateway.refreshCalls.size)
    }

    @Test
    fun `seguir sem sessao nao faz pedidos`() = runTest {
        session.start()
        assertEquals(FollowResult.Failure(FollowError.SESSION_ENDED), repository.setFollowing("user-9", true))
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `seguir e deixar de seguir usam PUT e DELETE com o token`() = runTest {
        session.login("r@example.org", "uma frase longa")
        api.followResponses = ArrayDeque(listOf({ Response.success(204, Unit) }, { Response.success(204, Unit) }))

        assertEquals(FollowResult.Success, repository.setFollowing("user-9", true))
        assertEquals(FollowResult.Success, repository.setFollowing("user-9", false))
        assertEquals(listOf("follow" to "Bearer a-login", "unfollow" to "Bearer a-login"), api.calls)
    }

    @Test
    fun `seguir com 401 renova a sessao uma vez e repete`() = runTest {
        session.login("r@example.org", "uma frase longa")
        api.followResponses = ArrayDeque(listOf({ error(401) }, { Response.success(204, Unit) }))

        assertEquals(FollowResult.Success, repository.setFollowing("user-9", true))
        assertEquals(listOf("follow" to "Bearer a-login", "follow" to "Bearer a-0"), api.calls)
    }

    @Test
    fun `erros de seguir sao classificados`() = runTest {
        session.login("r@example.org", "uma frase longa")
        api.followResponses = ArrayDeque(
            listOf(
                { error(400, """{"code":"CANNOT_FOLLOW_SELF"}""") },
                { error(404, """{"code":"USER_NOT_FOUND"}""") },
                { error(503, """{"code":"SERVICE_UNAVAILABLE"}""") },
                { throw IOException("sem rede") },
            ),
        )
        assertEquals(FollowResult.Failure(FollowError.CANNOT_FOLLOW_SELF), repository.setFollowing("user-1", true))
        assertEquals(FollowResult.Failure(FollowError.NOT_FOUND), repository.setFollowing("x", true))
        assertEquals(FollowResult.Failure(FollowError.SERVER), repository.setFollowing("user-9", false))
        assertEquals(FollowResult.Failure(FollowError.NETWORK), repository.setFollowing("user-9", true))
    }

    @Test
    fun `listas sao publicas e convertidas em resumos`() = runTest {
        session.start()
        val page = (repository.followers("user-9", 0, 20) as DataResult.Success).value
        assertEquals(listOf(UserSummary("u-2", "rui", null)), page.items)
        assertEquals(listOf("followers" to null), api.calls)
    }
}
