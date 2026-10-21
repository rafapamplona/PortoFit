package com.example.portofit.data.repository

import com.example.portofit.data.auth.SessionManager
import com.example.portofit.data.model.ShareAvailability
import com.example.portofit.data.remote.ConversationDto
import com.example.portofit.data.remote.InboxDto
import com.example.portofit.data.remote.MessageDto
import com.example.portofit.data.remote.MessagesDto
import com.example.portofit.data.remote.MessagingApi
import com.example.portofit.data.remote.OpenConversationDto
import com.example.portofit.data.remote.ParticipantDto
import com.example.portofit.data.remote.ReadDto
import com.example.portofit.data.remote.SendMessageDto
import com.example.portofit.data.remote.SharedExperienceDto
import com.example.portofit.data.remote.SharesDto
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
class NetworkMessagingRepositoryTest {

    private val gateway = FakeAuthGateway()
    private val session = SessionManager(gateway, InMemorySessionStore(), CoroutineScope(Dispatchers.Unconfined), io = UnconfinedTestDispatcher())
    private val json = Json { ignoreUnknownKeys = true }

    private val conversationDto = ConversationDto("c1", ParticipantDto("rui-id", "rui"), "2026-10-21T09:00:00Z", 2, 1, 1, null)

    private class FakeApi : MessagingApi {
        val sends = mutableListOf<Triple<String, String, SendMessageDto>>()
        val opens = mutableListOf<Pair<String, String>>()
        val reads = mutableListOf<Long>()
        var calls = 0
        var sendResponses = ArrayDeque<() -> Response<MessageDto>>()
        var openResponses = ArrayDeque<() -> Response<ConversationDto>>()
        var messagesResponse: () -> Response<MessagesDto> = { error("sem resposta") }

        override suspend fun inbox(authorization: String, cursor: String?, limit: Int): Response<InboxDto> {
            calls++
            return Response.success(InboxDto(emptyList(), null))
        }

        override suspend fun open(authorization: String, body: OpenConversationDto): Response<ConversationDto> {
            calls++
            opens += authorization to body.participantId
            return openResponses.removeFirst()()
        }

        override suspend fun conversation(authorization: String, conversationId: String): Response<ConversationDto> = error("não usado")

        override suspend fun messages(authorization: String, conversationId: String, before: Long?, after: Long?, limit: Int): Response<MessagesDto> {
            calls++
            return messagesResponse()
        }

        override suspend fun send(authorization: String, idempotencyKey: String, conversationId: String, body: SendMessageDto): Response<MessageDto> {
            calls++
            sends += Triple(authorization, idempotencyKey, body)
            return sendResponses.removeFirst()()
        }

        override suspend fun shares(authorization: String, conversationId: String, seqs: String): Response<SharesDto> = error("não usado")

        override suspend fun markRead(authorization: String, conversationId: String, body: ReadDto): Response<ReadDto> {
            calls++
            reads += body.lastReadSeq
            return Response.success(ReadDto(7, 0))
        }
    }

    private val api = FakeApi()
    private val repository = NetworkMessagingRepository(api, session, json)

    private fun <T> error(status: Int, body: String = "{}", headers: Map<String, String> = emptyMap()): Response<T> {
        val raw = okhttp3.Response.Builder().code(status).message("erro").protocol(okhttp3.Protocol.HTTP_1_1)
            .request(okhttp3.Request.Builder().url("http://localhost/").build())
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        return Response.error(body.toResponseBody("application/problem+json".toMediaType()), raw)
    }

    private val sent = MessageDto("m1", 3, "ana-id", "Olá", null, "2026-10-21T09:05:00Z", "k1")

    @Test
    fun `sem sessao nada e pedido`() = runTest {
        session.start()
        assertEquals(SessionResult.SessionEnded, repository.inbox(null, 20))
        assertEquals(OpenConversationResult.Failed(MessagingError.SESSION_ENDED), repository.open("rui-id"))
        assertEquals(SendOutcome.Rejected(MessagingError.SESSION_ENDED), repository.send("c1", "k1", "Olá", null))
        assertEquals(0, api.calls)
    }

    @Test
    fun `enviar - token, chave, renovacao e classificacao dos resultados`() = runTest {
        session.login("a@example.org", "uma frase longa")
        api.sendResponses = ArrayDeque(
            listOf(
                { error(401) },
                { Response.success(201, sent) },
                { Response.success(sent, headersOf("Idempotent-Replayed", "true")) },
                { throw IOException("timeout") },
                { error(500) },
                { error(503, """{"code":"EXPERIENCES_UNAVAILABLE"}""") },
                { error(404, """{"code":"EXPERIENCE_NOT_FOUND"}""") },
                { error(404, """{"code":"NOT_FOUND"}""") },
                { error(409, """{"code":"IDEMPOTENCY_CONFLICT","reason":"DIFFERENT_CONTENT","conversationId":"c1","messageId":"m-old"}""") },
                { error(409, """{"code":"CONVERSATION_FULL"}""") },
                { error(429, """{"code":"TOO_MANY_REQUESTS"}""", mapOf("Retry-After" to "90")) },
                { error(400, """{"code":"VALIDATION_FAILED"}""") },
            ),
        )
        val first = repository.send("c1", "k1", "Olá", null) as SendOutcome.Sent
        assertEquals(false, first.replayed)
        assertEquals("k1", first.message.clientKey)
        // O 401 renova a sessão e repete com a mesma chave.
        assertEquals(listOf("Bearer a-login" to "k1", "Bearer a-0" to "k1"), api.sends.map { it.first to it.second })
        assertEquals(SendMessageDto("Olá", null), api.sends.first().third)
        assertEquals(true, (repository.send("c1", "k1", "Olá", null) as SendOutcome.Sent).replayed)
        val expected = listOf(
            SendOutcome.Unconfirmed,
            SendOutcome.Unconfirmed,
            SendOutcome.Rejected(MessagingError.EXPERIENCES_UNAVAILABLE),
            SendOutcome.Rejected(MessagingError.EXPERIENCE_GONE),
            SendOutcome.Rejected(MessagingError.CONVERSATION_GONE),
            SendOutcome.AlreadySent("m-old"),
            SendOutcome.Rejected(MessagingError.CONVERSATION_FULL),
            SendOutcome.Rejected(MessagingError.TOO_MANY_REQUESTS, 90),
            SendOutcome.Rejected(MessagingError.VALIDATION),
        )
        for (outcome in expected) assertEquals(outcome, repository.send("c1", "k1", "Olá", "e1"))
        assertEquals(SendMessageDto("Olá", "e1"), api.sends.last().third)
        // Um texto vazio não vai no corpo: só a experiência.
        assertNull(json.parseToJsonElement(json.encodeToString(SendMessageDto.serializer(), SendMessageDto(null, "e1"))).jsonObject["text"])
    }

