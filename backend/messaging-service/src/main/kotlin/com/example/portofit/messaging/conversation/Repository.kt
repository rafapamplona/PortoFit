package com.example.portofit.messaging.conversation

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Spring JDBC; valores do pedido sempre como parâmetros. Todas as leituras de conversas passam por
 * `conversation_member`, por isso só devolvem conversas em que [me] participa.
 */
@Repository
class ConversationRepository(private val jdbc: JdbcClient) {

	/** A conversa do par, se existir (a ordem do par é a do PostgreSQL, não a de `UUID.compareTo`). */
	fun findPair(a: UUID, b: UUID): UUID? =
		jdbc.sql("SELECT id FROM conversation WHERE user_low = LEAST(CAST(:a AS uuid), CAST(:b AS uuid)) AND user_high = GREATEST(CAST(:a AS uuid), CAST(:b AS uuid))")
			.param("a", a).param("b", b)
			.query(UUID::class.java).optional().orElse(null)

	/**
	 * Cria a conversa e as duas linhas de participante, na transação em curso. Devolve null se o par já existir
	 * (um pedido simultâneo criou-a primeiro: este espera pela inserção dele e não insere nada).
	 */
	fun insertPair(me: UUID, myUsername: String, other: UUID, otherUsername: String): UUID? {
		val id = jdbc.sql(
			"""
			INSERT INTO conversation (user_low, user_high, user_low_username, user_high_username)
			SELECT LEAST(a, b), GREATEST(a, b), CASE WHEN a < b THEN :aName ELSE :bName END, CASE WHEN a < b THEN :bName ELSE :aName END
			FROM (SELECT CAST(:a AS uuid) AS a, CAST(:b AS uuid) AS b) p
			ON CONFLICT (user_low, user_high) DO NOTHING
			RETURNING id
			""",
		).param("a", me).param("b", other).param("aName", myUsername).param("bName", otherUsername)
			.query(UUID::class.java).optional().orElse(null) ?: return null
		jdbc.sql("INSERT INTO conversation_member (user_id, conversation_id) VALUES (:a, :id), (:b, :id)")
			.param("a", me).param("b", other).param("id", id).update()
		return id
	}

	fun isMember(conversationId: UUID, me: UUID): Boolean =
		jdbc.sql("SELECT EXISTS (SELECT 1 FROM conversation_member WHERE user_id = :me AND conversation_id = :c)")
			.param("me", me).param("c", conversationId).query(Boolean::class.javaObjectType).single()

	fun find(conversationId: UUID, me: UUID): ConversationView? =
		jdbc.sql("$SELECT_VIEW WHERE m.user_id = :me AND m.conversation_id = :c")
			.param("me", me).param("c", conversationId)
			.query { rs, _ -> rs.toView() }
			.optional().orElse(null)

	/** Caixa (§5.2): só conversas com mensagens; ordem total por (última mensagem, id). Pede [limit] + 1 para saber se há mais. */
	fun inbox(me: UUID, cursor: InboxCursor?, limit: Int): List<ConversationView> {
		val after = if (cursor == null) "" else "AND (m.last_message_at, m.conversation_id) < (:t, :cid)"
		val spec = jdbc.sql(
			"$SELECT_VIEW WHERE m.user_id = :me AND m.last_message_at IS NOT NULL $after " +
				"ORDER BY m.last_message_at DESC, m.conversation_id DESC LIMIT :limit",
		).param("me", me).param("limit", limit + 1)
		if (cursor != null) spec.param("t", OffsetDateTime.ofInstant(cursor.lastMessageAt, ZoneOffset.UTC)).param("cid", cursor.conversationId)
		return spec.query { rs, _ -> rs.toView() }.list()
	}

	/** Bloqueia a conversa até ao fim da transação; devolve (last_seq, message_count). */
	fun lock(conversationId: UUID): Pair<Long, Int> =
		jdbc.sql("SELECT last_seq, message_count FROM conversation WHERE id = :c FOR UPDATE")
			.param("c", conversationId)
			.query { rs, _ -> rs.getLong("last_seq") to rs.getInt("message_count") }
			.single()

