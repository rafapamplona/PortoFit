package com.example.portofit.messaging

import com.example.portofit.messaging.config.NotificationsProperties
import com.example.portofit.messaging.outbox.Delivery
import com.example.portofit.messaging.outbox.NotificationsClient
import com.example.portofit.messaging.outbox.OutboxRelay
import com.example.portofit.messaging.outbox.OutboxRepository
import com.example.portofit.messaging.outbox.OutboxRow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import tools.jackson.databind.JsonNode
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Outbox de notificações (docs/notifications-api.md §7): o evento nasce na transação da mensagem e é entregue pelo
 * envio periódico (aqui chamado diretamente, com o agendamento desligado) ao falso notifications-service.
 */
@ExtendWith(OutputCaptureExtension::class)
class OutboxIntegrationTest : ApiIntegrationTest() {

	@Autowired lateinit var relay: OutboxRelay
	@Autowired lateinit var outbox: OutboxRepository

	data class Event(val id: UUID, val status: String, val attempts: Int, val nextAttemptAt: Instant, val lastError: String?, val payload: JsonNode)

	/** Cada teste começa sem eventos pendentes de outros testes (a base de testes é partilhada). */
	@BeforeEach
	fun clearOutbox() {
		jdbc.sql("DELETE FROM outbox").update()
		relay.configFailures.set(0)
		notifications.reset()
	}

	private fun events(conversationId: String): List<Event> = jdbc.sql(
		"SELECT event_id, status, attempts, next_attempt_at, last_error, payload FROM outbox WHERE payload::jsonb ->> 'conversationId' = :c ORDER BY created_at, event_id",
	).param("c", conversationId).query { rs, _ ->
		Event(rs.getObject("event_id", UUID::class.java), rs.getString("status"), rs.getInt("attempts"), rs.getTimestamp("next_attempt_at").toInstant(), rs.getString("last_error"), mapper.readTree(rs.getString("payload")))
	}.list()

	private fun event(id: UUID): Event = jdbc.sql("SELECT event_id, status, attempts, next_attempt_at, last_error, payload FROM outbox WHERE event_id = :id").param("id", id)
		.query { rs, _ -> Event(rs.getObject("event_id", UUID::class.java), rs.getString("status"), rs.getInt("attempts"), rs.getTimestamp("next_attempt_at").toInstant(), rs.getString("last_error"), mapper.readTree(rs.getString("payload"))) }
		.single()

	/**
	 * Torna já devidos os eventos adiados (sem esperar pelo recuo). Também antes do primeiro envio: o `now()` da base
	 * (relógio do contentor) pode estar uns milissegundos à frente do relógio da JVM que o envio usa.
	 */
	private fun makeDue() {
		jdbc.sql("UPDATE outbox SET next_attempt_at = now() - interval '1 second' WHERE status = 'PENDING'").update()
	}

	/** Eventos sintéticos diretamente na outbox (para lotes grandes, sem os limites de mensagens). */
	private fun synthetic(n: Int): List<UUID> = (1..n).map {
		val id = UUID.randomUUID()
		outbox.insert(id, "MESSAGE_RECEIVED", """{"eventId":"$id","type":"MESSAGE_RECEIVED"}""")
		// created_at distinto e crescente: a ordem de criação é a ordem de envio.
		jdbc.sql("UPDATE outbox SET created_at = now() - make_interval(secs => :s), next_attempt_at = now() - interval '1 minute' WHERE event_id = :id").param("s", (n - it).toDouble()).param("id", id).update()
		id
	}

	@Test
	fun `um evento por mensagem, na mesma transacao, sem o texto`() {
		val ana = person()
		val bruno = person()
		val c = conversation(ana, bruno)
		val key = UUID.randomUUID().toString()
		val first = send(ana, c, "Texto privado da Ana", key = key)
		assertEquals(201, first.status, first.body)
		// Repetição idempotente: a mesma mensagem, nenhum evento novo.
		assertEquals(200, send(ana, c, "Texto privado da Ana", key = key).status)
		val shared = experiences.available()
		assertEquals(201, send(bruno, c, "Vê isto", experienceId = shared).status)
		// Recusas não gravam mensagem nem evento.
		assertEquals(404, send(ana, c, "x", experienceId = UUID.randomUUID()).status)
		assertEquals(400, send(ana, c, "").status)

		val list = events(c)
		assertEquals(2, list.size)
		assertEquals(count("SELECT count(*) FROM message WHERE conversation_id = :c", "c" to UUID.fromString(c)), list.size.toLong())
		val (toBruno, toAna) = list
		assertEquals("MESSAGE_RECEIVED", toBruno.payload["type"].asString())
		assertEquals(bruno.id.toString(), toBruno.payload["recipientId"].asString())
		assertEquals(ana.id.toString(), toBruno.payload["actor"]["userId"].asString())
		assertEquals(ana.username, toBruno.payload["actor"]["username"].asString())
		assertEquals(1L, toBruno.payload["messageSeq"].asLong())
		assertEquals("TEXT", toBruno.payload["messageKind"].asString())
		assertEquals(toBruno.id.toString(), toBruno.payload["eventId"].asString())
		assertEquals(ana.id.toString(), toAna.payload["recipientId"].asString())
		assertEquals("SHARE", toAna.payload["messageKind"].asString())
		assertEquals(
			setOf("eventId", "type", "occurredAt", "actor", "recipientId", "conversationId", "messageSeq", "messageKind"),
			toBruno.payload.propertyNames().toSet(),
		)
		assertFalse(list.any { it.payload.toString().contains("privado") || it.payload.toString().contains("Vê isto") || it.payload.toString().contains(shared.toString()) })
		assertTrue(list.all { it.status == "PENDING" && it.attempts == 0 })

		// Envio: ambos entregues uma vez, e os corpos enviados também não levam conteúdo.
		makeDue()
		assertEquals(2, relay.runOnce())
		assertTrue(events(c).all { it.status == "SENT" })
		assertTrue(list.all { notifications.accepted[it.id.toString()] == 1 })
		assertFalse(notifications.bodies.any { it.contains("privado") })
		assertEquals(0, relay.runOnce())
	}

