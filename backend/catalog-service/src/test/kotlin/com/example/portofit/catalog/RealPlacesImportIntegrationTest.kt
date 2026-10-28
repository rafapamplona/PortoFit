package com.example.portofit.catalog

import com.example.portofit.catalog.demo.DemoDataLoader
import com.example.portofit.catalog.realdata.RealPlacesImporter
import com.example.portofit.catalog.realdata.RealPlacesValidationException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Importação dos locais reais e retirada dos fictícios (docs/locais-reais.md), contra PostgreSQL real, com os dados de
 * demonstração carregados (perfil "demo") como no volume atual antes da mudança. Base própria: não altera as contagens
 * dos outros testes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@Import(TestcontainersConfiguration::class)
class RealPlacesImportIntegrationTest {

	@Autowired lateinit var mvc: MockMvc
	@Autowired lateinit var mapper: ObjectMapper
	@Autowired lateinit var jdbc: JdbcClient
	@Autowired lateinit var importer: RealPlacesImporter
	@Autowired lateinit var demoDataLoader: DemoDataLoader

	private val constituicao = "5d3604be-1a4b-404e-bc2e-0f5a5a683781"
	private val campanha = "79102cf3-5dd2-4d29-8b5f-e795049898db"
	private val estadio = "89c39b8b-b466-4201-a0c4-79f146783924"
	private val marchaCorrida = "5f8cd7dd-a446-48b2-8f0d-2ecce28b48e3"
	private val ribeira = "3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e01"

	@BeforeEach
	fun ratingsUp() {
		experiences.reset(ExperiencesStub.Mode.UP)
	}

	@Test
	fun `importa so os locais e atividades com acesso do publico confirmado, com nivel nao indicado`() {
		val report = importer.import()
		assertEquals(30, report.places)
		assertEquals(61, report.offerings)
		assertEquals(9, report.skippedPlaces.size) // 9 pendentes (o estádio entrou com futebol e ténis)

		assertEquals(30L, count("SELECT count(*) FROM place WHERE NOT is_demo"))
		assertEquals(61L, count("SELECT count(*) FROM activity_offering o JOIN place p ON p.id = o.place_id WHERE NOT p.is_demo"))
		assertEquals(setOf("UNSPECIFIED"), jdbc.sql("SELECT DISTINCT o.level FROM activity_offering o JOIN place p ON p.id = o.place_id WHERE NOT p.is_demo").query(String::class.java).set())
		// Atividades não confirmadas ficam fora, mesmo num local importado (o boxe da secção do clube).
		assertEquals(0L, count("SELECT count(*) FROM activity_offering o JOIN place p ON p.id = o.place_id WHERE p.id = '$campanha' AND o.sport_id = 'boxe'"))
		// O estádio entra só com as atividades confirmadas (aluguer de futebol e ténis); a pista continua de fora.
		assertEquals(setOf("futebol", "tenis"), jdbc.sql("SELECT sport_id FROM activity_offering WHERE place_id = '$estadio'").query(String::class.java).set())
		// Morada do regulamento oficial (prevalece sobre a página).
		assertEquals("4250-322", jdbc.sql("SELECT postal_code FROM place WHERE id = '$constituicao'").query(String::class.java).single())
		// Ponto de encontro de treino sem código postal (V7): fica null, sem inventar.
		assertEquals(1L, count("SELECT count(*) FROM place WHERE id = '$marchaCorrida' AND postal_code IS NULL"))
		val meeting = json("/api/v1/places/$marchaCorrida")
		assertTrue(meeting["address"]["postalCode"]?.isNull ?: true, meeting["address"].toString())
		// O detalhe devolve o nível tal como está.
		val detail = json("/api/v1/places/$constituicao")
		assertEquals(false, detail["demo"].asBoolean())
		assertTrue(detail["offerings"].elements.all { it["level"].asString() == "UNSPECIFIED" })
	}

	@Test
	fun `repetir a importacao nao duplica nem reativa um local retirado a mao`() {
		importer.import()
		val before = counts()
		jdbc.sql("UPDATE place SET status = 'RETIRED' WHERE id = '$campanha'").update()
		importer.import()
		importer.import()
		assertEquals(before, counts())
		assertEquals("RETIRED", jdbc.sql("SELECT status FROM place WHERE id = '$campanha'").query(String::class.java).single())
		jdbc.sql("UPDATE place SET status = 'ACTIVE' WHERE id = '$campanha'").update()
	}

	@Test
	fun `um valor invalido rejeita o ficheiro inteiro sem gravar nada`() {
		val before = counts()
		val ok = place("aaaaaaaa-0000-4000-8000-000000000001", "bbbbbbbb-0000-4000-8000-000000000001", "4000-001")
		val bad = place("aaaaaaaa-0000-4000-8000-000000000002", "bbbbbbbb-0000-4000-8000-000000000002", "4000 001")
		val error = assertThrows<RealPlacesValidationException> { importer.import(resource(ok, bad)) }
		assertTrue(error.errors.single().contains("código postal"), error.errors.toString())
		assertEquals(before, counts())
		// Um id que pertence a um local fictício também é recusado (nunca se reaproveita um id fictício).
		val demoId = place(ribeira, "bbbbbbbb-0000-4000-8000-000000000003", "4000-001")
		assertThrows<RealPlacesValidationException> { importer.import(resource(demoId)) }
		assertEquals(before, counts())
		assertEquals("Clube de Combate Ribeira (fictício)", jdbc.sql("SELECT name FROM place WHERE id = '$ribeira'").query(String::class.java).single())
	}

