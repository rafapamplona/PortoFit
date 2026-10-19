package com.example.portofit.experiences

import com.example.portofit.experiences.config.FeedProperties
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import tools.jackson.databind.JsonNode
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Feeds da E4 (docs/experiences-api.md §14.1–14.3) contra PostgreSQL real, com um falso users-service. Cada teste usa
 * modalidades novas (o feed popular é global), e por isso os resultados não dependem dos outros testes.
 */
class FeedApiIntegrationTest : ApiIntegrationTest() {

	@Autowired lateinit var feedProperties: FeedProperties

	@AfterEach
	fun restore() {
		users.mode = UsersStub.Mode.UP
		catalog.mode = CatalogStub.Mode.UP
		feedProperties.includeDemoPlaces = true
	}

	// --- Popular ---

	@Test
	fun `feed popular publico por interacoes em dois blocos, com desempates e paginacao`() {
		val sport = newSport()
		val ana = newUser("ana")
		val fans = (1..3).map { newUser("fan") }
		val offering = catalog.add(sportId = sport, sportName = "Modalidade")
		val e1 = publish(ana, offering)
		val e2 = publish(ana, offering)
		val e3 = publish(ana, offering)
		val e5 = publish(ana, offering)
		val old = publish(ana, offering)
		like(fans[0], e1)
		like(fans[0], e2)
		comment(fans[1], e2)
		// A antiga tem mais interações, mas fica no segundo bloco.
		fans.forEach { like(it, old) }
		comment(fans[0], old)
		val recentSince = clock.instant().truncatedTo(ChronoUnit.DAYS).minus(30, ChronoUnit.DAYS)
		setCreated(old, recentSince.minusSeconds(1))

		val page = json("/api/v1/feed/popular?sportId=$sport")
		// e2 (2), e1 (1), e5 e e3 (0, a mais recente primeiro), depois o bloco antigo.
		assertEquals(listOf(e2, e1, e5, e3, old), ids(page))
		assertEquals("POPULAR", page["mode"].asString())
		assertTrue(page["fallbackReason"].isNull)
		assertEquals(recentSince, Instant.parse(page["recentSince"].asString()))
		assertTrue(page.has("generatedAt"))
		assertEquals(5, page["totalItems"].asInt())
		assertEquals(listOf(e2, e1), ids(json("/api/v1/feed/popular?sportId=$sport&size=2")))
		assertEquals(listOf(old), ids(json("/api/v1/feed/popular?sportId=$sport&size=2&page=2")))
		assertEquals(emptyList(), ids(json("/api/v1/feed/popular?sportId=$sport&size=2&page=9")))

		// Visitante sem likedByMe; com sessão de outra pessoa, presente. Nada sobre visualizações.
		mvc.post("/api/v1/experiences/$e1/views") { header("X-Viewer-Key", UUID.randomUUID().toString()) }
		mvc.get("/api/v1/feed/popular?sportId=$sport").andExpect {
			status { isOk() }
			jsonPath("$.items[0].likedByMe") { doesNotExist() }
			content { string(not(containsString("view"))) }
			content { string(not(containsString("email"))) }
		}
		mvc.get("/api/v1/feed/popular?sportId=$sport") { bearer(fans[0].token) }.andExpect {
			jsonPath("$.items[0].likedByMe") { value(true) }
			jsonPath("$.items[0].matchedBy") { doesNotExist() }
		}
		mvc.get("/api/v1/feed/popular") { bearer("x.y.z") }.andExpect { status { isUnauthorized() } }
		expectFieldErrors(mvc.get("/api/v1/feed/popular?sportId=Boxe!&page=-1&size=51"), "sportId", "page", "size")
		assertTrue(users.requests.none { it.first?.contains(fans[0].token) == true }, "o popular nunca pede o contexto")
	}

