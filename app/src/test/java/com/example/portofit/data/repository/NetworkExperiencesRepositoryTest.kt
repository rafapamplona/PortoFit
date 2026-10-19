package com.example.portofit.data.repository

import com.example.portofit.data.auth.SessionManager
import com.example.portofit.data.model.ExperienceSort
import com.example.portofit.data.remote.EditExperienceDto
import com.example.portofit.data.remote.ExperienceAuthorDto
import com.example.portofit.data.remote.ExperienceDto
import com.example.portofit.data.remote.ExperienceOfferingDto
import com.example.portofit.data.remote.ExperiencesApi
import com.example.portofit.data.remote.PageDto
import com.example.portofit.data.remote.PlaceRefDto
import com.example.portofit.data.remote.PublishExperienceDto
import com.example.portofit.data.remote.SportRefDto
import com.example.portofit.testing.FakeAuthGateway
import com.example.portofit.testing.InMemorySessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Headers.Companion.headersOf
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkExperiencesRepositoryTest {

    private val gateway = FakeAuthGateway()
    private val session = SessionManager(gateway, InMemorySessionStore(), CoroutineScope(Dispatchers.Unconfined), io = UnconfinedTestDispatcher())

    private val dto = ExperienceDto(
        id = "e1",
        author = ExperienceAuthorDto("user-1", "ana"),
        place = PlaceRefDto("p1", "Clube de Combate Ribeira (fictício)", true),
        offering = ExperienceOfferingDto("o1", "Boxe iniciação"),
        sport = SportRefDto("boxe", "Boxe"),
        text = "Boa aula.",
        stars = 4,
        createdAt = "2026-10-19T18:30:00.123456Z",
    )

    private class FakeApi : ExperiencesApi {
        /** (pedido, Authorization, Idempotency-Key) por ordem. */
        val calls = mutableListOf<Triple<String, String?, String?>>()
        val listParams = mutableListOf<List<Any?>>()
        var publishResponses = ArrayDeque<() -> Response<ExperienceDto>>()
        var editResponses = ArrayDeque<() -> Response<ExperienceDto>>()
        var deleteResponses = ArrayDeque<() -> Response<Unit>>()
        var page: () -> PageDto<ExperienceDto> = { PageDto(emptyList(), 0, 10, 0, 0) }
        var single: () -> ExperienceDto = { error("sem resposta") }

        override suspend fun list(placeId: String?, authorId: String?, sort: String, page: Int, size: Int): PageDto<ExperienceDto> {
            calls += Triple("list", null, null)
            listParams += listOf(placeId, authorId, sort, page, size)
            return this.page()
        }

        override suspend fun get(experienceId: String): ExperienceDto = single()

        override suspend fun publish(authorization: String, idempotencyKey: String, body: PublishExperienceDto): Response<ExperienceDto> {
            calls += Triple("publish", authorization, idempotencyKey)
            return publishResponses.removeFirst()()
        }

        override suspend fun edit(authorization: String, experienceId: String, body: EditExperienceDto): Response<ExperienceDto> {
            calls += Triple("edit", authorization, null)
            return editResponses.removeFirst()()
        }

        override suspend fun delete(authorization: String, experienceId: String): Response<Unit> {
            calls += Triple("delete", authorization, null)
            return deleteResponses.removeFirst()()
        }
    }

    private val api = FakeApi()
    private val repository = NetworkExperiencesRepository(api, session, Json { ignoreUnknownKeys = true })
    private val draft = ExperienceDraft("p1", "o1", "Boa aula.", 4)

    private fun <T> error(status: Int, body: String = "{}", headers: Map<String, String> = emptyMap()): Response<T> {
        val raw = okhttp3.Response.Builder()
            .code(status).message("erro").protocol(okhttp3.Protocol.HTTP_1_1)
            .request(okhttp3.Request.Builder().url("http://localhost/").build())
            .headers(headersOf(*headers.flatMap { listOf(it.key, it.value) }.toTypedArray()))
            .build()
        return Response.error(body.toResponseBody("application/problem+json".toMediaType()), raw)
    }

    @Test
    fun `listas sao publicas e convertidas no modelo`() = runTest {
        session.start()
        api.page = { PageDto(listOf(dto), 0, 10, 1, 1) }
        val page = (repository.list("p1", null, ExperienceSort.STARS, 0, 10) as DataResult.Success).value
        assertEquals(listOf(listOf("p1", null, "stars", 0, 10)), api.listParams)
        val experience = page.items.single()
        assertEquals("ana", experience.authorUsername)
        assertTrue(experience.placeDemo)
        assertEquals(Instant.parse("2026-10-19T18:30:00.123456Z"), experience.createdAt)
    }

    @Test
    fun `data invalida na resposta e erro inesperado`() = runTest {
        api.single = { dto.copy(createdAt = "ontem") }
        assertEquals(DataResult.Failure(DataError.UNEXPECTED), repository.get("e1"))
    }

    @Test
    fun `publicar sem sessao nao faz pedidos`() = runTest {
        session.start()
        assertEquals(PublishOutcome.Rejected(PublishRejection.SESSION_ENDED), repository.publish("k1", draft))
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `publicar envia token e chave, e um 401 renova e repete com a mesma chave`() = runTest {
        session.login("a@example.org", "uma frase longa")
        api.publishResponses = ArrayDeque(listOf({ error(401) }, { Response.success(201, dto) }))

        val outcome = repository.publish("k1", draft) as PublishOutcome.Published

        assertEquals(listOf(Triple("publish", "Bearer a-login", "k1"), Triple("publish", "Bearer a-0", "k1")), api.calls)
        assertEquals(false, outcome.replayed)
        assertEquals("e1", outcome.experience.id)
    }

    @Test
    fun `repeticao idempotente devolvida com 200`() = runTest {
        session.login("a@example.org", "uma frase longa")
        api.publishResponses = ArrayDeque(listOf({ Response.success(dto, headersOf("Idempotent-Replayed", "true")) }))
        assertEquals(PublishOutcome.Published(repository.run { dto.toModel() }, replayed = true), repository.publish("k1", draft))
    }

    @Test
    fun `resultados desconhecidos e recusas definitivas`() = runTest {
        session.login("a@example.org", "uma frase longa")
        api.publishResponses = ArrayDeque(
            listOf(
                { throw IOException("timeout") },
                { error(503, """{"code":"SERVICE_UNAVAILABLE"}""") },
                { error(500, """{"code":"INTERNAL_ERROR"}""") },
                { error(503, """{"code":"CATALOG_UNAVAILABLE"}""") },
                { error(400, """{"code":"VALIDATION_FAILED"}""") },
                { error(400, """{"code":"UNKNOWN_OFFERING"}""") },
                { error(400, """{"code":"OFFERING_PLACE_MISMATCH"}""") },
                { error(400, """{"code":"OFFERING_RETIRED"}""") },
                { error(429, """{"code":"TOO_MANY_REQUESTS"}""", mapOf("Retry-After" to "1740")) },
                { error(409, """{"code":"IDEMPOTENCY_CONFLICT","reason":"DIFFERENT_CONTENT","experienceId":"e-old"}""") },
                { error(409, """{"code":"IDEMPOTENCY_CONFLICT","reason":"EXPERIENCE_DELETED"}""") },
                { Response.success(201, dto.copy(createdAt = "?")) },
            ),
        )
        val expected = listOf(
            PublishOutcome.Unconfirmed,
            PublishOutcome.Unconfirmed,
            PublishOutcome.Unconfirmed,
            PublishOutcome.Rejected(PublishRejection.CATALOG_UNAVAILABLE),
            PublishOutcome.Rejected(PublishRejection.VALIDATION),
            PublishOutcome.Rejected(PublishRejection.UNKNOWN_OFFERING),
            PublishOutcome.Rejected(PublishRejection.OFFERING_MISMATCH),
            PublishOutcome.Rejected(PublishRejection.OFFERING_RETIRED),
            PublishOutcome.Rejected(PublishRejection.TOO_MANY, 1740),
            PublishOutcome.AlreadyPublished("e-old"),
            PublishOutcome.PublishedThenDeleted,
            // Aceite, mas sem resposta legível: não se sabe o resultado.
            PublishOutcome.Unconfirmed,
        )
        for (outcome in expected) assertEquals(outcome, repository.publish("k1", draft))
    }

    @Test
    fun `editar e eliminar com token e erros classificados`() = runTest {
        session.login("a@example.org", "uma frase longa")
        api.editResponses = ArrayDeque(listOf({ Response.success(dto.copy(stars = 5)) }, { error(403) }, { throw IOException("sem rede") }))
        api.deleteResponses = ArrayDeque(listOf({ Response.success(204, Unit) }, { error(404) }, { error(503) }))

        assertEquals(5, (repository.edit("e1", null, 5) as MutationResult.Success).value.stars)
        assertEquals(MutationResult.Failure(MutationError.FORBIDDEN), repository.edit("e1", "x", null))
        assertEquals(MutationResult.Failure(MutationError.NETWORK), repository.edit("e1", "x", null))
        assertEquals(MutationResult.Success(Unit), repository.delete("e1"))
        assertEquals(MutationResult.Failure(MutationError.NOT_FOUND), repository.delete("e1"))
        assertEquals(MutationResult.Failure(MutationError.SERVER), repository.delete("e1"))
        assertTrue(api.calls.filter { it.first != "list" }.all { it.second == "Bearer a-login" })
    }
}
