package com.example.portofit.catalog.place

import com.example.portofit.catalog.api.NotFoundException
import com.example.portofit.catalog.api.PageResponse
import com.example.portofit.catalog.rating.Rating
import com.example.portofit.catalog.rating.RatingProvider
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/** Junta os dados do catálogo com o complemento das avaliações. */
@Service
class PlaceService(
	private val places: PlaceRepository,
	private val ratings: RatingProvider,
) {

	fun search(criteria: PlaceSearchCriteria): PageResponse<PlaceSummary> {
		val (rows, total) = places.search(criteria)
		val ids = rows.map { it.id }
		val sports = places.sportsByPlace(ids)
		val placeRatings = ratings.placeRatings(ids)

		val items = rows.map { row ->
			PlaceSummary(
				id = row.id,
				name = row.name,
				address = row.address,
				location = row.location,
				sports = sports[row.id].orEmpty(),
				distanceKm = row.distanceKm?.let(::roundKm),
				rating = placeRatings[row.id] ?: Rating.UNAVAILABLE,
				demo = row.demo,
			)
		}
		return PageResponse.of(items, criteria.page, criteria.size, total)
	}

	fun detail(id: UUID): PlaceDetail {
		val row = places.findById(id) ?: throw NotFoundException("Não existe nenhum local com este identificador.")
		val offeringRows = places.offeringsOf(id)
		val offeringRatings = ratings.offeringRatings(offeringRows.map { it.id })

		return PlaceDetail(
			id = row.id,
			name = row.name,
			description = row.description,
			address = row.address,
			location = row.location,
			contacts = row.contacts,
			rating = ratings.placeRatings(listOf(id))[id] ?: Rating.UNAVAILABLE,
			offerings = offeringRows.map {
				Offering(
					id = it.id,
					sport = it.sport,
					name = it.name,
					description = it.description,
					level = it.level,
					scheduleNotes = it.scheduleNotes,
					rating = offeringRatings[it.id] ?: Rating.UNAVAILABLE,
				)
			},
			demo = row.demo,
		)
	}

	/** Distância apresentada com 2 casas decimais (arredondamento meio para cima). */
	private fun roundKm(km: Double): Double = BigDecimal.valueOf(km).setScale(2, RoundingMode.HALF_UP).toDouble()
}
