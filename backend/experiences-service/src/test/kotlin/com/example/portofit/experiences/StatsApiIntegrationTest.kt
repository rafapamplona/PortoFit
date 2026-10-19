package com.example.portofit.experiences

import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import tools.jackson.databind.JsonNode
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Dashboard da E4 (docs/experiences-api.md §14.4): períodos em dias UTC, datas de criação/interação/visualização,
 * comentários do autor, remoções, estrelas dadas, publicações eliminadas e falhas sem zeros.
 */
class StatsApiIntegrationTest : ApiIntegrationTest() {

	@AfterEach
	fun resetSpy() = Mockito.reset(statsRepository)

	private val now: Instant get() = clock.instant()
	private val today: Instant get() = now.truncatedTo(ChronoUnit.DAYS)
	private fun daysAgo(days: Long): Instant = today.minus(days, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS)

	@Test
	fun `periodos UTC com datas de criacao, interacao e visualizacao, e o efeito das remocoes`() {
		val ana = newUser("ana")
		val rui = newUser("rui")
		val eva = newUser("eva")
		val offering = catalog.add()

		val eOld = publish(ana, offering, stars = 5)
		val eMid = publish(ana, offering, stars = 3)
		val eNew = publish(ana, offering, stars = 3)
		val eDel = publish(ana, offering, stars = 1)
		setCreated(eOld, daysAgo(40))
		setCreated(eMid, daysAgo(10))
		setCreated(eNew, today.plusSeconds(60))
		setCreated(eDel, today.plusSeconds(120))

		// Publicação antiga com interações recentes e antigas; comentário do próprio autor.
		like(rui, eOld, at = daysAgo(2))
		like(eva, eOld, at = daysAgo(35))
		comment(rui, eOld, at = daysAgo(1))
		comment(ana, eOld, at = daysAgo(0))
		view(eOld, daysAgo(3))
		view(eOld, daysAgo(60))

		like(eva, eMid, at = daysAgo(10))
		comment(rui, eMid, at = daysAgo(20))
		val removed = comment(eva, eMid, at = daysAgo(1))
		mvc.delete("/api/v1/comments/$removed") { bearer(eva.token) }.andExpect { status { isNoContent() } }
		view(eMid, daysAgo(10))

		// Gosto retirado não conta; retirar e voltar a dar conta uma vez, na data nova.
		like(eva, eNew)
		mvc.delete("/api/v1/experiences/$eNew/like") { bearer(eva.token) }.andExpect { status { isNoContent() } }
		like(rui, eNew, at = daysAgo(5))
		mvc.delete("/api/v1/experiences/$eNew/like") { bearer(rui.token) }.andExpect { status { isNoContent() } }
		like(rui, eNew, at = daysAgo(0))
		mvc.post("/api/v1/experiences/$eNew/views") { bearer(eva.token) }.andExpect { status { isNoContent() } }
		mvc.post("/api/v1/experiences/$eNew/views") { bearer(rui.token) }.andExpect { status { isNoContent() } }
		mvc.post("/api/v1/experiences/$eNew/views") { bearer(ana.token) }.andExpect { status { isNoContent() } }

		// Eliminada: sai de tudo, também do passado.
		like(rui, eDel, at = daysAgo(0))
		view(eDel, daysAgo(0))
		mvc.delete("/api/v1/experiences/$eDel") { bearer(ana.token) }.andExpect { status { isNoContent() } }

		// Editar o texto não muda nada; editar as estrelas muda o histograma.
		mvc.patch("/api/v1/experiences/$eMid") { json("""{"text":"Revisto"}"""); bearer(ana.token) }.andExpect { status { isOk() } }

		val all = stats(ana, "ALL")
		assertEquals("ALL", all["period"].asString())
		assertTrue(all["from"].isNull)
		assertEquals(all["to"].asString(), all["generatedAt"].asString())
		assertEquals("UTC", all["timezone"].asString())
		assertEquals(3, all["publications"].asInt())
		assertEquals(Totals(likes = 4, comments = 3, views = 5), totals(all))
		assertEquals(mapOf("1" to 0, "2" to 0, "3" to 2, "4" to 0, "5" to 1), stars(all))
		assertEquals("ONE_PER_VIEWER_PER_UTC_DAY", all["received"]["viewsRule"].asString())
		assertTrue(all["received"]["commentsIncludeOwn"].asBoolean())

		val week = stats(ana, "LAST_7_DAYS")
		assertEquals(today.minus(6, ChronoUnit.DAYS), Instant.parse(week["from"].asString()))
		assertEquals(1, week["publications"].asInt())
		// eOld: gosto de há 2 dias, comentários de há 1 dia e de hoje (do autor), visualização de há 3 dias; eNew: 1 gosto, 2 visualizações.
		assertEquals(Totals(likes = 2, comments = 2, views = 3), totals(week))
		assertEquals(mapOf("1" to 0, "2" to 0, "3" to 1, "4" to 0, "5" to 0), stars(week))

		val month = stats(ana, "LAST_30_DAYS")
		assertEquals(today.minus(29, ChronoUnit.DAYS), Instant.parse(month["from"].asString()))
		assertEquals(2, month["publications"].asInt())
		assertEquals(Totals(likes = 3, comments = 3, views = 4), totals(month))
		assertEquals(mapOf("1" to 0, "2" to 0, "3" to 2, "4" to 0, "5" to 0), stars(month))

		// Por publicação: todas as ativas, com o que receberam no período; a soma bate com os totais.
		val rows = perExperience(ana, "?period=LAST_7_DAYS")
		assertEquals(listOf(eNew, eMid, eOld), rows.map { it["experience"]["id"].asString() })
		assertEquals(Totals(2, 2, 3), Totals(rows.sumOf { it["likes"].asInt() }, rows.sumOf { it["comments"].asInt() }, rows.sumOf { it["views"].asInt() }))
		assertEquals(listOf(0, 0, 0, 0), rows[1].let { listOf(it["likes"].asInt(), it["comments"].asInt(), it["interactions"].asInt(), it["views"].asInt()) })
		assertEquals(listOf(eNew, eOld, eMid), perExperience(ana, "?period=LAST_7_DAYS&sort=views").map { it["experience"]["id"].asString() })
		assertEquals(listOf(eOld, eNew, eMid), perExperience(ana, "?period=LAST_7_DAYS&sort=interactions").map { it["experience"]["id"].asString() })
		assertEquals(listOf(eNew), perExperience(ana, "?size=1").map { it["experience"]["id"].asString() })
		assertEquals(listOf(eOld), perExperience(ana, "?size=1&page=2").map { it["experience"]["id"].asString() })

		mvc.patch("/api/v1/experiences/$eOld") { json("""{"stars":2}"""); bearer(ana.token) }.andExpect { status { isOk() } }
		assertEquals(mapOf("1" to 0, "2" to 1, "3" to 2, "4" to 0, "5" to 0), stars(stats(ana, "ALL")))
	}

