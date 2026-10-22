package com.example.portofit.users.outbox

import com.example.portofit.users.config.NotificationsProperties
import com.example.portofit.users.config.OutboxProperties
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.net.http.HttpClient
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Uma linha da outbox a enviar. */
data class OutboxRow(val eventId: UUID, val payload: String, val createdAt: Instant, val attempts: Int)

/** Resumo para o log (só contagens e idades; nunca conteúdo). */
data class OutboxSummary(val pending: Long, val oldestPendingSeconds: Long?, val failed: Long, val sentLastHour: Long)

@Repository
class OutboxRepository(private val jdbc: JdbcClient) {

	/** Na transação em curso (a da ação). */
	fun insert(eventId: UUID, type: String, payload: String) {
		jdbc.sql("INSERT INTO outbox (event_id, type, payload) VALUES (:id, :type, :payload)")
			.param("id", eventId).param("type", type).param("payload", payload).update()
	}

	/** Os eventos por enviar, pela ordem de criação, bloqueados até ao fim da transação (duas instâncias não os repetem). */
	fun claimDue(now: Instant, limit: Int): List<OutboxRow> =
		jdbc.sql(
			"""
			SELECT event_id, payload, created_at, attempts FROM outbox
			WHERE status = 'PENDING' AND next_attempt_at <= :now
			ORDER BY created_at, event_id LIMIT :limit FOR UPDATE SKIP LOCKED
			""",
		).param("now", Timestamp.from(now)).param("limit", limit)
			.query { rs, _ -> OutboxRow(rs.getObject("event_id", UUID::class.java), rs.getString("payload"), rs.getTimestamp("created_at").toInstant(), rs.getInt("attempts")) }
			.list()

	fun markSent(ids: Collection<UUID>, now: Instant) {
		if (ids.isEmpty()) return
		jdbc.sql("UPDATE outbox SET status = 'SENT', sent_at = :now, last_error = NULL WHERE event_id IN (:ids)")
			.param("now", Timestamp.from(now)).param("ids", ids).update()
	}

	fun markFailed(id: UUID, error: String) {
		jdbc.sql("UPDATE outbox SET status = 'FAILED', last_error = :e WHERE event_id = :id").param("e", error).param("id", id).update()
	}

	fun retry(id: UUID, attempts: Int, next: Instant, error: String) {
		jdbc.sql("UPDATE outbox SET attempts = :a, next_attempt_at = :n, last_error = :e WHERE event_id = :id")
			.param("a", attempts).param("n", Timestamp.from(next)).param("e", error).param("id", id).update()
	}

	/** Erro de configuração: adia sem gastar tentativas. */
	fun postpone(ids: Collection<UUID>, next: Instant, error: String) {
		if (ids.isEmpty()) return
		jdbc.sql("UPDATE outbox SET next_attempt_at = :n, last_error = :e WHERE event_id IN (:ids)")
			.param("n", Timestamp.from(next)).param("e", error).param("ids", ids).update()
	}

	fun summary(now: Instant): OutboxSummary = jdbc.sql(
		"""
		SELECT count(*) FILTER (WHERE status = 'PENDING') AS pending,
			extract(epoch FROM (CAST(:now AS timestamptz) - min(created_at) FILTER (WHERE status = 'PENDING')))::bigint AS oldest,
			count(*) FILTER (WHERE status = 'FAILED') AS failed,
			count(*) FILTER (WHERE status = 'SENT' AND sent_at > CAST(:now AS timestamptz) - interval '1 hour') AS sent
		FROM outbox
		""",
	).param("now", Timestamp.from(now))
		.query { rs, _ -> OutboxSummary(rs.getLong("pending"), rs.getObject("oldest", Long::class.javaObjectType), rs.getLong("failed"), rs.getLong("sent")) }
		.single()

	fun deleteOld(status: String, olderThan: Duration): Int =
		jdbc.sql("DELETE FROM outbox WHERE status = :s AND created_at < now() - CAST(:secs || ' seconds' AS interval)")
			.param("s", status).param("secs", olderThan.toSeconds().toString()).update()
}

/** Resultado de entregar um lote ao notifications-service. */
sealed interface Delivery {
	/** Resposta `200`: estado por evento (ACCEPTED, DUPLICATE, REJECTED) e motivo das recusas. */
	data class Results(val statuses: Map<UUID, Pair<String, String?>>) : Delivery

	/** Chave em falta ou recusada (401/403): erro de configuração do produtor, não dos eventos. */
	data class ConfigError(val code: String) : Delivery

	/** Sem resposta, timeout, 5xx ou resposta inesperada: repetir mais tarde. */
	data class Temporary(val code: String) : Delivery
}

/** `POST /api/v1/internal/events` com a chave exclusiva deste produtor. Os registos nunca têm a chave nem conteúdo. */
@Component
class NotificationsClient(private val properties: NotificationsProperties, private val mapper: JsonMapper) {

	private val log = LoggerFactory.getLogger(javaClass)

