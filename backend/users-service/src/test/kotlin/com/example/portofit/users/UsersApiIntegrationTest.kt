package com.example.portofit.users

import com.example.portofit.users.auth.TokenService
import com.example.portofit.users.outbox.OutboxRelay
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
import org.springframework.test.web.servlet.delete
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
	@Autowired lateinit var relay: OutboxRelay

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

	// --- Seguir ---

	@Test
	fun `seguir exige sessao e nao permite seguir-se a si proprio`() {
		val a = newSession()
		val b = newSession()
		mvc.put("/api/v1/users/${b.userId}/follow").andExpect {
			status { isUnauthorized() }
			jsonPath("$.code") { value("AUTHENTICATION_REQUIRED") }
		}
		mvc.delete("/api/v1/users/${b.userId}/follow").andExpect { status { isUnauthorized() } }
		mvc.put("/api/v1/users/${a.userId}/follow") { bearer(a.access) }.andExpect {
			status { isBadRequest() }
			jsonPath("$.code") { value("CANNOT_FOLLOW_SELF") }
		}
		mvc.put("/api/v1/users/${UUID.randomUUID()}/follow") { bearer(a.access) }.andExpect { status { isNotFound() } }
		mvc.delete("/api/v1/users/${UUID.randomUUID()}/follow") { bearer(a.access) }.andExpect { status { isNotFound() } }
		mvc.put("/api/v1/users/nao-e-uuid/follow") { bearer(a.access) }.andExpect { status { isBadRequest() } }
	}

	@Test
	fun `seguir e deixar de seguir sao idempotentes e atualizam contagens`() {
		val a = newSession()
		val b = newSession()
		repeat(2) { mvc.put("/api/v1/users/${b.userId}/follow") { bearer(a.access) }.andExpect { status { isNoContent() } } }
		mvc.get("/api/v1/users/${b.userId}").andExpect {
			jsonPath("$.followersCount") { value(1) }
			jsonPath("$.followingCount") { value(0) }
		}
		mvc.get("/api/v1/me") { bearer(a.access) }.andExpect {
			jsonPath("$.followingCount") { value(1) }
			jsonPath("$.followersCount") { value(0) }
		}
		repeat(2) { mvc.delete("/api/v1/users/${b.userId}/follow") { bearer(a.access) }.andExpect { status { isNoContent() } } }
		mvc.get("/api/v1/users/${b.userId}").andExpect { jsonPath("$.followersCount") { value(0) } }
	}

	@Test
	fun `pedidos simultaneos para seguir criam uma unica relacao`() {
		val a = newSession()
		val b = newSession()
		val statuses = concurrently(8) {
			HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/v1/users/${b.userId}/follow"))
					.header("Authorization", "Bearer ${a.access}").PUT(HttpRequest.BodyPublishers.noBody()).build(),
				HttpResponse.BodyHandlers.discarding(),
			).statusCode()
		}
		assertTrue(statuses.all { it == 204 }, "estados: $statuses")
		assertEquals(1L, jdbc.sql("SELECT count(*) FROM follow WHERE follower_id = :a AND followed_id = :b")
			.param("a", a.userId).param("b", b.userId).query(Long::class.javaObjectType).single())
	}

	@Test
	fun `seguido por mim so aparece com sessao de outra pessoa`() {
		val a = newSession()
		val b = newSession()
		// Visitante: o campo não existe (a relação não foi consultada).
		mvc.get("/api/v1/users/${b.userId}").andExpect { jsonPath("$.followedByMe") { doesNotExist() } }
		mvc.get("/api/v1/users/${b.userId}") { bearer(a.access) }.andExpect { jsonPath("$.followedByMe") { value(false) } }
		mvc.put("/api/v1/users/${b.userId}/follow") { bearer(a.access) }
		mvc.get("/api/v1/users/${b.userId}") { bearer(a.access) }.andExpect { jsonPath("$.followedByMe") { value(true) } }
		// O próprio perfil: omitido.
		mvc.get("/api/v1/users/${b.userId}") { bearer(b.access) }.andExpect { jsonPath("$.followedByMe") { doesNotExist() } }
		// Token inválido num pedido público não é tratado como visitante.
		mvc.get("/api/v1/users/${b.userId}") { bearer("lixo") }.andExpect { status { isUnauthorized() } }
	}

	@Test
	fun `listas de seguidores e seguidos paginadas, ordenadas e so com dados publicos`() {
		val target = newSession()
		val fans = (1..3).map { newSession() }
		fans.forEach { f -> mvc.put("/api/v1/users/${target.userId}/follow") { bearer(f.access) }.andExpect { status { isNoContent() } } }

		val page0 = mvc.get("/api/v1/users/${target.userId}/followers?size=2").andExpect {
			status { isOk() }
			jsonPath("$.totalItems") { value(3) }
			jsonPath("$.totalPages") { value(2) }
			jsonPath("$.items[0].since") { exists() }
			jsonPath("$.items[*].email") { doesNotExist() }
		}.andReturn().response.contentAsString
		assertFalse("@example.org" in page0)
		val page1 = mvc.get("/api/v1/users/${target.userId}/followers?size=2&page=1").andReturn().json()
		val ids = mapper.readTree(page0)["items"].let { n -> (0 until n.size()).map { n[it]["userId"].asString() } } +
			page1["items"].let { n -> (0 until n.size()).map { n[it]["userId"].asString() } }
		// Relação mais antiga primeiro: a ordem em que começaram a seguir.
		assertEquals(fans.map { it.userId.toString() }, ids)

		mvc.get("/api/v1/users/${fans[0].userId}/following").andExpect {
			jsonPath("$.items[0].userId") { value(target.userId.toString()) }
			jsonPath("$.items[0].username") { value(target.username) }
		}
		mvc.get("/api/v1/users/${UUID.randomUUID()}/followers").andExpect { status { isNotFound() } }
		mvc.get("/api/v1/users/${target.userId}/following?size=51").andExpect { status { isBadRequest() } }
	}

	// --- Limite de seguidos e contexto do feed (E4, docs/users-api.md §3.9 e §3.11) ---

	@Test
	fun `limite de pessoas seguidas aplicado ao seguir, tambem com pedidos simultaneos`() {
		val a = newSession()
		val targets = (1..MAX_FOLLOWING + 2).map { newSession() }
		targets.take(MAX_FOLLOWING).forEach { t -> mvc.put("/api/v1/users/${t.userId}/follow") { bearer(a.access) }.andExpect { status { isNoContent() } } }
		mvc.put("/api/v1/users/${targets.last().userId}/follow") { bearer(a.access) }.andExpect {
			status { isConflict() }
			jsonPath("$.code") { value("FOLLOW_LIMIT_REACHED") }
		}
		// Seguir de novo alguém já seguido continua 204 no limite; deixar de seguir abre espaço.
		mvc.put("/api/v1/users/${targets.first().userId}/follow") { bearer(a.access) }.andExpect { status { isNoContent() } }
		mvc.delete("/api/v1/users/${targets.first().userId}/follow") { bearer(a.access) }.andExpect { status { isNoContent() } }
		mvc.put("/api/v1/users/${targets.last().userId}/follow") { bearer(a.access) }.andExpect { status { isNoContent() } }
		assertEquals(MAX_FOLLOWING.toLong(), followingCount(a.userId))

		// Pedidos simultâneos de uma conta nova a seguir 6 pessoas: o limite não é ultrapassado.
		val b = newSession()
		val others = (1..6).map { newSession() }
		val statuses = concurrently(6) { i ->
			HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/v1/users/${others[i].userId}/follow"))
					.header("Authorization", "Bearer ${b.access}").PUT(HttpRequest.BodyPublishers.noBody()).build(),
				HttpResponse.BodyHandlers.ofString(),
			).statusCode()
		}
		assertEquals(MAX_FOLLOWING, statuses.count { it == 204 }, "estados: $statuses")
		assertEquals(6 - MAX_FOLLOWING, statuses.count { it == 409 }, "estados: $statuses")
		assertEquals(MAX_FOLLOWING.toLong(), followingCount(b.userId))
	}

	@Test
	fun `feed-context so com o token do proprio e a chave interna`() {
		val a = newSession()
		val b = newSession()
		val c = newSession()
		putInterests(a, "[\"boxe\",\"corrida\"]").andExpect { status { isOk() } }
		mvc.put("/api/v1/users/${b.userId}/follow") { bearer(a.access) }.andExpect { status { isNoContent() } }
		mvc.put("/api/v1/users/${c.userId}/follow") { bearer(a.access) }.andExpect { status { isNoContent() } }
		mvc.put("/api/v1/users/${a.userId}/follow") { bearer(b.access) }.andExpect { status { isNoContent() } }

		val path = "/api/v1/internal/me/feed-context"
		mvc.get(path) { header("X-Internal-Key", INTERNAL_KEY) }.andExpect {
			status { isUnauthorized() }
			jsonPath("$.code") { value("AUTHENTICATION_REQUIRED") }
		}
		mvc.get(path) { bearer("x.y.z"); header("X-Internal-Key", INTERNAL_KEY) }.andExpect {
			status { isUnauthorized() }
			jsonPath("$.code") { value("INVALID_ACCESS_TOKEN") }
		}
		mvc.get(path) { bearer(a.access) }.andExpect { status { isForbidden() }; jsonPath("$.code") { value("FORBIDDEN") } }
		mvc.get(path) { bearer(a.access); header("X-Internal-Key", INTERNAL_KEY + "x") }.andExpect { status { isForbidden() } }
		mvc.get(path) { bearer(a.access); header("X-Internal-Key", "") }.andExpect { status { isForbidden() } }

		val body = mvc.get(path) { bearer(a.access); header("X-Internal-Key", INTERNAL_KEY) }.andExpect {
			status { isOk() }
			header { string("Cache-Control", containsString("no-store")) }
			content { string(not(containsString("email"))) }
			content { string(not(containsString(b.username))) }
		}.andReturn().json()
		// Todos os seguidos, mais recentes primeiro; interesses pela ordem do perfil; só ids.
		assertEquals(listOf(c.userId.toString(), b.userId.toString()), texts(body["following"]))
		assertEquals(listOf("boxe", "corrida"), texts(body["interests"]))
		assertEquals(setOf("following", "interests"), body.propertyNames().toSet())

		// O contexto é sempre o do token: o de b é o de b, mesmo com a mesma chave.
		val ofB = mvc.get(path) { bearer(b.access); header("X-Internal-Key", INTERNAL_KEY) }.andExpect { status { isOk() } }.andReturn().json()
		assertEquals(listOf(a.userId.toString()), texts(ofB["following"]))
		assertEquals(0, ofB["interests"].size())
		// Não há forma de pedir o contexto de outra pessoa pelo caminho.
		mvc.get("/api/v1/internal/users/${a.userId}/feed-context") { bearer(b.access); header("X-Internal-Key", INTERNAL_KEY) }
			.andExpect { status { isNotFound() } }
	}

	// --- Notificações: evento de novo seguidor e seguidores internos (docs/notifications-api.md §3, §7, §8.1) ---

	private fun followEvents(recipient: UUID): List<JsonNode> =
		jdbc.sql("SELECT payload FROM outbox WHERE payload::jsonb ->> 'recipientId' = :r ORDER BY created_at")
			.param("r", recipient.toString()).query(String::class.java).list().map { mapper.readTree(it) }

	private fun follow(who: Session, target: UUID) =
		mvc.put("/api/v1/users/$target/follow") { bearer(who.access) }

	@Test
	fun `seguir grava um evento so por relacao nova, na mesma transacao e sem dados privados`() {
		val a = newSession()
		val b = newSession()
		follow(a, b.userId).andExpect { status { isNoContent() } }
		follow(a, b.userId).andExpect { status { isNoContent() } } // confirma a relação: sem evento
		val (event) = followEvents(b.userId)
		assertEquals(1, followEvents(b.userId).size)
		assertEquals(setOf("eventId", "type", "occurredAt", "actor", "recipientId"), event.propertyNames().toSet())
		assertEquals("USER_FOLLOWED", event["type"].asString())
		assertEquals(a.userId.toString(), event["actor"]["userId"].asString())
		assertEquals(a.username, event["actor"]["username"].asString())
		assertEquals(setOf("userId", "username"), event["actor"].propertyNames().toSet())
		val since = jdbc.sql("SELECT created_at FROM follow WHERE follower_id = :a AND followed_id = :b").param("a", a.userId).param("b", b.userId)
			.query { rs, _ -> rs.getTimestamp(1).toInstant() }.single()
		assertEquals(since, Instant.parse(event["occurredAt"].asString()), "occurredAt é a data da relação")
		assertFalse(event.toString().contains("@example.org"))
		// Recusas não gravam nada: a própria pessoa e o limite de seguidos.
		follow(a, a.userId).andExpect { status { isBadRequest() } }
		assertEquals(0, followEvents(a.userId).size)
		val full = newSession()
		val targets = (1..MAX_FOLLOWING + 1).map { newSession() }
		targets.take(MAX_FOLLOWING).forEach { follow(full, it.userId).andExpect { status { isNoContent() } } }
		follow(full, targets.last().userId).andExpect { status { isConflict() } }
		assertEquals(0, followEvents(targets.last().userId).size)
		// Deixar de seguir e voltar a seguir é uma relação nova: outro evento (o notifications-service junta-os, §6.3).
		mvc.delete("/api/v1/users/${b.userId}/follow") { bearer(a.access) }.andExpect { status { isNoContent() } }
		follow(a, b.userId).andExpect { status { isNoContent() } }
		assertEquals(2, followEvents(b.userId).size)
		// Pedidos simultâneos: uma relação, um evento.
		val c = newSession()
		val d = newSession()
		concurrently(8) {
			HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/v1/users/${d.userId}/follow"))
					.header("Authorization", "Bearer ${c.access}").PUT(HttpRequest.BodyPublishers.noBody()).build(),
				HttpResponse.BodyHandlers.ofString(),
			).statusCode()
		}
		assertEquals(1, followEvents(d.userId).size)
	}

	@Test
	fun `eventos de seguir esperam pelo notifications-service e o reenvio manual de um FAILED entrega uma vez`() {
		jdbc.sql("DELETE FROM outbox").update()
		notifications.reset()
		val a = newSession()
		val b = newSession()
		notifications.mode = NotificationsStub.Mode.DOWN
		follow(a, b.userId).andExpect { status { isNoContent() } } // seguir não espera pelas notificações
		makeOutboxDue()
		assertEquals(1, relay.runOnce())
		assertEquals("PENDING:1", outboxState())
		notifications.mode = NotificationsStub.Mode.UP
		makeOutboxDue()
		assertEquals(1, relay.runOnce())
		assertEquals("SENT:1", outboxState())
		val first = followEvents(b.userId).single()["eventId"].asString()
		assertEquals(1, notifications.accepted[first])

		// Um evento recusado passa a FAILED; depois de corrigida a causa, o procedimento documentado
		// (docs/infraestrutura.md, reenvio manual) volta a pô-lo PENDING e o envio seguinte entrega-o uma vez.
		jdbc.sql("DELETE FROM outbox").update()
		val c = newSession()
		notifications.mode = NotificationsStub.Mode.REJECT
		follow(c, b.userId).andExpect { status { isNoContent() } }
		makeOutboxDue()
		relay.runOnce()
		val failed = jdbc.sql("SELECT event_id FROM outbox WHERE status = 'FAILED' AND last_error = 'REJECTED/INVALID_EVENT'").query(UUID::class.java).single()
		notifications.mode = NotificationsStub.Mode.UP
		makeOutboxDue()
		assertEquals(0, relay.runOnce(), "um FAILED nunca é repetido sozinho")
		val requeued = jdbc.sql(
			"UPDATE outbox SET status = 'PENDING', attempts = 0, next_attempt_at = now(), last_error = NULL WHERE status = 'FAILED' AND event_id = '$failed'",
		).update()
		assertEquals(1, requeued)
		makeOutboxDue()
		assertEquals(1, relay.runOnce())
		assertEquals("SENT:0", outboxState())
		assertEquals(1, notifications.accepted[failed.toString()])
	}

	/** O `now()` da base pode ir uns milissegundos à frente do relógio da JVM usado pelo envio. */
	private fun makeOutboxDue() {
		jdbc.sql("UPDATE outbox SET next_attempt_at = now() - interval '1 minute' WHERE status = 'PENDING'").update()
	}

	private fun outboxState(): String =
		jdbc.sql("SELECT string_agg(status || ':' || attempts, ',') FROM outbox").query(String::class.java).single()

	private fun followers(target: UUID, query: String, key: String? = FOLLOWERS_KEY, token: String? = null) =
		mvc.get("/api/v1/internal/users/$target/followers$query") {
			if (key != null) header("X-Internal-Key", key)
			if (token != null) bearer(token)
		}

	private fun followerIds(target: UUID, query: String): Pair<List<String>, String?> {
		val body = followers(target, query).andExpect { status { isOk() } }.andReturn().json()
		assertEquals(setOf("items", "nextCursor"), body.propertyNames().toSet())
		return texts(body["items"]) to body["nextCursor"]?.takeUnless { it.isNull }?.asString()
	}

	@Test
	fun `seguidores internos so com a chave propria, paginacao estavel e recorte temporal`() {
		val target = newSession()
		val f = (1..8).map { newSession() }
		f.take(7).forEach { follow(it, target.userId).andExpect { status { isNoContent() } } }
		val now = Instant.now().plusSeconds(1)

		// Autorização: só a chave do notifications-service; nem a do feed-context, nem um token.
		for (key in listOf(null, "", "errada", INTERNAL_KEY)) {
			followers(target.userId, "?followedBefore=$now", key).andExpect {
				status { isForbidden() }
				jsonPath("$.code") { value("FORBIDDEN") }
			}
		}
		followers(target.userId, "?followedBefore=$now", key = null, token = target.access).andExpect { status { isForbidden() } }
		followers(UUID.randomUUID(), "?followedBefore=$now").andExpect { status { isNotFound() } }
		for (q in listOf("", "?followedBefore=ontem", "?followedBefore=$now&limit=0", "?followedBefore=$now&limit=501", "?followedBefore=$now&cursor=xyz")) {
			followers(target.userId, q).andExpect { status { isBadRequest() } }
		}

		// Páginas de 3, pela ordem da relação; só ids.
		val (page1, cursor1) = followerIds(target.userId, "?followedBefore=$now&limit=3")
		assertEquals(f.take(3).map { it.userId.toString() }, page1)
		// Entre páginas: alguém já lido e alguém por ler deixam de seguir; alguém começa depois da publicação.
		mvc.delete("/api/v1/users/${target.userId}/follow") { bearer(f[1].access) }.andExpect { status { isNoContent() } }
		mvc.delete("/api/v1/users/${target.userId}/follow") { bearer(f[4].access) }.andExpect { status { isNoContent() } }
		Thread.sleep(1100) // a relação seguinte fica depois de followedBefore
		follow(f[7], target.userId).andExpect { status { isNoContent() } }
		val (page2, cursor2) = followerIds(target.userId, "?followedBefore=$now&limit=3&cursor=$cursor1")
		assertEquals(listOf(f[3], f[5], f[6]).map { it.userId.toString() }, page2, "sem repetir nem saltar ninguém")
		assertEquals(null, cursor2, "fim: sem mais seguidores até followedBefore")

		// Recorte: followedBefore igual à data de uma relação inclui-a, e nada depois dela.
		val third = jdbc.sql("SELECT created_at FROM follow WHERE follower_id = :f AND followed_id = :t").param("f", f[2].userId).param("t", target.userId)
			.query { rs, _ -> rs.getTimestamp(1).toInstant() }.single()
		assertEquals(listOf(f[0], f[2]).map { it.userId.toString() } to null, followerIds(target.userId, "?followedBefore=$third"))
		// Sem seguidores: 200 com items vazio (a única forma de concluir que não há).
		assertEquals(emptyList<String>() to null, followerIds(f[0].userId, "?followedBefore=$now"))
	}

	// --- Auxiliares ---

	private fun texts(node: JsonNode): List<String> = (0 until node.size()).map { node[it].asString() }

	private fun followingCount(userId: UUID): Long =
		jdbc.sql("SELECT count(*) FROM follow WHERE follower_id = :id").param("id", userId).query(Long::class.javaObjectType).single()

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
		/** Limite de seguidos nos testes (o real é 500) e chave interna só dos testes. */
		const val MAX_FOLLOWING = 3
		const val INTERNAL_KEY = "chave-interna-dos-testes"
		const val FOLLOWERS_KEY = "chave-seguidores-dos-testes"

		private val keys = TestKeys.create()
		val catalog = CatalogStub.start()
		val notifications = NotificationsStub.start()

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
			registry.add("portofit.follows.max-following") { MAX_FOLLOWING }
			registry.add("portofit.internal.feed-context-key") { INTERNAL_KEY }
			registry.add("portofit.internal.followers-key") { FOLLOWERS_KEY }
			// A outbox é enviada pelos próprios testes (runOnce), nunca pelo agendamento.
			registry.add("portofit.outbox.enabled") { false }
			registry.add("portofit.notifications.base-url") { notifications.url }
			registry.add("portofit.notifications.internal-key") { NotificationsStub.KEY }
			registry.add("portofit.notifications.timeout") { "1s" }
		}
	}
}
