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
	): TestOffering {
		val id = UUID.randomUUID()
		offerings[id.toString()] =
			"""{"id":"$id","name":"$name","description":null,"level":"BEGINNER","scheduleNotes":null,"status":"$status",""" +
			""""sport":{"id":"$sportId","name":"$sportName"},"place":{"id":"$placeId","name":"$placeName","demo":true}}"""
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
