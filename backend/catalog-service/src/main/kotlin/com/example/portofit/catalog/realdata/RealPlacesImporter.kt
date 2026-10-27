package com.example.portofit.catalog.realdata

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.util.UUID

/** Uma atividade aprovada para importar (acesso do público confirmado na fonte). */
data class RealOffering(
	val id: UUID,
	val sportId: String,
	val name: String,
	val description: String?,
	val level: String,
)

/** Um local real aprovado, com os campos já validados. */
data class RealPlace(
	val id: UUID,
	val name: String,
	val description: String?,
	val street: String,
	val postalCode: String,
	val parish: String?,
	val city: String,
	val latitude: BigDecimal,
	val longitude: BigDecimal,
	val phone: String?,
	val website: String?,
	val offerings: List<RealOffering>,
)

data class ImportReport(val places: Int, val offerings: Int, val skippedPlaces: List<String>)

class RealPlacesValidationException(val errors: List<String>) :
	RuntimeException("Locais reais inválidos (nada foi gravado): " + errors.joinToString("; "))

/**
 * Lê e valida o ficheiro de locais reais (docs/locais-reais.md). Só entram locais com `status: ready` e, deles, só
 * as atividades com `publicAccess: CONFIRMED`; um local sem nenhuma atividade confirmada fica de fora (indicado em
 * [ImportReport.skippedPlaces]). Qualquer valor inválido rejeita o ficheiro inteiro.
 */
object RealPlacesFile {

	private val POSTAL_CODE = Regex("^\\d{4}-\\d{3}$")
	private val WEBSITE = Regex("^https?://.+")
	val LEVELS = setOf("BEGINNER", "INTERMEDIATE", "ADVANCED", "ALL_LEVELS", "UNSPECIFIED")

	data class Parsed(val places: List<RealPlace>, val skipped: List<String>)