	internal fun findKey(senderId: UUID, key: UUID): KeyRow? =
		jdbc.sql("SELECT conversation_id, seq, request_hash FROM message WHERE sender_id = :s AND idempotency_key = :k")
			.param("s", senderId).param("k", key)
			.query { rs, _ -> KeyRow(rs.getObject("conversation_id", UUID::class.java), rs.getLong("seq"), rs.getString("request_hash")) }
			.optional().orElse(null)

	/**
	 * Grava a mensagem com o [seq] seguinte e atualiza a conversa e os dois participantes, na transação em curso
	 * (a conversa tem de estar bloqueada por [lock]). Chave já usada noutra conversa → DuplicateKeyException.
	 */
	internal fun insertMessage(conversationId: UUID, seq: Long, senderId: UUID, content: MessageContent, key: UUID, hash: String): MessageRow {
		// clock_timestamp(): com a conversa bloqueada, as datas acompanham a ordem dos seq na mesma conversa.
		val row = jdbc.sql(
			"""
			INSERT INTO message (conversation_id, seq, sender_id, text, experience_id, created_at, idempotency_key, request_hash)
			VALUES (:c, :seq, :s, :text, :e, clock_timestamp(), :k, :h)
			RETURNING id, seq, sender_id, text, experience_id, created_at, idempotency_key
			""",
		).param("c", conversationId).param("seq", seq).param("s", senderId).param("text", content.text).param("e", content.experienceId)
			.param("k", key).param("h", hash)
			.query { rs, _ -> rs.toMessage() }
			.single()
		val at = OffsetDateTime.ofInstant(row.createdAt, ZoneOffset.UTC)
		jdbc.sql("UPDATE conversation SET last_seq = :seq, message_count = message_count + 1, last_message_at = :t WHERE id = :c")
			.param("seq", seq).param("t", at).param("c", conversationId).update()
		jdbc.sql("UPDATE conversation_member SET last_message_at = :t WHERE conversation_id = :c")
			.param("t", at).param("c", conversationId).update()
		return row
	}

	internal fun message(conversationId: UUID, seq: Long): MessageRow =
		jdbc.sql("$SELECT_MESSAGE WHERE conversation_id = :c AND seq = :seq")
			.param("c", conversationId).param("seq", seq)
			.query { rs, _ -> rs.toMessage() }
			.single()

	fun lastSeq(conversationId: UUID): Long =
		jdbc.sql("SELECT last_seq FROM conversation WHERE id = :c").param("c", conversationId).query(Long::class.javaObjectType).single()

	/** As últimas [limit] mensagens antes de [before] (ou do fim), por seq crescente. */
	internal fun messagesBefore(conversationId: UUID, before: Long?, limit: Int): List<MessageRow> {
		val condition = if (before == null) "" else "AND seq < :before"
		val spec = jdbc.sql("$SELECT_MESSAGE WHERE conversation_id = :c $condition ORDER BY seq DESC LIMIT :limit")
			.param("c", conversationId).param("limit", limit)
		if (before != null) spec.param("before", before)
		return spec.query { rs, _ -> rs.toMessage() }.list().reversed()
	}

	/** As primeiras [limit] mensagens depois de [after], por seq crescente. */
	internal fun messagesAfter(conversationId: UUID, after: Long, limit: Int): List<MessageRow> =
		jdbc.sql("$SELECT_MESSAGE WHERE conversation_id = :c AND seq > :after ORDER BY seq LIMIT :limit")
			.param("c", conversationId).param("after", after).param("limit", limit)
			.query { rs, _ -> rs.toMessage() }
			.list()

	fun existsBefore(conversationId: UUID, seq: Long): Boolean =
		jdbc.sql("SELECT EXISTS (SELECT 1 FROM message WHERE conversation_id = :c AND seq < :seq)")
			.param("c", conversationId).param("seq", seq).query(Boolean::class.javaObjectType).single()

	fun existsAfter(conversationId: UUID, seq: Long): Boolean =
		jdbc.sql("SELECT EXISTS (SELECT 1 FROM message WHERE conversation_id = :c AND seq > :seq)")
			.param("c", conversationId).param("seq", seq).query(Boolean::class.javaObjectType).single()

	/** As partilhas da conversa com estes seq, por seq crescente (as só de texto e as inexistentes ficam de fora). */
	fun shares(conversationId: UUID, seqs: List<Long>): List<Pair<Long, UUID>> =
		jdbc.sql("SELECT seq, experience_id FROM message WHERE conversation_id = :c AND seq IN (:seqs) AND experience_id IS NOT NULL ORDER BY seq")
			.param("c", conversationId).param("seqs", seqs)
			.query { rs, _ -> rs.getLong("seq") to rs.getObject("experience_id", UUID::class.java) }
			.list()

