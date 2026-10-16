package com.example.portofit.catalog.place

import com.example.portofit.catalog.api.FieldError
import com.example.portofit.catalog.api.InvalidParametersException
import com.example.portofit.catalog.api.PageResponse
import com.example.portofit.catalog.sport.SportRepository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Os parâmetros são recebidos como texto e validados em PlaceSearchCriteria, para que todos os erros
 * (incluindo números mal escritos) tenham o mesmo formato e mensagens em português.
 */
@RestController
@RequestMapping("/api/v1/places")
class PlaceController(
	private val placeService: PlaceService,
	private val sports: SportRepository,
) {

	@GetMapping
	fun search(
		@RequestParam("q", required = false) q: String?,
		@RequestParam("sport", required = false) sport: String?,
		@RequestParam("lat", required = false) lat: String?,
		@RequestParam("lng", required = false) lng: String?,
		@RequestParam("radiusKm", required = false) radiusKm: String?,
		@RequestParam("sort", required = false) sort: String?,
		@RequestParam("page", required = false) page: String?,
		@RequestParam("size", required = false) size: String?,
	): PageResponse<PlaceSummary> {
		val criteria = PlaceSearchCriteria.parse(q, sport, lat, lng, radiusKm, sort, page, size, sports::exists)
		return placeService.search(criteria)
	}

	@GetMapping("/{placeId}")
	fun detail(@PathVariable("placeId") placeId: String): PlaceDetail {
		val id = runCatching { UUID.fromString(placeId) }.getOrNull()
			?.takeIf { it.toString().equals(placeId, ignoreCase = true) }
			?: throw InvalidParametersException(listOf(FieldError("placeId", "Identificador de local inválido.")))
		return placeService.detail(id)
	}
}
