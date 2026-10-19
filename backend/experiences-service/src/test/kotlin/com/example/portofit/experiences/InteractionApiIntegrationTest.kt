package com.example.portofit.experiences

import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import tools.jackson.databind.JsonNode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Comentários, gostos e visualizações (E3, docs/experiences-api.md §13) contra PostgreSQL real. Cada teste usa
 * pessoas, locais e atividades novos.
 */
class InteractionApiIntegrationTest : ApiIntegrationTest() {

	// --- Comentários ---

	@Test
	fun `comentar, repetir, listar por ordem e editar`() {
		val ana = newUser("ana")
		val rui = newUser("rui")
		val eva = newUser("eva")
		val exp = publish(ana, catalog.add())

		val key = UUID.randomUUID().toString()
		val first = comment(rui, exp, "  Também gostei!\r\nVoltarei.  ", key).andExpect {
			status { isCreated() }
			header { doesNotExist("Idempotent-Replayed") }
			jsonPath("$.experienceId") { value(exp) }
			jsonPath("$.author.userId") { value(rui.id.toString()) }
			jsonPath("$.author.username") { value(rui.username) }
			jsonPath("$.text") { value("Também gostei!\nVoltarei.") }
			jsonPath("$.createdAt") { exists() }
			jsonPath("$.editedAt") { value(null as Any?) }
			content { string(not(containsString("email"))) }
		}.json()
		val c1 = first["id"].asString()
		assertEquals(7, UUID.fromString(c1).version())

		// Repetição da mesma tentativa: o mesmo comentário, sem duplicado.
		comment(rui, exp, "Também gostei!\nVoltarei.", key).andExpect {
			status { isOk() }
			header { string("Idempotent-Replayed", "true") }
			jsonPath("$.id") { value(c1) }
		}
		val c2 = commentId(comment(eva, exp, "Segundo"))
		val c3 = commentId(comment(rui, exp, "Terceiro"))
		val c4 = commentId(comment(ana, exp, "O autor também comenta"))

		// Mais antigos primeiro, desempate pelo id; páginas estáveis.
		val page0 = json("/api/v1/experiences/$exp/comments?size=3")
		assertEquals(listOf(c1, c2, c3), page0["items"].elements.map { it["id"].asString() })
		assertEquals(4, page0["totalItems"].asInt())
		assertEquals(2, page0["totalPages"].asInt())
		assertEquals(listOf(c4), commentIds(exp, "size=3&page=1"))
		assertEquals(emptyList(), commentIds(exp, "page=9"))
		assertEquals(4, detail(exp)["commentsCount"].asInt())

		// Editar: só muda editedAt quando o texto muda; createdAt nunca muda.
		mvc.patch("/api/v1/comments/$c2") { json("""{"text":"Segundo"}"""); bearer(eva.token) }.andExpect {
			status { isOk() }
			jsonPath("$.editedAt") { value(null as Any?) }
		}
		val edited = mvc.patch("/api/v1/comments/$c2") { json("""{"text":" Segundo, revisto "}"""); bearer(eva.token) }.andExpect {
			status { isOk() }
			jsonPath("$.text") { value("Segundo, revisto") }
			jsonPath("$.editedAt") { exists() }
		}.json()
		assertFalse(edited["editedAt"].isNull)
		assertEquals(listOf(c1, c2, c3, c4), commentIds(exp))

		// Experiência inexistente → 404.
		val unknown = UUID.randomUUID()
		mvc.get("/api/v1/experiences/$unknown/comments").andExpect { status { isNotFound() }; jsonPath("$.code") { value("NOT_FOUND") } }
		comment(rui, unknown.toString(), "Olá").andExpect { status { isNotFound() } }
	}

