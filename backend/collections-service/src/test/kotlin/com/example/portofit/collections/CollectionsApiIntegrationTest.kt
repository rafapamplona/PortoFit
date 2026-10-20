package com.example.portofit.collections

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.SecurityContext
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.AfterEach
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
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
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
import java.security.KeyPair
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Testes de integração do collections-service contra PostgreSQL real (Testcontainers), com um falso
 * experiences-service e tokens assinados por uma chave só dos testes. Cada teste usa donos novos.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class CollectionsApiIntegrationTest {

	@Autowired lateinit var mvc: MockMvc
	@Autowired lateinit var mapper: JsonMapper
	@Autowired lateinit var jdbc: JdbcClient

	@LocalServerPort var port: Int = 0

	@AfterEach
	fun restore() {
		experiences.mode = ExperiencesStub.Mode.UP
	}

	// --- Autenticação e privacidade ---

	@Test
	fun `sem token ou com token invalido nada responde`() {
		mvc.get("/api/v1/collections").andExpect { status { isUnauthorized() }; jsonPath("$.code") { value("AUTHENTICATION_REQUIRED") } }
		val forged = sign(UUID.randomUUID(), keyPair = TestKeys.generate())
		mvc.get("/api/v1/collections") { bearer(forged) }.andExpect { status { isUnauthorized() }; jsonPath("$.code") { value("INVALID_ACCESS_TOKEN") } }
		val otherAudience = sign(UUID.randomUUID(), audience = "outro")
		mvc.get("/api/v1/collections") { bearer(otherAudience) }.andExpect { status { isUnauthorized() } }
		mvc.get("/actuator/health").andExpect { status { isOk() } }
	}

	@Test
	fun `listas de outra pessoa respondem como inexistentes em todas as operacoes`() {
		val ana = newOwner()
		val rui = newOwner()
		val id = createId(ana, "Privada da Ana")
		val experience = experiences.available()
		add(ana, id, experience).andExpect { status { isNoContent() } }
		val unknown = UUID.randomUUID()

		for (target in listOf(id, unknown.toString())) {
			val results = listOf(
				mvc.get("/api/v1/collections/$target") { bearer(rui) },
				mvc.patch("/api/v1/collections/$target") { json("""{"name":"Roubada"}"""); bearer(rui) },
				mvc.get("/api/v1/collections/$target/items") { bearer(rui) },
				mvc.put("/api/v1/collections/$target/items/$experience") { bearer(rui) },
				mvc.delete("/api/v1/collections/$target/items/$experience") { bearer(rui) },
				mvc.delete("/api/v1/collections/$target") { bearer(rui) },
			)
			results.forEach { r ->
				r.andExpect {
					status { isNotFound() }
					jsonPath("$.code") { value("NOT_FOUND") }
					content { string(not(containsString("Privada da Ana"))) }
				}
			}
		}
		// Nada mudou para a Ana; a lista do Rui não mostra a da Ana; nenhuma resposta tem o dono.
		mvc.get("/api/v1/collections/$id") { bearer(ana) }.andExpect {
			status { isOk() }
			jsonPath("$.name") { value("Privada da Ana") }
			jsonPath("$.itemCount") { value(1) }
			jsonPath("$.ownerId") { doesNotExist() }
		}
		assertEquals(0, json("/api/v1/collections", rui)["totalItems"].asInt())
	}

	// --- Criar ---

	@Test
	fun `criar valida o nome e e idempotente pela chave`() {
		val ana = newOwner()
		for (bad in listOf("", "   \n ", "a".repeat(61), "com\u0007controlo", "linha\nnova")) expectFieldErrors(create(ana, bad), "name")
		expectFieldErrors(createRaw(ana, """{"name":42}"""), "name")
		expectFieldErrors(createRaw(ana, """{"name":"Ok","ownerId":"${UUID.randomUUID()}"}"""), "ownerId")
		expectFieldErrors(createRaw(ana, "[]"), "body")
		expectFieldErrors(createRaw(ana, """{"name":" "}""", key = null), "name", "Idempotency-Key")
		create(ana, "💪".repeat(60)).andExpect { status { isCreated() } }

		val key = UUID.randomUUID().toString()
		val first = create(ana, "  Para experimentar  ", key).andExpect {
			status { isCreated() }
			header { string("Location", containsString("/api/v1/collections/")) }
			jsonPath("$.name") { value("Para experimentar") }
			jsonPath("$.itemCount") { value(0) }
		}.json()
		val id = first["id"].asString()
		assertEquals(7, UUID.fromString(id).version())
		create(ana, "Para experimentar", key).andExpect {
			status { isOk() }
			header { string("Idempotent-Replayed", "true") }
			jsonPath("$.id") { value(id) }
		}
		create(ana, "Outro nome", key).andExpect {
			status { isConflict() }
			jsonPath("$.code") { value("IDEMPOTENCY_CONFLICT") }
			jsonPath("$.reason") { value("DIFFERENT_CONTENT") }
			jsonPath("$.collectionId") { value(id) }
		}
		// Renomeada entretanto: a repetição devolve a mesma lista.
		mvc.patch("/api/v1/collections/$id") { json("""{"name":"Renomeada"}"""); bearer(ana) }.andExpect { status { isOk() } }
		create(ana, "Para experimentar", key).andExpect { status { isOk() }; jsonPath("$.name") { value("Renomeada") } }
		// Eliminada: a repetição não a recria.
		mvc.delete("/api/v1/collections/$id") { bearer(ana) }.andExpect { status { isNoContent() } }
		create(ana, "Para experimentar", key).andExpect {
			status { isConflict() }
			jsonPath("$.reason") { value("COLLECTION_DELETED") }
		}
	}

	@Test
	fun `nome unico por dono sem distinguir maiusculas, e uma recusa nao gasta a chave`() {
		val ana = newOwner()
		val rui = newOwner()
		createId(ana, "Treinos")
		create(ana, "TREINOS").andExpect { status { isConflict() }; jsonPath("$.code") { value("NAME_TAKEN") } }
		create(rui, "treinos").andExpect { status { isCreated() } }

		val key = UUID.randomUUID().toString()
		create(ana, "treinos", key).andExpect { status { isConflict() } }
		create(ana, "Corridas", key).andExpect { status { isCreated() } }

		// Renomear: só maiúsculas no próprio nome é permitido; o nome de outra lista não.
		val corridas = (json("/api/v1/collections", ana)["items"].elements.first { it["name"].asString() == "Corridas" })["id"].asString()
		val before = json("/api/v1/collections/$corridas", ana)["updatedAt"].asString()
		mvc.patch("/api/v1/collections/$corridas") { json("""{"name":"CORRIDAS"}"""); bearer(ana) }.andExpect { status { isOk() }; jsonPath("$.name") { value("CORRIDAS") } }
		assertNotEquals(before, json("/api/v1/collections/$corridas", ana)["updatedAt"].asString())
		mvc.patch("/api/v1/collections/$corridas") { json("""{"name":"treinos"}"""); bearer(ana) }.andExpect { status { isConflict() }; jsonPath("$.code") { value("NAME_TAKEN") } }
		mvc.patch("/api/v1/collections/$corridas") { json("""{"name":"CORRIDAS"}"""); bearer(ana) }.andExpect { status { isOk() } }
		expectFieldErrors(mvc.patch("/api/v1/collections/$corridas") { json("""{"name":"  "}"""); bearer(ana) }, "name")
	}

	@Test
	fun `pedidos simultaneos nao duplicam listas e o limite nao e ultrapassado`() {
		val ana = newOwner()
		val key = UUID.randomUUID().toString()
		val sameKey = concurrently(8) { http("POST", "/api/v1/collections", ana, """{"name":"Mesma chave"}""", key) }
		assertEquals(1, sameKey.count { it.statusCode() == 201 }, sameKey.map { it.statusCode() }.toString())
		assertEquals(7, sameKey.count { it.statusCode() == 200 })

		val rui = newOwner()
		val sameName = concurrently(8) { http("POST", "/api/v1/collections", rui, """{"name":"Mesmo nome"}""", UUID.randomUUID().toString()) }
		assertEquals(1, sameName.count { it.statusCode() == 201 }, sameName.map { it.statusCode() }.toString())
		assertEquals(7, sameName.count { it.statusCode() == 409 })

		val eva = newOwner()
		val many = concurrently(6) { i -> http("POST", "/api/v1/collections", eva, """{"name":"Lista $i"}""", UUID.randomUUID().toString()) }
		assertEquals(MAX_COLLECTIONS, many.count { it.statusCode() == 201 }, many.map { it.statusCode() }.toString())
		assertTrue(many.filter { it.statusCode() != 201 }.all { it.body().contains("COLLECTION_LIMIT_REACHED") })
		assertEquals(MAX_COLLECTIONS.toLong(), count("SELECT count(*) FROM collection WHERE owner_id = :o", eva.owner))
	}

	// --- Listar, eliminar ---

	@Test
	fun `listas paginadas mais recentes primeiro e eliminar nao afeta experiencias`() {
		val ana = newOwner()
		val ids = (1..3).map { createId(ana, "Lista $it") }
		val page0 = json("/api/v1/collections?size=2", ana)
		assertEquals(listOf(ids[2], ids[1]), page0["items"].elements.map { it["id"].asString() })
		assertEquals(3, page0["totalItems"].asInt())
		assertEquals(2, page0["totalPages"].asInt())
		assertEquals(listOf(ids[0]), json("/api/v1/collections?size=2&page=1", ana)["items"].elements.map { it["id"].asString() })
		assertEquals(0, json("/api/v1/collections?page=9", ana)["items"].size())
		expectFieldErrors(mvc.get("/api/v1/collections?page=-1&size=51") { bearer(ana) }, "page", "size")
		expectFieldErrors(mvc.get("/api/v1/collections/nao-e-uuid") { bearer(ana) }, "collectionId")

		val experience = experiences.available()
		add(ana, ids[0], experience).andExpect { status { isNoContent() } }
		experiences.requests.clear()
		mvc.delete("/api/v1/collections/${ids[0]}") { bearer(ana) }.andExpect { status { isNoContent() } }
		mvc.delete("/api/v1/collections/${ids[0]}") { bearer(ana) }.andExpect { status { isNotFound() } }
		assertTrue(experiences.requests.isEmpty(), "eliminar uma lista não toca nas experiências")
		assertEquals(0L, count("SELECT count(*) FROM collection_item WHERE collection_id = :o", UUID.fromString(ids[0])))
	}

	// --- Itens ---

	@Test
	fun `guardar e remover sao idempotentes, com ordem, paginacao e uma consulta em lote por pagina`() {
		val ana = newOwner()
		val id = createId(ana, "Guardadas")
		val e1 = experiences.available()
		val e2 = experiences.available()
		val e3 = experiences.available()
		listOf(e1, e2, e3).forEach { add(ana, id, it).andExpect { status { isNoContent() } } }

		// Já guardada: 204 sem consultar o experiences-service (também com ele parado).
		experiences.requests.clear()
		experiences.mode = ExperiencesStub.Mode.ERROR
		add(ana, id, e1).andExpect { status { isNoContent() } }
		assertTrue(experiences.requests.isEmpty())
		experiences.mode = ExperiencesStub.Mode.UP
		assertEquals(3, json("/api/v1/collections/$id", ana)["itemCount"].asInt())

		val page = json("/api/v1/collections/$id/items?size=2", ana)
		assertEquals(listOf(e3, e2).map { it.toString() }, page["items"].elements.map { it["experienceId"].asString() })
		assertEquals(listOf("AVAILABLE", "AVAILABLE"), page["items"].elements.map { it["availability"].asString() })
		assertEquals("Experiência $e3", page["items"][0]["experience"]["text"].asString())
		assertEquals("AVAILABLE", page["experiencesStatus"].asString())
		assertEquals(3, page["totalItems"].asInt())
		assertEquals(listOf(e3, e2).map { it.toString() }, experiences.requests.last(), "um pedido com os ids da página")
		val requestsBefore = experiences.requests.size
		assertEquals(listOf(e1.toString()), json("/api/v1/collections/$id/items?size=2&page=1", ana)["items"].elements.map { it["experienceId"].asString() })
		assertEquals(requestsBefore + 1, experiences.requests.size)

		mvc.delete("/api/v1/collections/$id/items/$e2") { bearer(ana) }.andExpect { status { isNoContent() } }
		mvc.delete("/api/v1/collections/$id/items/$e2") { bearer(ana) }.andExpect { status { isNoContent() } }
		mvc.delete("/api/v1/collections/$id/items/${UUID.randomUUID()}") { bearer(ana) }.andExpect { status { isNoContent() } }
		assertEquals(2, json("/api/v1/collections/$id", ana)["itemCount"].asInt())
		expectFieldErrors(mvc.put("/api/v1/collections/$id/items/nao-e-uuid") { bearer(ana) }, "experienceId")
	}

	@Test
	fun `guardar em simultaneo nao duplica e o limite de itens e respeitado`() {
		val ana = newOwner()
		val id = createId(ana, "Concorrência")
		val e = experiences.available()
		val statuses = concurrently(8) { http("PUT", "/api/v1/collections/$id/items/$e", ana).statusCode() }
		assertEquals(List(8) { 204 }, statuses)
		assertEquals(1L, count("SELECT count(*) FROM collection_item WHERE collection_id = :o", UUID.fromString(id)))

		val others = (1..5).map { experiences.available() }
		val results = concurrently(5) { i -> http("PUT", "/api/v1/collections/$id/items/${others[i]}", ana) }
		assertEquals(MAX_ITEMS, 1 + results.count { it.statusCode() == 204 }, results.map { it.statusCode() }.toString())
		assertTrue(results.filter { it.statusCode() != 204 }.all { it.body().contains("ITEM_LIMIT_REACHED") })
		assertEquals(MAX_ITEMS.toLong(), count("SELECT count(*) FROM collection_item WHERE collection_id = :o", UUID.fromString(id)))
	}

	@Test
	fun `experiencia eliminada versus experiences-service indisponivel`() {
		val ana = newOwner()
		val id = createId(ana, "Estados")
		val kept = experiences.available()
		val removed = experiences.available()
		add(ana, id, kept).andExpect { status { isNoContent() } }
		add(ana, id, removed).andExpect { status { isNoContent() } }

		// Guardar uma eliminada ou inexistente: 404, nada guardado.
		val gone = UUID.randomUUID().also { experiences.delete(it) }
		add(ana, id, gone).andExpect { status { isNotFound() }; jsonPath("$.code") { value("EXPERIENCE_NOT_FOUND") } }
		add(ana, id, UUID.randomUUID()).andExpect { status { isNotFound() }; jsonPath("$.code") { value("EXPERIENCE_NOT_FOUND") } }

		// Eliminada depois de guardada: item DELETED sem conteúdo, removível.
		experiences.delete(removed)
		val items = json("/api/v1/collections/$id/items", ana)
		val byId = items["items"].elements.associateBy { it["experienceId"].asString() }
		assertEquals("DELETED", byId[removed.toString()]!!["availability"].asString())
		assertTrue(byId[removed.toString()]!!["experience"].isNull)
		assertEquals("AVAILABLE", byId[kept.toString()]!!["availability"].asString())

		// experiences-service em falha (erro, lento, chave recusada, resposta inválida): nunca parece eliminada.
		for (mode in listOf(ExperiencesStub.Mode.ERROR, ExperiencesStub.Mode.SLOW, ExperiencesStub.Mode.FORBIDDEN, ExperiencesStub.Mode.INVALID_BODY)) {
			experiences.mode = mode
			val down = json("/api/v1/collections/$id/items", ana)
			assertEquals("UNAVAILABLE", down["experiencesStatus"].asString(), mode.name)
			assertEquals(listOf("UNKNOWN", "UNKNOWN"), down["items"].elements.map { it["availability"].asString() }, mode.name)
			assertTrue(down["items"].elements.all { it["experience"].isNull })
			add(ana, id, UUID.randomUUID()).andExpect { status { isServiceUnavailable() }; jsonPath("$.code") { value("EXPERIENCES_UNAVAILABLE") } }
		}
		assertEquals(2, json("/api/v1/collections/$id", ana)["itemCount"].asInt(), "nada guardado durante a falha")
		// Remover continua a funcionar com o serviço em falha, também para a eliminada.
		mvc.delete("/api/v1/collections/$id/items/$removed") { bearer(ana) }.andExpect { status { isNoContent() } }
		assertEquals(1, json("/api/v1/collections/$id", ana)["itemCount"].asInt())
	}

	// --- Auxiliares ---

	data class Owner(val owner: UUID, val token: String)

	private fun newOwner(): Owner = UUID.randomUUID().let { Owner(it, sign(it)) }

	private fun sign(
		userId: UUID,
		audience: String = "portofit-api",
		keyPair: KeyPair = keys.keyPair,
		expiresAt: Instant = Instant.now().plusSeconds(900),
	): String {
		val rsa = RSAKey.Builder(keyPair.public as RSAPublicKey).privateKey(keyPair.private as RSAPrivateKey).keyIDFromThumbprint().build()
		val encoder = NimbusJwtEncoder(ImmutableJWKSet<SecurityContext>(JWKSet(rsa)))
		val claims = JwtClaimsSet.builder().issuer("portofit-users").audience(listOf(audience)).subject(userId.toString())
			.issuedAt(expiresAt.minusSeconds(900)).expiresAt(expiresAt).id(UUID.randomUUID().toString()).claim("username", "u").build()
		val header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(rsa.keyID).type("JWT").build()
		return encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
	}

	private fun create(owner: Owner, name: String, key: String? = UUID.randomUUID().toString()) =
		createRaw(owner, mapper.writeValueAsString(mapOf("name" to name)), key)

	private fun createRaw(owner: Owner, body: String, key: String? = UUID.randomUUID().toString()): ResultActionsDsl =
		mvc.post("/api/v1/collections") {
			json(body)
			bearer(owner)
			if (key != null) header("Idempotency-Key", key)
		}

	private fun createId(owner: Owner, name: String): String = create(owner, name).andExpect { status { isCreated() } }.json()["id"].asString()

	private fun add(owner: Owner, collectionId: String, experienceId: UUID) =
		mvc.put("/api/v1/collections/$collectionId/items/$experienceId") { bearer(owner) }

	private fun json(url: String, owner: Owner): JsonNode = mvc.get(url) { bearer(owner) }.andExpect { status { isOk() } }.json()

	private fun count(sql: String, id: UUID): Long = jdbc.sql(sql).param("o", id).query(Long::class.javaObjectType).single()

	private fun expectFieldErrors(result: ResultActionsDsl, vararg fields: String) {
		result.andExpect {
			status { isBadRequest() }
			content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
			jsonPath("$.code") { value("VALIDATION_FAILED") }
			for (field in fields) jsonPath("$.errors[*].field") { value(hasItem(field)) }
		}
	}

	private fun MockHttpServletRequestDsl.json(body: String) {
		contentType = MediaType.APPLICATION_JSON
		content = body
	}

	private fun MockHttpServletRequestDsl.bearer(owner: Owner) = bearer(owner.token)

	private fun MockHttpServletRequestDsl.bearer(token: String) {
		header("Authorization", "Bearer $token")
	}

	private fun ResultActionsDsl.json(): JsonNode = mapper.readTree(andReturn().response.contentAsString)

	private val JsonNode.elements: List<JsonNode> get() = (0 until size()).map { this[it] }

	private fun http(method: String, path: String, owner: Owner, body: String? = null, key: String? = null): HttpResponse<String> {
		val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
			.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
			.header("Authorization", "Bearer ${owner.token}")
		if (body != null) builder.header("Content-Type", "application/json")
		if (key != null) builder.header("Idempotency-Key", key)
		return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString())
	}

	/** Corre [n] tarefas em paralelo, a começar ao mesmo tempo. */
	private fun <T> concurrently(n: Int, task: (Int) -> T): List<T> {
		val pool = Executors.newFixedThreadPool(n)
		try {
			val start = CountDownLatch(1)
			val futures = (0 until n).map { i -> pool.submit(Callable { start.await(); task(i) }) }
			start.countDown()
			return futures.map { it.get() }
		} finally {
			pool.shutdown()
		}
	}

	companion object {
		/** Limites nos testes (os reais são 50 listas e 500 itens). */
		const val MAX_COLLECTIONS = 3
		const val MAX_ITEMS = 3

		val keys: TestKeys = TestKeys.create()
		val experiences: ExperiencesStub = ExperiencesStub.start()

		@JvmStatic
		@DynamicPropertySource
		fun properties(registry: DynamicPropertyRegistry) {
			registry.add("portofit.security.public-key-location") { keys.publicKeyUri }
			registry.add("portofit.experiences.base-url") { experiences.url }
			registry.add("portofit.experiences.internal-key") { ExperiencesStub.KEY }
			registry.add("portofit.collections.max-per-owner") { MAX_COLLECTIONS }
			registry.add("portofit.collections.max-items") { MAX_ITEMS }
		}
	}
}
