package com.example.portofit.data.repository

import com.example.portofit.data.auth.SessionManager
import com.example.portofit.data.auth.SessionState
import com.example.portofit.data.model.ChatMessage
import com.example.portofit.data.model.Conversation
import com.example.portofit.data.model.InboxPage
import com.example.portofit.data.model.LastMessage
import com.example.portofit.data.model.MessageKind
import com.example.portofit.data.model.MessagesPage
import com.example.portofit.data.model.Participant
import com.example.portofit.data.model.ReadState
import com.example.portofit.data.model.ShareAvailability
import com.example.portofit.data.model.SharedExperience
import com.example.portofit.data.model.SharesPage
import com.example.portofit.data.remote.ConversationDto
import com.example.portofit.data.remote.ExperienceDto
import com.example.portofit.data.remote.MessageDto
import com.example.portofit.data.remote.MessagesDto
import com.example.portofit.data.remote.MessagingApi
import com.example.portofit.data.remote.MessagingProblemDto
import com.example.portofit.data.remote.OpenConversationDto
import com.example.portofit.data.remote.ReadDto
import com.example.portofit.data.remote.SendMessageDto
import com.example.portofit.data.remote.SharedExperienceDto
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import retrofit2.Response
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeParseException

/** Falha de abrir uma conversa ou de enviar uma mensagem (docs/messaging-api.md §9). */
enum class MessagingError {
    /** Abrir: o users-service não confirmou o destinatário (nada criado; tentar mais tarde). */
    USERS_UNAVAILABLE,
    USER_NOT_FOUND,
    CANNOT_MESSAGE_SELF,
    /** A conversa não existe ou não é desta conta. */
    CONVERSATION_GONE,
    /** Partilhar: a experiência foi eliminada ou não existe (nada enviado). */
    EXPERIENCE_GONE,
    /** Partilhar: o serviço das experiências não confirmou a experiência (nada enviado; temporário). */
    EXPERIENCES_UNAVAILABLE,
    CONVERSATION_FULL,
    TOO_MANY_REQUESTS,
    VALIDATION,
    /**
     * Abrir: sem resposta (rede, timeout) ou erro do servidor. O resultado é desconhecido — a conversa pode ter sido criada —
     * mas abrir é idempotente: repetir devolve a mesma conversa.
     */
    UNAVAILABLE,
    SESSION_ENDED,
    SESSION_UNVERIFIED,
    NOT_CONFIGURED,
    UNEXPECTED,
}

sealed interface OpenConversationResult {
    data class Opened(val conversation: Conversation) : OpenConversationResult
    data class Failed(val error: MessagingError) : OpenConversationResult
}

/** Resultado de enviar (§6.2): uma `Idempotency-Key` por tentativa lógica, como na publicação. */
sealed interface SendOutcome {
    data class Sent(val message: ChatMessage, val replayed: Boolean) : SendOutcome

    /** Resultado desconhecido: pode ter sido enviada. Só é seguro repetir com a mesma chave e o mesmo conteúdo. */
    data object Unconfirmed : SendOutcome

    /** Recusa definitiva: nada foi enviado. [retryAfterSeconds] no limite de frequência. */
    data class Rejected(val error: MessagingError, val retryAfterSeconds: Long? = null) : SendOutcome

    /** 409 DIFFERENT_CONTENT: esta chave já tinha enviado outra mensagem. */
    data class AlreadySent(val messageId: String?) : SendOutcome
}

interface MessagingRepository {
    suspend fun inbox(cursor: String?, limit: Int): SessionResult<InboxPage>
    suspend fun conversation(conversationId: String): SessionResult<Conversation>
    suspend fun open(participantId: String): OpenConversationResult
    suspend fun messages(conversationId: String, before: Long?, after: Long?, limit: Int): SessionResult<MessagesPage>
    suspend fun send(conversationId: String, idempotencyKey: String, text: String?, experienceId: String?): SendOutcome
    suspend fun shares(conversationId: String, seqs: List<Long>): SessionResult<SharesPage>
    suspend fun markRead(conversationId: String, seq: Long): SessionResult<ReadState>
}

