package com.example.portofit.experiences

import com.example.portofit.experiences.outbox.OutboxRelay
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Eventos de notificações do experiences-service (docs/notifications-api.md §3, §7): `COMMENT_CREATED` e
 * `EXPERIENCE_PUBLISHED` na transação da ação, entregues pelo envio periódico (aqui chamado diretamente) a um falso
 * notifications-service.
 */
class NotificationEventsIntegrationTest : ApiIntegrationTest() {

	@Autowired lateinit var relay: OutboxRelay

	private fun events(type: String, field: String, value: String): List<JsonNode> =
		jdbc.sql("SELECT payload FROM outbox WHERE type = :t AND payload::jsonb ->> :f = :v ORDER BY created_at")
			.param("t", type).param("f", field).param("v", value).query(String::class.java).list().map { mapper.readTree(it) }

	@Test
	fun `publicar e comentar gravam um evento so por acao nova, sem texto`() {
		val ana = newUser("ana")
		val bruno = newUser("bruno")
		val offering = catalog.add()
		val key = UUID.randomUUID().toString()
		val exp = publish(ana, offering, "Texto privado da publicação", key).created()
		publish(ana, offering, "Texto privado da publicação", key).andExpect { status { isOk() } } // repetição
		publishRaw(ana, """{"placeId":"${offering.placeId}","offeringId":"${UUID.randomUUID()}","text":"x","stars":3}""")
			.andExpect { status { isBadRequest() } }
		val published = events("EXPERIENCE_PUBLISHED", "experienceId", exp).single()
		assertEquals(setOf("eventId", "type", "occurredAt", "actor", "experienceId"), published.propertyNames().toSet())
		assertEquals(ana.id.toString(), published["actor"]["userId"].asString())
		assertEquals(ana.username, published["actor"]["username"].asString())
		val createdAt = jdbc.sql("SELECT created_at FROM experience WHERE id = :id").param("id", UUID.fromString(exp))
			.query { rs, _ -> rs.getTimestamp(1).toInstant() }.single()
		assertEquals(createdAt, Instant.parse(published["occurredAt"].asString()), "occurredAt é a data da publicação")
		assertEquals(1L, count("SELECT count(*) FROM outbox WHERE type = 'EXPERIENCE_PUBLISHED' AND payload::jsonb -> 'actor' ->> 'userId' = :u", ana.id))

		val commentKey = UUID.randomUUID().toString()
		val comment = comment(bruno, exp, "Comentário privado do Bruno", commentKey).created()
		comment(bruno, exp, "Comentário privado do Bruno", commentKey).andExpect { status { isOk() } } // repetição
		comment(ana, exp, "A autora comenta a própria experiência").created() // sem notificação da própria ação
		comment(bruno, UUID.randomUUID().toString(), "Numa experiência inexistente").andExpect { status { isNotFound() } }
		val (created) = events("COMMENT_CREATED", "experienceId", exp).also { assertEquals(1, it.size) }
		assertEquals(setOf("eventId", "type", "occurredAt", "actor", "recipientId", "experienceId", "commentId"), created.propertyNames().toSet())
		assertEquals(ana.id.toString(), created["recipientId"].asString())
		assertEquals(bruno.id.toString(), created["actor"]["userId"].asString())
		assertEquals(comment, created["commentId"].asString())

		val all = jdbc.sql("SELECT payload FROM outbox WHERE payload LIKE :e").param("e", "%$exp%").query { rs, _ -> rs.getString(1) }.list()
		assertFalse(all.any { it.contains("privad") || it.contains("própria") })

		// Comentários simultâneos com a mesma chave: um comentário, um evento.
		val carla = newUser("carla")
		val sameKey = UUID.randomUUID().toString()
		concurrently(6) { http("/api/v1/experiences/$exp/comments", carla.token, """{"text":"Ao mesmo tempo"}""", sameKey) }
		assertEquals(1, events("COMMENT_CREATED", "experienceId", exp).count { it["actor"]["userId"].asString() == carla.id.toString() })
	}

