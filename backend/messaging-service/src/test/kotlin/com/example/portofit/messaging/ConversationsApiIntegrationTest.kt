package com.example.portofit.messaging

import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Conversas: autenticação, abrir por par, privacidade, users-service em falha, caixa e leitura (docs/messaging-api.md §3–§5, §8). */
class ConversationsApiIntegrationTest : ApiIntegrationTest() {

	@Test
	fun `autenticacao em todos os endpoints`() {
		val ana = person()
		val id = conversation(ana, person())
		val paths = listOf(
			"GET" to "/api/v1/conversations",
			"POST" to "/api/v1/conversations",
			"GET" to "/api/v1/conversations/$id",
			"GET" to "/api/v1/conversations/$id/messages",
			"POST" to "/api/v1/conversations/$id/messages",
			"GET" to "/api/v1/conversations/$id/shares?seqs=1",
			"PUT" to "/api/v1/conversations/$id/read",
		)
		val forged = sign(ana.id, ana.username, keyPair = TestKeys.generate())
		val otherAudience = sign(ana.id, ana.username, audience = "outro")
		val expired = sign(ana.id, ana.username, expiresAt = Instant.now().minusSeconds(3600))
		for ((method, path) in paths) {
			val body = if (method == "GET") null else "{}"
			assertEquals("AUTHENTICATION_REQUIRED", call(method, path, body = body).also { assertEquals(401, it.status) }.code, path)
			for (token in listOf(forged, otherAudience, expired)) {
				assertEquals("INVALID_ACCESS_TOKEN", call(method, path, token, body).also { assertEquals(401, it.status) }.code, path)
			}
		}
		assertEquals(200, call("GET", "/actuator/health").status)
		// Sem a claim username o token não serve para criar a cópia de apresentação.
		val noUsername = sign(ana.id, username = null)
		assertEquals(401, call("POST", "/api/v1/conversations", noUsername, """{"participantId":"${person().id}"}""").status)
	}

	@Test
	fun `abrir conversa - uma por par, nunca consigo proprio, destinatario validado so ao criar`() {
		val ana = person()
		val bruno = person()
		val self = open(ana, ana)
		assertEquals(400, self.status)
		assertEquals("CANNOT_MESSAGE_SELF", self.code)
		val nobody = call("POST", "/api/v1/conversations", ana.token, """{"participantId":"${UUID.randomUUID()}"}""")
		assertEquals(404, nobody.status)
		assertEquals("USER_NOT_FOUND", nobody.code)
		for (body in listOf("""{}""", """{"participantId":"nao-e-uuid"}""", """{"participantId":"${bruno.id}","senderId":"${ana.id}"}""", "[]")) {
			assertEquals("VALIDATION_FAILED", call("POST", "/api/v1/conversations", ana.token, body).also { assertEquals(400, it.status, body) }.code)
		}

		val before = users.requests.size
		val created = open(ana, bruno)
		assertEquals(201, created.status, created.body)
		val id = created.json["id"].asString()
		assertEquals("/api/v1/conversations/$id", created.header("Location"))
		assertEquals(bruno.id.toString(), created.json["otherParticipant"]["userId"].asString())
		assertEquals(bruno.username, created.json["otherParticipant"]["username"].asString())
		assertTrue(created.json["lastMessage"].isNull)
		assertEquals(0, created.json["lastSeq"].asInt())
		assertEquals(before + 1, users.requests.size)
		assertFalse(users.requests.last().second, "o perfil público é pedido sem token")

		// A mesma conversa, dos dois lados, sem voltar ao users-service.
		val again = open(ana, bruno)
		assertEquals(200, again.status)
		assertEquals(id, again.json["id"].asString())
		val other = open(bruno, ana)
		assertEquals(200, other.status)
		assertEquals(id, other.json["id"].asString())
		assertEquals(ana.username, other.json["otherParticipant"]["username"].asString())
		assertEquals(before + 1, users.requests.size)
		assertEquals(ana.username, ok("/api/v1/conversations/$id", bruno)["otherParticipant"]["username"].asString())
	}

	@Test
	fun `pedidos simultaneos nos dois sentidos criam uma so conversa`() {
		val ana = person()
		val bruno = person()
		val results = concurrently(8) { i -> if (i % 2 == 0) open(ana, bruno) else open(bruno, ana) }
		val statuses = results.map { it.status }
		assertEquals(1, statuses.count { it == 201 }, statuses.toString())
		assertEquals(7, statuses.count { it == 200 }, statuses.toString())
		assertEquals(1, results.map { it.json["id"].asString() }.distinct().size)
		val id = UUID.fromString(results.first().json["id"].asString())
		assertEquals(1, count("SELECT count(*) FROM conversation WHERE (user_low = :a AND user_high = :b) OR (user_low = :b AND user_high = :a)", "a" to ana.id, "b" to bruno.id))
		assertEquals(2, count("SELECT count(*) FROM conversation_member WHERE conversation_id = :c", "c" to id))
	}

