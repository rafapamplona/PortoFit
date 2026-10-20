package com.example.portofit.messaging

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Mensagens: conteúdo, envio idempotente, cursores, partilhas e limites (docs/messaging-api.md §6, §7, §10). */
@ExtendWith(OutputCaptureExtension::class)
class MessagesApiIntegrationTest : ApiIntegrationTest() {

	@Test
	fun `conteudo validado e normalizado`() {
		val ana = person()
		val id = conversation(ana, person())
		val path = "/api/v1/conversations/$id/messages"
		val invalid = listOf(
			"{}", """{"text":""}""", """{"text":"   \n  "}""", """{"text":"${"a".repeat(MAX_TEXT + 1)}"}""", """{"text":"com\u0007zqxcontrolo"}""",
			"""{"text":"solto\rzqxcarriage"}""", """{"text":123}""", """{"text":"Olá","senderId":"${ana.id}"}""", """{"experienceId":"nao-e-uuid"}""", "[]", "nada",
		)
		for (body in invalid) {
			val result = call("POST", path, ana.token, body, UUID.randomUUID().toString())
			assertEquals(400, result.status, body)
			assertEquals("VALIDATION_FAILED", result.code)
			assertFalse(result.body.contains("zqx"), "o texto nunca é repetido na resposta")
		}
		val noKey = call("POST", path, ana.token, """{"text":"Olá"}""")
		assertEquals(400, noKey.status)
		assertTrue(noKey.json["errors"].elements.any { it["field"].asString() == "Idempotency-Key" })
		assertEquals(415, call("POST", path, ana.token, "texto", UUID.randomUUID().toString(), contentType = "text/plain").status)
		assertEquals(413, call("POST", path, ana.token, """{"text":"${"a".repeat(9000)}"}""", UUID.randomUUID().toString()).status)

		val emojis = "😀".repeat(MAX_TEXT)
		assertEquals(emojis, send(ana, id, emojis).also { assertEquals(201, it.status, it.body) }.json["text"].asString())
		val normalized = send(ana, id, "  Linha 1\r\nLinha 2\t fim \n")
		assertEquals("Linha 1\nLinha 2\t fim", normalized.json["text"].asString())
		assertEquals(0, count("SELECT count(*) FROM message WHERE text LIKE '%' || chr(13) || '%'"))
	}

	@Test
	fun `envio idempotente - repeticao, conteudo diferente, concorrencia`() {
		val ana = person()
		val bruno = person()
		val id = conversation(ana, bruno)
		val key = UUID.randomUUID().toString()
		val first = send(ana, id, "Vamos treinar?", key = key)
		assertEquals(201, first.status, first.body)
		assertEquals("/api/v1/conversations/$id/messages/${first.json["id"].asString()}", first.header("Location"))
		assertEquals(key, first.json["clientKey"].asString())
		assertEquals(ana.id.toString(), first.json["senderId"].asString())
		val again = send(ana, id, "  Vamos treinar?  ", key = key)
		assertEquals(200, again.status)
		assertEquals("true", again.header("Idempotent-Replayed"))
		assertEquals(first.json["id"], again.json["id"])
		assertEquals(first.json["seq"], again.json["seq"])

		val different = send(ana, id, "Outro texto", key = key)
		assertEquals(409, different.status)
		assertEquals("DIFFERENT_CONTENT", different.json["reason"].asString())
		assertEquals(first.json["id"].asString(), different.json["messageId"].asString())
		assertEquals(id, different.json["conversationId"].asString())
		val elsewhere = send(ana, conversation(ana, person()), "Vamos treinar?", key = key)
		assertEquals(409, elsewhere.status, "a mesma chave noutra conversa é conteúdo diferente")
		// Para o Bruno, a mensagem da Ana não traz a chave dela.
		assertTrue(messages(bruno, id)["items"][0]["clientKey"].isNull)
		assertEquals(1, count("SELECT count(*) FROM message WHERE sender_id = :s", "s" to ana.id))

		// 8 pedidos simultâneos com a mesma chave: uma mensagem.
		val same = UUID.randomUUID().toString()
		val statuses = concurrently(8) { send(bruno, id, "Ao mesmo tempo", key = same).status }
		assertEquals(1, statuses.count { it == 201 }, statuses.toString())
		assertEquals(7, statuses.count { it == 200 }, statuses.toString())
		assertEquals(1, count("SELECT count(*) FROM message WHERE sender_id = :s AND idempotency_key = :k", "s" to bruno.id, "k" to UUID.fromString(same)))

		// A mesma chave, ao mesmo tempo, em duas conversas: uma mensagem e um 409.
		val carla = person()
		val c1 = conversation(carla, person())
		val c2 = conversation(carla, person())
		val shared = UUID.randomUUID().toString()
		val pair = concurrently(2) { i -> send(carla, if (i == 0) c1 else c2, "Duas conversas", key = shared).status }
		assertEquals(listOf(201, 409), pair.sorted())
		assertEquals(1, count("SELECT count(*) FROM message WHERE sender_id = :s", "s" to carla.id))
	}