	fun parse(root: JsonNode): Parsed {
		val errors = mutableListOf<String>()
		val places = mutableListOf<RealPlace>()
		val skipped = mutableListOf<String>()
		val ids = mutableSetOf<UUID>()
		val list = root.get("places")
		if (list == null || !list.isArray) throw RealPlacesValidationException(listOf("falta a lista 'places'"))
		for (node in list) {
			val label = text(node, "name") ?: text(node, "id") ?: "?"
			if (text(node, "status") != "ready") {
				skipped += "$label (pendente)"
				continue
			}
			val confirmed = node.get("offerings")?.filter { text(it, "publicAccess") == "CONFIRMED" }.orEmpty()
			if (confirmed.isEmpty()) {
				skipped += "$label (sem atividade com acesso do público confirmado)"
				continue
			}
			val e = mutableListOf<String>()
			fun check(ok: Boolean, message: String) { if (!ok) e += "$label: $message" }

			val id = uuid(node, "id").also { check(it != null, "id inválido") }
			val name = text(node, "name")
			check(name != null && name.trim().length in 2..120, "nome com 2 a 120 caracteres")
			val description = text(node, "description")
			check(description == null || description.length <= 1000, "descrição até 1000 caracteres")
			val address = node.get("address")
			val street = address?.let { text(it, "street") }
			check(street != null && street.isNotBlank() && street.length <= 200, "rua obrigatória, até 200 caracteres")
			val postalCode = address?.let { text(it, "postalCode") }
			check(postalCode != null && POSTAL_CODE.matches(postalCode), "código postal no formato 0000-000")
			val parish = address?.let { text(it, "parish") }
			check(parish == null || parish.length <= 80, "freguesia até 80 caracteres")
			val city = address?.let { text(it, "city") }
			check(city == "Porto", "cidade tem de ser Porto")
			val location = node.get("location")
			val latitude = location?.get("latitude")?.takeIf { it.isNumber }?.decimalValue()
			val longitude = location?.get("longitude")?.takeIf { it.isNumber }?.decimalValue()
			check(latitude != null && latitude.abs() <= BigDecimal(90) && latitude.scale() <= 6, "latitude entre −90 e 90, até 6 casas")
			check(longitude != null && longitude.abs() <= BigDecimal(180) && longitude.scale() <= 6, "longitude entre −180 e 180, até 6 casas")
			val contacts = node.get("contacts")
			val phone = contacts?.let { text(it, "phone") }
			check(phone == null || phone.length <= 20, "telefone até 20 caracteres")
			val website = contacts?.let { text(it, "website") }
			check(website == null || (website.length <= 200 && WEBSITE.matches(website)), "site http(s):// até 200 caracteres")
			if (id != null) check(ids.add(id), "id repetido")

			val offerings = mutableListOf<RealOffering>()
			val names = mutableSetOf<String>()
			for (o in confirmed) {
				val oLabel = "$label / ${text(o, "name") ?: "?"}"
				val oid = uuid(o, "id")
				if (oid == null) e += "$oLabel: id inválido" else if (!ids.add(oid)) e += "$oLabel: id repetido"
				val oName = text(o, "name")
				if (oName == null || oName.trim().length !in 2..120) e += "$oLabel: nome com 2 a 120 caracteres"
				else if (!names.add(normalize(oName))) e += "$oLabel: nome repetido no mesmo local"
				val sportId = text(o, "sportId")
				if (sportId == null) e += "$oLabel: modalidade em falta"
				val oDescription = text(o, "description")
				if (oDescription != null && oDescription.length > 1000) e += "$oLabel: descrição até 1000 caracteres"
				val level = text(o, "level")
				if (level !in LEVELS) e += "$oLabel: nível inválido"
				if (oid != null && oName != null && sportId != null && level != null) {
					offerings += RealOffering(oid, sportId, oName.trim(), oDescription, level)
				}
			}
			if (e.isEmpty()) {
				places += RealPlace(
					id!!, name!!.trim(), description, street!!, postalCode!!, parish, city!!, latitude!!, longitude!!, phone, website, offerings,
				)
			}
			errors += e
		}
		if (errors.isNotEmpty()) throw RealPlacesValidationException(errors)
		return Parsed(places, skipped)
	}

	/** Mesma regra de portofit_normalize (minúsculas e sem acentos), para a unicidade do nome no local. */
	private fun normalize(value: String) =
		value.lowercase().map { c -> "áàâãäåéèêëíìîïóòôõöúùûüçñý".indexOf(c).let { if (it < 0) c else "aaaaaaeeeeiiiiooooouuuucny"[it] } }.joinToString("")

	private fun text(node: JsonNode, field: String): String? = node.get(field)?.takeIf { it.isString }?.stringValue()

	private fun uuid(node: JsonNode, field: String): UUID? =
		text(node, field)?.let { runCatching { UUID.fromString(it) }.getOrNull()?.takeIf { u -> u.toString() == it.lowercase() } }
}

/**
 * Importa os locais REAIS aprovados (docs/locais-reais.md §5), com ids fixos do ficheiro. Repetível: corre numa
 * transação, com `INSERT … ON CONFLICT (id) DO UPDATE`, por isso repetir não cria duplicados.
 *
 * Só toca nos registos que gere: os ids do ficheiro, e nunca um registo fictício (a validação recusa ids que já existam
 * com `is_demo = true`, e a atualização só se aplica a `is_demo = false`). Não apaga nada e não altera o `status` de
 * registos existentes (uma retirada manual mantém-se). Uma validação falhada rejeita o ficheiro inteiro sem gravar nada.
 */
