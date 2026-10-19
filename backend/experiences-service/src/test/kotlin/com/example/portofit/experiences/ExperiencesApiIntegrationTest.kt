package com.example.portofit.experiences

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.SecurityContext
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.containsString
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
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyPair
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Testes de integração do experiences-service contra PostgreSQL real (Testcontainers), com um falso
 * catalog-service e tokens assinados por uma chave só dos testes (o serviço recebe só a chave pública).
 * Cada teste usa autores, locais e atividades novos, para não depender dos outros.
 */
class ExperiencesApiIntegrationTest : ApiIntegrationTest() {

	@AfterEach
	fun restoreCatalog() {
		catalog.mode = CatalogStub.Mode.UP
	}

	// --- Publicar ---

	@Test
	fun `publicar copia o autor do token e os nomes do catalogo`() {
		val ana = newUser("ana")
		val boxe = catalog.add()
		catalog.requests.clear()

		val result = publish(ana, boxe, text = "  Primeira aula.\r\nMuito trabalho de pés.  ", stars = 4).andExpect {
			status { isCreated() }
			header { string("Location", containsString("/api/v1/experiences/")) }
			jsonPath("$.author.userId") { value(ana.id.toString()) }
			jsonPath("$.author.username") { value(ana.username) }
			jsonPath("$.place.id") { value(boxe.placeId.toString()) }
			jsonPath("$.place.name") { value("Clube de Combate Ribeira (fictício)") }
			jsonPath("$.place.demo") { value(true) }
			jsonPath("$.offering.id") { value(boxe.id.toString()) }
			jsonPath("$.offering.name") { value("Boxe iniciação") }
			jsonPath("$.sport.id") { value("boxe") }
			jsonPath("$.sport.name") { value("Boxe") }
			jsonPath("$.text") { value("Primeira aula.\nMuito trabalho de pés.") }
			jsonPath("$.stars") { value(4) }
			jsonPath("$.createdAt") { exists() }
			jsonPath("$.editedAt") { value(null as Any?) }
			header { doesNotExist("Idempotent-Replayed") }
		}.andReturn().json()

		val id = UUID.fromString(result["id"].asString())
		assertEquals(7, id.version(), "UUID versão 7 gerado pelo PostgreSQL")
		assertEquals(listOf("/api/v1/offerings/${boxe.id}?includeRating=false"), catalog.requests.toList())
		mvc.get("/api/v1/experiences/$id").andExpect {
			status { isOk() }
			jsonPath("$.text") { value("Primeira aula.\nMuito trabalho de pés.") }
			content { string(not(containsString("email"))) }
		}
	}

	@Test
	fun `autenticacao local do token`() {
		val boxe = catalog.add()
		val body = publishBody(boxe, "Texto", 3)
		val userId = UUID.randomUUID()

		mvc.post("/api/v1/experiences") { json(body); header("Idempotency-Key", UUID.randomUUID().toString()) }.andExpect {
			status { isUnauthorized() }
			jsonPath("$.code") { value("AUTHENTICATION_REQUIRED") }
		}
		val invalid = listOf(
			sign(userId, "rui", expiresAt = Instant.now().minusSeconds(120)),
			sign(userId, "rui", keyPair = TestKeys.generate()),
			sign(userId, "rui", issuer = "outro"),
			sign(userId, "rui", audience = "outra-api"),
			"cabecalho.semassinatura.",
		)
		for (token in invalid) {
			mvc.post("/api/v1/experiences") { json(body); bearer(token); header("Idempotency-Key", UUID.randomUUID().toString()) }.andExpect {
				status { isUnauthorized() }
				jsonPath("$.code") { value("INVALID_ACCESS_TOKEN") }
				header { string("WWW-Authenticate", containsString("invalid_token")) }
			}
		}
		// Token válido sem a claim username: o autor não é guardado sem nome.
		mvc.post("/api/v1/experiences") { json(body); bearer(sign(userId, null)); header("Idempotency-Key", UUID.randomUUID().toString()) }.andExpect {
			status { isUnauthorized() }
			jsonPath("$.code") { value("INVALID_ACCESS_TOKEN") }
		}
		assertEquals(0, countByAuthor(userId))

		// Leituras públicas: sem token funcionam; com um token inválido dão 401 (não são tratadas como visitante).
		mvc.get("/api/v1/experiences").andExpect { status { isOk() } }
		mvc.get("/api/v1/experiences") { bearer(invalid[0]) }.andExpect { status { isUnauthorized() } }
	}

