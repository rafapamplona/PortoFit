package com.example.portofit.notifications.internal

import com.example.portofit.notifications.config.NotificationsProperties
import com.example.portofit.notifications.config.UsersProperties
import com.example.portofit.notifications.notification.Actor
import com.example.portofit.notifications.notification.NewNotification
import com.example.portofit.notifications.notification.NotificationRepository
import com.example.portofit.notifications.notification.parseUuid
import org.slf4j.LoggerFactory
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.net.http.HttpClient
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Um trabalho de resolução (§8.2): a publicação, o cursor da próxima página e quantos destinatários já foram tratados. */
data class FanOutJob(
	val eventId: UUID,
	val actor: Actor,
	val experienceId: UUID,
	val occurredAt: Instant,
	val cursor: String?,
	val recipients: Int,
	val attempts: Int,
	val createdAt: Instant,
)

@Repository
class FanOutRepository(private val jdbc: JdbcClient) {

	/** Na transação da deduplicação: um trabalho por evento. */
	fun create(eventId: UUID, request: FanOutRequest) {
		jdbc.sql(
			"INSERT INTO fan_out_job (event_id, actor_id, actor_username, experience_id, occurred_at) VALUES (:id, :a, :u, :e, :o)",
		).param("id", eventId).param("a", request.actor.userId).param("u", request.actor.username)
			.param("e", request.experienceId).param("o", Timestamp.from(request.occurredAt)).update()
	}

	/** O trabalho devido mais antigo, bloqueado até ao fim da transação; outro processo salta-o (SKIP LOCKED). */
	fun lockDue(now: Instant): FanOutJob? =
		jdbc.sql(
			"""
			SELECT * FROM fan_out_job WHERE status = 'PENDING' AND next_attempt_at <= :now
			ORDER BY created_at, event_id LIMIT 1 FOR UPDATE SKIP LOCKED
			""",
		).param("now", Timestamp.from(now)).query { rs, _ -> rs.toJob() }.optional().orElse(null)

	/** Página tratada: guarda o cursor seguinte com as notificações da página (mesma transação). */
	fun savePage(eventId: UUID, cursor: String, recipients: Int) {
		jdbc.sql(
			"UPDATE fan_out_job SET cursor = :c, recipients = :n, attempts = 0, last_error = NULL, updated_at = now() WHERE event_id = :id",
		).param("c", cursor).param("n", recipients).param("id", eventId).update()
	}

	fun finish(eventId: UUID, status: String, recipients: Int, error: String?) {
		jdbc.sql("UPDATE fan_out_job SET status = :s, recipients = :n, last_error = :e, updated_at = now() WHERE event_id = :id")
			.param("s", status).param("n", recipients).param("e", error).param("id", eventId).update()
	}

	fun retry(eventId: UUID, attempts: Int, next: Instant, error: String) {
		jdbc.sql("UPDATE fan_out_job SET attempts = :a, next_attempt_at = :n, last_error = :e, updated_at = now() WHERE event_id = :id")
			.param("a", attempts).param("n", Timestamp.from(next)).param("e", error).param("id", eventId).update()
	}

	/** Erro de configuração: adia sem gastar tentativas. */
	fun postpone(eventId: UUID, next: Instant, error: String) {
		jdbc.sql("UPDATE fan_out_job SET next_attempt_at = :n, last_error = :e, updated_at = now() WHERE event_id = :id")
			.param("n", Timestamp.from(next)).param("e", error).param("id", eventId).update()
	}

	/** Trabalhos terminados, guardados tanto quanto a deduplicação. */
	fun deleteFinishedOlderThan(age: Duration): Int =
		jdbc.sql("DELETE FROM fan_out_job WHERE status <> 'PENDING' AND updated_at < now() - CAST(:s || ' seconds' AS interval)")
			.param("s", age.toSeconds().toString()).update()

	private fun ResultSet.toJob() = FanOutJob(
		eventId = getObject("event_id", UUID::class.java),
		actor = Actor(getObject("actor_id", UUID::class.java), getString("actor_username")),
		experienceId = getObject("experience_id", UUID::class.java),
		occurredAt = getTimestamp("occurred_at").toInstant(),
		cursor = getString("cursor"),
		recipients = getInt("recipients"),
		attempts = getInt("attempts"),
		createdAt = getTimestamp("created_at").toInstant(),
	)
}

