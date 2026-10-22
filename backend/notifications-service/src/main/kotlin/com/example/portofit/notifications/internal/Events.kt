package com.example.portofit.notifications.internal

import com.example.portofit.notifications.api.ApiErrors
import com.example.portofit.notifications.config.NotificationsProperties
import com.example.portofit.notifications.config.ProducerKeys
import com.example.portofit.notifications.notification.Actor
import com.example.portofit.notifications.notification.NewNotification
import com.example.portofit.notifications.notification.NotificationRepository
import com.example.portofit.notifications.notification.parseUuid
import com.fasterxml.jackson.annotation.JsonInclude
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.HexFormat
import java.util.UUID

/** Serviços que podem entregar eventos, cada um com a sua chave e os seus tipos (docs/notifications-api.md §6.1). */
enum class Producer(val label: String, val types: Set<String>) {
	USERS("users", setOf("USER_FOLLOWED")),
	EXPERIENCES("experiences", setOf("COMMENT_CREATED", "EXPERIENCE_PUBLISHED")),
	MESSAGING("messaging", setOf("MESSAGE_RECEIVED")),
}

enum class EventStatus { ACCEPTED, DUPLICATE, REJECTED }

@JsonInclude(JsonInclude.Include.NON_NULL)
data class EventResult(val eventId: String?, val status: EventStatus, val reason: String? = null)

data class EventsResponse(val results: List<EventResult>)

/** Um evento válido, já normalizado; [hash] identifica o conteúdo (o mesmo `eventId` com outro conteúdo → CONFLICT). */
data class ParsedEvent(val eventId: UUID, val type: String, val notification: NewNotification, val hash: String)

class RejectedEvent(val reason: String) : Exception(reason)

/**
 * Validação estrita dos eventos (§3.1, §6.4). Campos desconhecidos são recusados: um produtor nunca consegue fazer
 * passar conteúdo privado (ex.: o texto de uma mensagem). Primeira parte: só `MESSAGE_RECEIVED` é tratado; os outros
 * tipos do contrato são recusados com `UNSUPPORTED_TYPE` até à segunda parte.
 */
class EventParser(private val properties: NotificationsProperties, private val clock: Clock) {

	fun parse(node: JsonNode, producer: Producer): ParsedEvent {
		if (!node.isObject) throw RejectedEvent(INVALID)
		val eventId = uuid(node, "eventId")
		val type = text(node, "type")
		if (type !in Producer.entries.flatMap { it.types }) throw RejectedEvent(INVALID)
		if (type !in producer.types) throw RejectedEvent("WRONG_PRODUCER")
		if (type != "MESSAGE_RECEIVED") throw RejectedEvent("UNSUPPORTED_TYPE")
		allowOnly(node, setOf("eventId", "type", "occurredAt", "actor", "recipientId", "conversationId", "messageSeq", "messageKind"))
		val occurredAt = instant(node, "occurredAt")
		val now = clock.instant()
		if (occurredAt.isAfter(now.plus(properties.maxFutureSkew))) throw RejectedEvent(INVALID)
		if (occurredAt.isBefore(now.minus(properties.eventMaxAge))) throw RejectedEvent("EXPIRED")
		val actorNode = node.get("actor") ?: throw RejectedEvent(INVALID)
		if (!actorNode.isObject) throw RejectedEvent(INVALID)
		allowOnly(actorNode, setOf("userId", "username"))
		val actor = Actor(uuid(actorNode, "userId"), text(actorNode, "username").also { if (!USERNAME.matches(it)) throw RejectedEvent(INVALID) })
		val recipientId = uuid(node, "recipientId")
		if (recipientId == actor.userId) throw RejectedEvent("SELF_EVENT")
		val conversationId = uuid(node, "conversationId")
		val seqNode = node.get("messageSeq")
		val messageSeq = if (seqNode != null && seqNode.isIntegralNumber && seqNode.canConvertToLong()) seqNode.longValue() else throw RejectedEvent(INVALID)
		if (messageSeq <= 0) throw RejectedEvent(INVALID)
		val kind = text(node, "messageKind")
		if (kind != "TEXT" && kind != "SHARE") throw RejectedEvent(INVALID)
		val canonical = listOf(eventId, type, occurredAt, actor.userId, actor.username, recipientId, conversationId, messageSeq, kind).joinToString("|")
		val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)))
		return ParsedEvent(
			eventId, type,
			NewNotification(
				recipientId = recipientId, type = type, actor = actor, conversationId = conversationId, messageSeq = messageSeq,
				messageKind = kind, eventId = eventId, occurredAt = occurredAt,
			),
			hash,
		)
	}

	private fun allowOnly(node: JsonNode, allowed: Set<String>) {
		if (node.propertyNames().any { it !in allowed }) throw RejectedEvent(INVALID)
	}

	private fun text(node: JsonNode, field: String): String =
		node.get(field)?.takeIf { it.isString }?.stringValue()?.takeIf { it.isNotBlank() } ?: throw RejectedEvent(INVALID)

	private fun uuid(node: JsonNode, field: String): UUID = parseUuid(text(node, field)) ?: throw RejectedEvent(INVALID)

	private fun instant(node: JsonNode, field: String): Instant = try {
		Instant.parse(text(node, field))
	} catch (e: DateTimeParseException) {
		throw RejectedEvent(INVALID)
	}

	companion object {
		const val INVALID = "INVALID_EVENT"
		/** Regras do username no users-service (docs/users-api.md §2.1). */
		val USERNAME = Regex("^[a-z0-9](?:[a-z0-9._]{1,28})[a-z0-9]$")
	}
}