	@Test
	fun `validacao e autorizacao dos comentarios`() {
		val ana = newUser("ana")
		val rui = newUser("rui")
		val exp = publish(ana, catalog.add())

		mvc.post("/api/v1/experiences/$exp/comments") { json("""{"text":"Olá"}"""); header("Idempotency-Key", UUID.randomUUID().toString()) }
			.andExpect { status { isUnauthorized() }; jsonPath("$.code") { value("AUTHENTICATION_REQUIRED") } }
		for (text in listOf("", "   \n\t ", "a".repeat(1001), "com\u0007controlo")) {
			expectFieldErrors(comment(rui, exp, text), "text")
		}
		expectFieldErrors(commentRaw(rui, exp, """{"text":42}"""), "text")
		expectFieldErrors(commentRaw(rui, exp, """{"text":"Olá","authorId":"${ana.id}"}"""), "authorId")
		expectFieldErrors(commentRaw(rui, exp, "[]"), "body")
		expectFieldErrors(commentRaw(rui, exp, """{"text":" "}""", key = null), "text", "Idempotency-Key")
		expectFieldErrors(commentRaw(rui, "nao-e-uuid", """{"text":"Olá"}"""), "experienceId")
		// 1000 caracteres (pontos de código, incluindo emojis) é aceite.
		comment(rui, exp, "💪".repeat(1000)).andExpect { status { isCreated() } }

		val c = commentId(comment(rui, exp, "Meu"))
		mvc.patch("/api/v1/comments/$c") { json("""{"text":"Alheio"}"""); bearer(ana.token) }
			.andExpect { status { isForbidden() }; jsonPath("$.code") { value("FORBIDDEN") } }
		mvc.delete("/api/v1/comments/$c") { bearer(ana.token) }.andExpect { status { isForbidden() } }
		mvc.patch("/api/v1/comments/$c") { json("""{"text":"Sem sessão"}""") }.andExpect { status { isUnauthorized() } }
		mvc.delete("/api/v1/comments/$c").andExpect { status { isUnauthorized() } }
		expectFieldErrors(mvc.patch("/api/v1/comments/$c") { json("""{"text":"  "}"""); bearer(rui.token) }, "text")
		expectFieldErrors(mvc.patch("/api/v1/comments/$c") { json("""{}"""); bearer(rui.token) }, "text")
		expectFieldErrors(mvc.patch("/api/v1/comments/x") { json("""{"text":"a"}"""); bearer(rui.token) }, "commentId")
		mvc.patch("/api/v1/comments/${UUID.randomUUID()}") { json("""{"text":"a"}"""); bearer(rui.token) }.andExpect { status { isNotFound() } }
		expectFieldErrors(mvc.get("/api/v1/experiences/$exp/comments?size=51&page=-1"), "size", "page")
		assertEquals(listOf("Meu"), json("/api/v1/experiences/$exp/comments")["items"].elements.map { it["text"].asString() }.filter { it == "Meu" })
	}

	@Test
	fun `idempotencia dos comentarios com conflito, eliminacao e pedidos simultaneos`() {
		val ana = newUser("ana")
		val rui = newUser("rui")
		val exp = publish(ana, catalog.add())
		val other = publish(ana, catalog.add())

		val key = UUID.randomUUID().toString()
		val c = commentId(comment(rui, exp, "Original", key))
		comment(rui, exp, "Outro texto", key).andExpect {
			status { isConflict() }
			jsonPath("$.code") { value("IDEMPOTENCY_CONFLICT") }
			jsonPath("$.reason") { value("DIFFERENT_CONTENT") }
			jsonPath("$.commentId") { value(c) }
		}
		// A mesma chave noutra experiência é outro conteúdo.
		comment(rui, other, "Original", key).andExpect { status { isConflict() }; jsonPath("$.reason") { value("DIFFERENT_CONTENT") } }
		// A chave é por autor.
		comment(ana, exp, "Original", key).andExpect { status { isCreated() } }

		mvc.delete("/api/v1/comments/$c") { bearer(rui.token) }.andExpect { status { isNoContent() } }
		comment(rui, exp, "Original", key).andExpect {
			status { isConflict() }
			jsonPath("$.reason") { value("COMMENT_DELETED") }
			jsonPath("$.text") { doesNotExist() }
		}

		// 8 pedidos simultâneos com a mesma chave → um só comentário.
		val eva = newUser("eva")
		val sameKey = UUID.randomUUID().toString()
		val body = """{"text":"Pedido repetido pela rede"}"""
		val responses = concurrently(8) { http("POST", "/api/v1/experiences/$exp/comments", eva.token, body, mapOf("Idempotency-Key" to sameKey)) }
		assertEquals(1, responses.count { it.statusCode() == 201 }, responses.map { it.statusCode() }.toString())
		assertEquals(7, responses.count { it.statusCode() == 200 && it.headers().firstValue("Idempotent-Replayed").orElse("") == "true" })
		assertEquals(1, responses.map { mapper.readTree(it.body())["id"].asString() }.toSet().size)
		assertEquals(1L, count("SELECT count(*) FROM comment WHERE author_id = :u", eva.id))
	}

