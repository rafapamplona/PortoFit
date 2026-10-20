package com.example.portofit.data.repository

import com.example.portofit.data.auth.SessionManager
import com.example.portofit.data.model.SavedAvailability
import com.example.portofit.data.remote.CollectionDto
import com.example.portofit.data.remote.CollectionItemDto
import com.example.portofit.data.remote.CollectionItemsDto
import com.example.portofit.data.remote.CollectionNameDto
import com.example.portofit.data.remote.CollectionsApi
import com.example.portofit.data.remote.PageDto
import com.example.portofit.testing.FakeAuthGateway
import com.example.portofit.testing.InMemorySessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Headers.Companion.headersOf
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkCollectionsRepositoryTest {

    private val gateway = FakeAuthGateway()
    private val session = SessionManager(gateway, InMemorySessionStore(), CoroutineScope(Dispatchers.Unconfined), io = UnconfinedTestDispatcher())
    private val json = Json { ignoreUnknownKeys = true }

    private val dto = CollectionDto("c1", "Para experimentar", 2, "2026-10-20T10:00:00Z", "2026-10-20T10:05:00Z")

    private class FakeApi : CollectionsApi {
        val calls = mutableListOf<Triple<String, String?, String?>>()
        var createResponses = ArrayDeque<() -> Response<CollectionDto>>()
        var writeResponses = ArrayDeque<() -> Response<*>>()
        var itemsResponse: () -> Response<CollectionItemsDto> = { error("sem resposta") }
        var listResponse: () -> Response<PageDto<CollectionDto>> = { error("sem resposta") }

        override suspend fun list(authorization: String, page: Int, size: Int): Response<PageDto<CollectionDto>> {
            calls += Triple("list", authorization, null)
            return listResponse()
        }

        override suspend fun create(authorization: String, idempotencyKey: String, body: CollectionNameDto): Response<CollectionDto> {
            calls += Triple("create", authorization, idempotencyKey)
            return createResponses.removeFirst()()
        }

        override suspend fun get(authorization: String, collectionId: String): Response<CollectionDto> = error("não usado")

        @Suppress("UNCHECKED_CAST")
        override suspend fun rename(authorization: String, collectionId: String, body: CollectionNameDto) =
            (writeResponses.removeFirst()() as Response<CollectionDto>).also { calls += Triple("rename", authorization, body.name) }

        @Suppress("UNCHECKED_CAST")
        override suspend fun delete(authorization: String, collectionId: String) =
            (writeResponses.removeFirst()() as Response<Unit>).also { calls += Triple("delete", authorization, null) }

        override suspend fun items(authorization: String, collectionId: String, page: Int, size: Int) = itemsResponse()

        @Suppress("UNCHECKED_CAST")
        override suspend fun addItem(authorization: String, collectionId: String, experienceId: String) =
            (writeResponses.removeFirst()() as Response<Unit>).also { calls += Triple("add", authorization, experienceId) }

        @Suppress("UNCHECKED_CAST")
        override suspend fun removeItem(authorization: String, collectionId: String, experienceId: String) =
            (writeResponses.removeFirst()() as Response<Unit>).also { calls += Triple("remove", authorization, experienceId) }
    }

    private val api = FakeApi()
    private val repository = NetworkCollectionsRepository(api, session, json)

    private fun <T> error(status: Int, body: String = "{}"): Response<T> {
        val raw = okhttp3.Response.Builder().code(status).message("erro").protocol(okhttp3.Protocol.HTTP_1_1)
            .request(okhttp3.Request.Builder().url("http://localhost/").build()).build()
        return Response.error(body.toResponseBody("application/problem+json".toMediaType()), raw)
    }

    @Test
    fun `sem sessao nada e pedido`() = runTest {
        session.start()
        assertEquals(SessionResult.SessionEnded, repository.list(0, 20))
        assertEquals(CreateCollectionOutcome.Rejected(CollectionError.SESSION_ENDED), repository.create("k1", "Lista"))
        assertEquals(CollectionResult.Failure(CollectionError.SESSION_ENDED), repository.add("c1", "e1"))
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `criar - token, chave, renovacao e resultados`() = runTest {
        session.login("a@example.org", "uma frase longa")
        api.createResponses = ArrayDeque(
            listOf(
                { error(401) },
                { Response.success(201, dto) },
                { Response.success(dto, headersOf("Idempotent-Replayed", "true")) },
                { throw IOException("timeout") },
                { error(503) },
                { error(409, """{"code":"NAME_TAKEN"}""") },
                { error(409, """{"code":"COLLECTION_LIMIT_REACHED"}""") },
                { error(409, """{"code":"IDEMPOTENCY_CONFLICT","reason":"DIFFERENT_CONTENT","collectionId":"c-old"}""") },
                { error(409, """{"code":"IDEMPOTENCY_CONFLICT","reason":"COLLECTION_DELETED"}""") },
                { error(400, """{"code":"VALIDATION_FAILED"}""") },
            ),
        )
        val created = repository.create("k1", "Para experimentar") as CreateCollectionOutcome.Created
        assertEquals(false, created.replayed)
        assertEquals(listOf("Bearer a-login" to "k1", "Bearer a-0" to "k1"), api.calls.map { it.second to it.third })
        assertEquals(true, (repository.create("k1", "Para experimentar") as CreateCollectionOutcome.Created).replayed)
        val expected = listOf(
            CreateCollectionOutcome.Unconfirmed,
            CreateCollectionOutcome.Unconfirmed,
            CreateCollectionOutcome.Rejected(CollectionError.NAME_TAKEN),
            CreateCollectionOutcome.Rejected(CollectionError.COLLECTION_LIMIT),
            CreateCollectionOutcome.AlreadyCreated("c-old"),
            CreateCollectionOutcome.CreatedThenDeleted,
            CreateCollectionOutcome.Rejected(CollectionError.VALIDATION),
        )
        for (outcome in expected) assertEquals(outcome, repository.create("k1", "Para experimentar"))
    }

    @Test
    fun `escritas distinguem confirmacao, resultado desconhecido e falhas definitivas`() = runTest {
        session.login("a@example.org", "uma frase longa")
        api.writeResponses = ArrayDeque(
            listOf(
                { Response.success(204, Unit) },
                { throw IOException("sem rede") },
                { error<Unit>(500) },
                { error<Unit>(503, """{"code":"EXPERIENCES_UNAVAILABLE"}""") },
                { error<Unit>(404, """{"code":"EXPERIENCE_NOT_FOUND"}""") },
                { error<Unit>(404, """{"code":"NOT_FOUND"}""") },
                { error<Unit>(409, """{"code":"ITEM_LIMIT_REACHED"}""") },
            ),
        )
        assertEquals(CollectionResult.Success(Unit), repository.add("c1", "e1"))
        val expected = listOf(
            CollectionError.UNCONFIRMED,
            CollectionError.UNCONFIRMED,
            CollectionError.EXPERIENCES_UNAVAILABLE,
            CollectionError.EXPERIENCE_GONE,
            CollectionError.COLLECTION_GONE,
            CollectionError.ITEM_LIMIT,
        )
        for (error in expected) assertEquals(CollectionResult.Failure(error), repository.add("c1", "e1"))

        api.writeResponses = ArrayDeque(listOf({ Response.success(204, Unit) }, { error<Unit>(404) }, { error<CollectionDto>(409, """{"code":"NAME_TAKEN"}""") }))
        assertEquals(CollectionResult.Success(Unit), repository.remove("c1", "e1"))
        assertEquals(CollectionResult.Failure(CollectionError.COLLECTION_GONE), repository.delete("c1"))
        assertEquals(CollectionResult.Failure(CollectionError.NAME_TAKEN), repository.rename("c1", "Outro"))
    }

    @Test
    fun `itens - disponivel com experiencia, eliminado sem conteudo e desconhecido nunca eliminado`() = runTest {
        session.login("a@example.org", "uma frase longa")
        val experience = json.parseToJsonElement(
            """{"id":"e1","author":{"userId":"u1","username":"ana"},"place":{"id":"p1","name":"Local","demo":true},
               "offering":{"id":"o1","name":"Boxe"},"sport":{"id":"boxe","name":"Boxe"},"text":"Boa.","stars":4,
               "createdAt":"2026-10-19T18:30:00Z","likesCount":2,"commentsCount":1}""",
        ).jsonObject
        api.itemsResponse = {
            Response.success(
                CollectionItemsDto(
                    listOf(
                        CollectionItemDto("e1", "2026-10-20T10:00:00Z", "AVAILABLE", experience),
                        CollectionItemDto("e2", "2026-10-20T09:00:00Z", "DELETED", null),
                        CollectionItemDto("e3", "2026-10-20T08:00:00Z", "UNKNOWN", null),
                        // Conteúdo ilegível ou estado desconhecido: tratado como desconhecido, nunca como eliminado.
                        CollectionItemDto("e4", "2026-10-20T07:00:00Z", "AVAILABLE", JsonObject(emptyMap())),
                        CollectionItemDto("e5", "2026-10-20T06:00:00Z", "NOVO_ESTADO", null),
                    ),
                    0, 20, 5, 1, "AVAILABLE",
                ),
            )
        }
        val page = (repository.items("c1", 0, 20) as SessionResult.Success).value
        assertEquals(
            listOf(SavedAvailability.AVAILABLE, SavedAvailability.DELETED, SavedAvailability.UNKNOWN, SavedAvailability.UNKNOWN, SavedAvailability.UNKNOWN),
            page.page.items.map { it.availability },
        )
        assertEquals("Boa.", page.page.items[0].experience?.text)
        assertEquals(2L, page.page.items[0].experience?.likesCount)
        assertNull(page.page.items[1].experience)
        assertTrue(page.experiencesAvailable)

        api.itemsResponse = { Response.success(CollectionItemsDto(listOf(CollectionItemDto("e1", "2026-10-20T10:00:00Z", "UNKNOWN")), 0, 20, 1, 1, "UNAVAILABLE")) }
        assertEquals(false, (repository.items("c1", 0, 20) as SessionResult.Success).value.experiencesAvailable)
        api.itemsResponse = { error(404) }
        assertEquals(SessionResult.Failure(DataError.NOT_FOUND), repository.items("c1", 0, 20))
        api.itemsResponse = { throw IOException("sem rede") }
        assertEquals(SessionResult.Failure(DataError.NETWORK), repository.items("c1", 0, 20))
    }
}
