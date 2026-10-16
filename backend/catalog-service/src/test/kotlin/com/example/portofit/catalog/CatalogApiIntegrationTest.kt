package com.example.portofit.catalog

import com.example.portofit.catalog.demo.DemoDataLoader
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.everyItem
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Testes de integração contra PostgreSQL real (Testcontainers), com as migrações Flyway
 * e os dados fictícios do perfil "demo".
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@Import(TestcontainersConfiguration::class)
class CatalogApiIntegrationTest {

	@Autowired lateinit var mvc: MockMvc
	@Autowired lateinit var mapper: ObjectMapper
	@Autowired lateinit var jdbc: JdbcClient
	@Autowired lateinit var demoDataLoader: DemoDataLoader

	private val ribeira = "3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e01"
	private val boavista = "3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e02"
	private val bonfim = "3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e06"
	private val antas = "3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e09"
	private val boxePlaces = listOf(ribeira, boavista, bonfim, antas)

	// --- Modalidades ---

	@Test
	fun `lista modalidades ordenadas por nome`() {
		mvc.get("/api/v1/sports").andExpect {
			status { isOk() }
			jsonPath("$.items[0].id") { value("boxe") }
			jsonPath("$.items[*].id") { value(hasItem("natacao")) }
			jsonPath("$.items[?(@.id == 'natacao')].name") { value(hasItem("Natação")) }
		}
		val names = json("/api/v1/sports")["items"].elements.map { it["name"].asString() }
		assertEquals(names.sortedBy(::normalize), names)
	}

	// --- Pesquisa por texto ---

	@Test
	fun `pesquisa por modalidade encontra locais de boxe`() {
		mvc.get("/api/v1/places?q=boxe").andExpect {
			status { isOk() }
			jsonPath("$.items[*].id") { value(containsInAnyOrder(*boxePlaces.toTypedArray())) }
			jsonPath("$.totalItems") { value(4) }
			jsonPath("$.items[0].sports[*].id") { value(hasItem("boxe")) }
		}
	}

	@Test
	fun `pesquisa ignora maiusculas e acentos`() {
		val lower = ids("/api/v1/places?q=boxe")
		assertEquals(lower, ids("/api/v1/places?q=BOXE"))
		assertEquals(lower, ids("/api/v1/places?q=BoXe"))

		// "Natação" encontrada com e sem acentos, em maiúsculas ou minúsculas.
		val natacao = ids("/api/v1/places?q=natacao")
		assertEquals(2, natacao.size)
		assertEquals(natacao, ids("/api/v1/places?q=NATAÇÃO"))

		// Nome do local com acento ("fictício") e nome de atividade ("iniciação").
		assertEquals(10, ids("/api/v1/places?q=FICTICIO&size=50").size)
		assertTrue(ribeira in ids("/api/v1/places?q=iniciacao"))
	}

	@Test
	fun `pesquisa pelo nome do local`() {
		assertEquals(listOf(ribeira), ids("/api/v1/places?q=ribeira"))
	}

	@Test
	fun `caracteres especiais do LIKE sao procurados literalmente`() {
		assertEquals(emptyList(), ids("/api/v1/places?q=%%"))
		assertEquals(emptyList(), ids("/api/v1/places?q=__"))
	}

	// --- Filtros ---

	@Test
	fun `filtro por modalidade`() {
		mvc.get("/api/v1/places?sport=natacao").andExpect {
			status { isOk() }
			jsonPath("$.totalItems") { value(2) }
			jsonPath("$.items[*].sports[*].id") { value(hasItem("natacao")) }
		}
		json("/api/v1/places?sport=ioga")["items"].elements.forEach { place ->
			assertTrue(place["sports"].elements.any { it["id"].asString() == "ioga" })
		}
	}

	@Test
	fun `texto e modalidade combinam-se com E`() {
		assertEquals(listOf(boavista), ids("/api/v1/places?q=boavista&sport=boxe"))
		assertEquals(emptyList(), ids("/api/v1/places?q=boavista&sport=natacao"))
	}

	// --- Paginação e ordenação ---