	@Test
	fun `cursores - ordem crescente, historico e novas sem saltos nem duplicados`() {
		val ana = person()
		val bruno = person()
		val id = conversation(ana, bruno)
		(1..7).forEach { sent(if (it % 2 == 0) ana else bruno, id, "Mensagem $it") }

		val latest = messages(ana, id, "?limit=3")
		assertEquals(listOf(5L, 6L, 7L), latest.seqs())
		assertEquals(7L, latest["lastSeq"].asLong())
		assertTrue(latest["hasMoreBefore"].asBoolean())
		assertFalse(latest["hasMoreAfter"].asBoolean())
		assertEquals("NOT_NEEDED", latest["experiencesStatus"].asString())
		assertEquals(listOf(2L, 3L, 4L), messages(ana, id, "?before=5&limit=3").seqs())
		val oldest = messages(ana, id, "?before=2&limit=3")
		assertEquals(listOf(1L), oldest.seqs())
		assertFalse(oldest["hasMoreBefore"].asBoolean())
		assertTrue(oldest["hasMoreAfter"].asBoolean())

		// Mais novidades do que a página: avançar só até ao último item recebido.
		val seen = mutableListOf<Long>()
		var cursor = 0L
		do {
			val page = messages(bruno, id, "?after=$cursor&limit=3")
			seen += page.seqs()
			cursor = page.seqs().lastOrNull() ?: cursor
		} while (page["hasMoreAfter"].asBoolean())
		assertEquals((1L..7L).toList(), seen)
		val empty = messages(bruno, id, "?after=7")
		assertEquals(0, empty["items"].size())
		assertTrue(empty["hasMoreBefore"].asBoolean())
		assertFalse(empty["hasMoreAfter"].asBoolean())
		assertEquals(0, messages(bruno, id, "?after=99")["items"].size())

		for (query in listOf("?before=3&after=1", "?limit=51", "?limit=0", "?after=-1", "?before=abc")) {
			assertEquals("VALIDATION_FAILED", get("/api/v1/conversations/$id/messages$query", ana).also { assertEquals(400, it.status, query) }.code)
		}
	}

	@Test
	fun `envios simultaneos - seq unicos e crescentes, e a consulta incremental ve todos`() {
		val ana = person()
		val bruno = person()
		val id = conversation(ana, bruno)
		val done = AtomicBoolean(false)
		val polled = mutableListOf<Long>()
		val poller = thread {
			var cursor = 0L
			while (true) {
				val finished = done.get()
				val page = messages(ana, id, "?after=$cursor&limit=4")
				polled += page.seqs()
				cursor = page.seqs().lastOrNull() ?: cursor
				if (finished && !page["hasMoreAfter"].asBoolean()) break
			}
		}
		val statuses = concurrently(20) { i -> send(if (i % 2 == 0) ana else bruno, id, "Simultânea $i").status }
		done.set(true)
		poller.join()
		assertEquals(List(20) { 201 }, statuses)
		val stored = jdbc.sql("SELECT seq FROM message WHERE conversation_id = :c ORDER BY created_at, seq").param("c", UUID.fromString(id))
			.query(Long::class.javaObjectType).list()
		assertEquals(20, stored.distinct().size)
		assertEquals(stored.map { it!! }.sorted(), stored.map { it!! }, "seq crescente pela ordem de gravação")
		assertEquals(stored, polled, "a consulta incremental não saltou nem repetiu mensagens")
		assertEquals(stored.last(), messages(bruno, id)["lastSeq"].asLong())
	}

