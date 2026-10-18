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
