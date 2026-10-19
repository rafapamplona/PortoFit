package com.example.portofit.data.remote

import kotlinx.serialization.Serializable

// Formato JSON do catalog-service (docs/catalog-api.md). Estas classes só existem na camada de rede;
// a interface usa os modelos de data/model, obtidos através de CatalogMappers.

@Serializable
data class SportsResponseDto(
    val items: List<SportDto>,
)

@Serializable
data class SportDto(
    val id: String,
    val name: String,
    val description: String? = null,
)

@Serializable
data class PageDto<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val totalItems: Long,
    val totalPages: Int,
)

@Serializable
data class PlaceSummaryDto(
    val id: String,
    val name: String,
    val address: AddressDto,
    val location: LocationDto,
    val sports: List<SportRefDto>,
    val distanceKm: Double? = null,
    val rating: RatingDto,
    val demo: Boolean,
)

@Serializable
data class PlaceDetailDto(
    val id: String,
    val name: String,
    val description: String? = null,
    val address: AddressDto,
    val location: LocationDto,
    val contacts: ContactsDto,
    val rating: RatingDto,
    val offerings: List<OfferingDto>,
    val demo: Boolean,
    /** Ausente em versões antigas do catálogo: tratado como ACTIVE. */
    val status: String = "ACTIVE",
)

@Serializable
data class AddressDto(
    val street: String,
    val postalCode: String,
    val parish: String? = null,
    val city: String,
)

@Serializable
data class LocationDto(
    val latitude: Double,
    val longitude: Double,
)

@Serializable
data class ContactsDto(
    val phone: String? = null,
    val website: String? = null,
)

@Serializable
data class SportRefDto(
    val id: String,
    val name: String,
)

@Serializable
data class OfferingDto(
    val id: String,
    val sport: SportRefDto,
    val name: String,
    val description: String? = null,
    val level: String,
    val scheduleNotes: String? = null,
    val rating: RatingDto,
    val status: String = "ACTIVE",
)

@Serializable
data class OfferingDetailDto(
    val id: String,
    val name: String,
    val status: String,
    val sport: SportRefDto,
    val place: PlaceRefDto,
)

@Serializable
data class PlaceRefDto(
    val id: String,
    val name: String,
    val demo: Boolean,
)

@Serializable
data class RatingDto(
    val status: String,
    val average: Double? = null,
    val ratingsCount: Int? = null,
)
