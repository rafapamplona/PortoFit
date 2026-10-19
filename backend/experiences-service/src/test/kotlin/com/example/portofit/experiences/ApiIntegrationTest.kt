package com.example.portofit.experiences

import com.example.portofit.experiences.media.MediaRepository
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.SecurityContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.UUID

/**
 * Base dos testes de integração: um só contexto Spring (e um só PostgreSQL) partilhado por todas as classes,
 * com catálogo falso, chaves só dos testes (o serviço recebe só a pública), relógio controlável e uma pasta
 * temporária para as fotografias. O repositório de fotografias é um spy, para simular falhas parciais.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
abstract class ApiIntegrationTest {

	@Autowired lateinit var mvc: MockMvc
	@Autowired lateinit var mapper: JsonMapper
	@Autowired lateinit var jdbc: JdbcClient

	@MockitoSpyBean lateinit var mediaRepository: MediaRepository

	@LocalServerPort var port: Int = 0

	data class TestUser(val id: UUID, val username: String, val token: String)

	protected fun newUser(prefix: String): TestUser {
		val id = UUID.randomUUID()
		val username = prefix + id.toString().take(8)
		return TestUser(id, username, sign(id, username))
	}

	protected fun sign(
		userId: UUID,
		username: String?,
		issuer: String = "portofit-users",
		audience: String = "portofit-api",
		expiresAt: Instant = Instant.now().plusSeconds(900),
		keyPair: KeyPair = keys.keyPair,
	): String {
		val rsa = RSAKey.Builder(keyPair.public as RSAPublicKey).privateKey(keyPair.private as RSAPrivateKey).keyIDFromThumbprint().build()
		val encoder = NimbusJwtEncoder(ImmutableJWKSet<SecurityContext>(JWKSet(rsa)))
		val claims = JwtClaimsSet.builder().issuer(issuer).audience(listOf(audience)).subject(userId.toString())
			.issuedAt(expiresAt.minusSeconds(900)).expiresAt(expiresAt).id(UUID.randomUUID().toString())
			.apply { if (username != null) claim("username", username) }
			.build()
		val header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(rsa.keyID).type("JWT").build()
		return encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
	}

	companion object {
		/** Publicações por autor nos testes (o limite real é 30 por hora). */
		const val LIMIT = 5

		/** Fotografias pendentes por pessoa nos testes (o limite real é 20). */
		const val PENDING_LIMIT = 3

		val keys: TestKeys = TestKeys.create()
		val catalog: CatalogStub = CatalogStub.start()
		val clock: MutableClock = MutableClock.instance
		val mediaDir: Path = Files.createTempDirectory("experiences-media-test")

		@JvmStatic
		@DynamicPropertySource
		fun properties(registry: DynamicPropertyRegistry) {
			registry.add("portofit.security.public-key-location") { keys.publicKeyUri }
			registry.add("portofit.catalog.base-url") { catalog.url }
			registry.add("portofit.publishing.max-per-author") { LIMIT }
			registry.add("portofit.media.storage-dir") { mediaDir.toString() }
			registry.add("portofit.media.max-pending-per-owner") { PENDING_LIMIT }
		}
	}
}