	@Test
	fun `partilhas - disponivel, eliminada e experiences-service indisponivel`() {
		val ana = person()
		val bruno = person()
		val id = conversation(ana, bruno)
		val experience = experiences.available()
		val shared = send(ana, id, "Olha esta", experienceId = experience)
		assertEquals(201, shared.status, shared.body)
		assertEquals("AVAILABLE", shared.json["sharedExperience"]["availability"].asString())
		assertEquals("Experiência $experience", shared.json["sharedExperience"]["experience"]["text"].asString())
		assertEquals(201, send(ana, id, experienceId = experiences.available()).status, "partilha sem texto")

		// Recusadas: eliminada ou inexistente → 404; nada enviado.
		val gone = UUID.randomUUID().also { experiences.delete(it) }
		for (target in listOf(gone, UUID.randomUUID())) {
			val result = send(ana, id, "Esta já não existe", experienceId = target)
			assertEquals(404, result.status)
			assertEquals("EXPERIENCE_NOT_FOUND", result.code)
		}
		// Indisponível → 503 temporário, sem consumir a chave; mensagens de texto continuam.
		val key = UUID.randomUUID().toString()
		val other = experiences.available()
		for (mode in listOf(ExperiencesStub.Mode.ERROR, ExperiencesStub.Mode.SLOW, ExperiencesStub.Mode.FORBIDDEN, ExperiencesStub.Mode.INVALID_BODY)) {
			experiences.mode = mode
			val result = send(ana, id, "Tenta esta", experienceId = other, key = key)
			assertEquals(503, result.status, mode.name)
			assertEquals("EXPERIENCES_UNAVAILABLE", result.code)
		}
		experiences.mode = ExperiencesStub.Mode.ERROR
		val requests = experiences.requests.size
		assertEquals(201, send(bruno, id, "Só texto, com o serviço em baixo").status)
		assertEquals(requests, experiences.requests.size, "mensagens só de texto não chamam o experiences-service")
		assertEquals(3, count("SELECT count(*) FROM message WHERE conversation_id = :c", "c" to UUID.fromString(id)))

		// Receber com o serviço em baixo: todas as mensagens, texto visível, partilhas UNKNOWN; o cursor avança.
		val down = messages(bruno, id, "?after=0")
		assertEquals(listOf(1L, 2L, 3L), down.seqs())
		assertEquals("UNAVAILABLE", down["experiencesStatus"].asString())
		assertEquals("Olha esta", down["items"][0]["text"].asString())
		assertEquals("UNKNOWN", down["items"][0]["sharedExperience"]["availability"].asString())
		assertEquals(experience.toString(), down["items"][0]["sharedExperience"]["experienceId"].asString())
		assertTrue(down["items"][0]["sharedExperience"]["experience"].isNull)
		assertTrue(down["items"][2]["sharedExperience"].isNull)
		assertEquals(3L, down["lastSeq"].asLong())
		// Repetir uma partilha com o serviço em baixo: a mesma mensagem, sem validar de novo.
		val replay = send(ana, id, "Olha esta", experienceId = experience, key = shared.json["clientKey"].asString())
		assertEquals(200, replay.status)
		assertEquals("UNKNOWN", replay.json["sharedExperience"]["availability"].asString())

		// Recupera: a chave que falhou envia agora; um pedido em lote por página, com ids distintos.
		experiences.mode = ExperiencesStub.Mode.UP
		assertEquals(201, send(ana, id, "Tenta esta", experienceId = other, key = key).status)
		assertEquals(201, send(ana, id, "Outra vez a primeira", experienceId = experience).status)
		val before = experiences.requests.size
		val up = messages(bruno, id)
		assertEquals(before + 1, experiences.requests.size)
		assertEquals(3, experiences.requests.last().size, "três experiências distintas em cinco mensagens")
		assertEquals("AVAILABLE", up["experiencesStatus"].asString())
		assertTrue(up["items"].elements.filter { it["sharedExperience"] != null && !it["sharedExperience"].isNull }.all { it["sharedExperience"]["availability"].asString() == "AVAILABLE" })

		// Eliminada depois: DELETED sem conteúdo, o texto mantém-se.
		experiences.delete(experience)
		val after = messages(bruno, id)["items"][0]
		assertEquals("DELETED", after["sharedExperience"]["availability"].asString())
		assertTrue(after["sharedExperience"]["experience"].isNull)
		assertEquals("Olha esta", after["text"].asString())

		// Atualizar partilhas sem pedir mensagens: só as partilhas desta conversa, por seq.
		val shares = ok("/api/v1/conversations/$id/shares?seqs=4,3,1,1,2,99", bruno)
		assertEquals(listOf(1L, 2L, 4L), shares.seqs())
		assertEquals(listOf("DELETED", "AVAILABLE", "AVAILABLE"), shares["items"].elements.map { it["sharedExperience"]["availability"].asString() })
		experiences.mode = ExperiencesStub.Mode.ERROR
		val sharesDown = ok("/api/v1/conversations/$id/shares?seqs=2", bruno)
		assertEquals("UNAVAILABLE", sharesDown["experiencesStatus"].asString())
		assertEquals("UNKNOWN", sharesDown["items"][0]["sharedExperience"]["availability"].asString())
		for (query in listOf("", "seqs=", "seqs=0", "seqs=abc", "seqs=" + (1..51).joinToString(","))) {
			assertEquals(400, get("/api/v1/conversations/$id/shares?$query", bruno).status, query)
		}
	}