    @Test
    fun `abrir conversa distingue users-service indisponivel, pessoa inexistente e o proprio`() = runTest {
        session.login("a@example.org", "uma frase longa")
        api.openResponses = ArrayDeque(
            listOf(
                { Response.success(201, conversationDto) },
                { error(503, """{"code":"USERS_UNAVAILABLE"}""") },
                { error(404, """{"code":"USER_NOT_FOUND"}""") },
                { error(400, """{"code":"CANNOT_MESSAGE_SELF"}""") },
                { throw IOException("sem rede") },
                { error(503, """{"code":"SERVICE_UNAVAILABLE"}""") },
            ),
        )
        val opened = repository.open("rui-id") as OpenConversationResult.Opened
        assertEquals("rui", opened.conversation.other.username)
        assertEquals(1, opened.conversation.unreadCount)
        val expected = listOf(
            MessagingError.USERS_UNAVAILABLE,
            MessagingError.USER_NOT_FOUND,
            MessagingError.CANNOT_MESSAGE_SELF,
            MessagingError.UNAVAILABLE,
            MessagingError.UNAVAILABLE,
        )
        for (error in expected) assertEquals(OpenConversationResult.Failed(error), repository.open("rui-id"))
        assertTrue(api.opens.all { it.second == "rui-id" })
    }

    @Test
    fun `mensagens - partilha disponivel, eliminada sem conteudo e desconhecida nunca eliminada`() = runTest {
        session.login("a@example.org", "uma frase longa")
        val experience = json.parseToJsonElement(
            """{"id":"e1","author":{"userId":"u1","username":"ana"},"place":{"id":"p1","name":"Local","demo":true},
               "offering":{"id":"o1","name":"Boxe"},"sport":{"id":"boxe","name":"Boxe"},"text":"Boa.","stars":4,
               "createdAt":"2026-10-19T18:30:00Z","likesCount":2,"commentsCount":1}""",
        ).jsonObject
        fun msg(seq: Long, shared: SharedExperienceDto?) = MessageDto("m$seq", seq, "rui-id", "texto $seq", shared, "2026-10-21T09:0$seq:00Z")
        api.messagesResponse = {
            Response.success(
                MessagesDto(
                    listOf(
                        msg(1, SharedExperienceDto("e1", "AVAILABLE", experience)),
                        msg(2, SharedExperienceDto("e2", "DELETED")),
                        msg(3, SharedExperienceDto("e3", "UNKNOWN")),
                        // Conteúdo ilegível ou estado novo: desconhecido, nunca eliminado.
                        msg(4, SharedExperienceDto("e4", "AVAILABLE", JsonObject(emptyMap()))),
                        msg(5, SharedExperienceDto("e5", "NOVO_ESTADO")),
                        msg(6, null),
                    ),
                    lastSeq = 6, hasMoreBefore = false, hasMoreAfter = true, experiencesStatus = "AVAILABLE",
                ),
            )
        }
        val page = (repository.messages("c1", null, 0, 50) as SessionResult.Success).value
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), page.items.map { it.seq })
        assertEquals(
            listOf(ShareAvailability.AVAILABLE, ShareAvailability.DELETED, ShareAvailability.UNKNOWN, ShareAvailability.UNKNOWN, ShareAvailability.UNKNOWN, null),
            page.items.map { it.shared?.availability },
        )
        assertEquals("Boa.", page.items[0].shared?.experience?.text)
        assertNull(page.items[1].shared?.experience)
        assertEquals("texto 2", page.items[1].text)
        assertTrue(page.hasMoreAfter)
        api.messagesResponse = { Response.success(MessagesDto(emptyList(), 6, true, false, "UNAVAILABLE")) }
        assertTrue((repository.messages("c1", null, 6, 50) as SessionResult.Success).value.experiencesUnavailable)
        api.messagesResponse = { error(404) }
        assertEquals(SessionResult.Failure(DataError.NOT_FOUND), repository.messages("c1", null, null, 30))
        api.messagesResponse = { throw IOException("sem rede") }
        assertEquals(SessionResult.Failure(DataError.NETWORK), repository.messages("c1", null, null, 30))

        assertEquals(SessionResult.Success(com.example.portofit.data.model.ReadState(7, 0)), repository.markRead("c1", 5))
        assertEquals(listOf(5L), api.reads)
    }
}
