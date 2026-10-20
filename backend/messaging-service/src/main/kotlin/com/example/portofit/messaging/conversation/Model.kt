package com.example.portofit.messaging.conversation

import com.example.portofit.messaging.api.ApiErrors
import com.example.portofit.messaging.api.FieldError
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

// ------------------------------------------------------------------ Respostas (docs/messaging-api.md §4)

data class Participant(val userId: UUID, val username: String)

enum class MessageKind { TEXT, SHARE }

data class LastMessage(val seq: Long, val senderId: UUID, val kind: MessageKind, val textPreview: String?, val createdAt: Instant)

/** Formato `Conversation`, sempre do ponto de vista de quem pede: o próprio nunca aparece (é o `sub`). */
data class ConversationView(
	val id: UUID,
	val otherParticipant: Participant,
	val createdAt: Instant,
	val lastSeq: Long,
	val lastReadSeq: Long,
	val unreadCount: Int,
	val lastMessage: LastMessage?,
)

data class InboxPage(val items: List<ConversationView>, val nextCursor: String?)

enum class Availability { AVAILABLE, DELETED, UNKNOWN }

/** Uma partilha (§7.2): conteúdo da experiência só quando AVAILABLE, nunca guardado neste serviço. */
data class SharedExperience(val experienceId: UUID, val availability: Availability, val experience: JsonNode?)

data class MessageView(
	val id: UUID,
	val seq: Long,
	val senderId: UUID,
	val text: String?,
	val sharedExperience: SharedExperience?,
	val createdAt: Instant,
	/** A `Idempotency-Key` com que foi enviada, só nas mensagens do próprio (§4.2). */
	val clientKey: UUID?,
)

/** `experiencesStatus`: AVAILABLE, UNAVAILABLE (partilhas UNKNOWN) ou NOT_NEEDED (sem partilhas, sem pedido). */
data class MessagesPage(
	val items: List<MessageView>,
	val lastSeq: Long,
	val hasMoreBefore: Boolean,
	val hasMoreAfter: Boolean,
	val experiencesStatus: String,
)

data class ShareItem(val seq: Long, val sharedExperience: SharedExperience)

data class SharesPage(val items: List<ShareItem>, val experiencesStatus: String)

data class ReadState(val lastReadSeq: Long, val unreadCount: Int)

// ------------------------------------------------------------------ Linhas da base de dados

internal data class MessageRow(
	val id: UUID,
	val seq: Long,
	val senderId: UUID,
	val text: String?,
	val experienceId: UUID?,
	val createdAt: Instant,
	val idempotencyKey: UUID,
)

internal data class KeyRow(val conversationId: UUID, val seq: Long, val requestHash: String)

/** Conteúdo de uma mensagem já validado e normalizado (§6.1). */
data class MessageContent(val text: String?, val experienceId: UUID?)

/** Pedido de mensagens (§6.3): no máximo um de [before] e [after]. */
data class MessagesQuery(val before: Long?, val after: Long?, val limit: Int)

/** Posição na caixa (§5.2): última mensagem e id da última conversa da página anterior. */
data class InboxCursor(val lastMessageAt: Instant, val conversationId: UUID)

// ------------------------------------------------------------------ Regras

object MessagingRules {
	const val DEFAULT_MESSAGES = 30
	const val DEFAULT_CONVERSATIONS = 20
	const val MAX_LIMIT = 50
	const val MAX_SEQS = 50
	const val PREVIEW_LENGTH = 100
	const val UNREAD_CAP = 100

	/** UUID na forma canónica, sem distinguir maiúsculas; outra forma → null. */
	fun parseUuid(value: String?): UUID? {
		val text = value?.trim() ?: return null
		return runCatching { UUID.fromString(text) }.getOrNull()?.takeIf { it.toString().equals(text, ignoreCase = true) }
	}