	@Test
	fun `eliminar comentario e idempotente para o autor e deixa de contar`() {
		val ana = newUser("ana")
		val rui = newUser("rui")
		val eva = newUser("eva")
		val exp = publish(ana, catalog.add())
		val c1 = commentId(comment(rui, exp, "Fica"))
		val c2 = commentId(comment(rui, exp, "Sai"))
		assertEquals(2, detail(exp)["commentsCount"].asInt())

		mvc.delete("/api/v1/comments/$c2") { bearer(rui.token) }.andExpect { status { isNoContent() } }
		mvc.delete("/api/v1/comments/$c2") { bearer(rui.token) }.andExpect { status { isNoContent() } }
		mvc.delete("/api/v1/comments/$c2") { bearer(eva.token) }.andExpect { status { isNotFound() } }
		mvc.patch("/api/v1/comments/$c2") { json("""{"text":"Volta"}"""); bearer(rui.token) }.andExpect { status { isNotFound() } }
		mvc.delete("/api/v1/comments/${UUID.randomUUID()}") { bearer(rui.token) }.andExpect { status { isNotFound() } }

		assertEquals(listOf(c1), commentIds(exp))
		assertEquals(1, json("/api/v1/experiences/$exp/comments")["totalItems"].asInt())
		assertEquals(1, detail(exp)["commentsCount"].asInt())
		// Fica só a marca, sem o texto.
		assertEquals(1L, count("SELECT count(*) FROM comment WHERE id = :u AND text IS NULL AND deleted_at IS NOT NULL", UUID.fromString(c2)))
	}

	// --- Gostos e contagens ---

	@Test
	fun `gostos idempotentes, proprio recusado e likedByMe so com sessao de outra pessoa`() {
		val ana = newUser("ana")
		val rui = newUser("rui")
		val eva = newUser("eva")
		val offering = catalog.add()
		val exp = publish(ana, offering)
		mvc.get("/api/v1/experiences/$exp").andExpect {
			jsonPath("$.likesCount") { value(0) }
			jsonPath("$.commentsCount") { value(0) }
			jsonPath("$.likedByMe") { doesNotExist() }
		}

		like(rui, exp).andExpect { status { isNoContent() } }
		like(rui, exp).andExpect { status { isNoContent() } }
		assertEquals(1, detail(exp)["likesCount"].asInt())

		// Visitante: sem likedByMe (desconhecido, nunca false); outra pessoa: true/false; o autor: omitido.
		assertFalse(detail(exp).has("likedByMe"))
		assertEquals(true, detail(exp, rui)["likedByMe"].asBoolean())
		assertEquals(false, detail(exp, eva)["likedByMe"].asBoolean())
		assertTrue(detail(exp, eva).has("likedByMe"))
		assertFalse(detail(exp, ana).has("likedByMe"))
		val listed = mvc.get("/api/v1/experiences?placeId=${offering.placeId}") { bearer(rui.token) }.andExpect { status { isOk() } }.json()
		assertEquals(true, listed["items"][0]["likedByMe"].asBoolean())
		assertEquals(1, listed["items"][0]["likesCount"].asInt())
		assertFalse(json("/api/v1/experiences?placeId=${offering.placeId}")["items"][0].has("likedByMe"))
		// Token inválido numa leitura pública → 401, nunca visitante.
		mvc.get("/api/v1/experiences/$exp") { bearer("x.y.z") }.andExpect { status { isUnauthorized() }; jsonPath("$.code") { value("INVALID_ACCESS_TOKEN") } }

		like(ana, exp).andExpect { status { isBadRequest() }; jsonPath("$.code") { value("CANNOT_LIKE_OWN") } }
		unlike(ana, exp).andExpect { status { isNoContent() } }
		mvc.put("/api/v1/experiences/$exp/like").andExpect { status { isUnauthorized() } }
		mvc.delete("/api/v1/experiences/$exp/like").andExpect { status { isUnauthorized() } }
		like(rui, UUID.randomUUID().toString()).andExpect { status { isNotFound() } }
		expectFieldErrors(like(rui, "nao-e-uuid"), "experienceId")

		unlike(rui, exp).andExpect { status { isNoContent() } }
		unlike(rui, exp).andExpect { status { isNoContent() } }
		assertEquals(0, detail(exp)["likesCount"].asInt())
		assertEquals(false, detail(exp, rui)["likedByMe"].asBoolean())

		// 8 pedidos simultâneos da mesma pessoa → um gosto.
		val responses = concurrently(8) { http("PUT", "/api/v1/experiences/$exp/like", eva.token) }
		assertEquals(List(8) { 204 }, responses.map { it.statusCode() })
		assertEquals(1L, count("SELECT count(*) FROM experience_like WHERE experience_id = :u", UUID.fromString(exp)))
		// Gostos não mexem na avaliação.
		assertEquals("4.0", json("/api/v1/ratings/places?ids=${offering.placeId}")["items"][0]["rating"]["average"].decimalValue().toPlainString())
	}