	@Test
	fun `paginacao estavel sem repeticoes nem falhas`() {
		val all = ids("/api/v1/places?size=50")
		assertEquals(10, all.size)

		val paged = (0..3).flatMap { page -> ids("/api/v1/places?size=3&page=$page") }
		assertEquals(all, paged)

		mvc.get("/api/v1/places?size=3&page=1").andExpect {
			jsonPath("$.page") { value(1) }
			jsonPath("$.size") { value(3) }
			jsonPath("$.totalItems") { value(10) }
			jsonPath("$.totalPages") { value(4) }
		}
		// Página para além do fim: lista vazia, não é erro.
		mvc.get("/api/v1/places?size=3&page=99").andExpect {
			status { isOk() }
			jsonPath("$.items.length()") { value(0) }
			jsonPath("$.totalItems") { value(10) }
		}
	}

	@Test
	fun `ordenacao por nome ignora maiusculas e acentos`() {
		val names = json("/api/v1/places?size=50")["items"].elements.map { it["name"].asString() }
		assertEquals(names.sortedBy(::normalize), names)
	}

	// --- Proximidade ---

	@Test
	fun `proximidade devolve so locais dentro do raio ordenados por distancia`() {
		val body = json("/api/v1/places?lat=41.1496&lng=-8.6109&radiusKm=1.5")
		val distances = body["items"].elements.map { it["distanceKm"].asDouble() }
		assertTrue(distances.isNotEmpty())
		assertEquals(distances.sorted(), distances)
		assertTrue(distances.all { it <= 1.5 })
		assertTrue(boavista !in body["items"].elements.map { it["id"].asString() }) // ~1,8 km

		// Com raio maior, o local da Boavista passa a aparecer.
		assertTrue(boavista in ids("/api/v1/places?lat=41.1496&lng=-8.6109&radiusKm=2"))
	}

	@Test
	fun `distancia em km com duas casas e ausente sem coordenadas`() {
		mvc.get("/api/v1/places?lat=41.1406&lng=-8.6131&radiusKm=0.1").andExpect {
			jsonPath("$.items[0].id") { value(ribeira) }
			jsonPath("$.items[0].distanceKm") { value(0.0) }
		}
		val distance = json("/api/v1/places?lat=41.1496&lng=-8.6109&sport=boxe")["items"].elements
			.first { it["id"].asString() == ribeira }["distanceKm"].asDouble()
		assertEquals(1.02, distance) // ~1 km a sul: Haversine, 2 casas decimais
		mvc.get("/api/v1/places?q=boxe").andExpect {
			jsonPath("$.items[0].distanceKm") { doesNotExist() }
		}
	}

	@Test
	fun `proximidade com ordenacao por nome`() {
		val names = json("/api/v1/places?lat=41.1496&lng=-8.6109&radiusKm=50&sort=name&size=50")["items"].elements
			.map { it["name"].asString() }
		assertEquals(10, names.size)
		assertEquals(ids("/api/v1/places?size=50"), ids("/api/v1/places?lat=41.1496&lng=-8.6109&radiusKm=50&sort=name&size=50"))
	}

	// --- Avaliações ---

	@Test
	fun `avaliacoes indisponiveis nunca aparecem como zero`() {
		mvc.get("/api/v1/places?q=boxe").andExpect {
			jsonPath("$.items[*].rating.status") { value(everyItem(org.hamcrest.Matchers.equalTo("UNAVAILABLE"))) }
			jsonPath("$.items[0].rating.average") { value(null as Any?) }
			jsonPath("$.items[0].rating.ratingsCount") { value(null as Any?) }
		}
		mvc.get("/api/v1/places/$ribeira").andExpect {
			jsonPath("$.rating.status") { value("UNAVAILABLE") }
			jsonPath("$.offerings[*].rating.status") { value(everyItem(org.hamcrest.Matchers.equalTo("UNAVAILABLE"))) }
		}
	}

	// --- Detalhe ---

	@Test
	fun `detalhe de um local com atividades`() {
		mvc.get("/api/v1/places/$ribeira").andExpect {
			status { isOk() }
			jsonPath("$.name") { value("Clube de Combate Ribeira (fictício)") }
			jsonPath("$.demo") { value(true) }
			jsonPath("$.address.postalCode") { value("4050-001") }
			jsonPath("$.location.latitude") { value(41.1406) }
			jsonPath("$.contacts.phone") { value(null as Any?) }
			jsonPath("$.offerings.length()") { value(2) }
			jsonPath("$.offerings[0].sport.id") { value("boxe") }
			jsonPath("$.offerings[0].level") { value("BEGINNER") }
			jsonPath("$.offerings[1].sport.id") { value("kickboxing") }
		}
	}

