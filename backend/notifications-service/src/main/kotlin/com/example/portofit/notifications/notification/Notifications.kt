package com.example.portofit.notifications.notification

import com.example.portofit.notifications.api.ApiErrors
import com.example.portofit.notifications.api.FieldError
import com.example.portofit.notifications.config.NotificationsProperties
import com.fasterxml.jackson.annotation.JsonInclude
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

// ------------------------------------------------------------------ Modelos (docs/notifications-api.md §5.1)

data class Actor(val userId: UUID, val username: String)

/** Uma notificação: só tipo, ator e referências; nunca conteúdo (§9). Campos de outros tipos ficam de fora. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class NotificationView(
	val id: UUID,
	val seq: Long,
	val type: String,
	val actor: Actor,
	val experienceId: UUID?,
	val commentId: UUID?,
	val conversationId: UUID?,
	val messageSeq: Long?,
	val messageKind: String?,
	val occurredAt: Instant,
	val createdAt: Instant,
	val read: Boolean,
)

data class NotificationsPage(val items: List<NotificationView>, val lastSeq: Long, val hasMoreBefore: Boolean, val hasMoreAfter: Boolean)

data class UnreadCount(val unreadCount: Int)

data class ReadOne(val id: UUID, val read: Boolean, val unreadCount: Int)

/** Uma notificação a gravar (a partir de um evento aceite). */
data class NewNotification(
	val recipientId: UUID,
	val type: String,
	val actor: Actor,
	val conversationId: UUID? = null,
	val messageSeq: Long? = null,
	val messageKind: String? = null,
	val experienceId: UUID? = null,
	val commentId: UUID? = null,
	val eventId: UUID,
	val occurredAt: Instant,
)

// ------------------------------------------------------------------ Repositório

/** Spring JDBC; todas as leituras filtram pelo destinatário (o `sub` do token). */
@Repository
class NotificationRepository(private val jdbc: JdbcClient) {

	/** Bloqueia a caixa da pessoa até ao fim da transação (cria-a se não existir) e devolve o último `seq`. */
	fun lockInbox(recipientId: UUID): Long {
		jdbc.sql("INSERT INTO inbox (recipient_id) VALUES (:r) ON CONFLICT DO NOTHING").param("r", recipientId).update()
		return jdbc.sql("SELECT last_seq FROM inbox WHERE recipient_id = :r FOR UPDATE").param("r", recipientId)
			.query(Long::class.javaObjectType).single()
	}

	/** Na transação em curso, com a caixa bloqueada por [lockInbox]. */
	fun insert(n: NewNotification, seq: Long) {
		jdbc.sql(
			"""
			INSERT INTO notification (recipient_id, seq, type, actor_id, actor_username, experience_id, comment_id, conversation_id,
				message_seq, message_kind, event_id, occurred_at)
			VALUES (:r, :seq, :type, :actor, :username, :e, :c, :conv, :mseq, :kind, :event, :occurred)
			""",
		).param("r", n.recipientId).param("seq", seq).param("type", n.type).param("actor", n.actor.userId).param("username", n.actor.username)
			.param("e", n.experienceId).param("c", n.commentId).param("conv", n.conversationId).param("mseq", n.messageSeq)
			.param("kind", n.messageKind).param("event", n.eventId).param("occurred", Timestamp.from(n.occurredAt))
			.update()
		jdbc.sql("UPDATE inbox SET last_seq = :seq WHERE recipient_id = :r").param("seq", seq).param("r", n.recipientId).update()
	}

	/** Limite por pessoa: as mais antigas saem primeiro (lidas ou não). */
	fun trim(recipientId: UUID, max: Int): Int =
		jdbc.sql(
			"""
			DELETE FROM notification WHERE recipient_id = :r AND seq <= (
				SELECT seq FROM notification WHERE recipient_id = :r ORDER BY seq DESC OFFSET :max LIMIT 1
			)
			""",
		).param("r", recipientId).param("max", max).update()

	fun lastSeq(recipientId: UUID): Long =
		jdbc.sql("SELECT last_seq FROM inbox WHERE recipient_id = :r").param("r", recipientId)
			.query(Long::class.javaObjectType).optional().orElse(0L)

	/** As mais recentes antes de [before] (ou do fim), por `seq` decrescente. */
	fun before(recipientId: UUID, before: Long?, limit: Int): List<NotificationView> {
		val condition = if (before == null) "" else "AND seq < :before"
		val spec = jdbc.sql("$SELECT WHERE recipient_id = :r $condition ORDER BY seq DESC LIMIT :limit").param("r", recipientId).param("limit", limit)
		if (before != null) spec.param("before", before)
		return spec.query { rs, _ -> rs.toView() }.list()
	}

