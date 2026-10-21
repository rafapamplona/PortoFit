package com.example.portofit.testing

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
import com.example.portofit.data.repository.MessagingRepository
import com.example.portofit.data.repository.OpenConversationResult
import com.example.portofit.data.repository.SendOutcome
import com.example.portofit.data.repository.SessionResult
import java.time.Instant

/** messaging-service controlado pelos testes: cada resposta é uma função (que pode suspender); os pedidos ficam registados. */
class FakeMessagingRepository : MessagingRepository {
    data class MessagesCall(val conversationId: String, val before: Long?, val after: Long?, val limit: Int)
    data class SendCall(val conversationId: String, val key: String, val text: String?, val experienceId: String?)

    val inboxCalls = mutableListOf<String?>()
    val conversationCalls = mutableListOf<String>()
    val openCalls = mutableListOf<String>()
    val messagesCalls = mutableListOf<MessagesCall>()
    val sendCalls = mutableListOf<SendCall>()
    val sharesCalls = mutableListOf<List<Long>>()
    val readCalls = mutableListOf<Long>()

    /** Pedidos de mensagens em curso ao mesmo tempo (para provar que não há sobreposição). */
    var inFlightMessages = 0
    var maxInFlightMessages = 0

    var onInbox: suspend (String?) -> SessionResult<InboxPage> = { SessionResult.Success(InboxPage(listOf(conversationOf("c1")), null)) }
    var onConversation: suspend (String) -> SessionResult<Conversation> = { id -> SessionResult.Success(conversationOf(id)) }
    var onOpen: suspend (String) -> OpenConversationResult = { userId -> OpenConversationResult.Opened(conversationOf("c-$userId", other = userId)) }
    var onMessages: suspend (MessagesCall) -> SessionResult<MessagesPage> = { SessionResult.Success(page(emptyList())) }
    var onSend: suspend (SendCall) -> SendOutcome = { call -> SendOutcome.Sent(message(99, ME, call.text, clientKey = call.key), replayed = false) }
    var onShares: suspend (List<Long>) -> SessionResult<SharesPage> = { SessionResult.Success(SharesPage(emptyMap(), false)) }
    var onRead: suspend (Long) -> SessionResult<ReadState> = { seq -> SessionResult.Success(ReadState(seq, 0)) }

    override suspend fun inbox(cursor: String?, limit: Int): SessionResult<InboxPage> {
        inboxCalls += cursor
        return onInbox(cursor)
    }

    override suspend fun conversation(conversationId: String): SessionResult<Conversation> {
        conversationCalls += conversationId
        return onConversation(conversationId)
    }

    override suspend fun open(participantId: String): OpenConversationResult {
        openCalls += participantId
        return onOpen(participantId)
    }

    override suspend fun messages(conversationId: String, before: Long?, after: Long?, limit: Int): SessionResult<MessagesPage> {
        val call = MessagesCall(conversationId, before, after, limit)
        messagesCalls += call
        inFlightMessages++
        maxInFlightMessages = maxOf(maxInFlightMessages, inFlightMessages)
        try {
            return onMessages(call)
        } finally {
            inFlightMessages--
        }
    }

    override suspend fun send(conversationId: String, idempotencyKey: String, text: String?, experienceId: String?): SendOutcome {
        val call = SendCall(conversationId, idempotencyKey, text, experienceId)
        sendCalls += call
        return onSend(call)
    }

    override suspend fun shares(conversationId: String, seqs: List<Long>): SessionResult<SharesPage> {
        sharesCalls += seqs
        return onShares(seqs)
    }

    override suspend fun markRead(conversationId: String, seq: Long): SessionResult<ReadState> {
        readCalls += seq
        return onRead(seq)
    }

    companion object {
        const val ME = "ana-id"
        const val OTHER = "rui-id"

        fun conversationOf(id: String, other: String = OTHER, lastReadSeq: Long = 0, unread: Int = 0, lastAt: Long = 1_000) = Conversation(
            id = id,
            other = Participant(other, "rui"),
            createdAt = Instant.ofEpochSecond(100),
            lastSeq = 0,
            lastReadSeq = lastReadSeq,
            unreadCount = unread,
            lastMessage = LastMessage(1, other, MessageKind.TEXT, "Olá", Instant.ofEpochSecond(lastAt)),
        )

        fun message(seq: Long, sender: String = OTHER, text: String? = "m$seq", clientKey: String? = null, shared: SharedExperience? = null) =
            ChatMessage("id-$seq", seq, sender, text, shared, Instant.ofEpochSecond(1_000 + seq), clientKey)

        fun share(id: String, availability: ShareAvailability) = SharedExperience(
            id,
            availability,
            if (availability == ShareAvailability.AVAILABLE) FakeExperiencesRepository.experience(id) else null,
        )

        fun page(items: List<ChatMessage>, hasMoreBefore: Boolean = false, hasMoreAfter: Boolean = false, unavailable: Boolean = false) =
            MessagesPage(items, items.lastOrNull()?.seq ?: 0, hasMoreBefore, hasMoreAfter, unavailable)
    }
}