	@Test
	fun `pessoas diferentes em simultaneo dao contagens exatas`() {
		val ana = newUser("ana")
		val people = (1..8).map { newUser("p") }
		val exp = publish(ana, catalog.add())

		val results = concurrently(8) { i ->
			val like = http("PUT", "/api/v1/experiences/$exp/like", people[i].token)
			val comment = http("POST", "/api/v1/experiences/$exp/comments", people[i].token, """{"text":"Olá $i"}""", mapOf("Idempotency-Key" to UUID.randomUUID().toString()))
			like.statusCode() to comment.statusCode()
		}
		assertEquals(List(8) { 204 to 201 }, results)
		val counted = detail(exp)
		assertEquals(8, counted["likesCount"].asInt())
		assertEquals(8, counted["commentsCount"].asInt())
	}

	@Test
	fun `ordenacoes por gostos e por interacoes com desempate estavel`() {
		val ana = newUser("ana")
		val fans = (1..3).map { newUser("fan") }
		val offering = catalog.add()
		val e1 = publish(ana, offering)
		val e2 = publish(ana, offering)
		val e3 = publish(ana, offering)
		val e4 = publish(ana, offering)
		// Gostos: e1=2, e2=0, e3=2, e4=1. Comentários: e2=3, e4=1.
		listOf(e1 to 2, e3 to 2, e4 to 1).forEach { (e, n) -> fans.take(n).forEach { like(it, e).andExpect { status { isNoContent() } } } }
		val e2comments = fans.map { commentId(comment(it, e2, "Comentário")) }
		comment(fans[0], e4, "Comentário").andExpect { status { isCreated() } }
		// Visualizações não entram em nenhuma ordenação.
		fans.forEach { view(e2, it.token).andExpect { status { isNoContent() } } }

		val place = offering.placeId
		assertEquals(listOf(e4, e3, e2, e1), ids("/api/v1/experiences?placeId=$place"))
		assertEquals(listOf(e3, e1, e4, e2), ids("/api/v1/experiences?placeId=$place&sort=likes"))
		// Interações: e2=3, e4=2, e3=2, e1=2 → empates por data mais recente.
		assertEquals(listOf(e2, e4, e3, e1), ids("/api/v1/experiences?placeId=$place&sort=interactions"))
		assertEquals(listOf(e2, e4), ids("/api/v1/experiences?placeId=$place&sort=interactions&size=2"))
		assertEquals(listOf(e3, e1), ids("/api/v1/experiences?placeId=$place&sort=interactions&size=2&page=1"))

		// Um comentário eliminado deixa de contar: e2=2 empata com os outros.
		mvc.delete("/api/v1/comments/${e2comments[0]}") { bearer(fans[0].token) }.andExpect { status { isNoContent() } }
		assertEquals(listOf(e4, e3, e2, e1), ids("/api/v1/experiences?placeId=$place&sort=interactions"))
		val items = json("/api/v1/experiences?placeId=$place&sort=interactions")["items"].elements
		assertEquals(listOf(1 to 1, 2 to 0, 0 to 2, 2 to 0), items.map { it["likesCount"].asInt() to it["commentsCount"].asInt() })
	}