	@Test
	fun `validacoes da publicacao`() {
		val ana = newUser("ana")
		val boxe = catalog.add()
		for (stars in listOf("0", "6", "4.5", "4.0", "\"4\"", "null", "99999999999")) {
			expectFieldErrors(publishRaw(ana, """{"placeId":"${boxe.placeId}","offeringId":"${boxe.id}","text":"Bom","stars":$stars}"""), "stars")
		}
		for (text in listOf("\"\"", "\"   \\n\\t  \"", "\"\\u00a0\"", "\"${"a".repeat(2001)}\"", "\"com campainha \\u0007\"", "null", "5")) {
			expectFieldErrors(publishRaw(ana, """{"placeId":"${boxe.placeId}","offeringId":"${boxe.id}","text":$text,"stars":3}"""), "text")
		}
		expectFieldErrors(publishRaw(ana, """{"offeringId":"nao-uuid","text":"x","stars":3,"authorId":"${UUID.randomUUID()}","createdAt":"2020-01-01T00:00:00Z"}"""), "placeId", "offeringId", "authorId", "createdAt")
		expectFieldErrors(publishRaw(ana, publishBody(boxe, "Bom", 3), key = null), "Idempotency-Key")
		expectFieldErrors(publishRaw(ana, publishBody(boxe, "Bom", 3), key = "nao-e-uuid"), "Idempotency-Key")
		expectFieldErrors(publishRaw(ana, "[1,2]"), "body")
		expectFieldErrors(publishRaw(ana, "{ invalido"), "body")

		mvc.post("/api/v1/experiences") {
			bearer(ana.token); header("Idempotency-Key", UUID.randomUUID().toString())
			contentType = MediaType.TEXT_PLAIN; content = "texto"
		}.andExpect { status { isUnsupportedMediaType() }; jsonPath("$.code") { value("UNSUPPORTED_MEDIA_TYPE") } }
		publishRaw(ana, """{"placeId":"${boxe.placeId}","offeringId":"${boxe.id}","text":"${"b".repeat(17000)}","stars":3}""").andExpect {
			status { isPayloadTooLarge() }
			jsonPath("$.code") { value("PAYLOAD_TOO_LARGE") }
		}
		assertEquals(0, countByAuthor(ana.id))

		// 2000 caracteres (pontos de código), incluindo emojis com dois carateres UTF-16 cada: aceite.
		val emojis = "😀".repeat(1000) + "a".repeat(1000)
		publish(ana, boxe, text = emojis, stars = 1).andExpect { status { isCreated() } }
	}

	@Test
	fun `atividade inexistente, de outro local ou retirada`() {
		val ana = newUser("ana")
		val boxe = catalog.add()
		publishRaw(ana, publishBody(boxe, "Bom", 3).replace(boxe.id.toString(), UUID.randomUUID().toString())).andExpect {
			status { isBadRequest() }
			jsonPath("$.code") { value("UNKNOWN_OFFERING") }
			jsonPath("$.errors[*].field") { value(containsInAnyOrder("offeringId")) }
		}
		publishRaw(ana, publishBody(boxe, "Bom", 3).replace(boxe.placeId.toString(), UUID.randomUUID().toString())).andExpect {
			status { isBadRequest() }
			jsonPath("$.code") { value("OFFERING_PLACE_MISMATCH") }
			jsonPath("$.errors[*].field") { value(containsInAnyOrder("placeId", "offeringId")) }
		}
		publish(ana, catalog.add(status = "RETIRED"), "Bom", 3).andExpect {
			status { isBadRequest() }
			jsonPath("$.code") { value("OFFERING_RETIRED") }
		}
		assertEquals(0, countByAuthor(ana.id))
	}