@Repository
class ReceivedEventRepository(private val jdbc: JdbcClient) {

	/** true se registou; false se o `eventId` já existia (um pedido simultâneo com o mesmo id espera e não insere). */
	fun register(eventId: UUID, producer: Producer, type: String, hash: String): Boolean =
		jdbc.sql(
			"INSERT INTO received_event (event_id, producer, type, payload_hash) VALUES (:id, :p, :t, :h) ON CONFLICT (event_id) DO NOTHING",
		).param("id", eventId).param("p", producer.label).param("t", type).param("h", hash).update() > 0

	fun hash(eventId: UUID): String? =
		jdbc.sql("SELECT payload_hash FROM received_event WHERE event_id = :id").param("id", eventId)
			.query(String::class.java).optional().orElse(null)

	fun deleteOlderThan(age: Duration): Int =
		jdbc.sql("DELETE FROM received_event WHERE received_at < now() - CAST(:s || ' seconds' AS interval)")
			.param("s", age.toSeconds().toString()).update()
}

/**
 * Entrega interna (§6): cada evento na sua transação — deduplicação e notificação juntas, com o `seq` atribuído com a
 * caixa do destinatário bloqueada. Um evento recusado não estraga os outros do lote.
 */
@Service
class EventIngestService(
	private val events: ReceivedEventRepository,
	private val notifications: NotificationRepository,
	private val properties: NotificationsProperties,
	private val tx: TransactionTemplate,
	clock: Clock,
) {
	private val log = LoggerFactory.getLogger(javaClass)
	private val parser = EventParser(properties, clock)

	fun ingest(producer: Producer, nodes: List<JsonNode>): List<EventResult> = nodes.map { node ->
		val rawId = node.get("eventId")?.takeIf { it.isString }?.stringValue()
		val parsed = try {
			parser.parse(node, producer)
		} catch (e: RejectedEvent) {
			log.warn("Evento recusado de {}: {}", producer.label, e.reason)
			return@map EventResult(rawId, EventStatus.REJECTED, e.reason)
		}
		store(producer, parsed)
	}

	private fun store(producer: Producer, event: ParsedEvent): EventResult = tx.execute {
		if (!events.register(event.eventId, producer, event.type, event.hash)) {
			val known = events.hash(event.eventId)
			return@execute if (known == event.hash) EventResult(event.eventId.toString(), EventStatus.DUPLICATE)
			else EventResult(event.eventId.toString(), EventStatus.REJECTED, "CONFLICT")
		}
		val n = event.notification
		val seq = notifications.lockInbox(n.recipientId) + 1
		notifications.insert(n, seq)
		notifications.trim(n.recipientId, properties.maxPerRecipient)
		EventResult(event.eventId.toString(), EventStatus.ACCEPTED)
	}!!

	/** A deduplicação dura mais do que a idade máxima aceite (validado no arranque), nunca menos (§6.4). */
	@Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT3M")
	fun removeOldEvents() {
		val removed = events.deleteOlderThan(properties.eventRetention)
		if (removed > 0) log.info("Retenção: removidos {} registos de eventos recebidos", removed)
	}
}

@RestController
class InternalEventsController(
	private val service: EventIngestService,
	private val keys: ProducerKeys,
	private val mapper: JsonMapper,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	@PostMapping("/api/v1/internal/events", consumes = [MediaType.APPLICATION_JSON_VALUE])
	fun receive(
		@RequestHeader("X-Internal-Key", required = false) key: String?,
		@RequestBody(required = false) body: String?,
	): EventsResponse {
		val producer = producer(key) ?: run {
			// Nunca regista a chave recebida.
			log.warn("Entrega interna recusada: chave de produtor em falta ou desconhecida")
			throw ApiErrors.forbidden()
		}
		val root = try {
			body?.takeIf { it.isNotBlank() }?.let { mapper.readTree(it) }
		} catch (_: JacksonException) {
			null
		}
		val list = root?.takeIf { it.isObject && it.propertyNames().toList() == listOf("events") }?.get("events")
		if (list == null || !list.isArray || list.size() !in 1..MAX_EVENTS) {
			throw ApiErrors.validation("events", "Indica de 1 a $MAX_EVENTS eventos.")
		}
		return EventsResponse(service.ingest(producer, (0 until list.size()).map { list[it] }))
	}

	/** O produtor dono da chave; compara com todas, em tempo constante. Chaves vazias ou repetidas não autorizam. */
	private fun producer(received: String?): Producer? {
		if (received.isNullOrEmpty()) return null
		val bytes = received.toByteArray(Charsets.UTF_8)
		val configured = listOf(Producer.USERS to keys.usersKey, Producer.EXPERIENCES to keys.experiencesKey, Producer.MESSAGING to keys.messagingKey)
			.filter { it.second.isNotBlank() }
		if (configured.map { it.second }.toSet().size != configured.size) {
			log.error("Chaves de produtores repetidas na configuração: nenhuma é aceite")
			return null
		}
		return configured.filter { (_, expected) -> MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), bytes) }.singleOrNull()?.first
	}

	private companion object {
		const val MAX_EVENTS = 100
	}
}