@Component
class RealPlacesImporter(
	private val jdbc: JdbcClient,
	private val transactions: TransactionTemplate,
	private val mapper: JsonMapper,
) {

	fun import(resource: Resource = ClassPathResource(FILE)): ImportReport {
		val parsed = resource.inputStream.use { RealPlacesFile.parse(mapper.readTree(it)) }
		return transactions.execute {
			checkAgainstDatabase(parsed.places)
			parsed.places.forEach(::upsert)
			ImportReport(parsed.places.size, parsed.places.sumOf { it.offerings.size }, parsed.skipped)
		}!!
	}

	private fun checkAgainstDatabase(places: List<RealPlace>) {
		val errors = mutableListOf<String>()
		val sports = jdbc.sql("SELECT id FROM sport").query(String::class.java).set()
		for (place in places) {
			val demo = jdbc.sql("SELECT is_demo FROM place WHERE id = :id").param("id", place.id).query(Boolean::class.javaObjectType).optional()
			if (demo.orElse(false)) errors += "${place.name}: o id pertence a um local fictício"
			for (o in place.offerings) {
				if (o.sportId !in sports) errors += "${place.name} / ${o.name}: modalidade '${o.sportId}' não existe"
				val owner = jdbc.sql("SELECT place_id FROM activity_offering WHERE id = :id").param("id", o.id).query(UUID::class.java).optional()
				if (owner.isPresent && owner.get() != place.id) errors += "${place.name} / ${o.name}: o id pertence a uma atividade de outro local"
			}
		}
		if (errors.isNotEmpty()) throw RealPlacesValidationException(errors)
	}

	private fun upsert(place: RealPlace) {
		jdbc.sql(
			"""
			INSERT INTO place (id, name, description, street, postal_code, parish, city, latitude, longitude, phone, website, is_demo)
			VALUES (:id, :name, :description, :street, :postalCode, :parish, :city, :latitude, :longitude, :phone, :website, false)
			ON CONFLICT (id) DO UPDATE SET
				name = EXCLUDED.name, description = EXCLUDED.description, street = EXCLUDED.street,
				postal_code = EXCLUDED.postal_code, parish = EXCLUDED.parish, city = EXCLUDED.city,
				latitude = EXCLUDED.latitude, longitude = EXCLUDED.longitude, phone = EXCLUDED.phone,
				website = EXCLUDED.website, updated_at = now()
			WHERE place.is_demo = false
			""",
		)
			.param("id", place.id).param("name", place.name).param("description", place.description)
			.param("street", place.street).param("postalCode", place.postalCode).param("parish", place.parish)
			.param("city", place.city).param("latitude", place.latitude).param("longitude", place.longitude)
			.param("phone", place.phone).param("website", place.website)
			.update()
		for (o in place.offerings) {
			jdbc.sql(
				"""
				INSERT INTO activity_offering (id, place_id, sport_id, name, description, level)
				VALUES (:id, :placeId, :sportId, :name, :description, :level)
				ON CONFLICT (id) DO UPDATE SET
					sport_id = EXCLUDED.sport_id, name = EXCLUDED.name, description = EXCLUDED.description,
					level = EXCLUDED.level, updated_at = now()
				WHERE activity_offering.place_id = EXCLUDED.place_id
				""",
			)
				.param("id", o.id).param("placeId", place.id).param("sportId", o.sportId).param("name", o.name)
				.param("description", o.description).param("level", o.level)
				.update()
		}
	}

	companion object {
		const val FILE = "real/locais-reais-porto.json"
	}
}

/**
 * No ambiente normal (perfil "real-places"), importa os locais reais em cada arranque. Um ficheiro inválido não grava
 * nada e fica no registo; o catálogo continua a responder com o que já tinha.
 */
@Component
@Profile("real-places")
class RealPlacesStartupImport(private val importer: RealPlacesImporter) : ApplicationRunner {
	private val log = LoggerFactory.getLogger(javaClass)

	override fun run(args: ApplicationArguments) {
		try {
			val report = importer.import()
			log.info("Locais reais importados: {} locais e {} atividades; fora: {}.", report.places, report.offerings, report.skippedPlaces.size)
		} catch (e: RealPlacesValidationException) {
			log.error("Importação de locais reais recusada, nada foi gravado: {}", e.errors.joinToString("; "))
		}
	}
}