	@Test
	fun `catalogo indisponivel com e sem memoria valida`() {
		val ana = newUser("ana")
		val cached = catalog.add()
		val other = catalog.add()
		publish(ana, cached, "Primeira", 4).andExpect { status { isCreated() } }

		catalog.mode = CatalogStub.Mode.ERROR
		// Atividade validada há menos de 10 min: aceite sem o catálogo.
		publish(ana, cached, "Segunda", 3).andExpect { status { isCreated() } }
		// Atividade nunca validada: 503, sem criar nada.
		publish(ana, other, "Terceira", 3).andExpect {
			status { isServiceUnavailable() }
			jsonPath("$.code") { value("CATALOG_UNAVAILABLE") }
		}
		assertEquals(2, countByAuthor(ana.id))

		// Lento: a espera é limitada pelo prazo (2 s), sem novas tentativas.
		catalog.mode = CatalogStub.Mode.SLOW
		val started = System.nanoTime()
		publish(ana, other, "Quarta", 3).andExpect { status { isServiceUnavailable() } }
		assertTrue((System.nanoTime() - started) / 1_000_000 < 3500)

		// Mais de 10 min depois, a memória já não é usada.
		catalog.mode = CatalogStub.Mode.ERROR
		clock.advance(Duration.ofMinutes(11))
		publish(ana, cached, "Quinta", 3).andExpect { status { isServiceUnavailable() } }

		catalog.mode = CatalogStub.Mode.UP
		publish(ana, other, "Sexta", 3).andExpect { status { isCreated() } }
		assertEquals(3, countByAuthor(ana.id))
	}

	// --- Idempotência ---

	@Test
	fun `repeticao da mesma tentativa devolve 200 com a mesma experiencia, mesmo sem catalogo`() {
		val ana = newUser("ana")
		val boxe = catalog.add()
		val key = UUID.randomUUID().toString()
		val created = publish(ana, boxe, "Aula muito boa.", 5, key).andExpect { status { isCreated() } }.andReturn().json()
		val id = created["id"].asString()

		catalog.mode = CatalogStub.Mode.ERROR
		catalog.requests.clear()
		// Diferenças só de espaços nas pontas e de fim de linha não são conteúdo diferente.
		publish(ana, boxe, "  Aula muito boa.\r\n", 5, key).andExpect {
			status { isOk() }
			header { string("Idempotent-Replayed", "true") }
			header { string("Location", "/api/v1/experiences/$id") }
			jsonPath("$.id") { value(id) }
			jsonPath("$.text") { value("Aula muito boa.") }
		}
		assertTrue(catalog.requests.isEmpty())

		publish(ana, boxe, "Outro texto", 5, key).andExpect {
			status { isConflict() }
			jsonPath("$.code") { value("IDEMPOTENCY_CONFLICT") }
			jsonPath("$.reason") { value("DIFFERENT_CONTENT") }
			jsonPath("$.experienceId") { value(id) }
		}
		assertEquals(1, countByAuthor(ana.id))

		// Eliminada: a repetição não recria nem devolve o conteúdo apagado.
		mvc.delete("/api/v1/experiences/$id") { bearer(ana.token) }.andExpect { status { isNoContent() } }
		for (text in listOf("Aula muito boa.", "Outro texto")) {
			publish(ana, boxe, text, 5, key).andExpect {
				status { isConflict() }
				jsonPath("$.reason") { value("EXPERIENCE_DELETED") }
				jsonPath("$.experienceId") { doesNotExist() }
				content { string(not(containsString("Aula muito boa"))) }
			}
		}
		assertEquals(0, countByAuthor(ana.id))
		assertEquals(1, jdbc.sql("SELECT count(*) FROM experience WHERE author_id = :a").param("a", ana.id).query(Long::class.javaObjectType).single().toInt())
	}