	private fun parseObject(body: String?, mapper: JsonMapper): JsonNode {
		val node = try {
			body?.takeIf { it.isNotBlank() }?.let { mapper.readTree(it) }
		} catch (_: JacksonException) {
			null
		}
		if (node == null || !node.isObject) throw ApiErrors.validation("body", "O corpo do pedido tem de ser um objeto JSON.")
		return node
	}

	private fun extraFields(node: JsonNode, allowed: Set<String>, errors: MutableList<FieldError>) {
		node.propertyNames().filter { it !in allowed }.forEach { errors += FieldError(it, "Este campo não pode ser enviado.") }
	}

	/** `{"participantId": "<uuid>"}` e só esse campo (§5.1). */
	fun parseParticipant(body: String?, mapper: JsonMapper): UUID {
		val node = parseObject(body, mapper)
		val errors = mutableListOf<FieldError>()
		extraFields(node, setOf("participantId"), errors)
		val value = node.get("participantId")
		val id = if (value != null && value.isString) parseUuid(value.stringValue()) else null
		if (id == null) errors += FieldError("participantId", "Indica o identificador da pessoa.")
		if (errors.isNotEmpty()) throw ApiErrors.validation(errors)
		return id!!
	}

	/**
	 * Texto (§6.1): `\r\n` → `\n`; pontas removidas; 1–[maxLength] pontos de código; mudanças de linha e tabulações
	 * permitidas, outros caracteres de controlo não. Só espaços = sem texto (null). Inválido → [FieldError].
	 */
	fun cleanText(raw: String, maxLength: Int): Result<String?> {
		val text = raw.replace("\r\n", "\n").trim()
		if (text.isEmpty()) return Result.success(null)
		val invalid = text.codePoints().anyMatch { c ->
			val type = Character.getType(c)
			(type == Character.CONTROL.toInt() && c != '\n'.code && c != '\t'.code) || type == Character.SURROGATE.toInt()
		}
		val length = text.codePointCount(0, text.length)
		return if (invalid || length > maxLength) Result.failure(IllegalArgumentException()) else Result.success(text)
	}

	/** `{"text": "…", "experienceId": "…"}`, pelo menos um dos dois (§6.1). */
	fun parseContent(body: String?, mapper: JsonMapper, maxLength: Int): MessageContent {
		val node = parseObject(body, mapper)
		val errors = mutableListOf<FieldError>()
		extraFields(node, setOf("text", "experienceId"), errors)
		val textMessage = "O texto deve ter entre 1 e $maxLength caracteres, sem caracteres de controlo."
		var text: String? = null
		val textNode = node.get("text")
		if (textNode != null && !textNode.isNull) {
			if (!textNode.isString) {
				errors += FieldError("text", textMessage)
			} else {
				cleanText(textNode.stringValue(), maxLength).onSuccess { text = it }.onFailure { errors += FieldError("text", textMessage) }
			}
		}
		var experienceId: UUID? = null
		val experience = node.get("experienceId")
		if (experience != null && !experience.isNull) {
			experienceId = if (experience.isString) parseUuid(experience.stringValue()) else null
			if (experienceId == null) errors += FieldError("experienceId", "Identificador de experiência inválido.")
		}
		if (errors.isEmpty() && text == null && experienceId == null) {
			errors += FieldError("text", "Escreve uma mensagem ou escolhe uma experiência para partilhar.")
		}
		if (errors.isNotEmpty()) throw ApiErrors.validation(errors)
		return MessageContent(text, experienceId)
	}

	/** `{"lastReadSeq": n}`, inteiro ≥ 0 (§5.4). */
	fun parseReadSeq(body: String?, mapper: JsonMapper): Long {
		val node = parseObject(body, mapper)
		val errors = mutableListOf<FieldError>()
		extraFields(node, setOf("lastReadSeq"), errors)
		val value = node.get("lastReadSeq")
		val seq = if (value != null && value.isIntegralNumber && value.canConvertToLong()) value.longValue() else -1
		if (seq < 0) errors += FieldError("lastReadSeq", "Indica o número da última mensagem lida (inteiro maior ou igual a 0).")
		if (errors.isNotEmpty()) throw ApiErrors.validation(errors)
		return seq
	}

