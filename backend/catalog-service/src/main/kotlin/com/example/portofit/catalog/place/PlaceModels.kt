package com.example.portofit.catalog.place

import com.example.portofit.catalog.rating.Rating
import com.fasterxml.jackson.annotation.JsonInclude
import java.util.UUID

data class Address(
	val street: String,
	/** Opcional: um ponto de encontro de treino não tem código postal. */
	val postalCode: String?,
	val parish: String?,
	val city: String,
)

data class Location(
	val latitude: Double,
	val longitude: Double,
)

data class Contacts(
	val phone: String?,
	val website: String?,
)

data class SportRef(
	val id: String,
	val name: String,
)

/** Local num resultado de pesquisa. */
data class PlaceSummary(
	val id: UUID,
	val name: String,
	val address: Address,
	val location: Location,
	val sports: List<SportRef>,
	/** Só presente quando a pesquisa inclui coordenadas. */
	@get:JsonInclude(JsonInclude.Include.NON_NULL)
	val distanceKm: Double?,
	val rating: Rating,
	val demo: Boolean,
)

data class Offering(
	val id: UUID,
	val sport: SportRef,
	val name: String,
	val description: String?,
	val level: String,
	val scheduleNotes: String?,
	/** Estado efetivo: RETIRED se a atividade ou o local tiverem sido retirados (docs/catalog-api.md §2.3). */
	val status: String,
	val rating: Rating,
)

/** Detalhe de um local com as atividades oferecidas. */
data class PlaceDetail(
	val id: UUID,
	val name: String,
	val description: String?,
	val address: Address,
	val location: Location,
	val contacts: Contacts,
	val rating: Rating,
	val offerings: List<Offering>,
	/** ACTIVE ou RETIRED: o detalhe continua acessível pelo id para referências históricas. */
	val status: String,
	val demo: Boolean,
)
