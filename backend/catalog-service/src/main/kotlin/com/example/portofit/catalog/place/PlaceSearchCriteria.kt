package com.example.portofit.catalog.place

import com.example.portofit.catalog.api.FieldError
import com.example.portofit.catalog.api.InvalidParametersException

enum class PlaceSort { NAME, DISTANCE }

data class GeoPoint(val latitude: Double, val longitude: Double)

/** Critérios de pesquisa de locais já validados (ver docs/catalog-api.md §3.2). */
data class PlaceSearchCriteria(
	val text: String?,
	val sportId: String?,
	val origin: GeoPoint?,
	val radiusKm: Double,
	val sort: PlaceSort,
	val page: Int,
	val size: Int,
) {
	val offset: Long get() = page.toLong() * size

	companion object {
		const val DEFAULT_RADIUS_KM = 5.0
		const val MIN_RADIUS_KM = 0.1
		const val MAX_RADIUS_KM = 50.0
		const val DEFAULT_SIZE = 20
		const val MAX_SIZE = 50
		const val MIN_TEXT_LENGTH = 2
		const val MAX_TEXT_LENGTH = 100

		private val SPORT_ID_PATTERN = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")

		/**
		 * Valida os parâmetros recebidos como texto e reúne todos os erros numa só resposta.
		 * Parâmetros vazios (ex.: `q=`) são tratados como ausentes.
		 */
		fun parse(
			q: String?,
			sport: String?,
			lat: String?,
			lng: String?,
			radiusKm: String?,
			sort: String?,
			page: String?,
			size: String?,
			sportExists: (String) -> Boolean,
		): PlaceSearchCriteria {
			val errors = mutableListOf<FieldError>()

			val text = q.blankToNull()
			if (text != null && text.length !in MIN_TEXT_LENGTH..MAX_TEXT_LENGTH) {
				errors += FieldError("q", "A pesquisa deve ter entre 2 e 100 caracteres.")
			}

			val sportId = sport.blankToNull()
			if (sportId != null) {
				when {
					!SPORT_ID_PATTERN.matches(sportId) || sportId.length > 40 ->
						errors += FieldError("sport", "Modalidade desconhecida.")
					!sportExists(sportId) ->
						errors += FieldError("sport", "Modalidade desconhecida: $sportId.")
				}
			}

			val latText = lat.blankToNull()
			val lngText = lng.blankToNull()
			val latitude = latText?.let { parseInRange(it, -90.0, 90.0) }
			val longitude = lngText?.let { parseInRange(it, -180.0, 180.0) }
			if (latText != null && latitude == null) {
				errors += FieldError("lat", "A latitude deve ser um número entre -90 e 90.")
			}
			if (lngText != null && longitude == null) {
				errors += FieldError("lng", "A longitude deve ser um número entre -180 e 180.")
			}
			if (latText != null && lngText == null) {
				errors += FieldError("lng", "Indica a latitude e a longitude em conjunto.")
			}
			if (lngText != null && latText == null) {
				errors += FieldError("lat", "Indica a latitude e a longitude em conjunto.")
			}
			val origin = if (latitude != null && longitude != null) GeoPoint(latitude, longitude) else null
			val coordinatesGiven = latText != null || lngText != null

			val radiusText = radiusKm.blankToNull()
			var radius = DEFAULT_RADIUS_KM
			if (radiusText != null) {
				if (!coordinatesGiven) {
					errors += FieldError("radiusKm", "O raio só pode ser usado com coordenadas.")
				}
				val parsed = parseInRange(radiusText, MIN_RADIUS_KM, MAX_RADIUS_KM)
				if (parsed == null) {
					errors += FieldError("radiusKm", "O raio deve estar entre 0,1 e 50 km.")
				} else {
					radius = parsed
				}
			}

			val sortText = sort.blankToNull()
			val sortValue = when (sortText) {
				null -> if (coordinatesGiven) PlaceSort.DISTANCE else PlaceSort.NAME
				"name" -> PlaceSort.NAME
				"distance" -> PlaceSort.DISTANCE
				else -> {
					errors += FieldError("sort", "Ordenação inválida. Valores aceites: name, distance.")
					PlaceSort.NAME
				}
			}
			if (sortText == "distance" && !coordinatesGiven) {
				errors += FieldError("sort", "A ordenação por distância exige latitude e longitude.")
			}

			val pageText = page.blankToNull()
			val pageValue = pageText?.toIntOrNull()
			if (pageText != null && (pageValue == null || pageValue < 0)) {
				errors += FieldError("page", "A página deve ser um número inteiro maior ou igual a 0.")
			}

			val sizeText = size.blankToNull()
			val sizeValue = sizeText?.toIntOrNull()
			if (sizeText != null && (sizeValue == null || sizeValue !in 1..MAX_SIZE)) {
				errors += FieldError("size", "O tamanho da página deve estar entre 1 e 50.")
			}

			if (errors.isNotEmpty()) throw InvalidParametersException(errors)

			return PlaceSearchCriteria(
				text = text,
				sportId = sportId,
				origin = origin,
				radiusKm = radius,
				sort = sortValue,
				page = pageValue ?: 0,
				size = sizeValue ?: DEFAULT_SIZE,
			)
		}

		private fun String?.blankToNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

		/** Número decimal com ponto, finito e dentro do intervalo; caso contrário null. */
		private fun parseInRange(text: String, min: Double, max: Double): Double? =
			text.toDoubleOrNull()?.takeIf { it.isFinite() && it in min..max }
	}
}