	@Test
	fun `pedidos simultaneos com a mesma chave criam uma so experiencia`() {
		val ana = newUser("ana")
		val boxe = catalog.add()
		val key = UUID.randomUUID().toString()
		val body = publishBody(boxe, "Pedido repetido pela rede", 4)
		val responses = concurrently(8) { httpPost("/api/v1/experiences", body, ana.token, key) }

		assertEquals(1, responses.count { it.statusCode() == 201 }, responses.map { it.statusCode() }.toString())
		assertEquals(7, responses.count { it.statusCode() == 200 && it.headers().firstValue("Idempotent-Replayed").orElse("") == "true" })
		assertEquals(1, responses.map { mapper.readTree(it.body())["id"].asString() }.toSet().size)
		assertEquals(1, countByAuthor(ana.id))
	}

	@Test
	fun `chave de tentativa recusada pode ser usada de novo e chaves sao por autor`() {
		val ana = newUser("ana")
		val rui = newUser("rui")
		val boxe = catalog.add()
		val key = UUID.randomUUID().toString()

		catalog.mode = CatalogStub.Mode.ERROR
		publish(ana, boxe, "Texto", 4, key).andExpect { status { isServiceUnavailable() } }
		catalog.mode = CatalogStub.Mode.UP
		publish(ana, boxe, "Texto", 4, key).andExpect { status { isCreated() } }
		publish(ana, boxe, "Texto", 4, key).andExpect { status { isOk() } }
		publish(rui, boxe, "Texto", 4, key).andExpect { status { isCreated() } }
		assertEquals(1, countByAuthor(ana.id))
		assertEquals(1, countByAuthor(rui.id))
	}

	@Test
	fun `limite de publicacoes por autor com Retry-After`() {
		val ana = newUser("ana")
		val boxe = catalog.add()
		val firstKey = UUID.randomUUID().toString()
		publish(ana, boxe, "1", 3, firstKey).andExpect { status { isCreated() } }
		repeat(LIMIT - 1) { publish(ana, boxe, "n$it", 3).andExpect { status { isCreated() } } }

		val retryAfter = publish(ana, boxe, "a mais", 3).andExpect {
			status { isTooManyRequests() }
			jsonPath("$.code") { value("TOO_MANY_REQUESTS") }
		}.andReturn().response.getHeader("Retry-After")!!.toLong()
		assertTrue(retryAfter in 1..3600)
		// Uma repetição não conta e continua a funcionar no limite; outra pessoa não é afetada.
		publish(ana, boxe, "1", 3, firstKey).andExpect { status { isOk() } }
		publish(newUser("rui"), boxe, "outro autor", 3).andExpect { status { isCreated() } }
		assertEquals(LIMIT, countByAuthor(ana.id))

		clock.advance(Duration.ofHours(1).plusSeconds(1))
		publish(ana, catalog.add(), "depois da janela", 3).andExpect { status { isCreated() } }
	}

	// --- Listar, pesquisar, detalhe ---