	@Test
	fun `eventos esperam pelo notifications-service parado e chegam uma vez`() {
		jdbc.sql("DELETE FROM outbox").update()
		notifications.reset()
		val ana = newUser("ana")
		val bruno = newUser("bruno")
		notifications.mode = NotificationsStub.Mode.DOWN
		val exp = publish(ana, catalog.add(), "Publicação", UUID.randomUUID().toString()).created()
		comment(bruno, exp, "Comentário").created()
		makeDue()
		assertEquals(2, relay.runOnce())
		assertEquals(listOf("PENDING:1", "PENDING:1"), states())
		notifications.mode = NotificationsStub.Mode.UP
		makeDue()
		assertEquals(2, relay.runOnce())
		assertEquals(listOf("SENT:1", "SENT:1"), states())
		val ids = jdbc.sql("SELECT event_id FROM outbox").query(UUID::class.java).list()
		assertTrue(ids.all { notifications.accepted[it.toString()] == 1 })
		makeDue()
		assertEquals(0, relay.runOnce())
	}

	private fun states(): List<String> =
		jdbc.sql("SELECT status || ':' || attempts FROM outbox ORDER BY created_at").query { rs, _ -> rs.getString(1) }.list()

	/**
	 * Alinha os pendentes com o relógio dos testes (MutableClock), que outros testes adiantam: sem isto, um evento
	 * acabado de criar pareceria ainda não devido, ou com mais de 24 h (FAILED por idade).
	 */
	private fun makeDue() {
		val at = java.sql.Timestamp.from(clock.instant().minusSeconds(1))
		jdbc.sql("UPDATE outbox SET created_at = :at, next_attempt_at = :at WHERE status = 'PENDING'").param("at", at).update()
	}

	private fun count(sql: String, id: UUID): Long = jdbc.sql(sql).param("u", id.toString()).query(Long::class.javaObjectType).single()

	private fun publish(user: TestUser, offering: CatalogStub.TestOffering, text: String, key: String) =
		publishRaw(user, mapper.writeValueAsString(mapOf("placeId" to offering.placeId, "offeringId" to offering.id, "text" to text, "stars" to 4)), key)

	private fun publishRaw(user: TestUser, body: String, key: String = UUID.randomUUID().toString()): ResultActionsDsl =
		mvc.post("/api/v1/experiences") {
			json(body)
			bearer(user.token)
			header("Idempotency-Key", key)
		}

	private fun comment(user: TestUser, exp: String, text: String, key: String = UUID.randomUUID().toString()): ResultActionsDsl =
		mvc.post("/api/v1/experiences/$exp/comments") {
			json(mapper.writeValueAsString(mapOf("text" to text)))
			bearer(user.token)
			header("Idempotency-Key", key)
		}

	private fun ResultActionsDsl.created(): String =
		andExpect { status { isCreated() } }.andReturn().response.contentAsString.let { mapper.readTree(it)["id"].asString() }

	private fun MockHttpServletRequestDsl.json(body: String) {
		contentType = org.springframework.http.MediaType.APPLICATION_JSON
		content = body
	}

	private fun MockHttpServletRequestDsl.bearer(token: String) {
		header("Authorization", "Bearer $token")
	}

	private fun http(path: String, token: String, body: String, key: String): Int =
		HttpClient.newHttpClient().send(
			HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).header("Authorization", "Bearer $token")
				.header("Content-Type", "application/json").header("Idempotency-Key", key).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
			HttpResponse.BodyHandlers.ofString(),
		).statusCode()

	private fun <T> concurrently(n: Int, task: (Int) -> T): List<T> {
		val pool = Executors.newFixedThreadPool(n)
		val start = CountDownLatch(1)
		try {
			val futures = (0 until n).map { i -> pool.submit(Callable { start.await(); task(i) }) }
			start.countDown()
			return futures.map { it.get() }
		} finally {
			pool.shutdownNow()
		}
	}
}
