package com.example.portofit.catalog.rating

import org.springframework.stereotype.Component
import java.util.UUID

enum class RatingStatus { AVAILABLE, NO_RATINGS, UNAVAILABLE }

/**
 * Avaliação agregada de um local ou atividade. Pertence ao experiences-service;
 * o catálogo só a mostra como complemento (ver docs/catalog-api.md §2.4).
 */
data class Rating(
	val status: RatingStatus,
	val average: Double?,
	val ratingsCount: Int?,
) {
	companion object {
		/** Avaliação que não foi possível obter: nunca é apresentada como zero estrelas. */
		val UNAVAILABLE = Rating(RatingStatus.UNAVAILABLE, average = null, ratingsCount = null)
	}
}

/** Fonte das avaliações agregadas. Um id ausente do mapa devolvido é tratado como UNAVAILABLE. */
interface RatingProvider {
	fun placeRatings(placeIds: Collection<UUID>): Map<UUID, Rating>
	fun offeringRatings(offeringIds: Collection<UUID>): Map<UUID, Rating>
}

/**
 * Implementação enquanto o experiences-service não existe: não faz chamadas e declara todas
 * as avaliações como indisponíveis. Será substituída por um cliente HTTP com timeout.
 */
@Component
class UnavailableRatingProvider : RatingProvider {
	override fun placeRatings(placeIds: Collection<UUID>): Map<UUID, Rating> =
		placeIds.associateWith { Rating.UNAVAILABLE }

	override fun offeringRatings(offeringIds: Collection<UUID>): Map<UUID, Rating> =
		offeringIds.associateWith { Rating.UNAVAILABLE }
}