/** Resposta de uma página de seguidores (§8.1, §8.3). */
sealed interface FollowersPage {
	data class Page(val items: List<UUID>, val nextCursor: String?) : FollowersPage

	/** `404`: autor inexistente. */
	data object NotFound : FollowersPage

	/** Chave em falta ou recusada (401/403): erro de configuração, não da publicação. */
	data class ConfigError(val code: String) : FollowersPage

	/** Sem resposta, timeout, 5xx ou resposta ilegível: **nunca** "sem seguidores". */
	data class Temporary(val code: String) : FollowersPage
}

/** `GET /api/v1/internal/users/{id}/followers` no users-service. Os registos nunca têm a chave. */
@Component
class FollowersClient(private val properties: UsersProperties, private val mapper: JsonMapper) {

	private val log = LoggerFactory.getLogger(javaClass)

	private val restClient = RestClient.builder()
		.baseUrl(properties.baseUrl)
		.requestFactory(
			JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(properties.connectTimeout).build())
				.apply { setReadTimeout(properties.timeout) },
		)
		.build()

	fun page(authorId: UUID, followedBefore: Instant, cursor: String?, limit: Int): FollowersPage {
		if (properties.followersKey.isBlank()) return FollowersPage.ConfigError("KEY_NOT_CONFIGURED")
		return try {
			restClient.get()
				.uri { b ->
					b.path("/api/v1/internal/users/{id}/followers").queryParam("followedBefore", followedBefore.toString()).queryParam("limit", limit)
						.apply { if (cursor != null) queryParam("cursor", cursor) }
						.build(authorId)
				}
				.header("X-Internal-Key", properties.followersKey)
				.exchange { _, response ->
					when (val status = response.statusCode.value()) {
						200 -> parse(response.body.readAllBytes())
						404 -> FollowersPage.NotFound
						401, 403 -> FollowersPage.ConfigError("HTTP_$status")
						else -> FollowersPage.Temporary("HTTP_$status")
					}
				}
		} catch (e: Exception) {
			log.warn("users-service indisponível para a resolução de seguidores: {} ({})", e.javaClass.simpleName, e.cause?.javaClass?.simpleName)
			FollowersPage.Temporary(e.cause?.javaClass?.simpleName?.take(40) ?: e.javaClass.simpleName.take(40))
		}
	}

	/** Estrita: qualquer coisa inesperada é uma falha temporária, nunca uma lista vazia. */
	private fun parse(bytes: ByteArray): FollowersPage {
		val root = runCatching { mapper.readTree(bytes) }.getOrNull() ?: return FollowersPage.Temporary("INVALID_RESPONSE")
		val items = root.get("items")
		if (items == null || !items.isArray) return FollowersPage.Temporary("INVALID_RESPONSE")
		val ids = (0 until items.size()).map { i ->
			items[i].takeIf { it.isString }?.let { parseUuid(it.stringValue()) } ?: return FollowersPage.Temporary("INVALID_RESPONSE")
		}
		val next = root.get("nextCursor")
		val cursor = when {
			next == null || next.isNull -> null
			next.isString && next.stringValue().length in 1..200 -> next.stringValue()
			else -> return FollowersPage.Temporary("INVALID_RESPONSE")
		}
		return FollowersPage.Page(ids, cursor)
	}
}

/**
 * Resolução dos destinatários de `EXPERIENCE_PUBLISHED` (§8.2–8.3), de 5 em 5 s. Cada página numa transação, com o
 * trabalho bloqueado: pedido ao users-service, notificações da página (exceto o autor; repetidas não duplicam) e o
 * cursor seguinte. Uma interrupção a meio desfaz a página inteira e retoma do último cursor guardado.
 */
