package com.example.portofit.data.remote

import com.example.portofit.data.model.ActivityLevel
import com.example.portofit.data.model.CatalogStatus
import com.example.portofit.data.model.OfferingSummary
import com.example.portofit.data.model.Address
import com.example.portofit.data.model.Offering
import com.example.portofit.data.model.Page
import com.example.portofit.data.model.PlaceDetail
import com.example.portofit.data.model.PlaceSummary
import com.example.portofit.data.model.Rating
import com.example.portofit.data.model.Sport

// Conversão dos DTOs da API para os modelos da interface.

fun SportDto.toModel() = Sport(id = id, name = name)

fun SportRefDto.toModel() = Sport(id = id, name = name)

/**
 * Valores desconhecidos ou incoerentes (ex.: AVAILABLE sem média) são tratados como indisponíveis,
 * para nunca mostrar uma avaliação inventada ou zero estrelas.
 */
fun RatingDto.toModel(): Rating = when (status) {
    "AVAILABLE" ->
        if (average != null && ratingsCount != null && ratingsCount > 0) {
            Rating.Available(average, ratingsCount)
        } else {
            Rating.Unavailable
        }
    "NO_RATINGS" -> Rating.NoRatings
    else -> Rating.Unavailable
}

fun levelFrom(value: String): ActivityLevel =
    ActivityLevel.entries.firstOrNull { it.name == value && it != ActivityLevel.UNKNOWN } ?: ActivityLevel.UNKNOWN

/** Valor desconhecido → RETIRED: nunca se oferece publicar numa atividade de estado incerto. */
fun statusFrom(value: String): CatalogStatus = if (value == "ACTIVE") CatalogStatus.ACTIVE else CatalogStatus.RETIRED

fun OfferingDetailDto.toModel() = OfferingSummary(id, name, statusFrom(status), place.id, place.name)

fun PlaceSummaryDto.toModel() = PlaceSummary(
    id = id,
    name = name,
    parish = address.parish,
    city = address.city,
    sports = sports.map { it.toModel() },
    rating = rating.toModel(),
    isDemo = demo,
    distanceKm = distanceKm,
)

fun OfferingDto.toModel() = Offering(
    id = id,
    name = name,
    sport = sport.toModel(),
    description = description,
    level = levelFrom(level),
    scheduleNotes = scheduleNotes,
    rating = rating.toModel(),
    status = statusFrom(status),
)

fun PlaceDetailDto.toModel(): PlaceDetail {
    val offeringModels = offerings.map { it.toModel() }
    return PlaceDetail(
        id = id,
        name = name,
        description = description,
        address = Address(address.street, address.postalCode, address.parish, address.city),
        phone = contacts.phone,
        website = contacts.website,
        rating = rating.toModel(),
        sports = offeringModels.map { it.sport }.distinctBy { it.id },
        offerings = offeringModels,
        isDemo = demo,
        status = statusFrom(status),
    )
}

fun <D, M> PageDto<D>.toModel(transform: (D) -> M) = Page(
    items = items.map(transform),
    page = page,
    totalItems = totalItems,
    totalPages = totalPages,
)
