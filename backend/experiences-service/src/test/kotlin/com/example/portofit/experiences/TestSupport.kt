package com.example.portofit.experiences

import com.sun.net.httpserver.HttpServer
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** PostgreSQL real em contentor, com a mesma imagem do docker-compose.yml; relógio controlável. */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {
	@Bean
	@ServiceConnection
	fun postgresContainer(): PostgreSQLContainer = PostgreSQLContainer(DockerImageName.parse("postgres:18.6-trixie"))

	@Bean
	@Primary
	fun testClock(): MutableClock = MutableClock.instance
}

/** Relógio que os testes podem adiantar (memória do catálogo e limite de publicações). */
class MutableClock private constructor(@Volatile private var now: Instant) : Clock() {
	fun advance(duration: Duration) {
		now = now.plus(duration)
	}

	override fun instant(): Instant = now

	override fun getZone(): ZoneId = ZoneOffset.UTC

	override fun withZone(zone: ZoneId): Clock = this

	companion object {
		val instance = MutableClock(Instant.now())
	}
}

/** Par de chaves RSA só dos testes: o serviço recebe só a pública (PEM); os testes assinam com a privada. */
class TestKeys private constructor(val keyPair: KeyPair, val publicKeyUri: String) {
	companion object {
		fun generate(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

		fun create(): TestKeys {
			val pair = generate()
			val file = Files.createTempDirectory("experiences-jwt-test").resolve("public.pem")
			Files.writeString(file, "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pair.public.encoded) + "\n-----END PUBLIC KEY-----\n")
			return TestKeys(pair, file.toUri().toString())
		}
	}
}

/**
 * Falso catalog-service: `GET /api/v1/offerings/{id}`. Cada teste regista as suas atividades (ids aleatórios,
 * para não depender da memória de outros testes). [mode] simula falhas e lentidão.
 */
class CatalogStub private constructor(private val server: HttpServer) {

	enum class Mode { UP, ERROR, SLOW }

	@Volatile
	var mode = Mode.UP

	private val offerings = ConcurrentHashMap<String, String>()

	/** Caminho e query de cada pedido recebido. */
	val requests = CopyOnWriteArrayList<String>()

	val url: String get() = "http://127.0.0.1:${server.address.port}"

	data class TestOffering(val id: UUID, val placeId: UUID, val name: String, val sportId: String, val sportName: String)

	fun add(
		placeId: UUID = UUID.randomUUID(),
		name: String = "Boxe iniciação",
		sportId: String = "boxe",
		sportName: String = "Boxe",
		status: String = "ACTIVE",
		placeName: String = "Clube de Combate Ribeira (fictício)",
		demo: Boolean = true,
	): TestOffering {
		val id = UUID.randomUUID()
		offerings[id.toString()] =
			"""{"id":"$id","name":"$name","description":null,"level":"BEGINNER","scheduleNotes":null,"status":"$status",""" +
			""""sport":{"id":"$sportId","name":"$sportName"},"place":{"id":"$placeId","name":"$placeName","demo":$demo}}"""
		return TestOffering(id, placeId, name, sportId, sportName)
	}

	companion object {
		fun start(): CatalogStub {
			val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
			val stub = CatalogStub(server)
			server.createContext("/api/v1/offerings/") { exchange ->
				stub.requests += exchange.requestURI.toString()
				val id = exchange.requestURI.path.substringAfterLast('/')
				val (status, body) = when (stub.mode) {
					Mode.ERROR -> 503 to """{"status":503}"""
					Mode.SLOW -> {
						Thread.sleep(4000)
						503 to """{"status":503}"""
					}
					Mode.UP -> stub.offerings[id]?.let { 200 to it } ?: (404 to """{"status":404}""")
				}
				val bytes = body.toByteArray(Charsets.UTF_8)
				exchange.responseHeaders.add("Content-Type", "application/json")
				runCatching {
					exchange.sendResponseHeaders(status, bytes.size.toLong())
					exchange.responseBody.use { it.write(bytes) }
				}
				exchange.close()
			}
			server.executor = Executors.newCachedThreadPool()
			server.start()
			return stub
		}
	}
}

/**
 * Falso users-service: `GET /api/v1/internal/me/feed-context`. Lê o `sub` do token (sem validar a assinatura: só a
 * forma do pedido interessa aqui), confirma a chave interna (403 se errada) e devolve o contexto registado pelo teste.
 * [mode] simula falhas: erro, lentidão, token recusado, chave recusada e corpo inválido.
 */
class UsersStub private constructor(private val server: HttpServer) {

	enum class Mode { UP, ERROR, SLOW, REJECT_TOKEN, FORBIDDEN, INVALID_BODY }

	@Volatile
	var mode = Mode.UP

	data class Context(val following: List<UUID>, val interests: List<String>)

	private val contexts = ConcurrentHashMap<UUID, Context>()

	/** (Authorization, X-Internal-Key) de cada pedido recebido. */
	val requests = CopyOnWriteArrayList<Pair<String?, String?>>()

	val url: String get() = "http://127.0.0.1:${server.address.port}"

	fun context(userId: UUID, following: List<UUID> = emptyList(), interests: List<String> = emptyList()) {
		contexts[userId] = Context(following, interests)
	}

	companion object {
		const val KEY = "chave-interna-dos-testes"

		fun start(): UsersStub {
			val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
			val stub = UsersStub(server)
			server.createContext("/api/v1/internal/me/feed-context") { exchange ->
				val authorization = exchange.requestHeaders.getFirst("Authorization")
				val key = exchange.requestHeaders.getFirst("X-Internal-Key")
				stub.requests += authorization to key
				val subject = authorization?.removePrefix("Bearer ")?.split('.')?.getOrNull(1)
					?.let { String(Base64.getUrlDecoder().decode(it)) }
					?.let { Regex("\"sub\":\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
					?.let { UUID.fromString(it) }
				val (status, body) = when {
					stub.mode == Mode.ERROR -> 503 to """{"status":503}"""
					stub.mode == Mode.SLOW -> {
						Thread.sleep(3000)
						200 to """{"following":[],"interests":[]}"""
					}
					stub.mode == Mode.REJECT_TOKEN || subject == null -> 401 to """{"status":401,"code":"INVALID_ACCESS_TOKEN"}"""
					stub.mode == Mode.FORBIDDEN || key != KEY -> 403 to """{"status":403,"code":"FORBIDDEN"}"""
					stub.mode == Mode.INVALID_BODY -> 200 to """{"following":["nao-e-uuid"],"interests":[]}"""
					else -> {
						val context = stub.contexts[subject] ?: Context(emptyList(), emptyList())
						200 to """{"following":[${context.following.joinToString(",") { "\"$it\"" }}],"interests":[${context.interests.joinToString(",") { "\"$it\"" }}]}"""
					}
				}
				val bytes = body.toByteArray(Charsets.UTF_8)
				exchange.responseHeaders.add("Content-Type", "application/json")
				runCatching {
					exchange.sendResponseHeaders(status, bytes.size.toLong())
					exchange.responseBody.use { it.write(bytes) }
				}
				exchange.close()
			}
			server.executor = Executors.newCachedThreadPool()
			server.start()
			return stub
		}
	}
}