	@Test
	fun `notificacoes paradas nao impedem enviar e depois entrega uma vez`() {
		notifications.mode = NotificationsStub.Mode.DOWN
		val ana = person()
		val bruno = person()
		val c = conversation(ana, bruno)
		val started = System.nanoTime()
		repeat(3) { assertEquals(201, send(ana, c, "olá $it").status) }
		assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(5), "o envio nunca espera pelo notifications-service")

		makeDue()
		assertEquals(3, relay.runOnce())
		var list = events(c)
		assertTrue(list.all { it.status == "PENDING" && it.attempts == 1 && it.lastError == "HTTP_503" })
		assertTrue(list.all { it.nextAttemptAt > Instant.now().plusSeconds(3) }, "recuo de 5 s")
		assertEquals(0, relay.runOnce(), "ainda não devidos")
		makeDue()
		relay.runOnce()
		list = events(c)
		assertTrue(list.all { it.attempts == 2 && it.nextAttemptAt > Instant.now().plusSeconds(8) }, "recuo de 10 s")

		notifications.mode = NotificationsStub.Mode.UP
		makeDue()
		assertEquals(3, relay.runOnce())
		assertTrue(events(c).all { it.status == "SENT" })
		assertTrue(list.all { notifications.accepted[it.id.toString()] == 1 })
		makeDue()
		assertEquals(0, relay.runOnce())
		assertEquals(3, notifications.requests.size)
	}

	@Test
	fun `resposta perdida - o reenvio e DUPLICATE e fica entregue uma vez`() {
		val ana = person()
		val c = conversation(ana, person())
		sent(ana, c, "olá")
		notifications.mode = NotificationsStub.Mode.LOST_RESPONSE
		makeDue()
		relay.runOnce()
		val id = events(c).single().id
		assertEquals("PENDING", event(id).status)
		notifications.mode = NotificationsStub.Mode.UP
		makeDue()
		relay.runOnce()
		assertEquals("SENT", event(id).status)
		assertEquals(1, notifications.accepted[id.toString()])
		assertEquals(2, notifications.requests.size)
	}

	@Test
	fun `resposta parcial - o evento sem resultado repete, os outros ficam entregues`() {
		val ids = synthetic(3)
		notifications.mode = NotificationsStub.Mode.PARTIAL
		relay.runOnce()
		assertEquals(listOf("SENT", "SENT", "PENDING"), ids.map { event(it).status })
		assertEquals("MISSING_RESULT", event(ids[2]).lastError)
		assertEquals(1, event(ids[2]).attempts)
		notifications.mode = NotificationsStub.Mode.UP
		makeDue()
		relay.runOnce()
		assertEquals("SENT", event(ids[2]).status)
		assertEquals(listOf(ids.map { it.toString() }, listOf(ids[2].toString())), notifications.requests.toList())
	}

	@Test
	fun `recusa definitiva fica FAILED sem repetir`() {
		val ids = synthetic(2)
		notifications.mode = NotificationsStub.Mode.REJECT
		relay.runOnce()
		assertTrue(ids.all { event(it).status == "FAILED" && event(it).lastError == "REJECTED/INVALID_EVENT" })
		makeDue()
		assertEquals(0, relay.runOnce())
	}

	@Test
	fun `chave recusada - recuo proprio, sem gastar tentativas, e recupera depois de corrigida`(output: CapturedOutput) {
		val (id) = synthetic(1)
		notifications.mode = NotificationsStub.Mode.FORBIDDEN
		relay.runOnce()
		var e = event(id)
		assertEquals(0, e.attempts)
		assertEquals("CONFIG_HTTP_403", e.lastError)
		assertTrue(e.nextAttemptAt > Instant.now().plusSeconds(3) && e.nextAttemptAt < Instant.now().plusSeconds(6))
		assertEquals(0, relay.runOnce(), "não insiste antes do recuo")
		makeDue()
		relay.runOnce()
		e = event(id)
		assertEquals(0, e.attempts)
		assertTrue(e.nextAttemptAt > Instant.now().plusSeconds(8), "o recuo duplica")
		assertEquals(2, relay.configFailures.get())
		// Falhas de configuração seguidas nunca esgotam as tentativas do evento.
		repeat(25) { makeDue(); relay.runOnce() }
		assertEquals("PENDING", event(id).status)
		assertEquals(0, event(id).attempts)
		// 1 + 1 (o ciclo antes do recuo não pede nada) + 25.
		assertEquals(27, notifications.requests.size)

		notifications.mode = NotificationsStub.Mode.UP
		makeDue()
		relay.runOnce()
		assertEquals("SENT", event(id).status)
		assertEquals(0, relay.configFailures.get())
		assertFalse(output.all.contains(NotificationsStub.KEY), "a chave nunca aparece nos registos")
		assertTrue(output.all.contains("Falha de configuração na entrega de notificações (HTTP_403)"))
	}

	@Test
	fun `sem chave configurada e erro de configuracao sem pedido`() {
		val client = NotificationsClient(NotificationsProperties(notifications.url, Duration.ofMillis(500), Duration.ofSeconds(1), ""), mapper)
		val delivery = client.deliver(listOf(OutboxRow(UUID.randomUUID(), "{}", Instant.now(), 0)))
		assertEquals(Delivery.ConfigError("KEY_NOT_CONFIGURED"), delivery)
		assertTrue(notifications.requests.isEmpty())
	}

	@Test
	fun `falha persistente por tentativas ou por idade`() {
		val (byAttempts, byAge, young) = synthetic(3)
		jdbc.sql("UPDATE outbox SET attempts = 19 WHERE event_id = :id").param("id", byAttempts).update()
		jdbc.sql("UPDATE outbox SET created_at = now() - interval '25 hours' WHERE event_id = :id").param("id", byAge).update()
		notifications.mode = NotificationsStub.Mode.DOWN
		relay.runOnce()
		assertEquals("FAILED" to "MAX_ATTEMPTS", event(byAttempts).let { it.status to it.lastError })
		assertEquals("FAILED" to "MAX_AGE", event(byAge).let { it.status to it.lastError })
		assertEquals("PENDING", event(young).status)
	}

	@Test
	fun `lotes de 100 pela ordem de criacao`() {
		val ids = synthetic(150).map { it.toString() }
		assertEquals(100, relay.runOnce())
		assertEquals(50, relay.runOnce())
		assertEquals(0, relay.runOnce())
		assertEquals(listOf(ids.take(100), ids.drop(100)), notifications.requests.toList())
	}

	@Test
	fun `dois envios ao mesmo tempo nunca entregam o mesmo evento duas vezes`() {
		val ids = synthetic(150)
		notifications.delayMillis = 300
		val handled = concurrently(2) { relay.runOnce() }
		assertEquals(150, handled.sum(), handled.toString())
		val sentIds = notifications.requests.flatten()
		assertEquals(sentIds.size, sentIds.toSet().size, "nenhum evento em dois pedidos")
		assertTrue(ids.all { notifications.accepted[it.toString()] == 1 && event(it).status == "SENT" })
	}

	@Test
	fun `limpeza dos entregues e falhados antigos`() {
		val (oldSent, newSent, oldFailed, newFailed, pending) = synthetic(5)
		jdbc.sql("UPDATE outbox SET status = 'SENT', sent_at = now() WHERE event_id IN (:ids)").param("ids", listOf(oldSent, newSent)).update()
		jdbc.sql("UPDATE outbox SET status = 'FAILED' WHERE event_id IN (:ids)").param("ids", listOf(oldFailed, newFailed)).update()
		jdbc.sql("UPDATE outbox SET created_at = now() - interval '4 days' WHERE event_id IN (:ids)").param("ids", listOf(oldSent, newFailed, pending)).update()
		jdbc.sql("UPDATE outbox SET created_at = now() - interval '31 days' WHERE event_id = :id").param("id", oldFailed).update()
		relay.cleanup()
		val left = jdbc.sql("SELECT event_id FROM outbox").query(UUID::class.java).set()
		assertEquals(setOf(newSent, newFailed, pending), left)
	}

	@Test
	fun `resumo observavel sem conteudo`(output: CapturedOutput) {
		val ana = person()
		val c = conversation(ana, person())
		sent(ana, c, "conteudo-secreto-123")
		synthetic(1).also { jdbc.sql("UPDATE outbox SET status = 'FAILED' WHERE event_id = :id").param("id", it.single()).update() }
		val summary = outbox.summary(Instant.now())
		assertEquals(1, summary.pending)
		assertEquals(1, summary.failed)
		relay.logSummary()
		assertTrue(output.all.contains("Outbox: 1 pendentes"))
		assertFalse(output.all.contains("conteudo-secreto-123") || output.all.contains(ana.username))
		jdbc.sql("DELETE FROM outbox").update()
		assertNull(outbox.summary(Instant.now()).oldestPendingSeconds)
	}
}
