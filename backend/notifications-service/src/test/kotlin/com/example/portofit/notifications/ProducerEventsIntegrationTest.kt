package com.example.portofit.notifications

import com.example.portofit.notifications.internal.FanOutProcessor
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Segunda parte (docs/notifications-api.md §3, §6.3, §8): `COMMENT_CREATED` e `USER_FOLLOWED` com destinatário
 * indicado pelo produtor, e `EXPERIENCE_PUBLISHED` resolvido por páginas de seguidores num falso users-service (a
 * resolução é chamada pelos próprios testes).
 */
@ExtendWith(OutputCaptureExtension::class)
class ProducerEventsIntegrationTest : ApiIntegrationTest() {

	@Autowired lateinit var fanOut: FanOutProcessor

	data class Job(val status: String, val cursor: String?, val recipients: Int, val attempts: Int, val lastError: String?, val nextAttemptAt: Instant)

	/** Cada teste começa sem trabalhos de outros testes (a base de testes é partilhada). */
	@BeforeEach
	fun clean() {
		jdbc.sql("DELETE FROM fan_out_job").update()
		users.reset()
		fanOut.configFailures.set(0)
	}

	private fun job(eventId: UUID): Job = jdbc.sql("SELECT status, cursor, recipients, attempts, last_error, next_attempt_at FROM fan_out_job WHERE event_id = :id")
		.param("id", eventId)
		.query { rs, _ ->
			Job(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getString(5), rs.getTimestamp(6).toInstant())
		}.single()

	/** O `now()` da base pode ir uns milissegundos à frente do relógio da JVM usado pela resolução. */
	private fun makeDue() {
		jdbc.sql("UPDATE fan_out_job SET next_attempt_at = now() - interval '1 minute' WHERE status = 'PENDING'").update()
	}