	/** As primeiras depois de [after], por `seq` crescente. */
	fun after(recipientId: UUID, after: Long, limit: Int): List<NotificationView> =
		jdbc.sql("$SELECT WHERE recipient_id = :r AND seq > :after ORDER BY seq LIMIT :limit")
			.param("r", recipientId).param("after", after).param("limit", limit)
			.query { rs, _ -> rs.toView() }.list()

	fun exists(recipientId: UUID, below: Long? = null, above: Long? = null): Boolean {
		val condition = when {
			below != null -> "seq < :v"
			else -> "seq > :v"
		}
		return jdbc.sql("SELECT EXISTS (SELECT 1 FROM notification WHERE recipient_id = :r AND $condition)")
			.param("r", recipientId).param("v", below ?: above!!)
			.query(Boolean::class.javaObjectType).single()
	}

	/** Por ler, até [cap] (cap = "cap ou mais"). */
	fun unread(recipientId: UUID, cap: Int): Int =
		jdbc.sql("SELECT count(*) FROM (SELECT 1 FROM notification WHERE recipient_id = :r AND read_at IS NULL LIMIT :cap) u")
			.param("r", recipientId).param("cap", cap)
			.query(Int::class.javaObjectType).single()

	/** Idempotente; false = inexistente ou de outra pessoa (indistinguíveis). */
	fun markRead(id: UUID, recipientId: UUID): Boolean =
		jdbc.sql("UPDATE notification SET read_at = coalesce(read_at, now()) WHERE id = :id AND recipient_id = :r")
			.param("id", id).param("r", recipientId).update() > 0

	/** Todas até `min(seq, last_seq)`; nunca marca o que ainda não existe. */
	fun markAllUpTo(recipientId: UUID, seq: Long): Int =
		jdbc.sql(
			"""
			UPDATE notification SET read_at = now()
			WHERE recipient_id = :r AND read_at IS NULL
				AND seq <= LEAST(:seq, (SELECT last_seq FROM inbox WHERE recipient_id = :r))
			""",
		).param("r", recipientId).param("seq", seq).update()

	fun deleteOlderThan(age: Duration): Int =
		jdbc.sql("DELETE FROM notification WHERE created_at < now() - CAST(:s || ' seconds' AS interval)")
			.param("s", age.toSeconds().toString()).update()

	private fun ResultSet.toView() = NotificationView(
		id = getObject("id", UUID::class.java),
		seq = getLong("seq"),
		type = getString("type"),
		actor = Actor(getObject("actor_id", UUID::class.java), getString("actor_username")),
		experienceId = getObject("experience_id", UUID::class.java),
		commentId = getObject("comment_id", UUID::class.java),
		conversationId = getObject("conversation_id", UUID::class.java),
		messageSeq = getObject("message_seq", Long::class.javaObjectType),
		messageKind = getString("message_kind"),
		occurredAt = getTimestamp("occurred_at").toInstant(),
		createdAt = getTimestamp("created_at").toInstant(),
		read = getTimestamp("read_at") != null,
	)

	private companion object {
		const val SELECT = """
			SELECT id, seq, type, actor_id, actor_username, experience_id, comment_id, conversation_id, message_seq, message_kind,
				occurred_at, created_at, read_at
			FROM notification
		"""
	}
}

// ------------------------------------------------------------------ Serviço

@Service
class NotificationService(
	private val notifications: NotificationRepository,
	private val properties: NotificationsProperties,
	transactionManager: PlatformTransactionManager,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	/** Várias consultas sobre o mesmo instantâneo (itens, lastSeq, haver mais). */
	private val snapshot = TransactionTemplate(transactionManager).apply {
		isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
		isReadOnly = true
	}

	/**
	 * Caixa (§5.1). Sem `after`: as mais recentes (ou anteriores a `before`), por `seq` decrescente. Com `after`: as novas,
	 * por `seq` crescente. Lacunas (retenção) são normais e nunca são erro.
	 */
	fun list(recipientId: UUID, before: Long?, after: Long?, limit: Int): NotificationsPage = snapshot.execute {
		val lastSeq = notifications.lastSeq(recipientId)
		if (after != null) {
			val items = notifications.after(recipientId, after, limit)
			val low = items.firstOrNull()?.seq ?: (after + 1)
			val high = items.lastOrNull()?.seq ?: after
			NotificationsPage(items, lastSeq, notifications.exists(recipientId, below = low), notifications.exists(recipientId, above = high))
		} else {
			val items = notifications.before(recipientId, before, limit)
			val low = items.lastOrNull()?.seq ?: (before ?: (lastSeq + 1))
			val high = items.firstOrNull()?.seq ?: ((before ?: (lastSeq + 1)) - 1)
			NotificationsPage(items, lastSeq, notifications.exists(recipientId, below = low), notifications.exists(recipientId, above = high))
		}
	}!!

	fun unread(recipientId: UUID) = UnreadCount(notifications.unread(recipientId, UNREAD_CAP))

	fun markRead(id: UUID, recipientId: UUID): ReadOne {
		if (!notifications.markRead(id, recipientId)) throw ApiErrors.notFound()
		return ReadOne(id, true, notifications.unread(recipientId, UNREAD_CAP))
	}

	fun markAllUpTo(recipientId: UUID, seq: Long): UnreadCount {
		notifications.markAllUpTo(recipientId, seq)
		return UnreadCount(notifications.unread(recipientId, UNREAD_CAP))
	}

	/** Retenção por idade (§12); a deduplicação é limpa à parte, com o seu próprio prazo. */
	@Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT2M")
	fun removeOld() {
		val removed = notifications.deleteOlderThan(properties.retention)
		if (removed > 0) log.info("Retenção: removidas {} notificações antigas", removed)
	}

	companion object {
		const val UNREAD_CAP = 100
		const val DEFAULT_LIMIT = 20
		const val MAX_LIMIT = 50
	}
}