	@Test
	fun `retirar os ficticios tira-os da pesquisa sem apagar, e recarregar a demo nao os reativa`() {
		importer.import()
		val demoBefore = count("SELECT count(*) FROM place WHERE is_demo")
		retireDemo()
		assertEquals(demoBefore, count("SELECT count(*) FROM place WHERE is_demo"))
		assertEquals(0L, count("SELECT count(*) FROM place WHERE is_demo AND status <> 'RETIRED'"))
		assertEquals(0L, count("SELECT count(*) FROM activity_offering o JOIN place p ON p.id = o.place_id WHERE p.is_demo AND o.status <> 'RETIRED'"))
		demoDataLoader.load()
		assertEquals(0L, count("SELECT count(*) FROM place WHERE is_demo AND status <> 'RETIRED'"))

		// Pesquisa pública: só reais.
		val all = json("/api/v1/places?size=50")["items"].elements
		assertTrue(all.isNotEmpty() && all.none { it["demo"].asBoolean() })
		// Locais com cada modalidade (docs/locais-reais.md §10); corrida e kickboxing abaixo de 5, escalada sem nenhum.
		val perSport = mapOf(
			"natacao" to 5, "ioga" to 12, "pilates" to 11, "crossfit" to 5, "boxe" to 6, "corrida" to 2, "escalada" to 0, "kickboxing" to 4,
			"futebol" to 4, "futsal" to 1, "basquetebol" to 0, "voleibol" to 0, "tenis" to 3, "padel" to 3, "musculacao" to 5,
		)
		perSport.forEach { (sport, expected) -> assertEquals(expected, json("/api/v1/places?sport=$sport&size=50")["totalItems"].asInt(), sport) }
		// Pesquisa por texto: com ou sem acento dá o mesmo; futebol e futsal são categorias distintas.
		val tenis = json("/api/v1/places?q=tenis&size=50")["items"].elements.map { it["name"].asString() }.toSet()
		assertEquals(tenis, json("/api/v1/places?q=ténis&size=50")["items"].elements.map { it["name"].asString() }.toSet())
		assertTrue("Clube de Ténis do Porto" in tenis && "Estádio Universitário do Porto" in tenis, tenis.toString())
		val futsal = json("/api/v1/places?q=futsal&size=50")["items"].elements.map { it["name"].asString() }.toSet()
		assertEquals(setOf("Oporto Indoor Games"), futsal)
		assertTrue("Campo Municipal do Viso" !in futsal && "Campo Municipal do Viso" in json("/api/v1/places?q=futebol&size=50")["items"].elements.map { it["name"].asString() })
		// Sem avaliações inventadas: NO_RATINGS com o experiences-service a responder.
		assertTrue(all.all { it["rating"]["status"].asString() == "NO_RATINGS" })
		// Proximidade a partir dos Aliados: só reais, com distância.
		val near = json("/api/v1/places?lat=41.1496&lng=-8.6109&radiusKm=5")["items"].elements
		assertTrue(near.isNotEmpty() && near.all { !it["demo"].asBoolean() && it["distanceKm"].isNumber })
		// O fictício continua a responder pelo id, com o estado.
		val ribeiraDetail = json("/api/v1/places/$ribeira")
		assertEquals("RETIRED", ribeiraDetail["status"].asString())
		assertEquals(true, ribeiraDetail["demo"].asBoolean())
	}

	/** A migração V6 tal como está no código (no contentor de testes corre antes de haver dados fictícios). */
	private fun retireDemo() {
		val sql = ClassPathResource("db/migration/V6__retire_demo_places.sql").inputStream.use { String(it.readAllBytes(), Charsets.UTF_8) }
		sql.lines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n").split(";").map { it.trim() }.filter { it.isNotEmpty() }
			.forEach { jdbc.sql(it).update() }
	}

	private fun place(id: String, offeringId: String, postalCode: String) = """
		{ "id": "$id", "status": "ready", "name": "Local de teste $id", "description": null,
		  "address": { "street": "Rua de Teste, 1", "postalCode": "$postalCode", "parish": null, "city": "Porto" },
		  "location": { "latitude": 41.15, "longitude": -8.61 }, "contacts": { "phone": null, "website": null },
		  "offerings": [ { "id": "$offeringId", "sportId": "natacao", "name": "Natação", "description": null,
		                   "level": "UNSPECIFIED", "publicAccess": "CONFIRMED" } ] }
	""".trimIndent()

	private fun resource(vararg places: String) = ByteArrayResource("""{ "places": [ ${places.joinToString(",")} ] }""".toByteArray())

	private fun count(sql: String): Long = jdbc.sql(sql).query(Long::class.javaObjectType).single()

	private fun counts(): Pair<Long, Long> = count("SELECT count(*) FROM place") to count("SELECT count(*) FROM activity_offering")

	private fun json(url: String): JsonNode =
		mapper.readTree(mvc.get(url).andExpect { status { isOk() } }.andReturn().response.contentAsByteArray)

	private val JsonNode.elements: List<JsonNode>
		get() = (0 until size()).map { this[it] }

	companion object {
		val experiences: ExperiencesStub = ExperiencesStub.start()

		@JvmStatic
		@DynamicPropertySource
		fun properties(registry: DynamicPropertyRegistry) {
			registry.add("portofit.experiences.base-url") { experiences.url }
		}
	}
}
