package com.example.portofit.data.repository

import com.example.portofit.data.auth.SessionManager
import com.example.portofit.data.remote.DescriptionUpdateDto
import com.example.portofit.data.remote.InterestsUpdateDto
import com.example.portofit.data.remote.ProfileApi
import com.example.portofit.data.remote.ProfileDto
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
import org.junit.Test
import retrofit2.Response
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkProfileRepositoryTest {

    private val gateway = FakeAuthGateway()
    private val session = SessionManager(gateway, InMemorySessionStore(), CoroutineScope(Dispatchers.Unconfined), io = UnconfinedTestDispatcher())
    private val dto = ProfileDto("user-1", "pamplona", "p@example.org", "Olá", listOf("boxe"))

    private class FakeApi : ProfileApi {
        val authorizations = mutableListOf<String>()
        var responses = ArrayDeque<() -> Response<ProfileDto>>()
        override suspend fun me(authorization: String) = next(authorization)
        override suspend fun updateDescription(authorization: String, body: DescriptionUpdateDto) = next(authorization)
        override suspend fun updateInterests(authorization: String, body: InterestsUpdateDto) = next(authorization)
        private fun next(authorization: String): Response<ProfileDto> {
            authorizations += authorization
            return responses.removeFirst()()
        }
    }

    private val api = FakeApi()
    private val repository = NetworkProfileRepository(api, session, Json { ignoreUnknownKeys = true })

    private fun error(status: Int, body: String = "{}") =
        Response.error<ProfileDto>(status, body.toResponseBody("application/problem+json".toMediaType()))

    @Test
    fun `sem sessao nao faz pedidos`() = runTest {
        session.start()
        assertEquals(ProfileResult.Failure(ProfileError.SESSION_ENDED), repository.load())
        assertEquals(0, api.authorizations.size)
    }

    @Test
    fun `401 renova a sessao uma vez e repete o pedido`() = runTest {
        session.login("p@example.org", "uma frase longa")
        api.responses = ArrayDeque(listOf({ error(401) }, { Response.success(dto) }))

        val result = repository.load()

        assertEquals(ProfileResult.Success(Profile("user-1", "pamplona", "p@example.org", "Olá", listOf("boxe"))), result)
        assertEquals(listOf("Bearer a-login", "Bearer a-0"), api.authorizations)
        assertEquals(1, gateway.refreshCalls.size)
    }

    @Test
    fun `erros do servidor e de rede sao classificados`() = runTest {
        session.login("p@example.org", "uma frase longa")
        api.responses = ArrayDeque(
            listOf(
                { error(503, """{"code":"CATALOG_UNAVAILABLE"}""") },
                { error(400, """{"code":"UNKNOWN_INTERESTS","errors":[{"field":"interests"}]}""") },
                { error(503, """{"code":"SERVICE_UNAVAILABLE"}""") },
                { throw IOException("sem rede") },
            ),
        )
        assertEquals(ProfileResult.Failure(ProfileError.CATALOG_UNAVAILABLE), repository.saveInterests(listOf("ioga")))
        assertEquals(ProfileResult.Failure(ProfileError.UNKNOWN_INTERESTS), repository.saveInterests(listOf("x")))
        assertEquals(ProfileResult.Failure(ProfileError.SERVER), repository.load())
        assertEquals(ProfileResult.Failure(ProfileError.NETWORK), repository.saveDescription("x"))
    }
}