	@Test
	fun `eliminar a experiencia apaga comentarios, gostos e visualizacoes`() {
		val ana = newUser("ana")
		val rui = newUser("rui")
		val exp = publish(ana, catalog.add())
		val key = UUID.randomUUID().toString()
		val c = commentId(comment(rui, exp, "Comentário que vai desaparecer", key))
		like(rui, exp).andExpect { status { isNoContent() } }
		view(exp, rui.token).andExpect { status { isNoContent() } }
		view(exp, null, UUID.randomUUID().toString()).andExpect { status { isNoContent() } }
		val id = UUID.fromString(exp)
		assertEquals(2L, count("SELECT count(*) FROM experience_view WHERE experience_id = :u", id))

		mvc.delete("/api/v1/experiences/$exp") { bearer(ana.token) }.andExpect { status { isNoContent() } }

		assertEquals(0L, count("SELECT count(*) FROM experience_like WHERE experience_id = :u", id))
		assertEquals(0L, count("SELECT count(*) FROM experience_view WHERE experience_id = :u", id))
		assertEquals(0L, count("SELECT count(*) FROM comment WHERE experience_id = :u AND (deleted_at IS NULL OR text IS NOT NULL)", id))
		mvc.get("/api/v1/experiences/$exp/comments").andExpect { status { isNotFound() } }
		comment(rui, exp, "Depois").andExpect { status { isNotFound() } }
		like(rui, exp).andExpect { status { isNotFound() } }
		unlike(rui, exp).andExpect { status { isNotFound() } }
		view(exp, rui.token).andExpect { status { isNotFound() } }
		view(exp, null, UUID.randomUUID().toString()).andExpect { status { isNotFound() } }
		// O comentário fica só como marca: repetir a chave não o recria; o autor pode repetir a eliminação.
		comment(rui, exp, "Comentário que vai desaparecer", key).andExpect { status { isConflict() }; jsonPath("$.reason") { value("COMMENT_DELETED") } }
		mvc.delete("/api/v1/comments/$c") { bearer(rui.token) }.andExpect { status { isNoContent() } }
		mvc.patch("/api/v1/comments/$c") { json("""{"text":"Volta"}"""); bearer(rui.token) }.andExpect { status { isNotFound() } }
	}

	// --- Visualizações ---

	@Test
	fun `visualizacoes contam uma vez por pessoa ou chave em cada periodo, sem o autor`() {
		val ana = newUser("ana")
		val rui = newUser("rui")
		val exp = publish(ana, catalog.add())
		val id = UUID.fromString(exp)
		fun views() = count("SELECT count(*) FROM experience_view WHERE experience_id = :u", id)

		repeat(3) { view(exp, rui.token).andExpect { status { isNoContent() } } }
		assertEquals(1, views())
		val k1 = UUID.randomUUID().toString()
		repeat(2) { view(exp, null, k1).andExpect { status { isNoContent() } } }
		assertEquals(2, views())
		view(exp, null, UUID.randomUUID().toString().uppercase()).andExpect { status { isNoContent() } }
		assertEquals(3, views())
		// Sem chave e o autor: 204, não contam. Com token, a chave é ignorada.
		view(exp, null).andExpect { status { isNoContent() } }
		view(exp, ana.token).andExpect { status { isNoContent() } }
		view(exp, rui.token, "nao-e-uuid").andExpect { status { isNoContent() } }
		assertEquals(3, views())
		expectFieldErrors(view(exp, null, "nao-e-uuid"), "X-Viewer-Key")
		view(exp, "x.y.z").andExpect { status { isUnauthorized() } }
		view(UUID.randomUUID().toString(), rui.token).andExpect { status { isNotFound() } }

		// GET nunca escreve; nenhuma resposta pública mostra visualizações ou hashes.
		mvc.get("/api/v1/experiences/$exp") { bearer(rui.token) }.andExpect {
			status { isOk() }
			content { string(not(containsString("view"))) }
		}
		json("/api/v1/experiences?authorId=${ana.id}")
		json("/api/v1/experiences/$exp/comments")
		assertEquals(3, views())
		// Guarda só hashes (64 hexadecimais), nunca a chave nem o id.
		val hashes = jdbc.sql("SELECT viewer_hash FROM experience_view WHERE experience_id = :u").param("u", id).query { rs, _ -> rs.getString(1) }.list()
		assertTrue(hashes.all { it.matches(Regex("^[0-9a-f]{64}$")) })
		assertFalse(hashes.any { it.contains(k1.replace("-", "")) })

		// Pedidos simultâneos da mesma chave → uma visualização.
		val k2 = UUID.randomUUID().toString()
		val responses = concurrently(8) { http("POST", "/api/v1/experiences/$exp/views", null, headers = mapOf("X-Viewer-Key" to k2)) }
		assertEquals(List(8) { 204 }, responses.map { it.statusCode() })
		assertEquals(4, views())

		// No período seguinte, a mesma pessoa e a mesma chave contam de novo.
		clock.advance(Duration.ofHours(24))
		view(exp, rui.token).andExpect { status { isNoContent() } }
		view(exp, rui.token).andExpect { status { isNoContent() } }
		view(exp, null, k1).andExpect { status { isNoContent() } }
		view(exp, ana.token).andExpect { status { isNoContent() } }
		assertEquals(6, views())
	}

	// --- Limites ---

