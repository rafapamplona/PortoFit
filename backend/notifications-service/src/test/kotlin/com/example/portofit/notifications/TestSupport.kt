package com.example.portofit.notifications

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.nio.file.Files
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
