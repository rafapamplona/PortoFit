package com.example.portofit.messaging

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.SecurityContext
import org.junit.jupiter.api.AfterEach
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
 * Base dos testes de integração do messaging-service: PostgreSQL real (Testcontainers), falsos users-service e
 * experiences-service, tokens assinados por uma chave só dos testes e pedidos HTTP reais. Cada teste usa pessoas novas.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
abstract class ApiIntegrationTest {

	@Autowired lateinit var mapper: JsonMapper
	@Autowired lateinit var jdbc: JdbcClient

	@LocalServerPort var port: Int = 0

	@AfterEach
	fun restoreStubs() {
		experiences.mode = ExperiencesStub.Mode.UP
		users.mode = UsersStub.Mode.UP
		notifications.reset()
	}

	data class Person(val id: UUID, val username: String, val token: String)

	data class Resp(val status: Int, val body: String, val headers: Map<String, List<String>>, val mapper: JsonMapper) {
		val json: JsonNode by lazy { mapper.readTree(body.ifEmpty { "{}" }) }
		val code: String? get() = json["code"]?.asString()

		fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
	}

	/** Uma pessoa nova, registada no falso users-service (o perfil público existe). */
	fun person(): Person {
		val id = UUID.randomUUID()
		val username = "u" + id.toString().replace("-", "").take(10)
		users.register(id, username)
		return Person(id, username, sign(id, username))
	}

	fun sign(
		userId: UUID,
		username: String? = "u",
		audience: String = "portofit-api",
		keyPair: KeyPair = keys.keyPair,
		expiresAt: Instant = Instant.now().plusSeconds(900),
	): String {
		val rsa = RSAKey.Builder(keyPair.public as RSAPublicKey).privateKey(keyPair.private as RSAPrivateKey).keyIDFromThumbprint().build()
		val encoder = NimbusJwtEncoder(ImmutableJWKSet<SecurityContext>(JWKSet(rsa)))
		val claims = JwtClaimsSet.builder().issuer("portofit-users").audience(listOf(audience)).subject(userId.toString())
			.issuedAt(expiresAt.minusSeconds(900)).expiresAt(expiresAt).id(UUID.randomUUID().toString())
			.apply { if (username != null) claim("username", username) }
			.build()
		val header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(rsa.keyID).type("JWT").build()
		return encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
	}

	private val client: HttpClient = HttpClient.newHttpClient()

	fun call(
		method: String,
		path: String,
		token: String? = null,
		body: String? = null,
		key: String? = null,
		contentType: String = "application/json",
	): Resp {
		val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
			.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
		if (token != null) builder.header("Authorization", "Bearer $token")
		if (body != null) builder.header("Content-Type", contentType)
		if (key != null) builder.header("Idempotency-Key", key)
		val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
		return Resp(response.statusCode(), response.body(), response.headers().map(), mapper)
	}

	fun get(path: String, who: Person) = call("GET", path, who.token)

	fun ok(path: String, who: Person): JsonNode = get(path, who).also { assertEquals(200, it.status, it.body) }.json

	/** Abre (cria ou devolve) a conversa de [a] com [b]. */
	fun open(a: Person, b: Person): Resp = call("POST", "/api/v1/conversations", a.token, """{"participantId":"${b.id}"}""")

	fun conversation(a: Person, b: Person): String = open(a, b).also { assertEquals(true, it.status in setOf(200, 201), it.body) }.json["id"].asString()

	fun send(
		who: Person,
		conversationId: String,
		text: String? = null,
		experienceId: UUID? = null,
		key: String = UUID.randomUUID().toString(),
	): Resp {
		val fields = buildList {
			if (text != null) add("\"text\":" + mapper.writeValueAsString(text))
			if (experienceId != null) add("\"experienceId\":\"$experienceId\"")
		}
		return call("POST", "/api/v1/conversations/$conversationId/messages", who.token, fields.joinToString(",", "{", "}"), key)
	}

	fun sent(who: Person, conversationId: String, text: String): Long =
		send(who, conversationId, text).also { assertEquals(201, it.status, it.body) }.json["seq"].asLong()

	fun messages(who: Person, conversationId: String, query: String = ""): JsonNode =
		ok("/api/v1/conversations/$conversationId/messages$query", who)

	fun read(who: Person, conversationId: String, seq: Long): Resp =
		call("PUT", "/api/v1/conversations/$conversationId/read", who.token, """{"lastReadSeq":$seq}""")

	fun count(sql: String, vararg params: Pair<String, Any>): Long =
		params.fold(jdbc.sql(sql)) { spec, (name, value) -> spec.param(name, value) }.query(Long::class.javaObjectType).single()

	val JsonNode.elements: List<JsonNode> get() = (0 until size()).map { this[it] }

	fun JsonNode.seqs(): List<Long> = this["items"].elements.map { it["seq"].asLong() }

	/** Corre [n] tarefas em paralelo, a começar ao mesmo tempo. */
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
		/** Limites nos testes (os reais são 1000 caracteres, 120 mensagens e 20 conversas por hora, 10 000 por conversa). */
		const val MAX_TEXT = 1000
		const val MAX_MESSAGES_PER_WINDOW = 40
		const val MAX_NEW_CONVERSATIONS = 6
		const val MAX_PER_CONVERSATION = 25

		val keys: TestKeys = TestKeys.create()
		val experiences: ExperiencesStub = ExperiencesStub.start()
		val users: UsersStub = UsersStub.start()
		val notifications: NotificationsStub = NotificationsStub.start()

		@JvmStatic
		@DynamicPropertySource
		fun properties(registry: DynamicPropertyRegistry) {
			registry.add("portofit.security.public-key-location") { keys.publicKeyUri }
			registry.add("portofit.experiences.base-url") { experiences.url }
			registry.add("portofit.experiences.internal-key") { ExperiencesStub.KEY }
			registry.add("portofit.users.base-url") { users.url }
			registry.add("portofit.messaging.max-text-length") { MAX_TEXT }
			registry.add("portofit.messaging.max-messages-per-window") { MAX_MESSAGES_PER_WINDOW }
			registry.add("portofit.messaging.max-new-conversations-per-window") { MAX_NEW_CONVERSATIONS }
			registry.add("portofit.messaging.max-messages-per-conversation") { MAX_PER_CONVERSATION }
			// A outbox é enviada pelos próprios testes (runOnce), nunca pelo agendamento.
			registry.add("portofit.outbox.enabled") { false }
			registry.add("portofit.notifications.base-url") { notifications.url }
			registry.add("portofit.notifications.internal-key") { NotificationsStub.KEY }
			registry.add("portofit.notifications.timeout") { "1s" }
		}
	}
}