	@Test
	fun `listar com filtros, pesquisa e paginacao`() {
		val place = UUID.randomUUID()
		val boxe = catalog.add(placeId = place)
		val kick = catalog.add(placeId = place, name = "Kickboxing aberto", sportId = "kickboxing", sportName = "Kickboxing")
		val ana = newUser("ana")
		val rui = newUser("rui")
		val e1 = id(publish(ana, boxe, "Treino com sacos, ótimo.", 4))
		val e2 = id(publish(ana, kick, "Natação não, mas kick sim! 100%", 2))
		val e3 = id(publish(rui, boxe, "Ambiente simpático.", 5))

		assertEquals(listOf(e3, e2, e1), ids("/api/v1/experiences?placeId=$place"))
		assertEquals(listOf(e3, e1), ids("/api/v1/experiences?offeringId=${boxe.id}"))
		assertEquals(listOf(e2), ids("/api/v1/experiences?placeId=$place&sportId=kickboxing"))
		assertEquals(listOf(e2, e1), ids("/api/v1/experiences?placeId=$place&authorId=${ana.id}"))
		assertEquals(listOf(e3), ids("/api/v1/experiences?authorId=${rui.id}&sportId=boxe&offeringId=${boxe.id}"))
		// Pesquisa no texto e nas cópias dos nomes, sem maiúsculas nem acentos.
		assertEquals(listOf(e3, e1), ids("/api/v1/experiences?placeId=$place&q=BOXE"))
		assertEquals(listOf(e2), ids("/api/v1/experiences?placeId=$place&q=natacao"))
		assertEquals(listOf(e3), ids("/api/v1/experiences?placeId=$place&q=SIMPÁTICO"))
		assertEquals(listOf(e3, e2, e1), ids("/api/v1/experiences?placeId=$place&q=ribeira"))
		// "%" é procurado literalmente (o texto de e2 tem "100%"); param() evita a codificação dupla do MockMvc.
		val percent = mvc.get("/api/v1/experiences") { param("placeId", place.toString()); param("q", "0%") }.andReturn().json()
		assertEquals(listOf(e2), percent["items"].elements.map { it["id"].asString() })
		val underscore = mvc.get("/api/v1/experiences") { param("placeId", place.toString()); param("q", "s_m") }.andReturn().json()
		assertEquals(0, underscore["totalItems"].asInt())
		assertEquals(emptyList(), ids("/api/v1/experiences?placeId=$place&q=a_b"))
		// Melhor avaliação do local: estrelas, depois data.
		assertEquals(listOf(e3, e1, e2), ids("/api/v1/experiences?placeId=$place&sort=stars"))

		val first = json("/api/v1/experiences?placeId=$place&size=2")
		assertEquals(3, first["totalItems"].asInt())
		assertEquals(2, first["totalPages"].asInt())
		val second = ids("/api/v1/experiences?placeId=$place&size=2&page=1")
		assertEquals(listOf(e3, e2, e1), first["items"].elements.map { it["id"].asString() } + second)
		assertEquals(emptyList(), ids("/api/v1/experiences?placeId=$place&size=2&page=5"))

		// Ids desconhecidos não são erro; parâmetros vazios são ignorados.
		assertEquals(emptyList(), ids("/api/v1/experiences?placeId=${UUID.randomUUID()}"))
		assertEquals(emptyList(), ids("/api/v1/experiences?sportId=quidditch&placeId=$place"))
		assertEquals(listOf(e3, e2, e1), ids("/api/v1/experiences?placeId=$place&q=&sort=&sportId="))

		expectFieldErrors(mvc.get("/api/v1/experiences?sort=views"), "sort")
		expectFieldErrors(mvc.get("/api/v1/experiences?q=a&placeId=x&authorId=1&offeringId=2&sportId=Boxe!&page=-1&size=51"),
			"q", "placeId", "authorId", "offeringId", "sportId", "page", "size")
	}

	@Test
	fun `detalhe, eliminadas e identificadores invalidos`() {
		val ana = newUser("ana")
		val boxe = catalog.add()
		val id = id(publish(ana, boxe, "Para apagar", 3))
		mvc.get("/api/v1/experiences/$id").andExpect { status { isOk() } }
		expectFieldErrors(mvc.get("/api/v1/experiences/nao-uuid"), "experienceId")
		mvc.get("/api/v1/experiences/${UUID.randomUUID()}").andExpect {
			status { isNotFound() }
			jsonPath("$.code") { value("NOT_FOUND") }
		}
		mvc.delete("/api/v1/experiences/$id") { bearer(ana.token) }.andExpect { status { isNoContent() } }
		mvc.get("/api/v1/experiences/$id").andExpect { status { isNotFound() } }
		assertEquals(emptyList(), ids("/api/v1/experiences?placeId=${boxe.placeId}"))
		assertEquals(emptyList(), ids("/api/v1/experiences?authorId=${ana.id}"))
	}

	// --- Editar e eliminar ---

