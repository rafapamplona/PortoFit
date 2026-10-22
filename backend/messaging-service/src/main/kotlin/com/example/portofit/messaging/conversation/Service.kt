package com.example.portofit.messaging.conversation

import com.example.portofit.messaging.api.ApiErrors
import com.example.portofit.messaging.config.MessagingProperties
import com.example.portofit.messaging.experiences.ExperienceStatus
import com.example.portofit.messaging.experiences.ExperiencesClient
import com.example.portofit.messaging.experiences.LookupResult
import com.example.portofit.messaging.outbox.OutboxRepository
import com.example.portofit.messaging.users.UserLookup
import com.example.portofit.messaging.users.UsersClient
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

data class OpenResult(val conversation: ConversationView, val created: Boolean)

data class SendResult(val conversationId: UUID, val message: MessageView, val replayed: Boolean)

/** Conversas e mensagens (docs/messaging-api.md §5–§7). Quem pede vem sempre do token. */
@Service
class ConversationService(
	private val conversations: ConversationRepository,
	private val users: UsersClient,
	private val experiences: ExperiencesClient,
	private val limits: MessagingLimits,
	private val properties: MessagingProperties,
	private val tx: TransactionTemplate,
	transactionManager: PlatformTransactionManager,
	private val outbox: OutboxRepository,
	private val mapper: JsonMapper,
) {
	/** Leituras com várias consultas (mensagens, lastSeq, haver mais) sobre o mesmo instantâneo. */
	private val snapshot = TransactionTemplate(transactionManager).apply {
		isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
		isReadOnly = true
	}

	/**
	 * Abrir (§5.1): uma conversa existente responde sem chamar o users-service; uma nova exige o destinatário
	 * confirmado. Pedidos simultâneos (também A→B e B→A) ficam com a mesma conversa.
	 */
	fun open(me: UUID, myUsername: String, other: UUID): OpenResult {
		if (me == other) throw ApiErrors.cannotMessageSelf()
		conversations.findPair(me, other)?.let { return OpenResult(view(it, me), created = false) }
		limits.conversations.tryAcquire(me)?.let { throw ApiErrors.tooManyConversations(it) }
		var created: UUID? = null
		try {
			val username = when (val result = users.lookup(other)) {
				is UserLookup.Found -> result.username
				UserLookup.NotFound -> throw ApiErrors.userNotFound()
				UserLookup.Unavailable -> throw ApiErrors.usersUnavailable()
			}
			created = tx.execute { conversations.insertPair(me, myUsername, other, username) }
		} finally {
			// Só uma conversa efetivamente criada conta para o limite.
			if (created == null) limits.conversations.release(me)
		}
		if (created != null) return OpenResult(view(created, me), created = true)
		val existing = conversations.findPair(me, other) ?: error("par registado sem conversa")
		return OpenResult(view(existing, me), created = false)
	}

	fun get(conversationId: UUID, me: UUID): ConversationView = conversations.find(conversationId, me) ?: throw ApiErrors.notFound()

	fun inbox(me: UUID, cursor: InboxCursor?, limit: Int): InboxPage {
		val rows = conversations.inbox(me, cursor, limit)
		val page = rows.take(limit)
		val next = if (rows.size > limit) page.last().let { MessagingRules.encodeCursor(InboxCursor(it.lastMessage!!.createdAt, it.id)) } else null
		return InboxPage(page, next)
	}

	/**
	 * Enviar (§6.2): idempotente por (remetente, chave). A experiência é confirmada fora da transação; uma recusa ou
	 * uma falha não grava nada nem consome a chave. Uma mensagem só de texto nunca chama o experiences-service.
	 */
	fun send(conversationId: UUID, me: UUID, myUsername: String, key: UUID, content: MessageContent): SendResult {
		if (!conversations.isMember(conversationId, me)) throw ApiErrors.notFound()
		val hash = MessagingRules.requestHash(conversationId, content)
		conversations.findKey(me, key)?.let { return replay(it, hash, me) }
		limits.messages.tryAcquire(me)?.let { throw ApiErrors.tooManyMessages(it) }
		var sent: SendResult? = null
		try {
			val shared = content.experienceId?.let { experienceId ->
				when (val result = experiences.lookup(listOf(experienceId))) {
					LookupResult.Unavailable -> throw ApiErrors.experiencesUnavailable()
					is LookupResult.Found -> {
						val entry = result.entries[experienceId]
						if (entry?.status != ExperienceStatus.AVAILABLE) throw ApiErrors.experienceNotFound()
						SharedExperience(experienceId, Availability.AVAILABLE, entry.experience)
					}
				}
			}
			val row = try {
				tx.execute {
					val (lastSeq, count) = conversations.lock(conversationId)
					// Um pedido simultâneo com a mesma chave pode ter confirmado enquanto esperávamos pelo bloqueio.
					if (conversations.findKey(me, key) != null) return@execute null
					if (count >= properties.maxMessagesPerConversation) throw ApiErrors.conversationFull(properties.maxMessagesPerConversation)
					val message = conversations.insertMessage(conversationId, lastSeq + 1, me, content, key, hash)
					// Mesma transação: ou ficam a mensagem e o evento, ou nenhum (docs/notifications-api.md §7).
					enqueueMessageEvent(conversationId, me, myUsername, message, shared = content.experienceId != null)
					message
				}
			} catch (e: DuplicateKeyException) {
				// A mesma chave, ao mesmo tempo, noutra conversa: a outra ficou com ela.
				null
			}
			if (row != null) sent = SendResult(conversationId, row.toView(me, shared), replayed = false)
		} finally {
			if (sent == null) limits.messages.release(me)
		}
		sent?.let { return it }
		val existing = conversations.findKey(me, key) ?: error("chave registada sem mensagem")
		return replay(existing, hash, me)
	}

	/**
	 * Evento `MESSAGE_RECEIVED` para a outra pessoa (docs/notifications-api.md §3.1). Nunca leva o texto nem a
	 * experiência partilhada: só ids, o tipo (`TEXT`/`SHARE`) e o username de quem enviou (cópia de apresentação).
	 */
	private fun enqueueMessageEvent(conversationId: UUID, me: UUID, myUsername: String, message: MessageRow, shared: Boolean) {
		val recipient = conversations.otherParticipant(conversationId, me)
		val eventId = UUID.randomUUID()
		val payload = linkedMapOf(
			"eventId" to eventId.toString(),
			"type" to MESSAGE_RECEIVED,
			"occurredAt" to message.createdAt.toString(),
			"actor" to linkedMapOf("userId" to me.toString(), "username" to myUsername),
			"recipientId" to recipient.toString(),
			"conversationId" to conversationId.toString(),
			"messageSeq" to message.seq,
			"messageKind" to if (shared) "SHARE" else "TEXT",
		)
		outbox.insert(eventId, MESSAGE_RECEIVED, mapper.writeValueAsString(payload))
	}

	/** Histórico e novas (§6.3): por seq crescente; uma falha do experiences-service nunca impede a resposta. */
	fun messages(conversationId: UUID, me: UUID, query: MessagesQuery): MessagesPage {
		val (rows, lastSeq, hasBefore, hasAfter) = snapshot.execute {
			if (!conversations.isMember(conversationId, me)) throw ApiErrors.notFound()
			val lastSeq = conversations.lastSeq(conversationId)
			val rows = if (query.after != null) {
				conversations.messagesAfter(conversationId, query.after, query.limit)
			} else {
				conversations.messagesBefore(conversationId, query.before, query.limit)
			}
			// Limites da página: sem itens, o próprio pedido define-os.
			val low = rows.firstOrNull()?.seq ?: query.before ?: query.after?.plus(1) ?: (lastSeq + 1)
			val high = rows.lastOrNull()?.seq ?: query.after ?: query.before?.minus(1) ?: lastSeq
			Page(rows, lastSeq, conversations.existsBefore(conversationId, low), conversations.existsAfter(conversationId, high))
		}!!
		val shares = enrich(rows.mapNotNull { it.experienceId })
		return MessagesPage(rows.map { row -> row.toView(me, row.experienceId?.let { shares(it) }) }, lastSeq, hasBefore, hasAfter, shares.status)
	}

	/** Atualizar partilhas já recebidas (§7.3), sem mexer no cursor. */
	fun shares(conversationId: UUID, me: UUID, seqs: List<Long>): SharesPage {
		if (!conversations.isMember(conversationId, me)) throw ApiErrors.notFound()
		val rows = conversations.shares(conversationId, seqs)
		val shares = enrich(rows.map { it.second })
		return SharesPage(rows.map { (seq, experienceId) -> ShareItem(seq, shares(experienceId)) }, shares.status)
	}

	/** Cursor de leitura monotónico e limitado ao fim (§5.4); devolve o valor efetivo. */
	fun markRead(conversationId: UUID, me: UUID, seq: Long): ReadState {
		val effective = conversations.markRead(conversationId, me, seq) ?: throw ApiErrors.notFound()
		return ReadState(effective, conversations.unread(conversationId, me, effective))
	}

	private data class Page(val rows: List<MessageRow>, val lastSeq: Long, val hasBefore: Boolean, val hasAfter: Boolean)

	private fun view(conversationId: UUID, me: UUID) = conversations.find(conversationId, me) ?: error("conversa sem participante")

	/** Repetição: a mesma mensagem, sem validar de novo a experiência (o estado atual vem da consulta, se possível). */
	private fun replay(key: KeyRow, hash: String, me: UUID): SendResult {
		val row = conversations.message(key.conversationId, key.seq)
		if (key.requestHash != hash) throw ApiErrors.differentContent(key.conversationId, row.id)
		val shared = row.experienceId?.let { enrich(listOf(it))(it) }
		return SendResult(key.conversationId, row.toView(me, shared), replayed = true)
	}

	/** Um único pedido em lote por resposta (§7.2); ids distintos, no máximo 50 (limite das páginas). */
	private fun enrich(experienceIds: List<UUID>): Shares {
		val distinct = experienceIds.distinct()
		if (distinct.isEmpty()) return Shares(null, "NOT_NEEDED")
		return when (val lookup = experiences.lookup(distinct)) {
			LookupResult.Unavailable -> Shares(null, "UNAVAILABLE")
			is LookupResult.Found -> Shares(lookup, "AVAILABLE")
		}
	}

	/** Resultado da consulta em lote; sem [lookup] (falha ou sem pedido), as partilhas ficam UNKNOWN. */
	private data class Shares(val lookup: LookupResult.Found?, val status: String) {
		operator fun invoke(id: UUID): SharedExperience {
			val found = lookup ?: return SharedExperience(id, Availability.UNKNOWN, null)
			val entry = found.entries[id]
			return if (entry?.status == ExperienceStatus.AVAILABLE) {
				SharedExperience(id, Availability.AVAILABLE, entry.experience)
			} else {
				// Eliminada ou inexistente: nenhum conteúdo (este serviço nunca o guardou).
				SharedExperience(id, Availability.DELETED, null)
			}
		}
	}

	private fun MessageRow.toView(me: UUID, shared: SharedExperience?) = MessageView(
		id = id,
		seq = seq,
		senderId = senderId,
		text = text,
		sharedExperience = shared,
		createdAt = createdAt,
		clientKey = idempotencyKey.takeIf { senderId == me },
	)

	private companion object {
		const val MESSAGE_RECEIVED = "MESSAGE_RECEIVED"
	}
}
