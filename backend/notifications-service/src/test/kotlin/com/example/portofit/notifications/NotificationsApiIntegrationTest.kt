package com.example.portofit.notifications

import com.example.portofit.notifications.config.NotificationsProperties
import com.example.portofit.notifications.internal.EventIngestService
import com.example.portofit.notifications.notification.NotificationService
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.SecurityContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyPair
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * notifications-service, primeira parte (docs/notifications-api.md): PostgreSQL real (Testcontainers), tokens assinados
 * por uma chave só dos testes, pedidos HTTP reais. Cada teste usa pessoas novas.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class NotificationsApiIntegrationTest {

	@Autowired lateinit var mapper: JsonMapper
	@Autowired lateinit var jdbc: JdbcClient
	@Autowired lateinit var notifications: NotificationService
	@Autowired lateinit var ingest: EventIngestService

	@LocalServerPort var port: Int = 0

	private val client: HttpClient = HttpClient.newHttpClient()

	data class Resp(val status: Int, val body: String, val json: JsonNode?)

	data class Person(val id: UUID, val username: String, val token: String)

	private fun person(): Person {
		val id = UUID.randomUUID()
		val username = "u" + id.toString().replace("-", "").take(10)
		return Person(id, username, sign(id))
	}

	private fun sign(userId: UUID, audience: String = "portofit-api", keyPair: KeyPair = keys.keyPair, expiresAt: Instant = Instant.now().plusSeconds(900)): String {
		val rsa = RSAKey.Builder(keyPair.public as RSAPublicKey).privateKey(keyPair.private as RSAPrivateKey).keyIDFromThumbprint().build()
		val encoder = NimbusJwtEncoder(ImmutableJWKSet<SecurityContext>(JWKSet(rsa)))
		val claims = JwtClaimsSet.builder().issuer("portofit-users").audience(listOf(audience)).subject(userId.toString())
			.issuedAt(expiresAt.minusSeconds(900)).expiresAt(expiresAt).id(UUID.randomUUID().toString()).claim("username", "u").build()
		val header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(rsa.keyID).type("JWT").build()
		return encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
	}

	private fun call(method: String, path: String, token: String? = null, body: String? = null, key: String? = null): Resp {
		val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
			.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
		if (token != null) builder.header("Authorization", "Bearer $token")
		if (body != null) builder.header("Content-Type", "application/json")
		if (key != null) builder.header("X-Internal-Key", key)
		val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
		return Resp(response.statusCode(), response.body(), runCatching { mapper.readTree(response.body()) }.getOrNull())
	}

	private fun messageEvent(
		recipient: Person,
		actor: Person,
		seq: Long = 1,
		eventId: UUID = UUID.randomUUID(),
		occurredAt: Instant = Instant.now(),
		kind: String = "TEXT",
		extra: Map<String, Any> = emptyMap(),
	): Map<String, Any> = linkedMapOf(
		"eventId" to eventId.toString(), "type" to "MESSAGE_RECEIVED", "occurredAt" to occurredAt.toString(),
		"actor" to mapOf("userId" to actor.id.toString(), "username" to actor.username),
		"recipientId" to recipient.id.toString(), "conversationId" to CONVERSATION.toString(), "messageSeq" to seq, "messageKind" to kind,
	) + extra

	private fun deliver(events: List<Map<String, Any>>, key: String = MESSAGING_KEY): Resp =
		call("POST", "/api/v1/internal/events", body = mapper.writeValueAsString(mapOf("events" to events)), key = key)

	private fun statuses(resp: Resp) = (0 until resp.json!!["results"].size()).map { resp.json["results"][it] }
		.map { it["status"].asString() + (it["reason"]?.let { r -> "/" + r.asString() } ?: "") }

	private fun list(who: Person, query: String = ""): JsonNode = call("GET", "/api/v1/notifications$query", who.token).also { assertEquals(200, it.status, it.body) }.json!!

	private fun seqs(page: JsonNode) = (0 until page["items"].size()).map { page["items"][it]["seq"].asLong() }

	private fun count(sql: String, vararg params: Pair<String, Any>): Long =
		params.fold(jdbc.sql(sql)) { spec, (k, v) -> spec.param(k, v) }.query(Long::class.javaObjectType).single()

	private fun <T> concurrently(n: Int, task: (Int) -> T): List<T> {
		val pool = Executors.newFixedThreadPool(n)
		try {
			val start = CountDownLatch(1)
			val futures = (0 until n).map { i -> pool.submit(Callable { start.await(); task(i) }) }
			start.countDown()
			return futures.map { it.get() }
		} finally {
			pool.shutdown()
		}
	}

	// --- Autenticação e privacidade ---

	@Test
	fun `caixa so com token valido e so as proprias notificacoes`() {
		val me = person()
		for (path in listOf("/api/v1/notifications", "/api/v1/notifications/unread-count")) {
			assertEquals(401, call("GET", path).status)
			assertEquals(401, call("GET", path, sign(me.id, keyPair = TestKeys.generate())).status)
			assertEquals(401, call("GET", path, sign(me.id, audience = "outro")).status)
			assertEquals(401, call("GET", path, sign(me.id, expiresAt = Instant.now().minusSeconds(3600))).status)
		}
		assertEquals(200, call("GET", "/actuator/health").status)

		val bruno = person()
		val intrusa = person()
		assertEquals(listOf("ACCEPTED"), statuses(deliver(listOf(messageEvent(me, bruno)))))
		val mine = list(me)
		assertEquals(1, mine["items"].size())
		val id = mine["items"][0]["id"].asString()
		assertEquals(0, list(intrusa)["items"].size())
		assertEquals(0, list(bruno)["items"].size(), "o ator não recebe a própria notificação")
		val other = call("PUT", "/api/v1/notifications/$id/read", intrusa.token)
		val missing = call("PUT", "/api/v1/notifications/${UUID.randomUUID()}/read", intrusa.token)
		assertEquals(404, other.status)
		assertEquals(other.json!!["code"], missing.json!!["code"])
		assertEquals(other.json["detail"], missing.json["detail"])
		assertFalse(list(me)["items"][0]["read"].asBoolean(), "a intrusa não marcou nada")
	}

	@Test
	fun `entrega interna so com a chave do produtor e so os seus tipos`() {
		val me = person()
		val event = messageEvent(me, person())
		for (key in listOf(null, "", "errada")) {
			assertEquals(403, call("POST", "/api/v1/internal/events", body = mapper.writeValueAsString(mapOf("events" to listOf(event))), key = key).status)
		}
		// Um token de utilizador não substitui a chave.
		assertEquals(403, call("POST", "/api/v1/internal/events", me.token, mapper.writeValueAsString(mapOf("events" to listOf(event)))).status)
		// Outro produtor não pode entregar mensagens; e os tipos da segunda parte ainda não são tratados.
		assertEquals(listOf("REJECTED/WRONG_PRODUCER"), statuses(deliver(listOf(event), USERS_KEY)))
		val comment = linkedMapOf<String, Any>(
			"eventId" to UUID.randomUUID().toString(), "type" to "COMMENT_CREATED", "occurredAt" to Instant.now().toString(),
			"actor" to mapOf("userId" to UUID.randomUUID().toString(), "username" to "rui.costa"), "recipientId" to me.id.toString(),
			"experienceId" to UUID.randomUUID().toString(), "commentId" to UUID.randomUUID().toString(),
		)
		assertEquals(listOf("REJECTED/UNSUPPORTED_TYPE"), statuses(deliver(listOf(comment), EXPERIENCES_KEY)))
		assertEquals(0, list(me)["items"].size())
	}

	// --- Lotes, validação e deduplicação ---

	@Test
	fun `lote com resultados por evento, validacao estrita e deduplicacao`() {
		val me = person()
		val ana = person()
		val id = UUID.randomUUID()
		val first = messageEvent(me, ana, seq = 1, eventId = id)
		val batch = listOf(
			first,
			first, // repetido no mesmo lote
			messageEvent(me, ana, seq = 2, eventId = id), // o mesmo id com outro conteúdo
			messageEvent(me, ana, extra = mapOf("text" to "segredo")), // campo a mais (nunca entra conteúdo)
			messageEvent(me, me), // a própria ação
			messageEvent(me, ana, occurredAt = Instant.now().minus(Duration.ofDays(31))), // demasiado antigo
			messageEvent(me, ana, occurredAt = Instant.now().plus(Duration.ofHours(1))), // no futuro
			messageEvent(me, ana, kind = "AUDIO"),
			messageEvent(me, ana, seq = 3, kind = "SHARE"),
		)
		val resp = deliver(batch)
		assertEquals(200, resp.status, resp.body)
		assertEquals(
			listOf("ACCEPTED", "DUPLICATE", "REJECTED/CONFLICT", "REJECTED/INVALID_EVENT", "REJECTED/SELF_EVENT", "REJECTED/EXPIRED",
				"REJECTED/INVALID_EVENT", "REJECTED/INVALID_EVENT", "ACCEPTED"),
			statuses(resp),
		)
		// Reenvio (resposta perdida): DUPLICATE, sem nova notificação.
		assertEquals(listOf("DUPLICATE"), statuses(deliver(listOf(first))))
		val page = list(me)
		assertEquals(2, page["items"].size())
		assertEquals(listOf("SHARE", "TEXT"), (0 until 2).map { page["items"][it]["messageKind"].asString() })
		assertEquals(ana.username, page["items"][0]["actor"]["username"].asString())
		assertFalse(page.toString().contains("segredo"))
		assertTrue(page["items"][0]["experienceId"] == null, "campos de outros tipos ficam de fora")

		assertEquals(400, call("POST", "/api/v1/internal/events", body = """{"events":[]}""", key = MESSAGING_KEY).status)
		assertEquals(400, deliver(List(101) { messageEvent(me, ana) }).status)
		assertEquals(400, call("POST", "/api/v1/internal/events", body = """{"eventos":[]}""", key = MESSAGING_KEY).status)
		assertEquals(413, call("POST", "/api/v1/internal/events", body = """{"events":["${"x".repeat(300_000)}"]}""", key = MESSAGING_KEY).status)
	}

	@Test
	fun `pedidos concorrentes - o mesmo evento cria uma notificacao e seq unicos por pessoa`() {
		val me = person()
		val ana = person()
		val same = messageEvent(me, ana)
		val results = concurrently(8) { deliver(listOf(same)) }.flatMap { statuses(it) }
		assertEquals(1, results.count { it == "ACCEPTED" }, results.toString())
		assertEquals(7, results.count { it == "DUPLICATE" }, results.toString())
		concurrently(20) { i -> deliver(listOf(messageEvent(me, ana, seq = i + 2L))) }
		val stored = jdbc.sql("SELECT seq FROM notification WHERE recipient_id = :r ORDER BY seq").param("r", me.id).query(Long::class.javaObjectType).list()
		assertEquals((1L..21L).toList(), stored)
	}

	// --- Cursores ---

	@Test
	fun `caixa por seq - recentes, anteriores, novas, e lacunas da retencao nunca sao erro`() {
		val me = person()
		val ana = person()
		deliver((1L..25L).map { messageEvent(me, ana, seq = it) })
		val latest = list(me, "?limit=10")
		assertEquals((25L downTo 16L).toList(), seqs(latest))
		assertTrue(latest["hasMoreBefore"].asBoolean())
		assertFalse(latest["hasMoreAfter"].asBoolean())
		assertEquals(25L, latest["lastSeq"].asLong())
		assertEquals((15L downTo 6L).toList(), seqs(list(me, "?before=16&limit=10")))
		// Novas por seq crescente, avançando pelo último recebido.
		val seen = mutableListOf<Long>()
		var cursor = 0L
		do {
			val page = list(me, "?after=$cursor&limit=10")
			seen += seqs(page)
			cursor = seqs(page).lastOrNull() ?: cursor
		} while (page["hasMoreAfter"].asBoolean())
		assertEquals((1L..25L).toList(), seen)
		assertEquals(0, list(me, "?after=25")["items"].size())
		assertEquals(0, list(me, "?after=99")["items"].size())
		for (q in listOf("?before=3&after=1", "?limit=0", "?limit=51", "?after=-1", "?before=x")) {
			assertEquals(400, call("GET", "/api/v1/notifications$q", me.token).status, q)
		}

		// Retenção: as mais antigas (1–10) desaparecem; os cursores continuam a funcionar.
		jdbc.sql("UPDATE notification SET created_at = now() - interval '100 days' WHERE recipient_id = :r AND seq <= 10").param("r", me.id).update()
		notifications.removeOld()
		assertEquals((11L..20L).toList(), seqs(list(me, "?after=3&limit=10")), "um cursor anterior à mais antiga devolve a partir da mais antiga")
		val oldest = list(me, "?before=12&limit=10")
		assertEquals(listOf(11L), seqs(oldest))
		assertFalse(oldest["hasMoreBefore"].asBoolean())
		assertEquals(0, list(me, "?before=5")["items"].size())
		assertEquals(25L, list(me)["lastSeq"].asLong())
	}

	// --- Leitura ---

	@Test
	fun `por ler e marcar como lidas - idempotente e nunca alem do fim`() {
		val me = person()
		val ana = person()
		deliver((1L..5L).map { messageEvent(me, ana, seq = it) })
		assertEquals(5, call("GET", "/api/v1/notifications/unread-count", me.token).json!!["unreadCount"].asInt())
		val id = list(me)["items"][0]["id"].asString()
		val one = call("PUT", "/api/v1/notifications/$id/read", me.token)
		assertEquals(200, one.status)
		assertEquals(4, one.json!!["unreadCount"].asInt())
		assertEquals(4, call("PUT", "/api/v1/notifications/$id/read", me.token).json!!["unreadCount"].asInt(), "repetir é inofensivo")
		// A 5 já estava lida; até 3 deixa só a 4.
		assertEquals(1, call("PUT", "/api/v1/notifications/read", me.token, """{"upToSeq":3}""").json!!["unreadCount"].asInt())
		assertEquals(0, call("PUT", "/api/v1/notifications/read", me.token, """{"upToSeq":999}""").json!!["unreadCount"].asInt())
		// Marcar até 999 não marcou notificações futuras.
		deliver(listOf(messageEvent(me, ana, seq = 6)))
		assertEquals(1, call("GET", "/api/v1/notifications/unread-count", me.token).json!!["unreadCount"].asInt())
		for (body in listOf("""{"upToSeq":-1}""", """{"upToSeq":"3"}""", """{"upToSeq":1.5}""", "{}", """{"upToSeq":1,"x":2}""")) {
			assertEquals(400, call("PUT", "/api/v1/notifications/read", me.token, body).status, body)
		}
		assertEquals(400, call("PUT", "/api/v1/notifications/nao-e-uuid/read", me.token).status)
		// Pedidos simultâneos com valores diferentes: o resultado final é o mesmo.
		val values = listOf(6L, 1L, 4L, 999L, 0L)
		concurrently(values.size) { call("PUT", "/api/v1/notifications/read", me.token, """{"upToSeq":${values[it]}}""") }
		assertEquals(0, call("GET", "/api/v1/notifications/unread-count", me.token).json!!["unreadCount"].asInt())
	}

	// --- Retenção e deduplicação ---

	@Test
	fun `limite por pessoa e retencao nao deixam um evento antigo recriar notificacoes`() {
		val me = person()
		val ana = person()
		val events = (1L..(MAX_PER_RECIPIENT + 5L)).map { messageEvent(me, ana, seq = it) }
		events.chunked(100).forEach { deliver(it) }
		val stored = jdbc.sql("SELECT seq FROM notification WHERE recipient_id = :r ORDER BY seq").param("r", me.id).query(Long::class.javaObjectType).list()
		assertEquals((6L..(MAX_PER_RECIPIENT + 5L)).toList(), stored, "as mais antigas saem primeiro")
		// O evento da notificação apagada pelo limite volta: DUPLICATE, nada é recriado.
		assertEquals(listOf("DUPLICATE"), statuses(deliver(listOf(events.first()))))
		assertEquals(MAX_PER_RECIPIENT.toLong(), count("SELECT count(*) FROM notification WHERE recipient_id = :r", "r" to me.id))
		// Retenção por idade: a notificação sai, a deduplicação fica; o reenvio continua DUPLICATE.
		jdbc.sql("UPDATE notification SET created_at = now() - interval '100 days' WHERE recipient_id = :r").param("r", me.id).update()
		notifications.removeOld()
		assertEquals(0L, count("SELECT count(*) FROM notification WHERE recipient_id = :r", "r" to me.id))
		assertEquals(listOf("DUPLICATE"), statuses(deliver(listOf(events.last()))))
		assertEquals(0L, count("SELECT count(*) FROM notification WHERE recipient_id = :r", "r" to me.id))
		// Limpeza da deduplicação: só depois da retenção própria (35 dias), quando o evento já seria EXPIRED.
		val old = messageEvent(me, ana, seq = 99, occurredAt = Instant.now().minus(Duration.ofDays(29)))
		assertEquals(listOf("ACCEPTED"), statuses(deliver(listOf(old))))
		jdbc.sql("UPDATE received_event SET received_at = now() - interval '36 days' WHERE event_id = :id").param("id", UUID.fromString(old["eventId"] as String)).update()
		ingest.removeOldEvents()
		assertEquals(0L, count("SELECT count(*) FROM received_event WHERE event_id = :id", "id" to UUID.fromString(old["eventId"] as String)))
	}

	@Test
	fun `a deduplicacao tem de durar mais do que a idade maxima de um evento`() {
		assertThrows<IllegalArgumentException> {
			NotificationsProperties(Duration.ofDays(90), 1000, eventMaxAge = Duration.ofDays(30), eventRetention = Duration.ofDays(30), maxFutureSkew = Duration.ofMinutes(5), maxBodyBytes = 1024)
		}
	}

	@Test
	fun `registos sem usernames, chaves nem tokens`(output: CapturedOutput) {
		val me = person()
		val ana = person()
		deliver(listOf(messageEvent(me, ana), messageEvent(me, ana, extra = mapOf("text" to "segredo-xyz"))))
		call("POST", "/api/v1/internal/events", body = """{"events":[]}""", key = "chave-errada-xyz")
		list(me)
		val logs = output.all
		assertFalse(logs.contains(ana.username) || logs.contains(me.username))
		assertFalse(logs.contains("segredo-xyz") || logs.contains(MESSAGING_KEY) || logs.contains("chave-errada-xyz") || logs.contains(me.token))
	}

	companion object {
		const val MESSAGING_KEY = "chave-mensagens-dos-testes"
		const val USERS_KEY = "chave-users-dos-testes"
		const val EXPERIENCES_KEY = "chave-experiencias-dos-testes"
		const val MAX_PER_RECIPIENT = 30
		val CONVERSATION: UUID = UUID.randomUUID()
		val keys: TestKeys = TestKeys.create()

		@JvmStatic
		@DynamicPropertySource
		fun properties(registry: DynamicPropertyRegistry) {
			registry.add("portofit.security.public-key-location") { keys.publicKeyUri }
			registry.add("portofit.producers.messaging-key") { MESSAGING_KEY }
			registry.add("portofit.producers.users-key") { USERS_KEY }
			registry.add("portofit.producers.experiences-key") { EXPERIENCES_KEY }
			registry.add("portofit.notifications.max-per-recipient") { MAX_PER_RECIPIENT }
		}
	}
}