	@Test
	fun `editar so pelo autor, sem mudar a atividade nem a data`() {
		val ana = newUser("ana")
		val rui = newUser("rui")
		val boxe = catalog.add()
		val created = publish(ana, boxe, "Original", 3).andReturn().json()
		val id = created["id"].asString()
		val path = "/api/v1/experiences/$id"

		mvc.patch(path) { json("""{"stars":5}""") }.andExpect { status { isUnauthorized() } }
		mvc.patch(path) { json("""{"stars":5}"""); bearer(rui.token) }.andExpect {
			status { isForbidden() }
			jsonPath("$.code") { value("FORBIDDEN") }
		}
		expectFieldErrors(mvc.patch(path) { json("""{"offeringId":"${UUID.randomUUID()}","placeId":"${UUID.randomUUID()}"}"""); bearer(ana.token) }, "offeringId", "placeId")
		mvc.patch(path) { json("""{"offeringId":"${UUID.randomUUID()}"}"""); bearer(ana.token) }.andExpect {
			jsonPath("$.errors[0].message") { value("A atividade não pode ser alterada. Apaga a experiência e publica uma nova.") }
		}
		expectFieldErrors(mvc.patch(path) { json("""{"createdAt":"2020-01-01T00:00:00Z"}"""); bearer(ana.token) }, "createdAt")
		expectFieldErrors(mvc.patch(path) { json("{}"); bearer(ana.token) }, "text")
		expectFieldErrors(mvc.patch(path) { json("""{"stars":0,"text":"   "}"""); bearer(ana.token) }, "stars", "text")
		expectFieldErrors(mvc.patch(path) { json("""{"text":null}"""); bearer(ana.token) }, "text")

		// Mesmos valores: 200 sem alterar nada.
		mvc.patch(path) { json("""{"stars":3,"text":" Original "}"""); bearer(ana.token) }.andExpect {
			status { isOk() }
			jsonPath("$.editedAt") { value(null as Any?) }
		}
		val edited = mvc.patch(path) { json("""{"stars":5}"""); bearer(ana.token) }.andExpect {
			status { isOk() }
			jsonPath("$.stars") { value(5) }
			jsonPath("$.text") { value("Original") }
			jsonPath("$.editedAt") { exists() }
			jsonPath("$.offering.id") { value(boxe.id.toString()) }
		}.andReturn().json()
		assertEquals(created["createdAt"].asString(), edited["createdAt"].asString())

		mvc.delete(path) { bearer(ana.token) }
		mvc.patch(path) { json("""{"stars":4}"""); bearer(ana.token) }.andExpect { status { isNotFound() } }
	}

	@Test
	fun `eliminar so pelo autor, de forma idempotente e apagando o conteudo`() {
		val ana = newUser("ana")
		val rui = newUser("rui")
		val id = id(publish(ana, catalog.add(), "Conteúdo a apagar", 2))
		val path = "/api/v1/experiences/$id"

		mvc.delete(path).andExpect { status { isUnauthorized() } }
		mvc.delete(path) { bearer(rui.token) }.andExpect { status { isForbidden() } }
		mvc.delete(path) { bearer(ana.token) }.andExpect { status { isNoContent() } }
		mvc.delete(path) { bearer(ana.token) }.andExpect { status { isNoContent() } }
		mvc.delete(path) { bearer(rui.token) }.andExpect { status { isNotFound() } }
		mvc.delete("/api/v1/experiences/${UUID.randomUUID()}") { bearer(ana.token) }.andExpect { status { isNotFound() } }

		val row = jdbc.sql("SELECT text, stars, deleted_at FROM experience WHERE id = :id").param("id", UUID.fromString(id))
			.query { rs, _ -> Triple(rs.getString("text"), rs.getObject("stars"), rs.getTimestamp("deleted_at")) }.single()
		assertNull(row.first)
		assertNull(row.second)
		assertTrue(row.third != null)
	}

	// --- Avaliações ---

	@Test
	fun `avaliacao com peso por pessoa - exemplo da arquitetura`() {
		val place = UUID.randomUUID()
		val boxe = catalog.add(placeId = place)
		val kick = catalog.add(placeId = place, name = "Kickboxing aberto", sportId = "kickboxing", sportName = "Kickboxing")
		val ana = newUser("ana")
		val rui = newUser("rui")
		val eva = newUser("eva")
		// Ana avalia duas atividades do mesmo local e publica duas vezes sobre o Boxe.
		publish(ana, boxe, "Boxe primeiro", 5)
		val anaBoxe3 = id(publish(ana, boxe, "Boxe segundo", 3))
		publish(ana, kick, "Kick", 4)
		publish(rui, boxe, "Boxe", 4)
		publish(eva, kick, "Kick", 2)

		val rating = ratings(place)
		assertEquals("AVAILABLE" to "3.2", rating["rating"].let { it["status"].asString() to it["average"].decimalValue().toPlainString() })
		assertEquals(3, rating["rating"]["ratingsCount"].asInt())
		assertEquals(mapOf(boxe.id.toString() to ("3.5" to 2), kick.id.toString() to ("3.0" to 2)), offeringRatings(rating))

		// Eliminar a vigente de Ana no Boxe: passa a contar a anterior (5).
		mvc.delete("/api/v1/experiences/$anaBoxe3") { bearer(ana.token) }.andExpect { status { isNoContent() } }
		val after = ratings(place)
		assertEquals("3.5", after["rating"]["average"].decimalValue().toPlainString())
		assertEquals(3, after["rating"]["ratingsCount"].asInt())
		assertEquals(mapOf(boxe.id.toString() to ("4.5" to 2), kick.id.toString() to ("3.0" to 2)), offeringRatings(after))
	}

