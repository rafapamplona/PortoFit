package com.example.portofit.experiences.experience

import com.example.portofit.experiences.api.ApiErrors
import com.example.portofit.experiences.api.FieldError
import com.example.portofit.experiences.api.PageResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.util.UUID

/**
 * Experiências (docs/experiences-api.md §4). O corpo é lido como texto e validado em ExperienceRules, para
 * todos os erros terem o mesmo formato. O autor vem sempre do token (`sub` e `username`).
 */
@RestController
@RequestMapping("/api/v1/experiences")
class ExperienceController(
	private val service: ExperienceService,
	private val mapper: JsonMapper,
) {

	@PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
	fun publish(
		@AuthenticationPrincipal jwt: Jwt,
		@RequestHeader(IDEMPOTENCY_KEY, required = false) idempotencyKey: String?,
		@RequestBody(required = false) body: String?,
	): ResponseEntity<ExperienceView> {
		val (authorId, username) = author(jwt)
		val key = ExperienceRules.parseUuid(idempotencyKey)
		val input = try {
			ExperienceRules.parsePublish(body, mapper)
		} catch (e: com.example.portofit.experiences.api.ApiException) {
			if (key == null) throw ApiErrors.validation(e.errors + FieldError(IDEMPOTENCY_KEY, KEY_MESSAGE)) else throw e
		}
		if (key == null) throw ApiErrors.validation(IDEMPOTENCY_KEY, KEY_MESSAGE)

		val result = service.publish(authorId, username, key, input)
		val location = URI.create("/api/v1/experiences/${result.experience.id}")
		return if (result.replayed) {
			ResponseEntity.ok().location(location).header(REPLAYED_HEADER, "true").body(result.experience)
		} else {
			ResponseEntity.status(HttpStatus.CREATED).location(location).body(result.experience)
		}
	}

	@GetMapping
	fun search(
		@RequestParam("placeId", required = false) placeId: String?,
		@RequestParam("offeringId", required = false) offeringId: String?,
		@RequestParam("sportId", required = false) sportId: String?,
		@RequestParam("authorId", required = false) authorId: String?,
		@RequestParam("q", required = false) q: String?,
		@RequestParam("sort", required = false) sort: String?,
		@RequestParam("page", required = false) page: String?,
		@RequestParam("size", required = false) size: String?,
	): PageResponse<ExperienceView> {
		val errors = mutableListOf<FieldError>()
		fun uuid(value: String?, field: String, message: String): UUID? {
			val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
			return ExperienceRules.parseUuid(text) ?: null.also { errors += FieldError(field, message) }
		}
		val place = uuid(placeId, "placeId", "Identificador de local inválido.")
		val offering = uuid(offeringId, "offeringId", "Identificador de atividade inválido.")
		val author = uuid(authorId, "authorId", "Identificador de utilizador inválido.")
		val sport = sportId?.trim()?.takeIf { it.isNotEmpty() }?.also {
			if (!ExperienceRules.isSportId(it)) errors += FieldError("sportId", "Identificador de modalidade inválido.")
		}
		val text = q?.trim()?.takeIf { it.isNotEmpty() }?.also {
			if (it.codePointCount(0, it.length) !in 2..100) errors += FieldError("q", "A pesquisa deve ter entre 2 e 100 caracteres.")
		}
		val order = when (sort?.trim()?.takeIf { it.isNotEmpty() }) {
			null, "recent" -> ExperienceSort.RECENT
			"stars" -> ExperienceSort.STARS
			else -> {
				errors += FieldError("sort", "A ordenação deve ser recent ou stars.")
				ExperienceSort.RECENT
			}
		}
		val (pageValue, sizeValue) = PageResponse.parse(page, size, errors)
		if (errors.isNotEmpty()) throw ApiErrors.validation(errors)
		return service.search(ExperienceCriteria(place, offering, sport, author, text, order, pageValue, sizeValue))
	}

	@GetMapping("/{experienceId}")
	fun detail(@PathVariable("experienceId") experienceId: String): ExperienceView = service.detail(pathId(experienceId))

	@PatchMapping("/{experienceId}", consumes = [MediaType.APPLICATION_JSON_VALUE])
	fun edit(
		@AuthenticationPrincipal jwt: Jwt,
		@PathVariable("experienceId") experienceId: String,
		@RequestBody(required = false) body: String?,
	): ExperienceView {
		val id = pathId(experienceId)
		return service.edit(id, author(jwt).first, ExperienceRules.parseEdit(body, mapper))
	}

	@DeleteMapping("/{experienceId}")
	fun delete(@AuthenticationPrincipal jwt: Jwt, @PathVariable("experienceId") experienceId: String): ResponseEntity<Void> {
		service.delete(pathId(experienceId), author(jwt).first)
		return ResponseEntity.noContent().build()
	}

	private fun pathId(value: String): UUID =
		ExperienceRules.parseUuid(value) ?: throw ApiErrors.validation("experienceId", "Identificador de experiência inválido.")

	/** `sub` e `username` do token; um token sem username é recusado, em vez de guardar um autor sem nome. */
	private fun author(jwt: Jwt): Pair<UUID, String> {
		val id = ExperienceRules.parseUuid(jwt.subject) ?: throw ApiErrors.tokenWithoutUsername()
		val username = jwt.getClaimAsString("username")?.takeIf { it.isNotBlank() } ?: throw ApiErrors.tokenWithoutUsername()
		return id to username
	}

	private companion object {
		const val IDEMPOTENCY_KEY = "Idempotency-Key"
		const val REPLAYED_HEADER = "Idempotent-Replayed"
		const val KEY_MESSAGE = "Falta a chave de idempotência do pedido."
	}
}
