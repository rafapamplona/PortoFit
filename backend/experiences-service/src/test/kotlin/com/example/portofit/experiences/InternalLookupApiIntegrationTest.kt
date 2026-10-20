package com.example.portofit.experiences

import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Consulta interna em lote para o collections-service (docs/experiences-api.md §4.7). */
class InternalLookupApiIntegrationTest : ApiIntegrationTest() {

	@Test
	fun `consulta em lote so com a chave, por ordem, sem conteudo de eliminadas`() {
		val ana = newUser("ana")
		val offering = catalog.add()
		val available = publish(ana, offering, "Disponível")
		val deleted = publish(ana, offering, "Texto que vai ser apagado")
		mvc.delete("/api/v1/experiences/$deleted") { header("Authorization", "Bearer ${ana.token}") }.andExpect { status { isNoContent() } }
		val missing = UUID.randomUUID()
		val path = "/api/v1/internal/experiences?ids=$deleted,$missing,$available,$available"

		mvc.get(path).andExpect { status { isForbidden() }; jsonPath("$.code") { value("FORBIDDEN") } }
		mvc.get(path) { header("X-Internal-Key", "errada") }.andExpect { status { isForbidden() } }
		// Um token de utilizador não substitui a chave.
		mvc.get(path) { header("Authorization", "Bearer ${ana.token}") }.andExpect { status { isForbidden() } }

		val body = mvc.get(path) { header("X-Internal-Key", COLLECTIONS_KEY) }.andExpect {
			status { isOk() }
			content { string(not(containsString("Texto que vai ser apagado"))) }
			jsonPath("$.items.length()") { value(3) }
		}.andReturn().response.contentAsString.let { mapper.readTree(it) }
		val items = (0 until body["items"].size()).map { body["items"][it] }
		assertEquals(listOf(deleted, missing.toString(), available), items.map { it["id"].asString() })
		assertEquals(listOf("DELETED", "NOT_FOUND", "AVAILABLE"), items.map { it["status"].asString() })
		assertTrue(items[0]["experience"].isNull && items[1]["experience"].isNull)
		assertEquals("Disponível", items[2]["experience"]["text"].asString())
		assertTrue(items[2]["experience"]["likedByMe"] == null)

		val tooMany = (1..51).joinToString(",") { UUID.randomUUID().toString() }
		for (ids in listOf("", "nao-e-uuid", tooMany)) {
			mvc.get("/api/v1/internal/experiences?ids=$ids") { header("X-Internal-Key", COLLECTIONS_KEY) }
				.andExpect { status { isBadRequest() }; jsonPath("$.code") { value("VALIDATION_FAILED") } }
		}
		val fifty = (1..50).joinToString(",") { UUID.randomUUID().toString() }
		mvc.get("/api/v1/internal/experiences?ids=$fifty") { header("X-Internal-Key", COLLECTIONS_KEY) }
			.andExpect { status { isOk() }; jsonPath("$.items.length()") { value(50) } }
	}

	private fun publish(user: TestUser, offering: CatalogStub.TestOffering, text: String): String =
		mvc.post("/api/v1/experiences") {
			contentType = MediaType.APPLICATION_JSON
			content = mapper.writeValueAsString(mapOf("placeId" to offering.placeId, "offeringId" to offering.id, "text" to text, "stars" to 4))
			header("Authorization", "Bearer ${user.token}")
			header("Idempotency-Key", UUID.randomUUID().toString())
		}.andExpect { status { isCreated() } }.andReturn().response.contentAsString.let { mapper.readTree(it)["id"].asString() }
}