	private fun publish(author: Person, followers: List<Person>?, occurredAt: Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)): Pair<UUID, UUID> {
		if (followers != null) users.register(author.id, followers.map { it.id })
		val eventId = UUID.randomUUID()
		val experienceId = UUID.randomUUID()
		assertEquals(listOf("ACCEPTED"), statuses(deliver(listOf(publishedEvent(author, experienceId, occurredAt, eventId)), EXPERIENCES_KEY)))
		makeDue()
		return eventId to experienceId
	}

	private fun published(who: Person): List<String> {
		val items = list(who)["items"]
		return (0 until items.size()).map { items[it] }.filter { it["type"].asString() == "EXPERIENCE_PUBLISHED" }.map { it["experienceId"].asString() }
	}

	// --- Comentários e seguidores: destinatário indicado pelo produtor ---

	@Test
	fun `comentario e novo seguidor chegam so ao destinatario, sem texto nem a propria acao`() {
		val ana = person()
		val rui = person()
		val experienceId = UUID.randomUUID()
		assertEquals(listOf("ACCEPTED"), statuses(deliver(listOf(commentEvent(ana, rui, experienceId)), EXPERIENCES_KEY)))
		val comment = list(ana)["items"][0]
		assertEquals("COMMENT_CREATED", comment["type"].asString())
		assertEquals(rui.username, comment["actor"]["username"].asString())
		assertEquals(experienceId.toString(), comment["experienceId"].asString())
		assertTrue(comment["commentId"] != null && comment["conversationId"] == null)
		assertEquals(0, list(rui)["items"].size())
		assertEquals(
			listOf("REJECTED/INVALID_EVENT", "REJECTED/SELF_EVENT", "REJECTED/INVALID_EVENT", "REJECTED/INVALID_EVENT"),
			statuses(
				deliver(
					listOf(
						commentEvent(ana, rui, extra = mapOf("text" to "segredo")),
						commentEvent(ana, ana),
						publishedEvent(rui, extra = mapOf("text" to "segredo")),
						publishedEvent(rui, extra = mapOf("recipientId" to ana.id.toString())),
					),
					EXPERIENCES_KEY,
				),
			),
		)
		assertEquals(listOf("ACCEPTED"), statuses(deliver(listOf(followEvent(ana, rui)), USERS_KEY)))
		val follow = list(ana)["items"][0]
		assertEquals("USER_FOLLOWED", follow["type"].asString())
		assertEquals(setOf("id", "seq", "type", "actor", "occurredAt", "createdAt", "read"), follow.propertyNames().toSet())
		assertEquals(listOf("REJECTED/SELF_EVENT"), statuses(deliver(listOf(followEvent(ana, ana)), USERS_KEY)))
		assertFalse(list(ana).toString().contains("segredo"))
	}

	@Test
	fun `seguir de novo dentro de 24 h nao gera outra notificacao, tambem em simultaneo`() {
		val ana = person()
		val rui = person()
		assertEquals(listOf("ACCEPTED"), statuses(deliver(listOf(followEvent(ana, rui)), USERS_KEY)))
		// Deixou de seguir e voltou a seguir: evento novo, mas o mesmo par dentro da janela.
		assertEquals(listOf("DUPLICATE"), statuses(deliver(listOf(followEvent(ana, rui)), USERS_KEY)))
		assertEquals(1, list(ana)["items"].size())
		// Fora da janela: notificação nova.
		val bea = person()
		val old = Instant.now().minus(Duration.ofHours(25))
		assertEquals(listOf("ACCEPTED", "ACCEPTED"), statuses(deliver(listOf(followEvent(bea, rui, old), followEvent(bea, rui)), USERS_KEY)))
		assertEquals(2, list(bea)["items"].size())
		// Seis eventos diferentes do mesmo par ao mesmo tempo: uma notificação.
		val dina = person()
		val results = concurrently(6) { deliver(listOf(followEvent(dina, rui)), USERS_KEY) }.flatMap { statuses(it) }
		assertEquals(1, results.count { it == "ACCEPTED" }, results.toString())
		assertEquals(1, list(dina)["items"].size())
	}

	// --- Novas experiências: resolução dos seguidores por páginas ---

	@Test
	fun `nova experiencia notifica quem segue o autor, por paginas, exceto o autor`() {
		val ana = person()
		val followers = (1..7).map { person() }
		val stranger = person()
		val occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS)
		// O users-service nunca devolve o próprio autor; mesmo assim, o serviço filtra-o.
		val (eventId, experienceId) = publish(ana, listOf(followers[0], ana) + followers.drop(1), occurredAt)
		assertEquals("PENDING", job(eventId).status)
		assertTrue(followers.all { published(it).isEmpty() }, "a resolução é feita depois de aceitar")
		val again = publishedEvent(ana, experienceId, occurredAt, eventId)
		assertEquals(listOf("DUPLICATE"), statuses(deliver(listOf(again), EXPERIENCES_KEY)))

		assertEquals(3, fanOut.runOnce())
		assertEquals(Job("DONE", "i6", 7, 0, null, job(eventId).nextAttemptAt), job(eventId))
		assertTrue(followers.all { published(it) == listOf(experienceId.toString()) })
		assertEquals(0, list(ana)["items"].size())
		assertEquals(0, list(stranger)["items"].size())
		// Pedidos: só ids, com a chave própria, followedBefore = occurredAt, páginas de FAN_OUT_PAGE.
		assertEquals(listOf(null, "i3", "i6"), users.requests.map { it.cursor })
		assertTrue(users.requests.all { it.authorId == ana.id.toString() && it.followedBefore == occurredAt.toString() && it.limit == FAN_OUT_PAGE && it.key == FollowersStub.KEY })
		makeDue()
		assertEquals(0, fanOut.runOnce())
	}

	@Test
	fun `resolucao interrompida retoma do ultimo cursor sem duplicar destinatarios`() {
		val ana = person()
		val followers = (1..7).map { person() }
		users.failOnCursor = "i3"
		val (eventId, experienceId) = publish(ana, followers)
		fanOut.runOnce()
		var state = job(eventId)
		assertEquals(listOf("PENDING", "i3", "3", "1", "HTTP_503"), listOf(state.status, state.cursor, "${state.recipients}", "${state.attempts}", state.lastError))
		assertTrue(state.nextAttemptAt > Instant.now().plusSeconds(8), "recuo de 10 s")
		assertEquals(3, followers.count { published(it).isNotEmpty() })

		users.failOnCursor = null
		makeDue()
		fanOut.runOnce()
		state = job(eventId)
		assertEquals("DONE" to 7, state.status to state.recipients)
		assertTrue(followers.all { published(it) == listOf(experienceId.toString()) })

		// Paragem depois de criar notificações mas antes de guardar o cursor: repetir as páginas não duplica nada.
		val seqs = followers.map { list(it)["lastSeq"].asLong() }
		jdbc.sql("UPDATE fan_out_job SET status = 'PENDING', cursor = NULL, recipients = 0 WHERE event_id = :id").param("id", eventId).update()
		makeDue()
		fanOut.runOnce()
		assertEquals("DONE", job(eventId).status)
		assertTrue(followers.all { published(it) == listOf(experienceId.toString()) })
		assertEquals(seqs, followers.map { list(it)["lastSeq"].asLong() }, "nenhum seq gasto")
	}

	@Test
	fun `duas resolucoes ao mesmo tempo nunca notificam a mesma pessoa duas vezes`() {
		val authors = (1..2).map { person() }
		val followers = (1..9).map { person() }
		users.delayMillis = 150
		val events = authors.map { publish(it, followers) }
		val pages = concurrently(3) { fanOut.runOnce() }
		assertEquals(6, pages.sum(), pages.toString()) // 3 páginas por publicação
		assertTrue(events.all { (id, _) -> job(id).status == "DONE" })
		assertTrue(followers.all { f -> published(f).sorted() == events.map { it.second.toString() }.sorted() })
	}

	@Test
	fun `falhas da consulta nunca contam como sem seguidores`() {
		val ana = person()
		val follower = person()
		val (eventId) = publish(ana, listOf(follower))
		users.mode = FollowersStub.Mode.DOWN
		fanOut.processNextPage()
		assertEquals(listOf("PENDING", "1", "HTTP_503"), job(eventId).let { listOf(it.status, "${it.attempts}", it.lastError) })
		users.mode = FollowersStub.Mode.INVALID_BODY
		makeDue()
		fanOut.processNextPage()
		var state = job(eventId)
		assertEquals(listOf("PENDING", "2", "INVALID_RESPONSE"), listOf(state.status, "${state.attempts}", state.lastError))
		assertTrue(state.nextAttemptAt > Instant.now().plusSeconds(17), "recuo de 20 s")
		assertTrue(published(follower).isEmpty())
		// Ao fim de 48 h sem conseguir: FAILED com o motivo, nunca DONE.
		jdbc.sql("UPDATE fan_out_job SET created_at = now() - interval '49 hours' WHERE event_id = :id").param("id", eventId).update()
		users.mode = FollowersStub.Mode.DOWN
		makeDue()
		fanOut.processNextPage()
		state = job(eventId)
		assertEquals("FAILED" to "MAX_AGE/HTTP_503", state.status to state.lastError)

		// Autor inexistente (404): DONE sem notificações, com o motivo. Sem seguidores (200 vazio): DONE com 0.
		users.mode = FollowersStub.Mode.UP
		val (unknown) = publish(person(), null)
		fanOut.processNextPage()
		assertEquals("DONE" to "AUTHOR_NOT_FOUND", job(unknown).let { it.status to it.lastError })
		val (lonely) = publish(person(), emptyList())
		fanOut.processNextPage()
		assertEquals("DONE" to 0, job(lonely).let { it.status to it.recipients })
	}

	@Test
	fun `chave recusada adia sem gastar tentativas e recupera`(output: CapturedOutput) {
		val ana = person()
		val follower = person()
		val (eventId) = publish(ana, listOf(follower))
		users.mode = FollowersStub.Mode.FORBIDDEN
		fanOut.processNextPage()
		makeDue()
		fanOut.processNextPage()
		val state = job(eventId)
		assertEquals(listOf("PENDING", "0", "CONFIG_HTTP_403"), listOf(state.status, "${state.attempts}", state.lastError))
		assertTrue(state.nextAttemptAt > Instant.now().plusSeconds(17), "o recuo de configuração duplica")
		assertEquals(2, fanOut.configFailures.get())
		users.mode = FollowersStub.Mode.UP
		makeDue()
		fanOut.processNextPage()
		assertEquals("DONE", job(eventId).status)
		assertEquals(0, fanOut.configFailures.get())
		assertTrue(output.all.contains("Falha de configuração na consulta de seguidores (HTTP_403)"))
		assertFalse(output.all.contains(FollowersStub.KEY) || output.all.contains(ana.username))
	}

	@Test
	fun `limite de destinatarios termina em TRUNCATED, nunca em silencio`(output: CapturedOutput) {
		val ana = person()
		val followers = (1..MAX_FAN_OUT + 2).map { person() }
		val (eventId) = publish(ana, followers)
		fanOut.runOnce()
		assertEquals("TRUNCATED" to MAX_FAN_OUT, job(eventId).let { it.status to it.recipients })
		assertEquals(MAX_FAN_OUT, followers.count { published(it).isNotEmpty() })
		assertTrue(output.all.contains("TRUNCATED"))
	}
}