	@Test
	fun `editar estrelas da vigente e de uma antiga, e eliminar a vigente`() {
		val place = UUID.randomUUID()
		val boxe = catalog.add(placeId = place)
		val ana = newUser("ana")
		val older = id(publish(ana, boxe, "Antiga", 2))
		val current = id(publish(ana, boxe, "Recente", 4))
		assertEquals("4.0", placeAverage(place))

		mvc.patch("/api/v1/experiences/$older") { json("""{"stars":1}"""); bearer(ana.token) }.andExpect { status { isOk() } }
		assertEquals("4.0", placeAverage(place), "editar uma antiga não muda a avaliação")
		mvc.patch("/api/v1/experiences/$current") { json("""{"stars":5}"""); bearer(ana.token) }.andExpect { status { isOk() } }
		assertEquals("5.0", placeAverage(place), "editar a vigente conta de imediato")
		mvc.delete("/api/v1/experiences/$current") { bearer(ana.token) }
		assertEquals("1.0", placeAverage(place), "eliminada a vigente, conta a anterior (com o valor editado)")
		mvc.delete("/api/v1/experiences/$older") { bearer(ana.token) }
		assertEquals("NO_RATINGS", ratings(place)["rating"]["status"].asString())
		assertEquals(0, ratings(place)["rating"]["ratingsCount"].asInt())
	}

	@Test
	fun `datas de criacao empatadas usam o id maior`() {
		val place = UUID.randomUUID()
		val boxe = catalog.add(placeId = place)
		val ana = newUser("ana")
		val a = UUID.fromString(id(publish(ana, boxe, "Uma", 2)))
		val b = UUID.fromString(id(publish(ana, boxe, "Outra", 5)))
		jdbc.sql("UPDATE experience SET created_at = TIMESTAMPTZ '2026-10-19 12:00:00+00' WHERE id IN (:a, :b)").param("a", a).param("b", b).update()

		// Maior id segundo o PostgreSQL (comparação byte a byte), não segundo UUID.compareTo do Java.
		val larger = jdbc.sql("SELECT id FROM experience WHERE id IN (:a, :b) ORDER BY id DESC LIMIT 1").param("a", a).param("b", b)
			.query(UUID::class.java).single()
		val expected = if (larger == a) "2.0" else "5.0"
		repeat(3) { assertEquals(expected, placeAverage(place)) }
		// A mesma ordem em sort=recent.
		assertEquals(listOf(larger.toString(), (if (larger == a) b else a).toString()), ids("/api/v1/experiences?placeId=$place"))
	}

