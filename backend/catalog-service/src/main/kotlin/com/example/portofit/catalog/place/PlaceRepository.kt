package com.example.portofit.catalog.place

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

/** Linha de local lida da base de dados, antes de juntar modalidades e avaliações. */
data class PlaceRow(
	val id: UUID,
	val name: String,
	val description: String?,
	val address: Address,
	val location: Location,
	val contacts: Contacts,
	val demo: Boolean,
	val status: String,
	val distanceKm: Double?,
)

data class OfferingRow(
	val id: UUID,
	val sport: SportRef,
	val name: String,
	val description: String?,
	val level: String,
	val scheduleNotes: String?,
	/** Estado efetivo (RETIRED se a atividade ou o local estiverem retirados). */
	val status: String,
)

/**
 * Acesso a locais e atividades com Spring JDBC. Todos os valores vindos do pedido são passados
 * como parâmetros; o texto SQL só é montado a partir de fragmentos fixos deste ficheiro.
 */
@Repository
class PlaceRepository(private val jdbc: JdbcClient) {

	fun search(criteria: PlaceSearchCriteria): Pair<List<PlaceRow>, Long> {
		val params = mutableMapOf<String, Any>()
		// A pesquisa pública só mostra locais ativos e só considera atividades ativas (docs/catalog-api.md §3.2).
		val conditions = mutableListOf("p.status = 'ACTIVE'")

		criteria.text?.let { text ->
			params["pattern"] = "%" + escapeLike(text) + "%"
			conditions += """
				(portofit_normalize(p.name) LIKE portofit_normalize(:pattern) ESCAPE '\'
				 OR EXISTS (
					SELECT 1 FROM activity_offering o JOIN sport s ON s.id = o.sport_id
					WHERE o.place_id = p.id AND o.status = 'ACTIVE'
					  AND (portofit_normalize(o.name) LIKE portofit_normalize(:pattern) ESCAPE '\'
					       OR portofit_normalize(s.name) LIKE portofit_normalize(:pattern) ESCAPE '\')))
			"""
		}
		criteria.sportId?.let { sportId ->
			params["sportId"] = sportId
			conditions += "EXISTS (SELECT 1 FROM activity_offering o WHERE o.place_id = p.id AND o.sport_id = :sportId AND o.status = 'ACTIVE')"
		}

		val distanceColumn = criteria.origin?.let { origin ->
			params["lat"] = origin.latitude
			params["lng"] = origin.longitude
			HAVERSINE_KM
		} ?: "CAST(NULL AS double precision)"

		val where = conditions.joinToString(" AND ", prefix = "WHERE ")
		val filtered = """
			SELECT * FROM (
				SELECT p.*, $distanceColumn AS distance_km
				FROM place p
				$where
			) AS f
		""" + if (criteria.origin != null) {
			params["radiusKm"] = criteria.radiusKm
			" WHERE f.distance_km <= :radiusKm"
		} else ""

		val total = jdbc.sql("SELECT count(*) FROM ($filtered) AS c")
			.params(params)
			.query(Long::class.javaObjectType)
			.single()

		// Ordenação sempre total (termina no id), para a paginação ser estável.
		val orderBy = when (criteria.sort) {
			PlaceSort.NAME -> "portofit_normalize(f.name), f.name, f.id"
			PlaceSort.DISTANCE -> "f.distance_km, portofit_normalize(f.name), f.name, f.id"
		}
		val rows = jdbc.sql("$filtered ORDER BY $orderBy LIMIT :limit OFFSET :offset")
			.params(params)
			.param("limit", criteria.size)
			.param("offset", criteria.offset)
			.query { rs, _ -> rs.toPlaceRow() }
			.list()

		return rows to total
	}

	fun findById(id: UUID): PlaceRow? =
		jdbc.sql("SELECT p.*, CAST(NULL AS double precision) AS distance_km FROM place p WHERE p.id = :id")
			.param("id", id)
			.query { rs, _ -> rs.toPlaceRow() }
			.optional()
			.orElse(null)

	/** Modalidades das atividades ativas de cada local, sem repetições, por nome. */
	fun sportsByPlace(placeIds: Collection<UUID>): Map<UUID, List<SportRef>> {
		if (placeIds.isEmpty()) return emptyMap()
		val pairs = jdbc.sql(
			"""
			SELECT o.place_id, s.id, s.name
			FROM activity_offering o JOIN sport s ON s.id = o.sport_id
			WHERE o.place_id IN (:placeIds) AND o.status = 'ACTIVE'
			GROUP BY o.place_id, s.id, s.name
			ORDER BY o.place_id, portofit_normalize(s.name), s.id
			""",
		)
			.param("placeIds", placeIds)
			.query { rs, _ -> rs.getObject("place_id", UUID::class.java) to SportRef(rs.getString("id"), rs.getString("name")) }
			.list()
		return pairs.groupBy({ it.first }, { it.second })
	}

	/** Todas as atividades de um local (também as retiradas, com o estado efetivo), por modalidade e nome. */
	fun offeringsOf(placeId: UUID): List<OfferingRow> =
		jdbc.sql(
			"""
			SELECT o.id, o.name, o.description, o.level, o.schedule_notes, s.id AS sport_id, s.name AS sport_name,
			       CASE WHEN o.status = 'RETIRED' OR p.status = 'RETIRED' THEN 'RETIRED' ELSE 'ACTIVE' END AS effective_status
			FROM activity_offering o JOIN sport s ON s.id = o.sport_id JOIN place p ON p.id = o.place_id
			WHERE o.place_id = :placeId
			ORDER BY portofit_normalize(s.name), portofit_normalize(o.name), o.id
			""",
		)
			.param("placeId", placeId)
			.query { rs, _ ->
				OfferingRow(
					id = rs.getObject("id", UUID::class.java),
					sport = SportRef(rs.getString("sport_id"), rs.getString("sport_name")),
					name = rs.getString("name"),
					description = rs.getString("description"),
					level = rs.getString("level"),
					scheduleNotes = rs.getString("schedule_notes"),
					status = rs.getString("effective_status"),
				)
			}
			.list()

	private fun ResultSet.toPlaceRow() = PlaceRow(
		id = getObject("id", UUID::class.java),
		name = getString("name"),
		description = getString("description"),
		address = Address(getString("street"), getString("postal_code"), getString("parish"), getString("city")),
		location = Location(getBigDecimal("latitude").toDouble(), getBigDecimal("longitude").toDouble()),
		contacts = Contacts(getString("phone"), getString("website")),
		demo = getBoolean("is_demo"),
		status = getString("status"),
		distanceKm = getDouble("distance_km").takeUnless { wasNull() },
	)

	private companion object {
		/** Raio médio da Terra em km (IUGG). */
		const val EARTH_RADIUS_KM = 6371.0088

		/** Distância em linha reta (Haversine), em km, entre (:lat, :lng) e o local p. */
		const val HAVERSINE_KM = """
			(2 * $EARTH_RADIUS_KM * asin(least(1.0, sqrt(
				power(sin(radians(CAST(p.latitude AS double precision) - :lat) / 2), 2)
				+ cos(radians(:lat)) * cos(radians(CAST(p.latitude AS double precision)))
				  * power(sin(radians(CAST(p.longitude AS double precision) - :lng) / 2), 2)))))
		"""

		/** Os caracteres especiais do LIKE (\ % _) escritos pelo utilizador são procurados literalmente. */
		fun escapeLike(text: String): String =
			text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
	}
}
