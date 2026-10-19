package com.example.portofit.users.user

import com.example.portofit.users.api.ApiErrors
import com.example.portofit.users.api.FieldError
import com.example.portofit.users.api.PageResponse
import com.example.portofit.users.catalog.CatalogClient
import com.fasterxml.jackson.annotation.JsonInclude
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/** Perfil do próprio utilizador: o único sítio onde aparecem o email e a data de registo. */
data class PrivateProfile(
	val userId: UUID,
	val username: String,
	val email: String,
	val description: String?,
	val interests: List<String>,
	val followersCount: Long,
	val followingCount: Long,
	val createdAt: Instant,
)

/**
 * Perfil visível a todos: sem email, sem data de registo.
 * `followedByMe` só existe quando quem pede tem sessão e é **outra** pessoa; para visitantes e para o
 * próprio é omitido (não é "false": a relação simplesmente não foi consultada).
 */
data class PublicProfile(
	val userId: UUID,
	val username: String,
	val description: String?,
	val interests: List<String>,
	val followersCount: Long,
	val followingCount: Long,
	@get:JsonInclude(JsonInclude.Include.NON_NULL)
	val followedByMe: Boolean?,
)

data class UserSummary(
	val userId: UUID,
	val username: String,
	val description: String?,
)

data class InterestsRequest(val interests: List<String>? = null)

@Service
class ProfileService(
	private val users: UserRepository,
	private val catalog: CatalogClient,
	private val transactions: TransactionTemplate,
	private val follows: FollowRepository,
) {

	fun privateProfile(id: UUID): PrivateProfile {
		val user = users.findById(id) ?: throw ApiErrors.notFound()
		val counts = follows.counts(id)
		return PrivateProfile(
			user.id, user.username, user.email, user.description, users.interests(id),
			counts.followers, counts.following, user.createdAt,
		)
	}

	/** [viewerId] = utilizador com sessão que pede o perfil (null para visitantes). */
	fun publicProfile(id: UUID, viewerId: UUID?): PublicProfile {
		val user = users.findById(id) ?: throw ApiErrors.notFound()
		val counts = follows.counts(id)
		val followedByMe = viewerId?.takeIf { it != id }?.let { follows.isFollowing(it, id) }
		return PublicProfile(user.id, user.username, user.description, users.interests(id), counts.followers, counts.following, followedByMe)
	}

	/** Só `description` é editável; qualquer outro campo é recusado. */
	fun updateProfile(id: UUID, body: Map<String, Any?>): PrivateProfile {
		val errors = body.keys.filter { it != "description" }.map { FieldError(it, "Este campo não pode ser alterado.") }.toMutableList()
		if ("description" !in body) errors += FieldError("description", "Indica a descrição (ou null para a apagar).")
		val raw = body["description"]
		if (raw != null && raw !is String) errors += FieldError("description", "A descrição tem de ser texto.")
		val description = (raw as? String)?.trim()?.ifEmpty { null }
		description?.let { UserRules.descriptionError(it) }?.let { errors += it }
		if (errors.isNotEmpty()) throw ApiErrors.validation(errors)

		users.updateDescription(id, description)
		return privateProfile(id)
	}

	/**
	 * Só os ids novos (que o utilizador ainda não tinha) são confirmados no catálogo. Remover interesses
	 * ou enviar uma lista vazia não depende do catálogo (docs/users-api.md §7).
	 */
	fun updateInterests(id: UUID, request: InterestsRequest): PrivateProfile {
		val interests = request.interests ?: throw ApiErrors.validation("interests", "Indica a lista de modalidades (pode ser vazia).")
		UserRules.interestsErrors(interests).takeIf { it.isNotEmpty() }?.let { throw ApiErrors.validation(it) }

		val newOnes = interests - users.interests(id).toSet()
		if (newOnes.isNotEmpty()) {
			val known = catalog.sportIds() // CATALOG_UNAVAILABLE se não for possível validar
			val unknown = newOnes.filterNot { it in known }
			if (unknown.isNotEmpty()) {
				throw ApiErrors.unknownInterests(unknown.map { FieldError("interests", "Modalidade desconhecida: $it.") })
			}
		}
		transactions.executeWithoutResult { users.replaceInterests(id, interests) }
		return privateProfile(id)
	}

	fun search(q: String?, page: String?, size: String?): PageResponse<UserSummary> {
		val query = q?.trim()?.lowercase(java.util.Locale.ROOT)
		if (query == null || query.length !in 2..50) {
			throw ApiErrors.validation("q", "Indica o texto a pesquisar (2 a 50 caracteres).")
		}
		val (pageValue, sizeValue) = PageResponse.parse(page, size)

		val (rows, total) = users.search(query!!, pageValue, sizeValue)
		return PageResponse.of(rows.map { UserSummary(it.id, it.username, it.description) }, pageValue, sizeValue, total)
	}
}

/** O userId vem sempre do token (claim `sub`), nunca do pedido. */
@RestController
@RequestMapping("/api/v1/me")
class MeController(private val profiles: ProfileService) {

	@GetMapping
	fun me(@AuthenticationPrincipal jwt: Jwt): PrivateProfile = profiles.privateProfile(jwt.userId())

	@PatchMapping
	fun update(@AuthenticationPrincipal jwt: Jwt, @RequestBody body: Map<String, Any?>): PrivateProfile =
		profiles.updateProfile(jwt.userId(), body)

	@PutMapping("/interests")
	fun interests(@AuthenticationPrincipal jwt: Jwt, @RequestBody body: InterestsRequest): PrivateProfile =
		profiles.updateInterests(jwt.userId(), body)
}

@RestController
@RequestMapping("/api/v1/users")
class UsersController(private val profiles: ProfileService) {

	@GetMapping
	fun search(
		@RequestParam("q", required = false) q: String?,
		@RequestParam("page", required = false) page: String?,
		@RequestParam("size", required = false) size: String?,
	): PageResponse<UserSummary> = profiles.search(q, page, size)

	/** Público; com um access token válido (de outra pessoa) acrescenta `followedByMe`. */
	@GetMapping("/{userId}")
	fun profile(@PathVariable("userId") userId: String, @AuthenticationPrincipal jwt: Jwt?): ResponseEntity<PublicProfile> =
		ResponseEntity.ok(profiles.publicProfile(parseUserId(userId), jwt?.userId()))
}

fun Jwt.userId(): UUID = UUID.fromString(subject)