	@Test
	fun `users-service em falha - conversas novas falham, as existentes continuam`() {
		val ana = person()
		val bruno = person()
		val id = conversation(ana, bruno)
		sent(bruno, id, "Olá!")
		val carla = person()
		for (mode in listOf(UsersStub.Mode.ERROR, UsersStub.Mode.SLOW, UsersStub.Mode.INVALID_BODY)) {
			users.mode = mode
			val result = open(ana, carla)
			assertEquals(503, result.status, mode.name)
			assertEquals("USERS_UNAVAILABLE", result.code)
		}
		assertEquals(0, count("SELECT count(*) FROM conversation_member WHERE user_id = :c", "c" to carla.id), "nada criado durante a falha")

		users.mode = UsersStub.Mode.ERROR
		val requests = users.requests.size
		assertEquals(200, open(ana, bruno).status)
		assertEquals(201, send(ana, id, "Continua a funcionar").status)
		assertEquals(id, ok("/api/v1/conversations", ana)["items"][0]["id"].asString())
		assertEquals(listOf(1L, 2L), messages(bruno, id).seqs())
		assertEquals(200, read(bruno, id, 2).status)
		assertEquals(requests, users.requests.size, "conversas existentes não chamam o users-service")

		// Recuperação: a conversa nova é criada e as falhas não gastaram o limite.
		users.mode = UsersStub.Mode.UP
		assertEquals(201, open(ana, carla).status)
	}

	@Test
	fun `conversas de outras pessoas respondem como inexistentes`() {
		val ana = person()
		val bruno = person()
		val carla = person()
		val id = conversation(ana, bruno)
		val seq = sent(ana, id, "Só para o Bruno")
		val missing = UUID.randomUUID().toString()

		fun attempts(target: String) = listOf(
			call("GET", "/api/v1/conversations/$target", carla.token),
			call("GET", "/api/v1/conversations/$target/messages", carla.token),
			call("GET", "/api/v1/conversations/$target/messages?after=0", carla.token),
			call("GET", "/api/v1/conversations/$target/shares?seqs=$seq", carla.token),
			send(carla, target, "Intrusa"),
			read(carla, target, 1),
		)
		val forOther = attempts(id)
		val forMissing = attempts(missing)
		for ((a, b) in forOther.zip(forMissing)) {
			assertEquals(404, a.status, a.body)
			assertEquals("NOT_FOUND", a.code)
			// O mesmo corpo, tirando o caminho pedido (instance).
			assertEquals(withoutInstance(b), withoutInstance(a))
			assertFalse(a.body.contains("Só para o Bruno") || a.body.contains(ana.username) || a.body.contains(bruno.username))
		}
		assertEquals(0, ok("/api/v1/conversations", carla)["items"].size())
		assertEquals(1, count("SELECT count(*) FROM message WHERE conversation_id = :c", "c" to UUID.fromString(id)), "nada enviado pela intrusa")
		assertEquals(0L, ok("/api/v1/conversations/$id", bruno)["lastReadSeq"].asLong(), "a leitura da intrusa não mudou nada")

		// Uma conversa sem mensagens não aparece na caixa de nenhum dos dois, mas abre-se pelo id.
		val dora = person()
		val empty = conversation(ana, dora)
		assertTrue(ok("/api/v1/conversations", ana)["items"].elements.none { it["id"].asString() == empty })
		assertEquals(0, ok("/api/v1/conversations", dora)["items"].size())
		assertEquals(200, get("/api/v1/conversations/$empty", dora).status)
	}

	private fun withoutInstance(resp: Resp) = (resp.json.deepCopy() as tools.jackson.databind.node.ObjectNode).apply { remove("instance") }