// ------------------------------------------------------------------ Controlador

@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController(private val service: NotificationService, private val mapper: JsonMapper) {

	@GetMapping
	fun list(
		@AuthenticationPrincipal jwt: Jwt,
		@RequestParam("before", required = false) before: String?,
		@RequestParam("after", required = false) after: String?,
		@RequestParam("limit", required = false) limit: String?,
	): NotificationsPage {
		val me = me(jwt)
		val errors = mutableListOf<FieldError>()
		val beforeValue = seq(before, "before", errors)
		val afterValue = seq(after, "after", errors)
		if (beforeValue != null && afterValue != null) errors += FieldError("before", "Usa só um de before e after.")
		val limitValue = limit?.trim()?.takeIf { it.isNotEmpty() }?.let { it.toIntOrNull() ?: -1 } ?: NotificationService.DEFAULT_LIMIT
		if (limitValue !in 1..NotificationService.MAX_LIMIT) errors += FieldError("limit", "O limite deve estar entre 1 e ${NotificationService.MAX_LIMIT}.")
		if (errors.isNotEmpty()) throw ApiErrors.validation(errors)
		return service.list(me, beforeValue, afterValue, limitValue)
	}

	@GetMapping("/unread-count")
	fun unread(@AuthenticationPrincipal jwt: Jwt) = service.unread(me(jwt))

	@PutMapping("/{notificationId}/read")
	fun markRead(@AuthenticationPrincipal jwt: Jwt, @PathVariable("notificationId") notificationId: String): ReadOne {
		val me = me(jwt)
		val id = parseUuid(notificationId) ?: throw ApiErrors.validation("notificationId", "Identificador de notificação inválido.")
		return service.markRead(id, me)
	}

	/** `{"upToSeq": n}`, inteiro ≥ 0 e só esse campo. */
	@PutMapping("/read")
	fun markAll(@AuthenticationPrincipal jwt: Jwt, @RequestBody(required = false) body: String?): UnreadCount {
		val me = me(jwt)
		val node = try {
			body?.takeIf { it.isNotBlank() }?.let { mapper.readTree(it) }
		} catch (_: JacksonException) {
			null
		}
		if (node == null || !node.isObject) throw ApiErrors.validation("body", "O corpo do pedido tem de ser um objeto JSON.")
		val errors = mutableListOf<FieldError>()
		node.propertyNames().filter { it != "upToSeq" }.forEach { errors += FieldError(it, "Este campo não pode ser enviado.") }
		val value = node.get("upToSeq")
		val seq = if (value != null && value.isIntegralNumber && value.canConvertToLong()) value.longValue() else -1
		if (seq < 0) errors += FieldError("upToSeq", "Indica o número da notificação (inteiro maior ou igual a 0).")
		if (errors.isNotEmpty()) throw ApiErrors.validation(errors)
		return service.markAllUpTo(me, seq)
	}

	private fun seq(value: String?, field: String, errors: MutableList<FieldError>): Long? {
		val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
		val n = text.toLongOrNull()
		if (n == null || n < 0) {
			errors += FieldError(field, "Deve ser um número inteiro maior ou igual a 0.")
			return null
		}
		return n
	}

	private fun me(jwt: Jwt): UUID = parseUuid(jwt.subject) ?: throw ApiErrors.invalidToken()
}

/** UUID na forma canónica, sem distinguir maiúsculas; outra forma → null. */
fun parseUuid(value: String?): UUID? {
	val text = value?.trim() ?: return null
	return runCatching { UUID.fromString(text) }.getOrNull()?.takeIf { it.toString().equals(text, ignoreCase = true) }
}