	@Test
	fun `experiencias eliminadas ficam fora e o modo de demonstracao controla os locais ficticios`() {
		val sport = newSport()
		val ana = newUser("ana")
		val demo = publish(ana, catalog.add(sportId = sport, sportName = "Modalidade"))
		val real = publish(ana, catalog.add(sportId = sport, sportName = "Modalidade", placeName = "Local real", demo = false))
		val deleted = publish(ana, catalog.add(sportId = sport, sportName = "Modalidade"))
		mvc.delete("/api/v1/experiences/$deleted") { bearer(ana.token) }.andExpect { status { isNoContent() } }
		assertEquals(listOf(real, demo), ids(json("/api/v1/feed/popular?sportId=$sport")))

		// Catálogo real: as de demonstração saem dos feeds, mas continuam no perfil e por referência direta.
		feedProperties.includeDemoPlaces = false
		assertEquals(listOf(real), ids(json("/api/v1/feed/popular?sportId=$sport")))
		val rui = newUser("rui")
		users.context(rui.id, following = listOf(ana.id))
		assertEquals(listOf(real), ids(feed(rui, "?sportId=$sport")))
		mvc.get("/api/v1/experiences/$demo").andExpect { status { isOk() } }
		assertTrue(demo in ids(json("/api/v1/experiences?authorId=${ana.id}")))
	}

	// --- Pessoal ---

	@Test
	fun `feed pessoal por seguidos ou interesses, sem as proprias, com matchedBy e o token reencaminhado`() {
		val interest = newSport()
		val other = newSport()
		val ana = newUser("ana")
		val bia = newUser("bia")
		val carl = newUser("carl")
		val inInterest = catalog.add(sportId = interest, sportName = "Interesse")
		val inOther = catalog.add(sportId = other, sportName = "Outra")
		val p1 = publish(bia, inOther)
		val p2 = publish(carl, inInterest)
		val p3 = publish(bia, inInterest)
		publish(carl, inOther)
		publish(ana, inInterest)
		users.context(ana.id, following = listOf(bia.id), interests = listOf(interest))
		users.requests.clear()

		val page = feed(ana)
		assertEquals("PERSONAL", page["mode"].asString())
		assertTrue(page["fallbackReason"].isNull)
		assertTrue(page["recentSince"].isNull)
		assertEquals(listOf(p3, p2, p1), ids(page))
		assertEquals(listOf(listOf("FOLLOWING", "INTEREST"), listOf("INTEREST"), listOf("FOLLOWING")), page["items"].elements.map { item -> item["matchedBy"].elements.map { it.asString() } })
		assertEquals(listOf("Bearer ${ana.token}" to UsersStub.KEY), users.requests.toList())

		assertEquals(listOf(p1), ids(feed(ana, "?sportId=$other")))
		assertEquals(listOf(p3), ids(feed(ana, "?size=1")))
		assertEquals(listOf(p1), ids(feed(ana, "?size=1&page=2")))
		// Para além do fim de um feed pessoal com resultados: continua pessoal, sem passar ao popular.
		val beyond = feed(ana, "?size=1&page=7")
		assertEquals("PERSONAL", beyond["mode"].asString())
		assertEquals(emptyList(), ids(beyond))
		assertEquals(3, beyond["totalItems"].asInt())

		// Sem sessão não há feed pessoal (nem pedido ao users-service).
		users.requests.clear()
		mvc.get("/api/v1/feed").andExpect { status { isUnauthorized() }; jsonPath("$.code") { value("AUTHENTICATION_REQUIRED") } }
		assertTrue(users.requests.isEmpty())
	}

	@Test
	fun `sem preferencias ou sem resultados usa o popular com o motivo, decidido pelo conjunto completo`() {
		val sport = newSport()
		val ana = newUser("ana")
		val bia = newUser("bia")
		val offering = catalog.add(sportId = sport, sportName = "Modalidade")
		val popularOne = publish(bia, offering)

		users.context(ana.id)
		feed(ana, "?sportId=$sport").let { page ->
			assertEquals("POPULAR", page["mode"].asString())
			assertEquals("NO_PREFERENCES", page["fallbackReason"].asString())
			assertFalse(page["recentSince"].isNull)
			assertEquals(listOf(popularOne), ids(page))
		}

		users.context(ana.id, interests = listOf(newSport()))
		feed(ana, "?sportId=$sport").let { page ->
			assertEquals("POPULAR", page["mode"].asString())
			assertEquals("NO_MATCHES", page["fallbackReason"].asString())
			assertEquals(listOf(popularOne), ids(page))
		}

		// Segue a bia (tem resultados), mas com um filtro sem correspondências o conjunto completo fica vazio.
		users.context(ana.id, following = listOf(bia.id))
		assertEquals("PERSONAL", feed(ana, "?sportId=$sport")["mode"].asString())
		val emptySport = newSport()
		feed(ana, "?sportId=$emptySport").let { page ->
			assertEquals("POPULAR", page["mode"].asString())
			assertEquals("NO_MATCHES", page["fallbackReason"].asString())
			assertEquals(0, page["totalItems"].asInt())
		}
	}