	private val restClient = RestClient.builder()
		.baseUrl(properties.baseUrl)
		.requestFactory(
			JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(properties.connectTimeout).build())
				.apply { setReadTimeout(properties.timeout) },
		)
		.build()

	fun deliver(rows: List<OutboxRow>): Delivery {
		if (properties.internalKey.isBlank()) return Delivery.ConfigError("KEY_NOT_CONFIGURED")
		val body = """{"events":[${rows.joinToString(",") { it.payload }}]}"""
		return try {
			restClient.post()
				.uri("/api/v1/internal/events")
				.contentType(MediaType.APPLICATION_JSON)
				.header("X-Internal-Key", properties.internalKey)
				.body(body)
				.exchange { _, response ->
					when (val status = response.statusCode.value()) {
						200 -> parse(response.body.readAllBytes())
						401, 403 -> Delivery.ConfigError("HTTP_$status")
						else -> Delivery.Temporary("HTTP_$status")
					}
				}
		} catch (e: Exception) {
			log.warn("notifications-service indisponível: {} ({})", e.javaClass.simpleName, e.cause?.javaClass?.simpleName)
			Delivery.Temporary(e.cause?.javaClass?.simpleName?.take(40) ?: e.javaClass.simpleName.take(40))
		}
	}

	private fun parse(bytes: ByteArray): Delivery {
		val results = runCatching { mapper.readTree(bytes) }.getOrNull()?.get("results")
		if (results == null || !results.isArray) return Delivery.Temporary("INVALID_RESPONSE")
		val statuses = mutableMapOf<UUID, Pair<String, String?>>()
		for (i in 0 until results.size()) {
			val node = results[i]
			val id = node.get("eventId")?.takeIf { it.isString }?.let { runCatching { UUID.fromString(it.stringValue()) }.getOrNull() } ?: continue
			val status = node.get("status")?.takeIf { it.isString }?.stringValue() ?: continue
			statuses[id] = status to node.get("reason")?.takeIf { it.isString }?.stringValue()
		}
		return Delivery.Results(statuses)
	}
}

/**
 * Envio da outbox (docs/notifications-api.md §7): de 5 em 5 s, lotes até 100, por ordem de criação; resultado por
 * evento; recuo por evento (5 s → 10 min); `FAILED` ao fim das tentativas ou da idade máxima; erro de configuração
 * com recuo próprio e sem gastar tentativas. Resumo no log de minuto a minuto e limpeza de hora a hora.
 */
@Component
class OutboxRelay(
	private val outbox: OutboxRepository,
	private val client: NotificationsClient,
	private val properties: OutboxProperties,
	private val tx: TransactionTemplate,
	private val clock: Clock,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	/** Falhas de configuração seguidas (recuo próprio); volta a 0 com a primeira resposta válida. */
	val configFailures = AtomicInteger(0)

	@Scheduled(fixedDelayString = "PT5S", initialDelayString = "PT10S")
	fun scheduled() {
		if (properties.enabled) runOnce()
	}

	/** Um ciclo: devolve quantos eventos foram tratados (enviados, adiados ou falhados). */
	fun runOnce(): Int = tx.execute {
		val now = clock.instant()
		val rows = outbox.claimDue(now, properties.batchSize)
		if (rows.isEmpty()) return@execute 0
		when (val delivery = client.deliver(rows)) {
			is Delivery.Results -> {
				configFailures.set(0)
				apply(rows, delivery, now)
			}
			is Delivery.ConfigError -> {
				val delay = backoff(configFailures.incrementAndGet())
				// Uma vez por período de recuo, só o código (nunca a chave).
				log.error("Falha de configuração na entrega de notificações ({}); {} eventos adiados {} s", delivery.code, rows.size, delay.toSeconds())
				outbox.postpone(rows.map { it.eventId }, now.plus(delay), "CONFIG_${delivery.code}".take(64))
			}
			is Delivery.Temporary -> rows.forEach { retryOrFail(it, now, delivery.code) }
		}
		rows.size
	}!!

	private fun apply(rows: List<OutboxRow>, delivery: Delivery.Results, now: Instant) {
		val sent = mutableListOf<UUID>()
		for (row in rows) {
			val (status, reason) = delivery.statuses[row.eventId] ?: run {
				retryOrFail(row, now, "MISSING_RESULT")
				null to null
			}
			when (status) {
				"ACCEPTED", "DUPLICATE" -> sent += row.eventId
				"REJECTED" -> {
					log.warn("Evento {} recusado pelo notifications-service: {}", row.eventId, reason)
					outbox.markFailed(row.eventId, "REJECTED/${reason ?: "?"}".take(64))
				}
				null -> Unit
				else -> retryOrFail(row, now, "UNKNOWN_STATUS")
			}
		}
		outbox.markSent(sent, now)
	}

	private fun retryOrFail(row: OutboxRow, now: Instant, error: String) {
		val attempts = row.attempts + 1
		when {
			attempts >= properties.maxAttempts -> fail(row, "MAX_ATTEMPTS")
			Duration.between(row.createdAt, now) > properties.maxAge -> fail(row, "MAX_AGE")
			else -> outbox.retry(row.eventId, attempts, now.plus(backoff(attempts)), error.take(64))
		}
	}

	private fun fail(row: OutboxRow, reason: String) {
		log.error("Evento {} passou a FAILED ({})", row.eventId, reason)
		outbox.markFailed(row.eventId, reason)
	}

	/** 5 s, 10 s, 20 s… até ao máximo. */
	fun backoff(n: Int): Duration {
		val factor = 1L shl (n - 1).coerceIn(0, 20)
		return properties.initialBackoff.multipliedBy(factor).coerceAtMost(properties.maxBackoff)
	}

	/** Resumo observável, só quando há pendentes ou falhados (contagens e idades; nunca conteúdo). */
	@Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
	fun logSummary() {
		val s = outbox.summary(clock.instant())
		if (s.pending > 0 || s.failed > 0) {
			log.info("Outbox: {} pendentes (mais antigo há {} s), {} falhados, {} entregues na última hora", s.pending, s.oldestPendingSeconds ?: 0, s.failed, s.sentLastHour)
		}
	}

	@Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
	fun cleanup() {
		val sent = outbox.deleteOld("SENT", properties.sentRetention)
		val failed = outbox.deleteOld("FAILED", properties.failedRetention)
		if (sent + failed > 0) log.info("Outbox: limpeza de {} entregues e {} falhados antigos", sent, failed)
	}
}