	@Test
	fun `limites de comentarios e de mudancas de gosto com Retry-After`() {
		val ana = newUser("ana")
		val rui = newUser("rui")
		val exp = publish(ana, catalog.add())

		val keys = (1..COMMENT_LIMIT).map { UUID.randomUUID().toString() }
		keys.forEach { comment(rui, exp, "Comentário $it", it).andExpect { status { isCreated() } } }
		comment(rui, exp, "Mais um").andExpect {
			status { isTooManyRequests() }
			jsonPath("$.code") { value("TOO_MANY_REQUESTS") }
			header { exists("Retry-After") }
		}
		// Repetições continuam a responder e não contam.
		comment(rui, exp, "Comentário ${keys[0]}", keys[0]).andExpect { status { isOk() } }
		comment(newUser("eva"), exp, "Outra pessoa").andExpect { status { isCreated() } }

		// Gostos: só contam as mudanças, não as repetições.
		like(rui, exp).andExpect { status { isNoContent() } }
		like(rui, exp).andExpect { status { isNoContent() } }
		repeat(LIKE_LIMIT - 1) { i -> (if (i % 2 == 0) unlike(rui, exp) else like(rui, exp)).andExpect { status { isNoContent() } } }
		like(rui, exp).andExpect { status { isTooManyRequests() }; header { exists("Retry-After") } }
	}

	// --- Auxiliares ---

	private fun publish(user: TestUser, offering: CatalogStub.TestOffering): String =
		mvc.post("/api/v1/experiences") {
			json(mapper.writeValueAsString(mapOf("placeId" to offering.placeId, "offeringId" to offering.id, "text" to "Experiência", "stars" to 4)))
			bearer(user.token)
			header("Idempotency-Key", UUID.randomUUID().toString())
		}.andExpect {
			status { isCreated() }
			jsonPath("$.likesCount") { value(0) }
			jsonPath("$.commentsCount") { value(0) }
			jsonPath("$.likedByMe") { doesNotExist() }
		}.json()["id"].asString()

	private fun comment(user: TestUser, exp: String, text: String, key: String = UUID.randomUUID().toString()) =
		commentRaw(user, exp, mapper.writeValueAsString(mapOf("text" to text)), key)

	private fun commentRaw(user: TestUser, exp: String, body: String, key: String? = UUID.randomUUID().toString()): ResultActionsDsl =
		mvc.post("/api/v1/experiences/$exp/comments") {
			json(body)
			bearer(user.token)
			if (key != null) header("Idempotency-Key", key)
		}

	private fun commentId(result: ResultActionsDsl): String = result.andExpect { status { isCreated() } }.json()["id"].asString()

	private fun commentIds(exp: String, query: String = ""): List<String> =
		json("/api/v1/experiences/$exp/comments?$query")["items"].elements.map { it["id"].asString() }

	private fun like(user: TestUser, exp: String) = mvc.put("/api/v1/experiences/$exp/like") { bearer(user.token) }

	private fun unlike(user: TestUser, exp: String) = mvc.delete("/api/v1/experiences/$exp/like") { bearer(user.token) }

	private fun view(exp: String, token: String?, viewerKey: String? = null) = mvc.post("/api/v1/experiences/$exp/views") {
		if (token != null) bearer(token)
		if (viewerKey != null) header("X-Viewer-Key", viewerKey)
	}

	private fun detail(exp: String, user: TestUser? = null): JsonNode =
		mvc.get("/api/v1/experiences/$exp") { if (user != null) bearer(user.token) }.andExpect { status { isOk() } }.json()

	private fun json(url: String): JsonNode = mvc.get(url).andExpect { status { isOk() } }.json()

	private fun ids(url: String): List<String> = json(url)["items"].elements.map { it["id"].asString() }

	private fun count(sql: String, id: UUID): Long = jdbc.sql(sql).param("u", id).query(Long::class.javaObjectType).single()

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

	private fun MockHttpServletRequestDsl.bearer(token: String) {
		header("Authorization", "Bearer $token")
	}

	private fun ResultActionsDsl.json(): JsonNode = mapper.readTree(andReturn().response.contentAsString)

	private val JsonNode.elements: List<JsonNode> get() = (0 until size()).map { this[it] }

	private fun http(method: String, path: String, token: String?, body: String? = null, headers: Map<String, String> = emptyMap()): HttpResponse<String> {
		val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
			.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
		if (body != null) builder.header("Content-Type", "application/json")
		if (token != null) builder.header("Authorization", "Bearer $token")
		headers.forEach { (name, value) -> builder.header(name, value) }
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
}
