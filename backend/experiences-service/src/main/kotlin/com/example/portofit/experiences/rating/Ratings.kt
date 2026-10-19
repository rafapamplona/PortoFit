package com.example.portofit.experiences.rating

import com.example.portofit.experiences.api.ApiErrors
import com.example.portofit.experiences.api.FieldError
import com.example.portofit.experiences.experience.ExperienceRules
import com.fasterxml.jackson.annotation.JsonInclude
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.math.BigInteger
import java.util.UUID

/** Só AVAILABLE e NO_RATINGS: UNAVAILABLE é decidido pelo catálogo quando este pedido falha. */
enum class RatingStatus { AVAILABLE, NO_RATINGS }

data class Rating(val status: RatingStatus, val average: BigDecimal?, val ratingsCount: Int) {
	companion object {
		val NO_RATINGS = Rating(RatingStatus.NO_RATINGS, null, 0)
	}
}

data class OfferingRating(val offeringId: UUID, val rating: Rating)

data class PlaceRating(
	val placeId: UUID,
	val rating: Rating,
	/** Omitido com includeOfferings=false. */
	@get:JsonInclude(JsonInclude.Include.NON_NULL)
	val offerings: List<OfferingRating>?,
)

data class RatingsResponse(val items: List<PlaceRating>)

/** Avaliação vigente de uma pessoa numa atividade (docs/experiences-api.md §7.1). */
data class CurrentRating(val placeId: UUID, val offeringId: UUID, val authorId: UUID, val stars: Int)

/** Fração exata, para que nenhuma média intermédia seja arredondada (docs/experiences-api.md §7.1, regra 5). */
data class Fraction(val numerator: BigInteger, val denominator: BigInteger) {
	operator fun plus(other: Fraction) = Fraction(
		numerator * other.denominator + other.numerator * denominator,
		denominator * other.denominator,
	)

	fun dividedBy(n: Int) = Fraction(numerator, denominator * BigInteger.valueOf(n.toLong()))

	/** Arredondamento a uma casa decimal, meio para cima (valores positivos): floor(x·10 + ½) / 10. */
	fun roundHalfUpOneDecimal(): BigDecimal {
		val two = BigInteger.TWO
		val tenths = (numerator * BigInteger.TEN * two + denominator).divide(denominator * two)
		return BigDecimal(tenths, 1)
	}

	companion object {
		fun of(value: Int) = Fraction(BigInteger.valueOf(value.toLong()), BigInteger.ONE)

		fun average(values: List<Fraction>) = values.reduce(Fraction::plus).dividedBy(values.size)
	}
}

@Repository
class RatingRepository(private val jdbc: JdbcClient) {

	/**
	 * Uma consulta para todos os locais pedidos: a experiência não eliminada mais recente de cada pessoa em
	 * cada atividade (createdAt e, em empate, o id maior; docs/experiences-api.md §7.2).
	 */
	fun currentRatings(placeIds: Collection<UUID>): List<CurrentRating> =
		jdbc.sql(
			"""
			SELECT DISTINCT ON (author_id, offering_id) place_id, offering_id, author_id, stars
			FROM experience
			WHERE deleted_at IS NULL AND place_id IN (:placeIds)
			ORDER BY author_id, offering_id, created_at DESC, id DESC
			""",
		)
			.param("placeIds", placeIds)
			.query { rs, _ ->
				CurrentRating(
					placeId = rs.getObject("place_id", UUID::class.java),
					offeringId = rs.getObject("offering_id", UUID::class.java),
					authorId = rs.getObject("author_id", UUID::class.java),
					stars = rs.getInt("stars"),
				)
			}
			.list()
}

/**
 * Peso 1 por pessoa (docs/arquitetura.md §3.7): na atividade, a média das avaliações vigentes; no local,
 * a média das contribuições de cada pessoa (a média das suas avaliações vigentes nas atividades do local).
 */
@Service
class RatingService(private val repository: RatingRepository) {

	fun ratings(placeIds: List<UUID>, includeOfferings: Boolean): RatingsResponse {
		val byPlace = repository.currentRatings(placeIds).groupBy { it.placeId }
		val items = placeIds.map { placeId ->
			val current = byPlace[placeId].orEmpty()
			val contributions = current.groupBy { it.authorId }.values.map { personal -> Fraction.average(personal.map { Fraction.of(it.stars) }) }
			val offerings = if (includeOfferings) {
				current.groupBy { it.offeringId }.toSortedMap().map { (offeringId, rows) ->
					OfferingRating(offeringId, rating(rows.map { Fraction.of(it.stars) }))
				}
			} else {
				null
			}
			PlaceRating(placeId, rating(contributions), offerings)
		}
		return RatingsResponse(items)
	}

	private fun rating(values: List<Fraction>): Rating =
		if (values.isEmpty()) Rating.NO_RATINGS else Rating(RatingStatus.AVAILABLE, Fraction.average(values).roundHalfUpOneDecimal(), values.size)
}

@RestController
@RequestMapping("/api/v1/ratings")
class RatingController(private val service: RatingService) {

	/** Avaliações em lote para o catálogo (docs/experiences-api.md §4.6): 1–50 locais num só pedido. */
	@GetMapping("/places")
	fun places(
		@RequestParam("ids", required = false) ids: String?,
		@RequestParam("includeOfferings", required = false) includeOfferings: String?,
	): RatingsResponse {
		val errors = mutableListOf<FieldError>()
		val parts = ids?.split(",")?.map { it.trim() }.orEmpty().filterNot { ids.isNullOrBlank() }
		val parsed = parts.map { ExperienceRules.parseUuid(it) }
		val distinct = parsed.filterNotNull().distinct()
		when {
			parts.isEmpty() -> errors += FieldError("ids", "Indica entre 1 e 50 identificadores de local.")
			parsed.any { it == null } -> errors += FieldError("ids", "Há identificadores de local inválidos.")
			distinct.size > MAX_PLACES -> errors += FieldError("ids", "Indica entre 1 e 50 identificadores de local.")
		}
		val withOfferings = when (includeOfferings?.trim()?.lowercase()) {
			null, "", "false" -> false
			"true" -> true
			else -> {
				errors += FieldError("includeOfferings", "Usa true ou false.")
				false
			}
		}
		if (errors.isNotEmpty()) throw ApiErrors.validation(errors)
		return service.ratings(distinct, withOfferings)
	}

	private companion object {
		const val MAX_PLACES = 50
	}
}