	@Test
	fun `falhas do users-service degradam sem parecer personalizado e um token recusado da 401`() {
		val sport = newSport()
		val ana = newUser("ana")
		val bia = newUser("bia")
		val offering = catalog.add(sportId = sport, sportName = "Modalidade")
		val e = publish(bia, offering)
		users.context(ana.id, following = listOf(bia.id))

		for (mode in listOf(UsersStub.Mode.ERROR, UsersStub.Mode.SLOW, UsersStub.Mode.FORBIDDEN, UsersStub.Mode.INVALID_BODY)) {
			users.mode = mode
			val started = System.nanoTime()
			val page = feed(ana, "?sportId=$sport")
			val millis = (System.nanoTime() - started) / 1_000_000
			assertEquals("POPULAR_FALLBACK", page["mode"].asString(), mode.name)
			assertEquals("USERS_UNAVAILABLE", page["fallbackReason"].asString(), mode.name)
			assertEquals(listOf(e), ids(page))
			assertTrue(page["items"][0].get("matchedBy") == null, "um resultado popular nunca tem matchedBy")
			assertTrue(millis < 2500, "$mode demorou $millis ms (prazo de 1 s)")
		}

		users.mode = UsersStub.Mode.REJECT_TOKEN
		mvc.get("/api/v1/feed") { bearer(ana.token) }.andExpect {
			status { isUnauthorized() }
			jsonPath("$.code") { value("INVALID_ACCESS_TOKEN") }
		}

		// Catálogo parado: os feeds não dependem dele.
		users.mode = UsersStub.Mode.UP
		catalog.mode = CatalogStub.Mode.ERROR
		assertEquals("PERSONAL", feed(ana, "?sportId=$sport")["mode"].asString())
		assertEquals(listOf(e), ids(json("/api/v1/feed/popular?sportId=$sport")))
	}

	// --- Auxiliares ---

	private fun newSport() = "t" + UUID.randomUUID().toString().replace("-", "").take(12)

	private fun publish(user: TestUser, offering: CatalogStub.TestOffering): String =
		mvc.post("/api/v1/experiences") {
			json(mapper.writeValueAsString(mapOf("placeId" to offering.placeId, "offeringId" to offering.id, "text" to "Experiência", "stars" to 4)))
			bearer(user.token)
			header("Idempotency-Key", UUID.randomUUID().toString())
		}.andExpect { status { isCreated() } }.json()["id"].asString()

	private fun like(user: TestUser, id: String) {
		mvc.put("/api/v1/experiences/$id/like") { bearer(user.token) }.andExpect { status { isNoContent() } }
	}

	private fun comment(user: TestUser, id: String) {
		mvc.post("/api/v1/experiences/$id/comments") {
			json("""{"text":"Comentário"}""")
			bearer(user.token)
			header("Idempotency-Key", UUID.randomUUID().toString())
		}.andExpect { status { isCreated() } }
	}

	private fun setCreated(id: String, at: Instant) {
		jdbc.sql("UPDATE experience SET created_at = :at WHERE id = :id").param("at", Timestamp.from(at)).param("id", UUID.fromString(id)).update()
	}

	private fun feed(user: TestUser, query: String = ""): JsonNode =
		mvc.get("/api/v1/feed$query") { bearer(user.token) }.andExpect { status { isOk() } }.json()

	private fun json(url: String): JsonNode = mvc.get(url).andExpect { status { isOk() } }.json()

	private fun ids(page: JsonNode): List<String> = page["items"].elements.map { it["id"].asString() }

	private fun expectFieldErrors(result: ResultActionsDsl, vararg fields: String) {
		result.andExpect {
			status { isBadRequest() }
			jsonPath("$.code") { value("VALIDATION_FAILED") }
			for (field in fields) jsonPath("$.errors[*].field") { value(hasItem(field)) }
		}
	}

	private fun MockHttpServletRequestDsl.json(body: String) {
		contentType = MediaType.APPLICATION_JSON
		content = body
	}

	private fun MockHttpServletRequestDsl.bearer(token: String) {
		header("Authorization", "Bearer $token")
	}

	private fun ResultActionsDsl.json(): JsonNode = mapper.readTree(andReturn().response.contentAsString)

	private val JsonNode.elements: List<JsonNode> get() = (0 until size()).map { this[it] }
}
