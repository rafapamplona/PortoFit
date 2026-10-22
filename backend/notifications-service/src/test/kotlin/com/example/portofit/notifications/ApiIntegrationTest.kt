package com.example.portofit.notifications

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.SecurityContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
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
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertEquals

/**
 * Base dos testes de integração do notifications-service: PostgreSQL real (Testcontainers), tokens assinados por
 * uma chave só dos testes, chaves de produtor dos testes, um falso users-service e pedidos HTTP reais.
 * Cada teste usa pessoas novas.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
abstract class ApiIntegrationTest {

	@Autowired lateinit var mapper: JsonMapper
	@Autowired lateinit var jdbc: JdbcClient

	@LocalServerPort var port: Int = 0

	val client: HttpClient = HttpClient.newHttpClient()

	data class Resp(val status: Int, val body: String, val json: JsonNode?)

	data class Person(val id: UUID, val username: String, val token: String)

	fun person(): Person {
		val id = UUID.randomUUID()
		val username = "u" + id.toString().replace("-", "").take(10)
		return Person(id, username, sign(id))
	}

	fun sign(userId: UUID, audience: String = "portofit-api", keyPair: KeyPair = keys.keyPair, expiresAt: Instant = Instant.now().plusSeconds(900)): String {
		val rsa = RSAKey.Builder(keyPair.public as RSAPublicKey).privateKey(keyPair.private as RSAPrivateKey).keyIDFromThumbprint().build()
		val encoder = NimbusJwtEncoder(ImmutableJWKSet<SecurityContext>(JWKSet(rsa)))
		val claims = JwtClaimsSet.builder().issuer("portofit-users").audience(listOf(audience)).subject(userId.toString())
			.issuedAt(expiresAt.minusSeconds(900)).expiresAt(expiresAt).id(UUID.randomUUID().toString()).claim("username", "u").build()
		val header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(rsa.keyID).type("JWT").build()
		return encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
	}

	fun call(method: String, path: String, token: String? = null, body: String? = null, key: String? = null): Resp {
		val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
			.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
		if (token != null) builder.header("Authorization", "Bearer $token")
		if (body != null) builder.header("Content-Type", "application/json")
		if (key != null) builder.header("X-Internal-Key", key)
		val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
		return Resp(response.statusCode(), response.body(), runCatching { mapper.readTree(response.body()) }.getOrNull())
	}

	fun messageEvent(
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

	private fun common(type: String, actor: Person, eventId: UUID, occurredAt: Instant): Map<String, Any> = linkedMapOf(
		"eventId" to eventId.toString(), "type" to type, "occurredAt" to occurredAt.toString(),
		"actor" to mapOf("userId" to actor.id.toString(), "username" to actor.username),
	)

	fun commentEvent(recipient: Person, actor: Person, experienceId: UUID = UUID.randomUUID(), eventId: UUID = UUID.randomUUID(), extra: Map<String, Any> = emptyMap()) =
		common("COMMENT_CREATED", actor, eventId, Instant.now()) +
			mapOf("recipientId" to recipient.id.toString(), "experienceId" to experienceId.toString(), "commentId" to UUID.randomUUID().toString()) + extra

	fun followEvent(recipient: Person, actor: Person, occurredAt: Instant = Instant.now(), eventId: UUID = UUID.randomUUID()) =
		common("USER_FOLLOWED", actor, eventId, occurredAt) + mapOf("recipientId" to recipient.id.toString())

	fun publishedEvent(actor: Person, experienceId: UUID = UUID.randomUUID(), occurredAt: Instant = Instant.now(), eventId: UUID = UUID.randomUUID(), extra: Map<String, Any> = emptyMap()) =
		common("EXPERIENCE_PUBLISHED", actor, eventId, occurredAt) + mapOf("experienceId" to experienceId.toString()) + extra

	fun deliver(events: List<Map<String, Any>>, key: String = MESSAGING_KEY): Resp =
		call("POST", "/api/v1/internal/events", body = mapper.writeValueAsString(mapOf("events" to events)), key = key)

	fun statuses(resp: Resp) = (0 until resp.json!!["results"].size()).map { resp.json["results"][it] }
		.map { it["status"].asString() + (it["reason"]?.let { r -> "/" + r.asString() } ?: "") }

	fun list(who: Person, query: String = ""): JsonNode = call("GET", "/api/v1/notifications$query", who.token).also { assertEquals(200, it.status, it.body) }.json!!

	fun seqs(page: JsonNode) = (0 until page["items"].size()).map { page["items"][it]["seq"].asLong() }

	fun count(sql: String, vararg params: Pair<String, Any>): Long =
		params.fold(jdbc.sql(sql)) { spec, (k, v) -> spec.param(k, v) }.query(Long::class.javaObjectType).single()

	fun <T> concurrently(n: Int, task: (Int) -> T): List<T> {
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

	companion object {
		const val MESSAGING_KEY = "chave-mensagens-dos-testes"
		const val USERS_KEY = "chave-users-dos-testes"
		const val EXPERIENCES_KEY = "chave-experiencias-dos-testes"
		const val MAX_PER_RECIPIENT = 30
		const val FAN_OUT_PAGE = 3
		const val MAX_FAN_OUT = 10
		val CONVERSATION: UUID = UUID.randomUUID()
		val keys: TestKeys = TestKeys.create()
		val users: FollowersStub = FollowersStub.start()

		@JvmStatic
		@DynamicPropertySource
		fun properties(registry: DynamicPropertyRegistry) {
			registry.add("portofit.security.public-key-location") { keys.publicKeyUri }
			registry.add("portofit.producers.messaging-key") { MESSAGING_KEY }
			registry.add("portofit.producers.users-key") { USERS_KEY }
			registry.add("portofit.producers.experiences-key") { EXPERIENCES_KEY }
			registry.add("portofit.notifications.max-per-recipient") { MAX_PER_RECIPIENT }
			// Resolução dos seguidores chamada pelos próprios testes (runOnce), nunca pelo agendamento.
			registry.add("portofit.notifications.fan-out-enabled") { false }
			registry.add("portofit.notifications.fan-out-page") { FAN_OUT_PAGE }
			registry.add("portofit.notifications.max-fan-out") { MAX_FAN_OUT }
			registry.add("portofit.users.base-url") { users.url }
			registry.add("portofit.users.followers-key") { FollowersStub.KEY }
			registry.add("portofit.users.timeout") { "1s" }
		}
	}
}
