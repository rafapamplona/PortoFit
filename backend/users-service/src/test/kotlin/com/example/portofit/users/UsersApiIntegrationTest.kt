package com.example.portofit.users

import com.example.portofit.users.auth.TokenService
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.SecurityContext
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Testes de integração do users-service contra PostgreSQL real (Testcontainers), com chaves RSA
 * próprias dos testes e um falso catalog-service. BCrypt com custo 4 só para os testes serem rápidos.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class UsersApiIntegrationTest {

	@Autowired lateinit var mvc: MockMvc
	@Autowired lateinit var mapper: JsonMapper
	@Autowired lateinit var jdbc: JdbcClient
	@Autowired lateinit var tokens: TokenService
	@Autowired lateinit var jwtEncoder: JwtEncoder
	@Autowired lateinit var signingKey: RSAKey

	@LocalServerPort var port: Int = 0

	private val password = "uma frase longa e minha"

	// --- Registo ---

	@Test
	fun `registo cria a conta e inicia sessao sem expor o hash`() {
		val name = uniqueName()
		mvc.post("/api/v1/auth/register") { json("""{"username":"  ${name.uppercase()} ","email":"${name.uppercase()}@Example.org","password":"$password"}""") }
			.andExpect {
				status { isCreated() }
				header { string("Location", containsString("/api/v1/users/")) }
				jsonPath("$.tokenType") { value("Bearer") }
				jsonPath("$.accessTokenExpiresIn") { value(900) }
				jsonPath("$.user.username") { value(name) } // normalizado para minúsculas
				jsonPath("$.user.email") { value("$name@example.org") }
				jsonPath("$.user.createdAt") { exists() }
				content { string(not(containsString("bcrypt"))) }
				content { string(not(containsString(password))) }
			}
		val stored = jdbc.sql("SELECT password_hash FROM app_user WHERE username = :u").param("u", name).query(String::class.java).single()
		assertTrue(stored.startsWith("{bcrypt}"))
	}

	@Test
	fun `validacao do registo`() {
		expectFieldErrors(register("ab", "x@example.org", password), "username")
		expectFieldErrors(register("demo_ana", "y@example.org", password), "username")
		expectFieldErrors(register("Ana!", "z@example.org", password), "username")
		expectFieldErrors(register(uniqueName(), "sem-arroba", password), "email")
		expectFieldErrors(register(uniqueName(), "${uniqueName()}@example.org", "curta"), "password")
		// 40 caracteres mas 80 bytes em UTF-8: rejeitada, nunca truncada.
		expectFieldErrors(register(uniqueName(), "${uniqueName()}@example.org", "ã".repeat(40)), "password")
		val same = uniqueName()
		expectFieldErrors(register(same + "xyz", "$same@example.org", "$same@example.org"), "password")
		expectFieldErrors(register(null, null, null), "username", "email", "password")
	}

	@Test
	fun `username e email repetidos dao 409, sem distinguir maiusculas`() {
		val name = uniqueName()
		register(name, "$name@example.org", password).andExpect { status { isCreated() } }

		register(name, "outro-$name@example.org", password).andExpect {
			status { isConflict() }
			jsonPath("$.code") { value("CONFLICT") }
			jsonPath("$.errors[*].field") { value(containsInAnyOrder("username")) }
		}
		register("outro$name", "${name.uppercase()}@EXAMPLE.ORG", password).andExpect {
			status { isConflict() }
			jsonPath("$.errors[*].field") { value(containsInAnyOrder("email")) }
		}
	}

	@Test
	fun `registos simultaneos com o mesmo username criam uma so conta`() {
		val name = uniqueName()
		val statuses = concurrently(8) { i ->
			httpPost("/api/v1/auth/register", """{"username":"$name","email":"$name$i@example.org","password":"$password"}""").statusCode()
		}
		assertEquals(1, statuses.count { it == 201 }, "estados: $statuses")
		assertEquals(7, statuses.count { it == 409 }, "estados: $statuses")
		assertEquals(1L, jdbc.sql("SELECT count(*) FROM app_user WHERE username = :u").param("u", name).query(Long::class.javaObjectType).single())
	}

	// --- Login ---

	@Test
	fun `login correto e respostas iguais para email inexistente e palavra-passe errada`() {
		val (name, email) = newAccount()
		mvc.post("/api/v1/auth/login") { json("""{"email":"${email.uppercase()}","password":"$password"}""") }.andExpect {
			status { isOk() }
			jsonPath("$.user.username") { value(name) }
			jsonPath("$.refreshToken") { exists() }
		}

		val wrongPassword = login(email, "palavra-passe errada").andExpect { status { isUnauthorized() } }.andReturn().response.contentAsString
		val unknownEmail = login("ninguem-${uniqueName()}@example.org", password).andExpect { status { isUnauthorized() } }.andReturn().response.contentAsString
		assertEquals(wrongPassword, unknownEmail)
		assertTrue("INVALID_CREDENTIALS" in wrongPassword)
	}

	@Test
	fun `limite de logins falhados por conta bloqueia mesmo com a palavra-passe certa`() {
		val (_, email) = newAccount()
		repeat(5) { login(email, "errada-errada").andExpect { status { isUnauthorized() } } }
		login(email, password).andExpect {
			status { isTooManyRequests() }
			header { exists("Retry-After") }
			jsonPath("$.code") { value("TOO_MANY_ATTEMPTS") }
		}
	}

	// --- Access token ---

	@Test
	fun `perfil proprio exige token valido`() {
		mvc.get("/api/v1/me").andExpect {
			status { isUnauthorized() }
			jsonPath("$.code") { value("AUTHENTICATION_REQUIRED") }
		}
		expectInvalidToken("lixo")
		val user = UUID.randomUUID()
		// Expirado há 45 min (emitido há 1 h com validade de 15 min).
		expectInvalidToken(tokens.accessToken(user, "x", Instant.now().minusSeconds(3600)))
		expectInvalidToken(signed(jwtEncoder, issuer = "outro-emissor", audience = "portofit-api", subject = user))
		expectInvalidToken(signed(jwtEncoder, issuer = "portofit-users", audience = "outro-servico", subject = user))
		// Assinado com uma chave diferente.
		val foreign = TestKeys.generate()
		val foreignEncoder = NimbusJwtEncoder(
			ImmutableJWKSet<SecurityContext>(JWKSet(RSAKey.Builder(foreign.public as RSAPublicKey).privateKey(foreign.private as RSAPrivateKey).keyID(signingKey.keyID).build())),
		)
		expectInvalidToken(signed(foreignEncoder, issuer = "portofit-users", audience = "portofit-api", subject = user))
		// Sem assinatura (alg "none").
		val b64 = Base64.getUrlEncoder().withoutPadding()
		val unsigned = b64.encodeToString("""{"alg":"none"}""".toByteArray()) + "." +
			b64.encodeToString("""{"iss":"portofit-users","aud":"portofit-api","sub":"$user","exp":${Instant.now().epochSecond + 600}}""".toByteArray()) + "."
		expectInvalidToken(unsigned)
	}

	@Test
	fun `editar o perfil so altera o proprio e so a descricao`() {
		val a = newSession()
		val b = newSession()
		mvc.patch("/api/v1/me") { bearer(a.access); json("""{"description":"  Corro ao fim de semana.  "}""") }.andExpect {
			status { isOk() }
			jsonPath("$.description") { value("Corro ao fim de semana.") }
		}
		mvc.get("/api/v1/me") { bearer(b.access) }.andExpect { jsonPath("$.description") { value(null as Any?) } }

		mvc.patch("/api/v1/me") { bearer(a.access); json("""{"description":"x","username":"outro","email":"o@example.org"}""") }.andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[*].field") { value(containsInAnyOrder("username", "email")) }
		}
		mvc.patch("/api/v1/me") { bearer(a.access); json("""{"description":"${"a".repeat(301)}"}""") }.andExpect { status { isBadRequest() } }
		mvc.patch("/api/v1/me") { bearer(a.access); json("""{"description":null}""") }.andExpect { jsonPath("$.description") { value(null as Any?) } }
	}

	// --- Privacidade ---

	@Test
	fun `perfil publico e pesquisa nao expoem email, hash nem data de registo`() {
		val s = newSession()
		val publicBody = mvc.get("/api/v1/users/${s.userId}").andExpect {
			status { isOk() }
			jsonPath("$.username") { value(s.username) }
			jsonPath("$.email") { doesNotExist() }
			jsonPath("$.createdAt") { doesNotExist() }
			jsonPath("$.passwordHash") { doesNotExist() }
		}.andReturn().response.contentAsString
		assertFalse("@example.org" in publicBody)

		mvc.get("/api/v1/users?q=${s.username.uppercase()}").andExpect {
			status { isOk() }
			jsonPath("$.items[0].username") { value(s.username) } // correspondência exata primeiro
			jsonPath("$.items[*].email") { doesNotExist() }
		}
		mvc.get("/api/v1/me") { bearer(s.access) }.andExpect {
			jsonPath("$.email") { value("${s.username}@example.org") }
			jsonPath("$.createdAt") { exists() }
		}
		mvc.get("/api/v1/users/${UUID.randomUUID()}").andExpect { status { isNotFound() } }
		mvc.get("/api/v1/users/nao-e-uuid").andExpect { status { isBadRequest() } }
		mvc.get("/api/v1/users?q=a").andExpect { status { isBadRequest() } }
	}

	// --- Refresh tokens ---

	@Test
	fun `rotacao do refresh token e reutilizacao revoga a sessao`() {
		val s = newSession()
		val first = refresh(s.refresh).andExpect {
			status { isOk() }
			jsonPath("$.user") { doesNotExist() }
		}.andReturn().json()
		val newRefresh = first["refreshToken"].asString()
		assertNotEquals(s.refresh, newRefresh)
		// A rotação não prolonga o prazo máximo da sessão.
		assertTrue(first["refreshTokenExpiresIn"].asLong() <= s.refreshExpiresIn)

		// Reutilizar o token antigo: 401 e a família inteira fica revogada, incluindo o token novo.
		refresh(s.refresh).andExpect {
			status { isUnauthorized() }
			jsonPath("$.code") { value("INVALID_REFRESH_TOKEN") }
		}
		refresh(newRefresh).andExpect { status { isUnauthorized() } }
	}

	@Test
	fun `logout revoga a sessao, e idempotente e ignora um access token expirado`() {
		val s = newSession()
		mvc.post("/api/v1/auth/logout") {
			header("Authorization", "Bearer ${tokens.accessToken(s.userId, s.username, Instant.now().minusSeconds(3600))}")
			json("""{"refreshToken":"${s.refresh}"}""")
		}.andExpect { status { isNoContent() } }
		refresh(s.refresh).andExpect { status { isUnauthorized() } }
		mvc.post("/api/v1/auth/logout") { json("""{"refreshToken":"${s.refresh}"}""") }.andExpect { status { isNoContent() } }
		mvc.post("/api/v1/auth/logout") { json("""{"refreshToken":"desconhecido"}""") }.andExpect { status { isNoContent() } }
	}

	@Test
	fun `sessao expirada nao pode ser renovada`() {
		val s = newSession()
		jdbc.sql("UPDATE refresh_token SET expires_at = now() - interval '1 minute' WHERE user_id = :u").param("u", s.userId).update()
		refresh(s.refresh).andExpect { status { isUnauthorized() } }
	}

	@Test
	fun `renovacoes simultaneas com o mesmo token nunca dao dois tokens novos`() {
		val s = newSession()
		val ok = AtomicInteger()
		val statuses = concurrently(6) {
			httpPost("/api/v1/auth/refresh", """{"refreshToken":"${s.refresh}"}""").statusCode().also { if (it == 200) ok.incrementAndGet() }
		}
		assertTrue(ok.get() <= 1, "estados: $statuses")
		assertTrue(statuses.all { it == 200 || it == 401 }, "estados: $statuses")
	}

	// --- Interesses ---

	@Test
	fun `interesses validados no catalogo, com degradacao quando o catalogo falha`() {
		val s = newSession()
		putInterests(s, """["corrida","boxe"]""").andExpect {
			status { isOk() }
			jsonPath("$.interests") { value(contains("corrida", "boxe")) }
		}
		putInterests(s, """["boxe","quidditch"]""").andExpect {
			status { isBadRequest() }
			jsonPath("$.code") { value("UNKNOWN_INTERESTS") }
		}
		putInterests(s, """["boxe","boxe"]""").andExpect { status { isBadRequest() } }
		putInterests(s, """["Boxe!"]""").andExpect { status { isBadRequest() } }
		putInterests(s, (1..11).joinToString(",", "[", "]") { "\"m$it\"" }).andExpect { status { isBadRequest() } }

		catalog.available = false
		try {
			// Acrescentar uma modalidade nova exige o catálogo: 503 e nada muda.
			putInterests(s, """["corrida","boxe","ioga"]""").andExpect {
				status { isServiceUnavailable() }
				jsonPath("$.code") { value("CATALOG_UNAVAILABLE") }
			}
			mvc.get("/api/v1/me") { bearer(s.access) }.andExpect { jsonPath("$.interests") { value(contains("corrida", "boxe")) } }
			// Só remover, reordenar ou esvaziar não depende do catálogo.
			putInterests(s, """["boxe"]""").andExpect { status { isOk() } }
			putInterests(s, """[]""").andExpect { status { isOk() } }
			// Ler perfis e editar a descrição continuam a funcionar.
			mvc.get("/api/v1/users/${s.userId}").andExpect { status { isOk() } }
			mvc.patch("/api/v1/me") { bearer(s.access); json("""{"description":"ok"}""") }.andExpect { status { isOk() } }
		} finally {
			catalog.available = true
		}
	}

	@Test
	fun `jwks publica so a chave publica`() {
		mvc.get("/.well-known/jwks.json").andExpect {
			status { isOk() }
			jsonPath("$.keys[0].kid") { value(signingKey.keyID) }
			jsonPath("$.keys[0].kty") { value("RSA") }
			jsonPath("$.keys[0].d") { doesNotExist() }
			jsonPath("$.keys[0].p") { doesNotExist() }
		}
	}

	// --- Auxiliares ---

	data class Session(val userId: UUID, val username: String, val access: String, val refresh: String, val refreshExpiresIn: Long)

	private fun uniqueName() = "u" + UUID.randomUUID().toString().replace("-", "").take(12)

	private fun newAccount(): Pair<String, String> {
		val name = uniqueName()
		register(name, "$name@example.org", password).andExpect { status { isCreated() } }
		return name to "$name@example.org"
	}

	private fun newSession(): Session {
		val name = uniqueName()
		val body = register(name, "$name@example.org", password).andExpect { status { isCreated() } }.andReturn().json()
		return Session(
			UUID.fromString(body["user"]["userId"].asString()), name,
			body["accessToken"].asString(), body["refreshToken"].asString(), body["refreshTokenExpiresIn"].asLong(),
		)
	}

	private fun register(username: String?, email: String?, password: String?): ResultActionsDsl {
		val fields = listOfNotNull(
			username?.let { "\"username\":\"$it\"" }, email?.let { "\"email\":\"$it\"" }, password?.let { "\"password\":\"$it\"" },
		)
		return mvc.post("/api/v1/auth/register") { json(fields.joinToString(",", "{", "}")) }
	}

	private fun login(email: String, password: String) =
		mvc.post("/api/v1/auth/login") { json("""{"email":"$email","password":"$password"}""") }

	private fun refresh(token: String) = mvc.post("/api/v1/auth/refresh") { json("""{"refreshToken":"$token"}""") }

	private fun putInterests(s: Session, list: String) =
		mvc.put("/api/v1/me/interests") { bearer(s.access); json("""{"interests":$list}""") }

	private fun expectFieldErrors(result: ResultActionsDsl, vararg fields: String) {
		result.andExpect {
			status { isBadRequest() }
			content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
			jsonPath("$.code") { value("VALIDATION_FAILED") }
			fields.forEach { f -> jsonPath("$.errors[*].field") { value(hasItem(f)) } }
		}
	}

	private fun expectInvalidToken(token: String) {
		mvc.get("/api/v1/me") { bearer(token) }.andExpect {
			status { isUnauthorized() }
			header { string("WWW-Authenticate", containsString("invalid_token")) }
			jsonPath("$.code") { value("INVALID_ACCESS_TOKEN") }
		}
	}

	private fun signed(encoder: JwtEncoder, issuer: String, audience: String, subject: UUID): String {
		val now = Instant.now()
		val claims = JwtClaimsSet.builder().issuer(issuer).audience(listOf(audience)).subject(subject.toString())
			.issuedAt(now).expiresAt(now.plusSeconds(600)).build()
		val header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(signingKey.keyID).build()
		return encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
	}

	private fun org.springframework.test.web.servlet.MockHttpServletRequestDsl.json(body: String) {
		contentType = MediaType.APPLICATION_JSON
		content = body
	}

	private fun org.springframework.test.web.servlet.MockHttpServletRequestDsl.bearer(token: String) {
		header("Authorization", "Bearer $token")
	}

	private fun org.springframework.test.web.servlet.MvcResult.json(): JsonNode = mapper.readTree(response.contentAsString)

	private fun httpPost(path: String, body: String): HttpResponse<String> =
		HttpClient.newHttpClient().send(
			HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
			HttpResponse.BodyHandlers.ofString(),
		)

	/** Corre [n] chamadas ao mesmo tempo (todas esperam por um sinal comum antes de começar). */
	private fun <T> concurrently(n: Int, task: (Int) -> T): List<T> {
		val pool = Executors.newFixedThreadPool(n)
		val start = CountDownLatch(1)
		try {
			val futures = (0 until n).map { i -> pool.submit(Callable { start.await(); task(i) }) }
			start.countDown()
			return futures.map { it.get() }
		} finally {
			pool.shutdownNow()
		}
	}

	companion object {
		private val keys = TestKeys.create()
		val catalog = CatalogStub.start()

		@JvmStatic
		@DynamicPropertySource
		fun properties(registry: DynamicPropertyRegistry) {
			registry.add("portofit.security.private-key-location") { keys.privateKeyUri }
			registry.add("portofit.security.public-key-location") { keys.publicKeyUri }
			registry.add("portofit.security.bcrypt-strength") { 4 }
			registry.add("portofit.catalog.base-url") { catalog.url }
			registry.add("portofit.catalog.cache-ttl") { "0s" }
			// Limites por origem altos: nos testes todos os pedidos vêm de 127.0.0.1 (testados à parte).
			registry.add("portofit.rate-limit.login-attempts-per-origin") { 100000 }
			registry.add("portofit.rate-limit.register-per-origin") { 100000 }
			registry.add("portofit.rate-limit.refresh-per-origin") { 100000 }
		}
	}
}
