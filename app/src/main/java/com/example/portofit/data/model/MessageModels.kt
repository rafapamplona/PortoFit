package com.example.portofit.data.model

import java.time.Instant

/** A outra pessoa de uma conversa. [username] é só para mostrar; a conversa liga-se sempre ao [userId] (docs/messaging-api.md §8.2). */
data class Participant(val userId: String, val username: String)

enum class MessageKind { TEXT, SHARE }

/** Última mensagem na caixa (§4.1): nunca traz o conteúdo da experiência partilhada. */
data class LastMessage(val seq: Long, val senderId: String, val kind: MessageKind, val textPreview: String?, val createdAt: Instant)

/** Uma conversa vista por quem pede: o próprio nunca aparece. [unreadCount] vai até 100 ("100 ou mais"). */
data class Conversation(
    val id: String,
    val other: Participant,
    val createdAt: Instant,
    val lastSeq: Long,
    val lastReadSeq: Long,
    val unreadCount: Int,
    val lastMessage: LastMessage?,
)

data class InboxPage(val items: List<Conversation>, val nextCursor: String?)

/**
 * Estado de uma experiência partilhada (§7.2). [DELETED]: eliminada ou inexistente, sem conteúdo. [UNKNOWN]: o serviço das
 * experiências não respondeu — **não** significa eliminada; pode ser atualizada mais tarde.
 */
enum class ShareAvailability { AVAILABLE, DELETED, UNKNOWN }

data class SharedExperience(val experienceId: String, val availability: ShareAvailability, val experience: Experience?)

/** [clientKey]: a Idempotency-Key com que a mensagem foi enviada, só nas do próprio (§4.2). */
data class ChatMessage(
    val id: String,
    val seq: Long,
    val senderId: String,
    val text: String?,
    val shared: SharedExperience?,
    val createdAt: Instant,
    val clientKey: String?,
)

/** Página de mensagens, sempre por `seq` crescente (§6.3). [experiencesUnavailable]: as partilhas vieram como UNKNOWN. */
data class MessagesPage(
    val items: List<ChatMessage>,
    val lastSeq: Long,
    val hasMoreBefore: Boolean,
    val hasMoreAfter: Boolean,
    val experiencesUnavailable: Boolean,
)

data class SharesPage(val items: Map<Long, SharedExperience>, val experiencesUnavailable: Boolean)

data class ReadState(val lastReadSeq: Long, val unreadCount: Int)

/** Regras do texto, iguais às do servidor (§6.1). */
object MessageRules {
    const val MAX_TEXT = 1000

    /** `\r\n` → `\n`; pontas removidas; mudanças de linha e tabulações aceites, outros controlos não. Só espaços → null. */
    fun cleanText(raw: String): String? {
        val text = raw.replace("\r\n", "\n").trim()
        if (text.isEmpty()) return null
        val invalid = text.codePoints().anyMatch { c ->
            val type = Character.getType(c)
            (type == Character.CONTROL.toInt() && c != '\n'.code && c != '\t'.code) || type == Character.SURROGATE.toInt()
        }
        return text.takeUnless { invalid || it.codePointCount(0, it.length) > MAX_TEXT }
    }

    fun length(raw: String): Int = raw.replace("\r\n", "\n").trim().let { it.codePointCount(0, it.length) }

    /** Texto vazio é aceite (sem texto); só um texto inválido ou longo de mais é recusado. */
    fun isValidOptional(raw: String): Boolean = raw.isBlank() || cleanText(raw) != null
}