	@Test
	fun `caixa - mais recente primeiro, cursor estavel, pre-visualizacao`() {
		val ana = person()
		val others = (1..3).map { person() }
		val ids = others.map { conversation(ana, it) }
		sent(others[0], ids[0], "Primeira")
		val share = experiences.available()
		assertEquals(201, send(others[1], ids[1], experienceId = share).status)
		val long = "x".repeat(150)
		sent(others[2], ids[2], long)

		val first = ok("/api/v1/conversations?limit=2", ana)
		assertEquals(listOf(ids[2], ids[1]), first["items"].elements.map { it["id"].asString() })
		val preview = first["items"][0]["lastMessage"]
		assertEquals("TEXT", preview["kind"].asString())
		assertEquals("x".repeat(100), preview["textPreview"].asString())
		assertEquals(others[2].id.toString(), preview["senderId"].asString())
		assertEquals("SHARE", first["items"][1]["lastMessage"]["kind"].asString())
		assertTrue(first["items"][1]["lastMessage"]["textPreview"].isNull)
		assertTrue(first["items"][1].toString().contains("experience").not(), "a caixa não traz conteúdo de experiências")
		val cursor = first["nextCursor"].asString()
		val second = ok("/api/v1/conversations?limit=2&cursor=$cursor", ana)
		assertEquals(listOf(ids[0]), second["items"].elements.map { it["id"].asString() })
		assertTrue(second["nextCursor"].isNull)

		// Uma mensagem nova sobe a conversa; a página seguinte do cursor antigo não a repete.
		sent(ana, ids[0], "Resposta")
		assertEquals(ids[0], ok("/api/v1/conversations?limit=2", ana)["items"][0]["id"].asString())
		assertEquals(0, ok("/api/v1/conversations?limit=2&cursor=$cursor", ana)["items"].size())

		for (query in listOf("cursor=nao-e-cursor", "limit=0", "limit=51", "limit=abc")) {
			assertEquals("VALIDATION_FAILED", get("/api/v1/conversations?$query", ana).also { assertEquals(400, it.status, query) }.code)
		}
	}

	@Test
	fun `mensagens por ler e marcar como lidas - monotonico, limitado ao fim, so as recebidas`() {
		val ana = person()
		val bruno = person()
		val id = conversation(ana, bruno)
		repeat(3) { sent(bruno, id, "Mensagem ${it + 1}") }
		assertEquals(3, ok("/api/v1/conversations/$id", ana)["unreadCount"].asInt())
		sent(ana, id, "Resposta")
		val forAna = ok("/api/v1/conversations/$id", ana)
		assertEquals(3, forAna["unreadCount"].asInt(), "enviar não marca as recebidas como lidas")
		assertEquals(0L, forAna["lastReadSeq"].asLong())
		assertEquals(1, ok("/api/v1/conversations/$id", bruno)["unreadCount"].asInt())

		val marked = read(ana, id, 2)
		assertEquals(200, marked.status)
		assertEquals(2L, marked.json["lastReadSeq"].asLong())
		assertEquals(1, marked.json["unreadCount"].asInt())
		assertEquals(2L, read(ana, id, 1).json["lastReadSeq"].asLong(), "não recua")
		assertEquals(2L, read(ana, id, 2).json["lastReadSeq"].asLong(), "repetir é inofensivo")
		val end = read(ana, id, 999)
		assertEquals(4L, end.json["lastReadSeq"].asLong(), "nunca passa da última mensagem")
		assertEquals(0, end.json["unreadCount"].asInt())
		val inbox = ok("/api/v1/conversations", ana)["items"][0]
		assertEquals(4L, inbox["lastReadSeq"].asLong())
		assertEquals(0, inbox["unreadCount"].asInt())
		// O cursor da Ana não é devolvido ao Bruno.
		assertEquals(0L, ok("/api/v1/conversations/$id", bruno)["lastReadSeq"].asLong())

		for (body in listOf("""{"lastReadSeq":-1}""", """{"lastReadSeq":"3"}""", """{"lastReadSeq":1.5}""", "{}", """{"lastReadSeq":1,"userId":"x"}""")) {
			val result = call("PUT", "/api/v1/conversations/$id/read", ana.token, body)
			assertEquals(400, result.status, body)
		}

		// Várias instâncias da app: pedidos simultâneos com valores diferentes ficam com o maior.
		val carla = person()
		val other = conversation(carla, bruno)
		repeat(6) { sent(bruno, other, "Nova ${it + 1}") }
		val values = listOf(3L, 6L, 1L, 5L, 2L, 4L, 6L, 0L)
		val results = concurrently(values.size) { read(carla, other, values[it]) }
		assertTrue(results.all { it.status == 200 })
		assertEquals(6L, ok("/api/v1/conversations/$other", carla)["lastReadSeq"].asLong())
		assertEquals(0, ok("/api/v1/conversations/$other", carla)["unreadCount"].asInt())
	}

	@Test
	fun `limite de conversas novas por janela, sem contar as existentes`() {
		val ana = person()
		val first = person()
		assertEquals(201, open(ana, first).status)
		repeat(MAX_NEW_CONVERSATIONS - 1) { assertEquals(201, open(ana, person()).status) }
		val blocked = open(ana, person())
		assertEquals(429, blocked.status)
		assertEquals("TOO_MANY_REQUESTS", blocked.code)
		assertNotNull(blocked.header("Retry-After")?.toLongOrNull()?.takeIf { it > 0 })
		assertEquals(200, open(ana, first).status, "abrir uma conversa existente não conta")
		// A outra pessoa pode abrir com a Ana (o limite é de quem cria).
		assertNull(open(person(), ana).code)
	}
}
