package com.example.portofit.notifications

import com.sun.net.httpserver.HttpServer
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.UUID
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

/** Par de chaves RSA só dos testes: o serviço recebe só a pública (PEM); os testes assinam com a privada. */
class TestKeys private constructor(val keyPair: KeyPair, val publicKeyUri: String) {
	companion object {
		fun generate(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

		fun create(): TestKeys {
			val pair = generate()
			val file = Files.createTempDirectory("notifications-jwt-test").resolve("public.pem")
			Files.writeString(file, "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pair.public.encoded) + "\n-----END PUBLIC KEY-----\n")
			return TestKeys(pair, file.toUri().toString())
		}
	}
}

/**
 * Falso users-service: `GET /api/v1/internal/users/{id}/followers` (docs/notifications-api.md §8.1). Confirma a
 * chave (403 se errada), devolve os seguidores registados pelo teste por páginas de `limit`, com um cursor opaco, e
 * 404 para um autor desconhecido. [mode] e [failOnCursor] simulam falhas; [requests] guarda cada pedido.
 */
class FollowersStub private constructor(private val server: HttpServer) {

	enum class Mode { UP, DOWN, FORBIDDEN, INVALID_BODY }

	data class Request(val authorId: String, val followedBefore: String?, val cursor: String?, val limit: Int?, val key: String?)

	@Volatile
	var mode = Mode.UP

	/** Responde 503 só ao pedido com este cursor (interrupção a meio da resolução). */
	@Volatile
	var failOnCursor: String? = null

	@Volatile
	var delayMillis = 0L

	private val followers = ConcurrentHashMap<UUID, List<UUID>>()

	val requests = CopyOnWriteArrayList<Request>()

	val url: String get() = "http://127.0.0.1:${server.address.port}"

	fun register(authorId: UUID, list: List<UUID>) {
		followers[authorId] = list
	}

	fun reset() {
		mode = Mode.UP
		failOnCursor = null
		delayMillis = 0
		requests.clear()
	}

	companion object {
		const val KEY = "chave-seguidores-dos-testes"

		fun start(): FollowersStub {
			val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
			val stub = FollowersStub(server)
			server.createContext("/api/v1/internal/users/") { exchange ->
				val author = exchange.requestURI.path.removePrefix("/api/v1/internal/users/").removeSuffix("/followers")
				val query = exchange.requestURI.rawQuery.orEmpty().split('&').filter { '=' in it }
					.associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
				val key = exchange.requestHeaders.getFirst("X-Internal-Key")
				stub.requests += Request(author, query["followedBefore"], query["cursor"], query["limit"]?.toIntOrNull(), key)
				if (stub.delayMillis > 0) Thread.sleep(stub.delayMillis)
				val list = runCatching { stub.followers[UUID.fromString(author)] }.getOrNull()
				val (status, body) = when {
					stub.mode == Mode.FORBIDDEN || key != KEY -> 403 to """{"status":403,"code":"FORBIDDEN"}"""
					stub.mode == Mode.DOWN || (query["cursor"] != null && query["cursor"] == stub.failOnCursor) -> 503 to """{"status":503}"""
					stub.mode == Mode.INVALID_BODY -> 200 to """{"items":"todos"}"""
					list == null -> 404 to """{"status":404,"code":"NOT_FOUND"}"""
					else -> {
						val from = query["cursor"]?.removePrefix("i")?.toInt() ?: 0
						val limit = query["limit"]?.toInt() ?: 500
						val page = list.drop(from).take(limit)
						val next = if (from + limit < list.size) "\"i${from + limit}\"" else "null"
						200 to page.joinToString(",", """{"items":[""", "],\"nextCursor\":$next}") { "\"$it\"" }
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
