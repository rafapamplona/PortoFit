package com.example.portofit.messaging

import com.sun.net.httpserver.HttpServer
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.net.InetSocketAddress
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
			val file = Files.createTempDirectory("messaging-jwt-test").resolve("public.pem")
			Files.writeString(file, "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pair.public.encoded) + "\n-----END PUBLIC KEY-----\n")
			return TestKeys(pair, file.toUri().toString())
		}
	}
}

/**
 * Falso experiences-service: `GET /api/v1/internal/experiences?ids=…`. Confirma a chave interna (403 se errada) e
 * responde, pela ordem pedida, com o estado registado pelo teste (por omissão NOT_FOUND). [mode] simula falhas.
 */
class ExperiencesStub private constructor(private val server: HttpServer) {

	enum class Mode { UP, ERROR, SLOW, FORBIDDEN, INVALID_BODY }

	@Volatile
	var mode = Mode.UP

	private val statuses = ConcurrentHashMap<UUID, String>()

	/** Ids pedidos em cada consulta (uma entrada por pedido HTTP). */
	val requests = CopyOnWriteArrayList<List<String>>()

	val url: String get() = "http://127.0.0.1:${server.address.port}"

	fun available(id: UUID = UUID.randomUUID()): UUID = id.also { statuses[it] = "AVAILABLE" }

	fun delete(id: UUID) {
		statuses[id] = "DELETED"
	}

	companion object {
		const val KEY = "chave-mensagens-dos-testes"

		fun start(): ExperiencesStub {
			val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
			val stub = ExperiencesStub(server)
			server.createContext("/api/v1/internal/experiences") { exchange ->
				val ids = exchange.requestURI.query.orEmpty().removePrefix("ids=").split(',').filter { it.isNotEmpty() }
				stub.requests += ids
				val key = exchange.requestHeaders.getFirst("X-Internal-Key")
				val (status, body) = when {
					stub.mode == Mode.ERROR -> 503 to """{"status":503}"""
					stub.mode == Mode.SLOW -> {
						Thread.sleep(4000)
						503 to """{"status":503}"""
					}
					stub.mode == Mode.FORBIDDEN || key != KEY -> 403 to """{"status":403,"code":"FORBIDDEN"}"""
					stub.mode == Mode.INVALID_BODY -> 200 to """{"items":[{"id":"${ids.first()}","status":"TALVEZ"}]}"""
					else -> 200 to ids.joinToString(",", """{"items":[""", "]}") { id ->
						when (val s = stub.statuses[UUID.fromString(id)] ?: "NOT_FOUND") {
							"AVAILABLE" -> """{"id":"$id","status":"AVAILABLE","experience":{"id":"$id","text":"Experiência $id"}}"""
							else -> """{"id":"$id","status":"$s","experience":null}"""
						}
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

/**
 * Falso users-service: `GET /api/v1/users/{id}` (perfil público). Responde com as pessoas registadas pelo teste
 * (outras → 404); [mode] simula falhas. Não exige token, como o perfil público real.
 */
class UsersStub private constructor(private val server: HttpServer) {

	enum class Mode { UP, ERROR, SLOW, INVALID_BODY }

	@Volatile
	var mode = Mode.UP

	private val usernames = ConcurrentHashMap<UUID, String>()

	/** Ids pedidos (um por pedido HTTP) e se o pedido levava cabeçalho Authorization. */
	val requests = CopyOnWriteArrayList<Pair<String, Boolean>>()

	val url: String get() = "http://127.0.0.1:${server.address.port}"

	fun register(id: UUID, username: String) {
		usernames[id] = username
	}

	companion object {
		fun start(): UsersStub {
			val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
			val stub = UsersStub(server)
			server.createContext("/api/v1/users/") { exchange ->
				val id = exchange.requestURI.path.removePrefix("/api/v1/users/")
				stub.requests += id to (exchange.requestHeaders.getFirst("Authorization") != null)
				val username = runCatching { stub.usernames[UUID.fromString(id)] }.getOrNull()
				val (status, body) = when {
					stub.mode == Mode.ERROR -> 503 to """{"status":503}"""
					stub.mode == Mode.SLOW -> {
						Thread.sleep(3000)
						503 to """{"status":503}"""
					}
					stub.mode == Mode.INVALID_BODY -> 200 to """{"userId":"${UUID.randomUUID()}","username":"outra"}"""
					username == null -> 404 to """{"status":404,"code":"NOT_FOUND"}"""
					else -> 200 to """{"userId":"$id","username":"$username","description":null,"interests":[],"followersCount":0,"followingCount":0}"""
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