@Component
class FanOutProcessor(
	private val jobs: FanOutRepository,
	private val notifications: NotificationRepository,
	private val client: FollowersClient,
	private val properties: NotificationsProperties,
	private val tx: TransactionTemplate,
	private val clock: Clock,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	/** Falhas de configuração seguidas (recuo próprio); volta a 0 com a primeira resposta válida. */
	val configFailures = AtomicInteger(0)

	@Scheduled(fixedDelayString = "PT5S", initialDelayString = "PT15S")
	fun scheduled() {
		if (properties.fanOutEnabled) runOnce()
	}

	/** Trata até [PAGES_PER_RUN] páginas (de um ou mais trabalhos); devolve quantas tratou. */
	fun runOnce(): Int {
		var pages = 0
		while (pages < PAGES_PER_RUN && processNextPage()) pages++
		return pages
	}

	/** Uma página do trabalho devido mais antigo; false se não houver nenhum. */
	fun processNextPage(): Boolean = tx.execute {
		val now = clock.instant()
		val job = jobs.lockDue(now) ?: return@execute false
		when (val page = client.page(job.actor.userId, job.occurredAt, job.cursor, properties.fanOutPage)) {
			is FollowersPage.Page -> {
				configFailures.set(0)
				handle(job, page)
			}
			FollowersPage.NotFound -> {
				log.info("Resolução {}: autor inexistente, sem destinatários", job.eventId)
				jobs.finish(job.eventId, "DONE", job.recipients, "AUTHOR_NOT_FOUND")
			}
			is FollowersPage.ConfigError -> {
				val delay = backoff(configFailures.incrementAndGet())
				log.error("Falha de configuração na consulta de seguidores ({}); resolução adiada {} s", page.code, delay.toSeconds())
				jobs.postpone(job.eventId, now.plus(delay), "CONFIG_${page.code}".take(64))
			}
			is FollowersPage.Temporary -> {
				val attempts = job.attempts + 1
				if (Duration.between(job.createdAt, now) > properties.fanOutMaxAge) {
					log.error("Resolução {} passou a FAILED (MAX_AGE; último erro {})", job.eventId, page.code)
					jobs.finish(job.eventId, "FAILED", job.recipients, "MAX_AGE/${page.code}".take(64))
				} else {
					jobs.retry(job.eventId, attempts, now.plus(backoff(attempts)), page.code.take(64))
				}
			}
		}
		true
	}!!

	private fun handle(job: FanOutJob, page: FollowersPage.Page) {
		val ids = page.items.filter { it != job.actor.userId }.distinct()
		val room = (properties.maxFanOut - job.recipients).coerceAtLeast(0)
		val taken = ids.take(room)
		// Por ordem de id: duas transações nunca bloqueiam as mesmas caixas em ordens diferentes.
		for (recipient in taken.sorted()) {
			val seq = notifications.lockInbox(recipient) + 1
			val n = NewNotification(
				recipientId = recipient, type = EventParser.EXPERIENCE_PUBLISHED, actor = job.actor,
				experienceId = job.experienceId, eventId = job.eventId, occurredAt = job.occurredAt,
			)
			if (notifications.insert(n, seq)) notifications.trim(recipient, properties.maxPerRecipient)
		}
		val total = job.recipients + taken.size
		when {
			taken.size < ids.size -> {
				// Nunca uma truncagem silenciosa (§8.3).
				log.warn("Resolução {} TRUNCATED: limite de {} destinatários atingido", job.eventId, properties.maxFanOut)
				jobs.finish(job.eventId, "TRUNCATED", total, "MAX_FAN_OUT")
			}
			page.nextCursor == null -> {
				log.info("Resolução {} concluída: {} destinatários", job.eventId, total)
				jobs.finish(job.eventId, "DONE", total, null)
			}
			else -> jobs.savePage(job.eventId, page.nextCursor, total)
		}
	}

	/** 10 s, 20 s, 40 s… até 10 min (§8.3). */
	fun backoff(n: Int): Duration = INITIAL_BACKOFF.multipliedBy(1L shl (n - 1).coerceIn(0, 20)).coerceAtMost(MAX_BACKOFF)

	@Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT4M")
	fun cleanup() {
		val removed = jobs.deleteFinishedOlderThan(properties.eventRetention)
		if (removed > 0) log.info("Retenção: removidos {} trabalhos de resolução terminados", removed)
	}

	private companion object {
		const val PAGES_PER_RUN = 20
		val INITIAL_BACKOFF: Duration = Duration.ofSeconds(10)
		val MAX_BACKOFF: Duration = Duration.ofMinutes(10)
	}
}