	/**
	 * Cursor de leitura (§5.4): `max(atual, min(valor, last_seq))` numa só instrução. A linha do participante fica
	 * bloqueada pelo UPDATE, por isso pedidos simultâneos ficam com o maior. null = não participo.
	 */
	fun markRead(conversationId: UUID, me: UUID, seq: Long): Long? =
		jdbc.sql(
			"""
			UPDATE conversation_member m SET last_read_seq = GREATEST(m.last_read_seq, LEAST(:seq, c.last_seq))
			FROM conversation c
			WHERE c.id = m.conversation_id AND m.user_id = :me AND m.conversation_id = :c
			RETURNING m.last_read_seq
			""",
		).param("seq", seq).param("me", me).param("c", conversationId)
			.query(Long::class.javaObjectType).optional().orElse(null)

	/** Mensagens recebidas depois do cursor, até [MessagingRules.UNREAD_CAP]. */
	fun unread(conversationId: UUID, me: UUID, lastReadSeq: Long): Int =
		jdbc.sql(
			"""
			SELECT count(*) FROM (
				SELECT 1 FROM message WHERE conversation_id = :c AND seq > :r AND sender_id <> :me LIMIT ${MessagingRules.UNREAD_CAP}
			) u
			""",
		).param("c", conversationId).param("r", lastReadSeq).param("me", me)
			.query(Int::class.javaObjectType).single()

	private fun ResultSet.toView(): ConversationView {
		val lastSeq = getLong("last_seq")
		val lastText = getString("lm_text")
		val lastMessage = getObject("lm_sender", UUID::class.java)?.let { sender ->
			LastMessage(
				seq = getLong("lm_seq"),
				senderId = sender,
				kind = if (getObject("lm_experience", UUID::class.java) != null) MessageKind.SHARE else MessageKind.TEXT,
				textPreview = MessagingRules.preview(lastText),
				createdAt = instant("lm_created_at"),
			)
		}
		return ConversationView(
			id = getObject("id", UUID::class.java),
			otherParticipant = Participant(getObject("other_id", UUID::class.java), getString("other_username")),
			createdAt = instant("created_at"),
			lastSeq = lastSeq,
			lastReadSeq = getLong("last_read_seq"),
			unreadCount = getInt("unread"),
			lastMessage = lastMessage,
		)
	}

	private fun ResultSet.toMessage() = MessageRow(
		id = getObject("id", UUID::class.java),
		seq = getLong("seq"),
		senderId = getObject("sender_id", UUID::class.java),
		text = getString("text"),
		experienceId = getObject("experience_id", UUID::class.java),
		createdAt = instant("created_at"),
		idempotencyKey = getObject("idempotency_key", UUID::class.java),
	)

	private fun ResultSet.instant(column: String): Instant = getObject(column, Timestamp::class.java).toInstant()

	private companion object {
		const val SELECT_MESSAGE = "SELECT id, seq, sender_id, text, experience_id, created_at, idempotency_key FROM message"

		/** Do ponto de vista de :me; a última mensagem é a do last_seq; as por ler contam só as recebidas. */
		val SELECT_VIEW = """
			SELECT c.id, c.created_at, c.last_seq, m.last_read_seq,
				CASE WHEN c.user_low = :me THEN c.user_high ELSE c.user_low END AS other_id,
				CASE WHEN c.user_low = :me THEN c.user_high_username ELSE c.user_low_username END AS other_username,
				lm.seq AS lm_seq, lm.sender_id AS lm_sender, lm.text AS lm_text, lm.experience_id AS lm_experience, lm.created_at AS lm_created_at,
				(SELECT count(*) FROM (
					SELECT 1 FROM message u WHERE u.conversation_id = c.id AND u.seq > m.last_read_seq AND u.sender_id <> :me
					LIMIT ${MessagingRules.UNREAD_CAP}
				) x) AS unread
			FROM conversation_member m
			JOIN conversation c ON c.id = m.conversation_id
			LEFT JOIN message lm ON lm.conversation_id = c.id AND lm.seq = c.last_seq
		"""
	}
}
