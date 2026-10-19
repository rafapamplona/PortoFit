package com.example.portofit.experiences.feed

import com.example.portofit.experiences.api.ApiErrors
import com.example.portofit.experiences.api.FieldError
import com.example.portofit.experiences.api.PageResponse
import com.example.portofit.experiences.config.FeedProperties
import com.example.portofit.experiences.experience.ExperienceController
import com.example.portofit.experiences.experience.ExperienceRepository
import com.example.portofit.experiences.experience.ExperienceRules
import com.example.portofit.experiences.experience.ExperienceService
import com.example.portofit.experiences.experience.ExperienceView
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

enum class FeedMode { PERSONAL, POPULAR, POPULAR_FALLBACK }

enum class FallbackReason { NO_PREFERENCES, NO_MATCHES, USERS_UNAVAILABLE }

/** Envelope de paginação comum mais o modo (docs/experiences-api.md §14.1–14.2). */
data class FeedPage(
	val items: List<ExperienceView>,
	val page: Int,
	val size: Int,
	val totalItems: Long,
	val totalPages: Int,
	val mode: FeedMode,
	val fallbackReason: FallbackReason?,
	/** Início do bloco recente (só nos modos populares). */
	val recentSince: Instant?,
	val generatedAt: Instant,
)

/**
 * Feeds (docs/experiences-api.md §14). Nunca consultam o catálogo; o pessoal pede o contexto ao users-service e decide o
 * modo pelo conjunto filtrado completo (o total), nunca pela página.
 */
@Service
class FeedService(
	private val experiences: ExperienceRepository,
	private val experienceService: ExperienceService,
	private val users: UsersClient,
	private val properties: FeedProperties,
	private val clock: Clock,
) {

	fun popular(viewerId: UUID?, sportId: String?, page: Int, size: Int): FeedPage =
		popularPage(viewerId, sportId, page, size, FeedMode.POPULAR, null)

	fun personal(userId: UUID, accessToken: String, sportId: String?, page: Int, size: Int): FeedPage {
		val context = when (val result = users.feedContext(accessToken)) {
			FeedContextResult.TokenRejected -> throw ApiErrors.invalidAccessToken()
			FeedContextResult.Unavailable ->
				return popularPage(userId, sportId, page, size, FeedMode.POPULAR_FALLBACK, FallbackReason.USERS_UNAVAILABLE)
			is FeedContextResult.Found -> result
		}
		if (context.empty) return popularPage(userId, sportId, page, size, FeedMode.POPULAR, FallbackReason.NO_PREFERENCES)
		val generatedAt = clock.instant()
		val (rows, total) = experiences.personalFeed(
			userId, context.following, context.interests, sportId, properties.includeDemoPlaces, page, size,
		)
		if (total == 0L) return popularPage(userId, sportId, page, size, FeedMode.POPULAR, FallbackReason.NO_MATCHES)
		val following = context.following.toSet()
		val interests = context.interests.toSet()
		val items = experienceService.views(rows, userId).zip(rows) { view, row ->
			view.copy(matchedBy = listOfNotNull("FOLLOWING".takeIf { row.authorId in following }, "INTEREST".takeIf { row.sportId in interests }))
		}
		val envelope = PageResponse.of(items, page, size, total)
		return FeedPage(envelope.items, page, size, total, envelope.totalPages, FeedMode.PERSONAL, null, null, generatedAt)
	}

	private fun popularPage(viewerId: UUID?, sportId: String?, page: Int, size: Int, mode: FeedMode, reason: FallbackReason?): FeedPage {
		val generatedAt = clock.instant()
		val recentSince = generatedAt.truncatedTo(ChronoUnit.DAYS).minus(properties.recentDays, ChronoUnit.DAYS)
		val (rows, total) = experiences.popularFeed(sportId, properties.includeDemoPlaces, recentSince, page, size)
		val envelope = PageResponse.of(experienceService.views(rows, viewerId), page, size, total)
		return FeedPage(envelope.items, page, size, total, envelope.totalPages, mode, reason, recentSince, generatedAt)
	}
}

@RestController
@RequestMapping("/api/v1/feed")
class FeedController(private val service: FeedService) {

	/** Público; com um token válido acrescenta `likedByMe` (como as outras leituras). */
	@GetMapping("/popular")
	fun popular(
		@AuthenticationPrincipal jwt: Jwt?,
		@RequestParam("sportId", required = false) sportId: String?,
		@RequestParam("page", required = false) page: String?,
		@RequestParam("size", required = false) size: String?,
	): FeedPage {
		val (sport, pageValue, sizeValue) = parse(sportId, page, size)
		return service.popular(ExperienceController.viewer(jwt), sport, pageValue, sizeValue)
	}

	/** Só com sessão; o utilizador é sempre o do token. */
	@GetMapping
	fun personal(
		@AuthenticationPrincipal jwt: Jwt,
		@RequestParam("sportId", required = false) sportId: String?,
		@RequestParam("page", required = false) page: String?,
		@RequestParam("size", required = false) size: String?,
	): FeedPage {
		val (sport, pageValue, sizeValue) = parse(sportId, page, size)
		val userId = ExperienceController.viewer(jwt)!!
		return service.personal(userId, jwt.tokenValue, sport, pageValue, sizeValue)
	}

	private fun parse(sportId: String?, page: String?, size: String?): Triple<String?, Int, Int> {
		val errors = mutableListOf<FieldError>()
		val sport = sportId?.trim()?.takeIf { it.isNotEmpty() }?.also {
			if (!ExperienceRules.isSportId(it)) errors += FieldError("sportId", "Identificador de modalidade inválido.")
		}
		val (pageValue, sizeValue) = PageResponse.parse(page, size, errors)
		if (errors.isNotEmpty()) throw ApiErrors.validation(errors)
		return Triple(sport, pageValue, sizeValue)
	}
}