	@Test
	fun `so o proprio, sem dados de quem interagiu, validacao e falha sem zeros`() {
		val ana = newUser("ana")
		val rui = newUser("rui")
		val e = publish(ana, catalog.add(), stars = 4)
		like(rui, e)
		comment(rui, e)

		mvc.get("/api/v1/me/stats").andExpect { status { isUnauthorized() } }
		mvc.get("/api/v1/me/stats/experiences").andExpect { status { isUnauthorized() } }
		mvc.get("/api/v1/me/stats") { bearer("x.y.z") }.andExpect { status { isUnauthorized() } }

		// Um parâmetro userId é ignorado: cada pessoa só vê o seu.
		mvc.get("/api/v1/me/stats?userId=${ana.id}") { bearer(rui.token) }.andExpect {
			status { isOk() }
			jsonPath("$.publications") { value(0) }
			jsonPath("$.received.likes") { value(0) }
		}
		mvc.get("/api/v1/me/stats") { bearer(ana.token) }.andExpect {
			status { isOk() }
			jsonPath("$.received.likes") { value(1) }
			content { string(not(containsString(rui.id.toString()))) }
			content { string(not(containsString(rui.username))) }
			content { string(not(containsString("email"))) }
		}
		mvc.get("/api/v1/me/stats/experiences") { bearer(ana.token) }.andExpect {
			status { isOk() }
			jsonPath("$.items[0].experience.id") { value(e) }
			jsonPath("$.items[0].experience.text") { doesNotExist() }
			jsonPath("$.timezone") { value("UTC") }
			content { string(not(containsString(rui.id.toString()))) }
		}
		expectFieldErrors(mvc.get("/api/v1/me/stats?period=ONTEM") { bearer(ana.token) }, "period")
		expectFieldErrors(mvc.get("/api/v1/me/stats/experiences?period=x&sort=likes&page=-1&size=0") { bearer(ana.token) }, "period", "sort", "page", "size")

		// Base de dados em falha: 503, nunca uma resposta com zeros.
		Mockito.doThrow(DataAccessResourceFailureException("base de dados em baixo")).`when`(statsRepository).received(anyUuid(), anyInstant())
		mvc.get("/api/v1/me/stats") { bearer(ana.token) }.andExpect {
			status { isServiceUnavailable() }
			jsonPath("$.code") { value("SERVICE_UNAVAILABLE") }
			jsonPath("$.received") { doesNotExist() }
		}
	}