	@Test
	fun `local inexistente devolve 404`() {
		mvc.get("/api/v1/places/00000000-0000-0000-0000-000000000000").andExpect {
			status { isNotFound() }
			content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
			jsonPath("$.title") { value("Não encontrado") }
			jsonPath("$.status") { value(404) }
			jsonPath("$.instance") { value("/api/v1/places/00000000-0000-0000-0000-000000000000") }
		}
	}

	@Test
	fun `identificador mal formado devolve 400`() {
		expectInvalid("/api/v1/places/nao-e-um-uuid", "placeId")
	}

	// --- Parâmetros inválidos ---

	@Test
	fun `latitude sem longitude`() {
		expectInvalid("/api/v1/places?lat=41.1496", "lng")
	}

	@Test
	fun `varios parametros invalidos sao devolvidos juntos`() {
		mvc.get("/api/v1/places?q=b&radiusKm=80&size=500").andExpect {
			status { isBadRequest() }
			content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
			jsonPath("$.title") { value("Pedido inválido") }
			jsonPath("$.errors[*].field") { value(containsInAnyOrder("q", "radiusKm", "radiusKm", "size")) }
		}
	}

	@Test
	fun `valores fora do intervalo ou mal escritos`() {
		expectInvalid("/api/v1/places?lat=91&lng=-8.61", "lat")
		expectInvalid("/api/v1/places?lat=41.1&lng=-181", "lng")
		expectInvalid("/api/v1/places?lat=abc&lng=-8.61", "lat")
		expectInvalid("/api/v1/places?lat=41.1&lng=-8.6&radiusKm=0.05", "radiusKm")
		expectInvalid("/api/v1/places?lat=41.1&lng=-8.6&radiusKm=NaN", "radiusKm")
		expectInvalid("/api/v1/places?size=0", "size")
		expectInvalid("/api/v1/places?size=51", "size")
		expectInvalid("/api/v1/places?page=-1", "page")
		expectInvalid("/api/v1/places?page=x", "page")
		expectInvalid("/api/v1/places?q=" + "a".repeat(101), "q")
	}

	@Test
	fun `ordenacao invalida ou por distancia sem coordenadas`() {
		expectInvalid("/api/v1/places?sort=rating", "sort")
		expectInvalid("/api/v1/places?sort=distance", "sort")
	}

	@Test
	fun `modalidade desconhecida`() {
		mvc.get("/api/v1/places?sport=quidditch").andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("sport") }
			jsonPath("$.errors[0].message") { value("Modalidade desconhecida: quidditch.") }
		}
	}

	@Test
	fun `parametros vazios sao ignorados`() {
		assertEquals(ids("/api/v1/places"), ids("/api/v1/places?q=&sport=&sort="))
	}

	// --- Dados de demonstração ---

	@Test
	fun `carregar os dados de demonstracao de novo nao cria duplicados`() {
		val before = counts()
		demoDataLoader.load()
		demoDataLoader.load()
		assertEquals(before, counts())
		assertEquals(10L to 19L, before)
	}

	// --- Auxiliares ---

	/** Minúsculas e sem acentos, para comparar ordenações. */
	private fun normalize(text: String): String =
		java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")

	private fun counts(): Pair<Long, Long> =
		jdbc.sql("SELECT count(*) FROM place").query(Long::class.javaObjectType).single() to
			jdbc.sql("SELECT count(*) FROM activity_offering").query(Long::class.javaObjectType).single()

	private fun json(url: String): JsonNode =
		mapper.readTree(mvc.get(url).andExpect { status { isOk() } }.andReturn().response.contentAsByteArray)

	private fun ids(url: String): List<String> = json(url)["items"].elements.map { it["id"].asString() }

	private fun expectInvalid(url: String, field: String) {
		mvc.get(url).andExpect {
			status { isBadRequest() }
			content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
			jsonPath("$.errors[*].field") { value(hasItem(field)) }
			jsonPath("$.errors[*].message") { value(everyItem(org.hamcrest.Matchers.notNullValue())) }
		}
	}

	/** Elementos de um array JSON como lista Kotlin. */
	private val JsonNode.elements: List<JsonNode>
		get() = (0 until size()).map { this[it] }
}