	fun parseMessagesQuery(before: String?, after: String?, limit: String?): MessagesQuery {
		val errors = mutableListOf<FieldError>()
		val beforeValue = parseSeq(before, "before", errors)
		val afterValue = parseSeq(after, "after", errors)
		if (beforeValue != null && afterValue != null) errors += FieldError("before", "Usa só um de before e after.")
		val limitValue = parseLimit(limit, DEFAULT_MESSAGES, errors)
		if (errors.isNotEmpty()) throw ApiErrors.validation(errors)
		return MessagesQuery(beforeValue, afterValue, limitValue)
	}

	fun parseInboxQuery(cursor: String?, limit: String?): Pair<InboxCursor?, Int> {
		val errors = mutableListOf<FieldError>()
		val parsed = cursor?.takeIf { it.isNotEmpty() }?.let { decodeCursor(it) ?: null.also { errors += FieldError("cursor", "Cursor inválido.") } }
		val limitValue = parseLimit(limit, DEFAULT_CONVERSATIONS, errors)
		if (errors.isNotEmpty()) throw ApiErrors.validation(errors)
		return parsed to limitValue
	}

	/** `seqs=1,5,9`: 1 a 50 inteiros positivos; repetidos ignorados (§7.3). */
	fun parseSeqs(value: String?): List<Long> {
		val message = "Indica de 1 a $MAX_SEQS números de mensagem, separados por vírgulas."
		val parts = value?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
		val seqs = parts.map { part -> part.toLongOrNull()?.takeIf { it > 0 } ?: throw ApiErrors.validation("seqs", message) }.distinct()
		if (seqs.isEmpty() || seqs.size > MAX_SEQS) throw ApiErrors.validation("seqs", message)
		return seqs.sorted()
	}

	private fun parseSeq(value: String?, field: String, errors: MutableList<FieldError>): Long? {
		val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
		val seq = text.toLongOrNull()
		if (seq == null || seq < 0) {
			errors += FieldError(field, "Deve ser um número inteiro maior ou igual a 0.")
			return null
		}
		return seq
	}

	private fun parseLimit(value: String?, default: Int, errors: MutableList<FieldError>): Int {
		val limit = value?.trim()?.takeIf { it.isNotEmpty() }?.let { it.toIntOrNull() ?: -1 } ?: default
		if (limit !in 1..MAX_LIMIT) errors += FieldError("limit", "O limite deve estar entre 1 e $MAX_LIMIT.")
		return limit
	}

	/** Base64 URL de `<instante ISO>|<uuid>`; opaco para a app. */
	fun encodeCursor(cursor: InboxCursor): String =
		Base64.getUrlEncoder().withoutPadding().encodeToString("${cursor.lastMessageAt}|${cursor.conversationId}".toByteArray(Charsets.UTF_8))

	private fun decodeCursor(value: String): InboxCursor? = runCatching {
		val (instant, id) = String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8).split('|').also { require(it.size == 2) }
		InboxCursor(Instant.parse(instant), parseUuid(id)!!)
	}.getOrNull()

	/** Hash do conteúdo normalizado e da conversa: a mesma chave noutra conversa conta como conteúdo diferente (§6.2). */
	fun requestHash(conversationId: UUID, content: MessageContent): String {
		val canonical = buildString {
			append(conversationId).append('\n')
			append(content.experienceId?.toString() ?: "-").append('\n')
			// Comprimento antes do texto, para "a" + experiência nunca colidir com outra combinação.
			append(content.text?.let { "${it.length}:$it" } ?: "-")
		}
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)))
	}

	/** Primeiros 100 pontos de código do texto, para a caixa (§4.1). */
	fun preview(text: String?): String? {
		if (text == null) return null
		val count = text.codePointCount(0, text.length)
		return if (count <= PREVIEW_LENGTH) text else text.substring(0, text.offsetByCodePoints(0, PREVIEW_LENGTH))
	}
}