	// --- Auxiliares ---

	data class Totals(val likes: Int, val comments: Int, val views: Int)

	private fun totals(node: JsonNode) = node["received"].let {
		assertEquals(it["likes"].asInt() + it["comments"].asInt(), it["interactions"].asInt())
		Totals(it["likes"].asInt(), it["comments"].asInt(), it["views"].asInt())
	}

	private fun stars(node: JsonNode): Map<String, Int> = (1..5).associate { it.toString() to node["starsGiven"][it.toString()].asInt() }

	private fun stats(user: TestUser, period: String): JsonNode =
		mvc.get("/api/v1/me/stats?period=$period") { bearer(user.token) }.andExpect { status { isOk() } }.json()

	private fun perExperience(user: TestUser, query: String): List<JsonNode> =
		mvc.get("/api/v1/me/stats/experiences$query") { bearer(user.token) }.andExpect { status { isOk() } }.json()["items"]
			.let { items -> (0 until items.size()).map { items[it] } }

	private fun publish(user: TestUser, offering: CatalogStub.TestOffering, stars: Int): String {
		return mvc.post("/api/v1/experiences") {
			json(mapper.writeValueAsString(mapOf("placeId" to offering.placeId, "offeringId" to offering.id, "text" to "Experiência", "stars" to stars)))
			bearer(user.token)
			header("Idempotency-Key", UUID.randomUUID().toString())
		}.andExpect { status { isCreated() } }.json()["id"].asString()
	}

	private fun like(user: TestUser, id: String, at: Instant? = null) {
		mvc.put("/api/v1/experiences/$id/like") { bearer(user.token) }.andExpect { status { isNoContent() } }
		at?.let {
			jdbc.sql("UPDATE experience_like SET created_at = :at WHERE experience_id = :e AND user_id = :u")
				.param("at", Timestamp.from(it)).param("e", UUID.fromString(id)).param("u", user.id).update()
		}
	}

	private fun comment(user: TestUser, id: String, at: Instant? = null): String {
		val commentId = mvc.post("/api/v1/experiences/$id/comments") {
			json("""{"text":"Comentário"}""")
			bearer(user.token)
			header("Idempotency-Key", UUID.randomUUID().toString())
		}.andExpect { status { isCreated() } }.json()["id"].asString()
		at?.let {
			jdbc.sql("UPDATE comment SET created_at = :at WHERE id = :id").param("at", Timestamp.from(it)).param("id", UUID.fromString(commentId)).update()
		}
		return commentId
	}

	/** Visualização já contada noutro dia UTC (como se tivesse sido registada nesse dia). */
	private fun view(id: String, day: Instant) {
		jdbc.sql("INSERT INTO experience_view (experience_id, viewer_hash, period_start) VALUES (:e, :h, :p)")
			.param("e", UUID.fromString(id))
			.param("h", UUID.randomUUID().toString().replace("-", "").repeat(2))
			.param("p", Timestamp.from(day.truncatedTo(ChronoUnit.DAYS)))
			.update()
	}

	private fun setCreated(id: String, at: Instant) {
		jdbc.sql("UPDATE experience SET created_at = :at WHERE id = :id").param("at", Timestamp.from(at)).param("id", UUID.fromString(id)).update()
	}

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

	private fun anyUuid(): UUID {
		Mockito.any(UUID::class.java)
		return uninitialized()
	}

	private fun anyInstant(): Instant {
		Mockito.any(Instant::class.java)
		return uninitialized()
	}

	@Suppress("UNCHECKED_CAST")
	private fun <T> uninitialized(): T = null as T
}