/** Todas as chamadas com o access token do [SessionManager]; um 401 renova uma vez e repete (no envio, com a mesma chave). */
class NetworkMessagingRepository(
    private val api: MessagingApi,
    private val session: SessionManager,
    private val json: Json,
) : MessagingRepository {

    override suspend fun inbox(cursor: String?, limit: Int) =
        read({ api.inbox(it, cursor, limit) }) { dto -> InboxPage(dto.items.map { it.toModel() }, dto.nextCursor) }

    override suspend fun conversation(conversationId: String) = read({ api.conversation(it, conversationId) }) { it.toModel() }

    override suspend fun messages(conversationId: String, before: Long?, after: Long?, limit: Int) =
        read({ api.messages(it, conversationId, before, after, limit) }) { it.toModel() }

    override suspend fun shares(conversationId: String, seqs: List<Long>) =
        read({ api.shares(it, conversationId, seqs.joinToString(",")) }) { dto ->
            SharesPage(dto.items.associate { it.seq to it.sharedExperience.toModel() }, dto.experiencesStatus == "UNAVAILABLE")
        }

    override suspend fun markRead(conversationId: String, seq: Long) =
        read({ api.markRead(it, conversationId, ReadDto(seq)) }) { ReadState(it.lastReadSeq, it.unreadCount ?: 0) }

    override suspend fun open(participantId: String): OpenConversationResult {
        val response = try {
            authorized { api.open(it, OpenConversationDto(participantId)) } ?: return OpenConversationResult.Failed(sessionError())
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return OpenConversationResult.Failed(MessagingError.UNAVAILABLE)
        }
        if (response.isSuccessful) {
            val conversation = response.body()?.let { runCatching { it.toModel() }.getOrNull() } ?: return OpenConversationResult.Failed(MessagingError.UNEXPECTED)
            return OpenConversationResult.Opened(conversation)
        }
        val problem = problem(response)
        return OpenConversationResult.Failed(
            when {
                problem?.code == "USERS_UNAVAILABLE" -> MessagingError.USERS_UNAVAILABLE
                response.code() in 500..599 -> MessagingError.UNAVAILABLE
                else -> errorOf(response.code(), problem?.code)
            },
        )
    }

    override suspend fun send(conversationId: String, idempotencyKey: String, text: String?, experienceId: String?): SendOutcome {
        val response = try {
            authorized { api.send(it, idempotencyKey, conversationId, SendMessageDto(text, experienceId)) } ?: return SendOutcome.Rejected(sessionError())
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return SendOutcome.Unconfirmed
        }
        if (response.isSuccessful) {
            val message = response.body()?.let { runCatching { it.toModel() }.getOrNull() } ?: return SendOutcome.Unconfirmed
            return SendOutcome.Sent(message, replayed = response.code() == 200 || response.headers()["Idempotent-Replayed"] == "true")
        }
        val problem = problem(response)
        return when {
            response.code() == 409 && problem?.code == "IDEMPOTENCY_CONFLICT" -> SendOutcome.AlreadySent(problem.messageId)
            // 503 EXPERIENCES_UNAVAILABLE é definitivo para este pedido (nada enviado); outros 5xx: resultado desconhecido.
            problem?.code == "EXPERIENCES_UNAVAILABLE" -> SendOutcome.Rejected(MessagingError.EXPERIENCES_UNAVAILABLE)
            response.code() in 500..599 -> SendOutcome.Unconfirmed
            response.code() == 429 -> SendOutcome.Rejected(MessagingError.TOO_MANY_REQUESTS, response.headers()["Retry-After"]?.toLongOrNull())
            else -> SendOutcome.Rejected(errorOf(response.code(), problem?.code))
        }
    }

    private suspend fun <D, T> read(call: suspend (String) -> Response<D>, convert: (D) -> T): SessionResult<T> =
        try {
            val response = authorized(call) ?: return if (session.state.value is SessionState.SignedIn) SessionResult.SessionUnverified else SessionResult.SessionEnded
            when {
                response.isSuccessful -> SessionResult.Success(convert(response.body()!!))
                response.code() == 401 -> SessionResult.SessionEnded
                else -> SessionResult.Failure(
                    when (response.code()) {
                        404 -> DataError.NOT_FOUND
                        400 -> DataError.INVALID_REQUEST
                        in 500..599 -> DataError.SERVER
                        else -> DataError.UNEXPECTED
                    },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            SessionResult.Failure(DataError.NETWORK)
        } catch (e: SerializationException) {
            SessionResult.Failure(DataError.UNEXPECTED)
        } catch (e: IllegalArgumentException) {
            SessionResult.Failure(DataError.UNEXPECTED)
        }

    private fun errorOf(status: Int, code: String?): MessagingError = when {
        code == "CANNOT_MESSAGE_SELF" -> MessagingError.CANNOT_MESSAGE_SELF
        code == "USER_NOT_FOUND" -> MessagingError.USER_NOT_FOUND
        code == "EXPERIENCE_NOT_FOUND" -> MessagingError.EXPERIENCE_GONE
        code == "CONVERSATION_FULL" -> MessagingError.CONVERSATION_FULL
        status == 401 -> MessagingError.SESSION_ENDED
        status == 404 -> MessagingError.CONVERSATION_GONE
        status == 429 -> MessagingError.TOO_MANY_REQUESTS
        status == 400 || status == 413 -> MessagingError.VALIDATION
        else -> MessagingError.UNEXPECTED
    }

    /** null quando não há token (sem sessão ou sessão por confirmar): o pedido não chega a ser enviado. */
    private suspend fun <R> authorized(call: suspend (String) -> Response<R>): Response<R>? {
        val token = session.accessToken() ?: return null
        val response = call("Bearer $token")
        if (response.code() != 401) return response
        val fresh = session.accessTokenAfterUnauthorized(token) ?: return null
        return call("Bearer $fresh")
    }

    private fun sessionError() =
        if (session.state.value is SessionState.SignedOut) MessagingError.SESSION_ENDED else MessagingError.SESSION_UNVERIFIED

    private fun problem(response: Response<*>): MessagingProblemDto? =
        runCatching { response.errorBody()?.string()?.let { json.decodeFromString<MessagingProblemDto>(it) } }.getOrNull()

    private fun MessagesDto.toModel() = MessagesPage(items.map { it.toModel() }, lastSeq, hasMoreBefore, hasMoreAfter, experiencesStatus == "UNAVAILABLE")

    private fun MessageDto.toModel() = ChatMessage(id, seq, senderId, text, sharedExperience?.toModel(), instant(createdAt), clientKey)

    /** Só AVAILABLE traz conteúdo; um conteúdo ilegível ou um estado novo contam como UNKNOWN (nunca como eliminada). */
    private fun SharedExperienceDto.toModel(): SharedExperience {
        val state = ShareAvailability.entries.firstOrNull { it.name == availability } ?: ShareAvailability.UNKNOWN
        val content = if (state == ShareAvailability.AVAILABLE) {
            experience?.let { runCatching { json.decodeFromJsonElement(ExperienceDto.serializer(), it).toModel() }.getOrNull() }
        } else {
            null
        }
        return SharedExperience(
            experienceId = experienceId,
            availability = if (state == ShareAvailability.AVAILABLE && content == null) ShareAvailability.UNKNOWN else state,
            experience = content,
        )
    }
}

private fun instant(value: String): Instant = try {
    Instant.parse(value)
} catch (e: DateTimeParseException) {
    throw SerializationException("Data inválida")
}

fun ConversationDto.toModel() = Conversation(
    id = id,
    other = Participant(otherParticipant.userId, otherParticipant.username),
    createdAt = instant(createdAt),
    lastSeq = lastSeq,
    lastReadSeq = lastReadSeq,
    unreadCount = unreadCount,
    lastMessage = lastMessage?.let {
        LastMessage(it.seq, it.senderId, if (it.kind == "SHARE") MessageKind.SHARE else MessageKind.TEXT, it.textPreview, instant(it.createdAt))
    },
)

class UnconfiguredMessagingRepository : MessagingRepository {
    override suspend fun inbox(cursor: String?, limit: Int) = SessionResult.Failure(DataError.NOT_CONFIGURED)
    override suspend fun conversation(conversationId: String) = SessionResult.Failure(DataError.NOT_CONFIGURED)
    override suspend fun open(participantId: String) = OpenConversationResult.Failed(MessagingError.NOT_CONFIGURED)
    override suspend fun messages(conversationId: String, before: Long?, after: Long?, limit: Int) = SessionResult.Failure(DataError.NOT_CONFIGURED)
    override suspend fun send(conversationId: String, idempotencyKey: String, text: String?, experienceId: String?) = SendOutcome.Rejected(MessagingError.NOT_CONFIGURED)
    override suspend fun shares(conversationId: String, seqs: List<Long>) = SessionResult.Failure(DataError.NOT_CONFIGURED)
    override suspend fun markRead(conversationId: String, seq: Long) = SessionResult.Failure(DataError.NOT_CONFIGURED)
}