	@Test
	fun `avaliacoes em lote - sem dados, ordem, arredondamento e limites`() {
		val p1 = UUID.randomUUID()
		val p2 = UUID.randomUUID()
		val o1 = catalog.add(placeId = p1)
		val o2 = catalog.add(placeId = p1, name = "Kickboxing aberto", sportId = "kickboxing", sportName = "Kickboxing")
		// Atividade: 13/4 = 3,25 → 3,3 (meio para cima). Local: (3,5 + 3 + 3 + 4) / 4 = 3,375 → 3,4.
		val users = (1..4).map { newUser("p$it") }
		publish(users[0], o1, "x", 3); publish(users[0], o2, "x", 4)
		publish(users[1], o1, "x", 3)
		publish(users[2], o1, "x", 3)
		publish(users[3], o1, "x", 4)

		val response = json("/api/v1/ratings/places?ids=$p2,$p1,$p2&includeOfferings=true")
		val items = response["items"].elements
		assertEquals(listOf(p2.toString(), p1.toString()), items.map { it["placeId"].asString() })
		assertEquals("NO_RATINGS", items[0]["rating"]["status"].asString())
		assertTrue(items[0]["rating"]["average"].isNull)
		assertEquals(0, items[0]["offerings"].size())
		assertEquals("3.4", items[1]["rating"]["average"].decimalValue().toPlainString())
		assertEquals(4, items[1]["rating"]["ratingsCount"].asInt())
		assertEquals(mapOf(o1.id.toString() to ("3.3" to 4), o2.id.toString() to ("4.0" to 1)), offeringRatings(items[1]))

		val withoutOfferings = json("/api/v1/ratings/places?ids=$p1")["items"][0]
		assertFalse(withoutOfferings.has("offerings"))

		val fifty = (1..50).map { UUID.randomUUID() }
		assertEquals(50, json("/api/v1/ratings/places?ids=${fifty.joinToString(",")}")["items"].size())
		expectFieldErrors(mvc.get("/api/v1/ratings/places?ids=${(fifty + UUID.randomUUID()).joinToString(",")}"), "ids")
		expectFieldErrors(mvc.get("/api/v1/ratings/places?ids=$p1,nao-uuid"), "ids")
		expectFieldErrors(mvc.get("/api/v1/ratings/places"), "ids")
		expectFieldErrors(mvc.get("/api/v1/ratings/places?ids=$p1&includeOfferings=sim"), "includeOfferings")
	}

	// --- Auxiliares ---


	private fun publishBody(offering: CatalogStub.TestOffering, text: String, stars: Int) =
		mapper.writeValueAsString(mapOf("placeId" to offering.placeId, "offeringId" to offering.id, "text" to text, "stars" to stars))

	private fun publish(user: TestUser, offering: CatalogStub.TestOffering, text: String, stars: Int, key: String = UUID.randomUUID().toString()) =
		publishRaw(user, publishBody(offering, text, stars), key)

	private fun publishRaw(user: TestUser, body: String, key: String? = UUID.randomUUID().toString()): ResultActionsDsl =
		mvc.post("/api/v1/experiences") {
			json(body)
			bearer(user.token)
			if (key != null) header("Idempotency-Key", key)
		}

	private fun id(result: ResultActionsDsl): String = result.andExpect { status { isCreated() } }.andReturn().json()["id"].asString()

	private fun countByAuthor(authorId: UUID): Int =
		jdbc.sql("SELECT count(*) FROM experience WHERE author_id = :a AND deleted_at IS NULL").param("a", authorId)
			.query(Long::class.javaObjectType).single().toInt()

	private fun ratings(place: UUID): JsonNode = json("/api/v1/ratings/places?ids=$place&includeOfferings=true")["items"][0]

	private fun placeAverage(place: UUID): String = ratings(place)["rating"]["average"].decimalValue().toPlainString()

	private fun offeringRatings(item: JsonNode): Map<String, Pair<String, Int>> = item["offerings"].elements.associate {
		it["offeringId"].asString() to (it["rating"]["average"].decimalValue().toPlainString() to it["rating"]["ratingsCount"].asInt())
	}

	private fun json(url: String): JsonNode = mvc.get(url).andExpect { status { isOk() } }.andReturn().json()

	private fun ids(url: String): List<String> = json(url)["items"].elements.map { it["id"].asString() }

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

	private fun org.springframework.test.web.servlet.MvcResult.json(): JsonNode = mapper.readTree(response.contentAsString)

	private val JsonNode.elements: List<JsonNode> get() = (0 until size()).map { this[it] }

	private fun httpPost(path: String, body: String, token: String, key: String): HttpResponse<String> =
		HttpClient.newHttpClient().send(
			HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer $token")
				.header("Idempotency-Key", key)
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build(),
			HttpResponse.BodyHandlers.ofString(),
		)

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
