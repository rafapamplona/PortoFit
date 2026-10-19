package com.example.portofit.catalog.offering

import com.example.portofit.catalog.api.FieldError
import com.example.portofit.catalog.api.InvalidParametersException
import com.example.portofit.catalog.api.NotFoundException
import com.example.portofit.catalog.place.SportRef
import com.example.portofit.catalog.rating.Rating
import com.example.portofit.catalog.rating.RatingProvider
import com.fasterxml.jackson.annotation.JsonInclude
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class PlaceRef(
	val id: UUID,
	val name: String,
	val demo: Boolean,
)

/** Detalhe de uma atividade oferecida (docs/catalog-api.md §3.4). */
data class OfferingDetail(
	val id: UUID,
	val name: String,
	val description: String?,
	val level: String,
	val scheduleNotes: String?,
	/** Estado efetivo: RETIRED se a atividade ou o seu local tiverem sido retirados. */
	val status: String,
	val sport: SportRef,
	val place: PlaceRef,
	/** Omitido com includeRating=false (validação de publicações pelo experiences-service). */
	@get:JsonInclude(JsonInclude.Include.NON_NULL)
	val rating: Rating?,
)

@Repository
class OfferingRepository(private val jdbc: JdbcClient) {

	fun findById(id: UUID): OfferingDetail? =
		jdbc.sql(
			"""
			SELECT o.id, o.name, o.description, o.level, o.schedule_notes,
			       CASE WHEN o.status = 'RETIRED' OR p.status = 'RETIRED' THEN 'RETIRED' ELSE 'ACTIVE' END AS effective_status,
			       s.id AS sport_id, s.name AS sport_name, p.id AS place_id, p.name AS place_name, p.is_demo
			FROM activity_offering o
			JOIN sport s ON s.id = o.sport_id
			JOIN place p ON p.id = o.place_id
			WHERE o.id = :id
			""",
		)
			.param("id", id)
			.query { rs, _ ->
				OfferingDetail(
					id = rs.getObject("id", UUID::class.java),
					name = rs.getString("name"),
					description = rs.getString("description"),
					level = rs.getString("level"),
					scheduleNotes = rs.getString("schedule_notes"),
					status = rs.getString("effective_status"),
					sport = SportRef(rs.getString("sport_id"), rs.getString("sport_name")),
					place = PlaceRef(rs.getObject("place_id", UUID::class.java), rs.getString("place_name"), rs.getBoolean("is_demo")),
					rating = null,
				)
			}
			.optional()
			.orElse(null)
}

@RestController
@RequestMapping("/api/v1/offerings")
class OfferingController(
	private val offerings: OfferingRepository,
	private val ratings: RatingProvider,
) {

	/**
	 * Com includeRating=false não há nenhum pedido ao experiences-service: é assim que este valida
	 * publicações sem que o catálogo lhe faça uma chamada de volta (docs/experiences-api.md §6.1).
	 */
	@GetMapping("/{offeringId}")
	fun detail(
		@PathVariable("offeringId") offeringId: String,
		@RequestParam("includeRating", required = false) includeRating: String?,
	): OfferingDetail {
		val errors = mutableListOf<FieldError>()
		val id = runCatching { UUID.fromString(offeringId) }.getOrNull()?.takeIf { it.toString().equals(offeringId, ignoreCase = true) }
		if (id == null) errors += FieldError("offeringId", "Identificador de atividade inválido.")
		val withRating = when (includeRating?.trim()?.lowercase()) {
			null, "", "true" -> true
			"false" -> false
			else -> {
				errors += FieldError("includeRating", "Usa true ou false.")
				true
			}
		}
		if (errors.isNotEmpty()) throw InvalidParametersException(errors)

		val offering = offerings.findById(id!!) ?: throw NotFoundException("Não existe nenhuma atividade com este identificador.")
		if (!withRating) return offering
		val rating = ratings.ratings(listOf(offering.place.id), includeOfferings = true).offering(offering.id)
		return offering.copy(rating = rating)
	}
}