	@Test
	fun `limites - mensagens por conversa e por janela, com pedidos simultaneos`() {
		val rui = person()
		val full = conversation(rui, person())
		repeat(MAX_PER_CONVERSATION) { sent(rui, full, "Mensagem ${it + 1}") }
		val over = send(rui, full, "Uma a mais")
		assertEquals(409, over.status)
		assertEquals("CONVERSATION_FULL", over.code)
		// A recusa não contou: faltam 15 para o limite por janela.
		val other = conversation(rui, person())
		val keys = (1..MAX_MESSAGES_PER_WINDOW - MAX_PER_CONVERSATION).map { UUID.randomUUID().toString() }
		keys.forEach { assertEquals(201, send(rui, other, "Contada", key = it).status) }
		val limited = send(rui, other, "Demasiadas")
		assertEquals(429, limited.status)
		assertEquals("TOO_MANY_REQUESTS", limited.code)
		assertNotNull(limited.header("Retry-After")?.toLongOrNull()?.takeIf { it > 0 })
		assertEquals(200, send(rui, other, "Contada", key = keys.first()).status, "repetições não contam")

		// No limite por conversa, pedidos simultâneos não o ultrapassam.
		val ana = person()
		val bruno = person()
		val busy = conversation(ana, bruno)
		repeat(MAX_PER_CONVERSATION - 5) { sent(if (it % 2 == 0) ana else bruno, busy, "Antes ${it + 1}") }
		val results = concurrently(10) { i -> send(if (i % 2 == 0) ana else bruno, busy, "No limite $i").status }
		assertEquals(5, results.count { it == 201 }, results.toString())
		assertEquals(5, results.count { it == 409 }, results.toString())
		assertEquals(MAX_PER_CONVERSATION.toLong(), count("SELECT count(*) FROM message WHERE conversation_id = :c", "c" to UUID.fromString(busy)))

		// Pedidos simultâneos de uma pessoa no limite por janela: só os que cabem.
		val dora = person()
		val target = conversation(dora, person())
		val more = conversation(dora, person())
		repeat(MAX_PER_CONVERSATION) { sent(dora, target, "Primeiras ${it + 1}") }
		val burst = concurrently(20) { send(dora, more, "Rajada $it").status }
		assertEquals(MAX_MESSAGES_PER_WINDOW - MAX_PER_CONVERSATION, burst.count { it == 201 }, burst.toString())
		assertTrue(burst.filter { it != 201 }.all { it == 429 })
	}

	@Test
	fun `registos sem texto das mensagens nem tokens`(output: CapturedOutput) {
		val ana = person()
		val bruno = person()
		val id = conversation(ana, bruno)
		val secret = "texto-secreto-${UUID.randomUUID()}"
		assertEquals(201, send(ana, id, secret).status)
		experiences.mode = ExperiencesStub.Mode.ERROR
		assertEquals(503, send(ana, id, secret, experienceId = UUID.randomUUID()).status)
		users.mode = UsersStub.Mode.ERROR
		assertEquals(503, open(ana, person()).status)
		assertEquals(400, call("POST", "/api/v1/conversations/$id/messages", ana.token, """{"text":"$secret","x":1}""", UUID.randomUUID().toString()).status)
		messages(bruno, id)
		val logs = output.all
		// A consola do Windows pode trocar os acentos: procura-se o nome dos serviços.
		assertTrue(logs.contains("experiences-service") && logs.contains("users-service"), "as falhas são registadas")
		assertFalse(logs.contains(secret), "texto da mensagem nos registos")
		assertFalse(logs.contains(ana.token) || logs.contains(bruno.token), "token nos registos")
		assertFalse(logs.contains(ExperiencesStub.KEY), "chave interna nos registos")
		assertFalse(logs.contains(ana.username) || logs.contains(bruno.username), "usernames nos registos")
	}
}
