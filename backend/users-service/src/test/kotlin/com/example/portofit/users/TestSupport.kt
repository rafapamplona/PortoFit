package com.example.portofit.users

import com.sun.net.httpserver.HttpServer
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** PostgreSQL real em contentor, com a mesma imagem do docker-compose.yml. */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {
	@Bean
	@ServiceConnection
	fun postgresContainer(): PostgreSQLContainer = PostgreSQLContainer(DockerImageName.parse("postgres:18.6-trixie"))
}

/** Par de chaves RSA só para os testes, escrito em PEM num diretório temporário. */
class TestKeys private constructor(val keyPair: KeyPair, val privateKeyUri: String, val publicKeyUri: String) {
	companion object {
		fun generate(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

		fun create(): TestKeys {
			val pair = generate()
			val dir = Files.createTempDirectory("users-jwt-test")
			val private = dir.resolve("private.pem").also { Files.writeString(it, pem("PRIVATE KEY", pair.private.encoded)) }
			val public = dir.resolve("public.pem").also { Files.writeString(it, pem("PUBLIC KEY", pair.public.encoded)) }
			return TestKeys(pair, private.uri(), public.uri())
		}

		private fun Path.uri() = toUri().toString()

		private fun pem(type: String, der: ByteArray) =
			"-----BEGIN $type-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) + "\n-----END $type-----\n"
	}
}

/** Falso catalog-service: responde a GET /api/v1/sports, ou falha quando [available] é false. */
class CatalogStub private constructor(private val server: HttpServer) {
	@Volatile
	var available = true

	val url: String get() = "http://127.0.0.1:${server.address.port}"

	companion object {
		private const val SPORTS =
			"""{"items":[{"id":"boxe","name":"Boxe","description":null},{"id":"corrida","name":"Corrida","description":null},""" +
				"""{"id":"ioga","name":"Ioga","description":null},{"id":"natacao","name":"Natação","description":null}]}"""

		fun start(): CatalogStub {
			val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
			val stub = CatalogStub(server)
			server.createContext("/api/v1/sports") { exchange ->
				val (status, body) = if (stub.available) 200 to SPORTS else 503 to """{"status":503}"""
				val bytes = body.toByteArray(Charsets.UTF_8)
				exchange.responseHeaders.add("Content-Type", "application/json")
				exchange.sendResponseHeaders(status, bytes.size.toLong())
				exchange.responseBody.use { it.write(bytes) }
			}
			server.start()
			return stub
		}
	}
}

/**
 * Falso notifications-service: `POST /api/v1/internal/events`. Confirma a chave (403 se errada), lembra-se dos eventos
 * aceites (um repetido → DUPLICATE) e regista quantas vezes cada um foi aceite. [mode] simula falhas: indisponível,
 * resposta perdida (aceita mas responde 503), resposta parcial (sem o último resultado), recusa e chave recusada.
 */
class NotificationsStub private constructor(private val server: HttpServer) {

	enum class Mode { UP, DOWN, LOST_RESPONSE, PARTIAL, REJECT, FORBIDDEN }

	@Volatile
	var mode = Mode.UP

	/** Atraso de cada resposta (para sobrepor pedidos). */
	@Volatile
	var delayMillis = 0L

	/** Quantas vezes cada evento foi aceite (ACCEPTED); tem de ser no máximo 1. */
	val accepted = ConcurrentHashMap<String, Int>()

	/** Ids de cada pedido HTTP, pela ordem do lote. */
	val requests = CopyOnWriteArrayList<List<String>>()

	/** Corpos recebidos (para confirmar que nunca levam conteúdo). */
	val bodies = CopyOnWriteArrayList<String>()

	val url: String get() = "http://127.0.0.1:${server.address.port}"

	fun reset() {
		mode = Mode.UP
		delayMillis = 0
		requests.clear()
		bodies.clear()
	}

	companion object {
		const val KEY = "chave-notificacoes-dos-testes"

		private val ID = Regex("\"eventId\":\"([0-9a-f-]{36})\"")

		fun start(): NotificationsStub {
			val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
			val stub = NotificationsStub(server)
			server.createContext("/api/v1/internal/events") { exchange ->
				val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
				val ids = ID.findAll(body).map { it.groupValues[1] }.toList()
				stub.requests += ids
				stub.bodies += body
				if (stub.delayMillis > 0) Thread.sleep(stub.delayMillis)
				val key = exchange.requestHeaders.getFirst("X-Internal-Key")
				fun results(list: List<String>) = list.joinToString(",", """{"results":[""", "]}") { id ->
					if (stub.accepted.putIfAbsent(id, 1) == null) """{"eventId":"$id","status":"ACCEPTED"}"""
					else """{"eventId":"$id","status":"DUPLICATE"}"""
				}
				val (status, response) = when {
					stub.mode == Mode.FORBIDDEN || key != KEY -> 403 to """{"status":403,"code":"FORBIDDEN"}"""
					stub.mode == Mode.DOWN -> 503 to """{"status":503}"""
					stub.mode == Mode.LOST_RESPONSE -> {
						results(ids)
						503 to """{"status":503}"""
					}
					stub.mode == Mode.PARTIAL -> 200 to results(ids.dropLast(1))
					stub.mode == Mode.REJECT -> 200 to ids.joinToString(",", """{"results":[""", "]}") { """{"eventId":"$it","status":"REJECTED","reason":"INVALID_EVENT"}""" }
					else -> 200 to results(ids)
				}
				val bytes = response.toByteArray(Charsets.UTF_8)
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
