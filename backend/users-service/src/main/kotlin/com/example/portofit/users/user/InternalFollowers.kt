package com.example.portofit.users.user

import com.example.portofit.users.api.ApiErrors
import com.example.portofit.users.config.InternalProperties
import org.slf4j.LoggerFactory
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.UUID

/** Uma página de seguidores: só ids (docs/notifications-api.md §8.1). */
data class FollowerIdsPage(val items: List<UUID>, val nextCursor: String?)

/** Posição na ordem total (`since`, `followerId`). */
data class FollowerCursor(val since: Instant, val followerId: UUID) {
	fun encode(): String = Base64.getUrlEncoder().withoutPadding().encodeToString("$since|$followerId".toByteArray(Charsets.UTF_8))

	companion object {
		fun decode(value: String): FollowerCursor? = runCatching {
			val (since, id) = String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8).split('|').also { require(it.size == 2) }
			FollowerCursor(Instant.parse(since), UUID.fromString(id))
		}.getOrNull()
	}
}

@Repository
class FollowerIdsRepository(private val jdbc: JdbcClient) {

	/**
	 * Até [limit] + 1 seguidores de [userId] com relação criada até [followedBefore], depois de [after], por
	 * (`created_at`, `follower_id`): começar ou deixar de seguir entre páginas não faz repetir nem saltar ninguém.
	 */
	fun page(userId: UUID, followedBefore: Instant, after: FollowerCursor?, limit: Int): List<FollowerCursor> {
		val condition = if (after == null) "" else "AND (created_at, follower_id) > (:since, :follower)"
		val spec = jdbc.sql(
			"""
			SELECT created_at, follower_id FROM follow
			WHERE followed_id = :id AND created_at <= :before $condition
			ORDER BY created_at, follower_id LIMIT :limit
			""",
		).param("id", userId).param("before", Timestamp.from(followedBefore)).param("limit", limit + 1)
		if (after != null) spec.param("since", Timestamp.from(after.since)).param("follower", after.followerId)
		return spec.query { rs, _ -> FollowerCursor(rs.getTimestamp("created_at").toInstant(), rs.getObject("follower_id", UUID::class.java)) }.list()
	}
}

/**
 * `GET /api/v1/internal/users/{userId}/followers`, só para o notifications-service (docs/notifications-api.md §8.1),
 * com `X-Internal-Key` = `USERS_FOLLOWERS_KEY` (comparação em tempo constante; senão → 403, antes de tudo o resto).
 * Um token de utilizador não serve. Pessoa inexistente → 404.
 */
@RestController
class InternalFollowersController(
	private val followers: FollowerIdsRepository,
	private val users: UserRepository,
	private val properties: InternalProperties,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	@GetMapping("/api/v1/internal/users/{userId}/followers")
	fun followers(
		@RequestHeader(INTERNAL_KEY, required = false) key: String?,
		@PathVariable("userId") userId: String,
		@RequestParam("followedBefore", required = false) followedBefore: String?,
		@RequestParam("cursor", required = false) cursor: String?,
		@RequestParam("limit", required = false) limit: String?,
	): ResponseEntity<FollowerIdsPage> {
		if (!validKey(key)) {
			// Nunca regista a chave recebida.
			log.warn("Pedido de seguidores recusado: chave interna em falta ou errada")
			throw ApiErrors.internalForbidden()
		}
		val id = parseUserId(userId)
		val before = followedBefore?.let {
			try {
				Instant.parse(it)
			} catch (_: DateTimeParseException) {
				null
			}
		} ?: throw ApiErrors.validation("followedBefore", "Indica um instante ISO 8601.")
		val after = cursor?.let { FollowerCursor.decode(it) ?: throw ApiErrors.validation("cursor", "Cursor inválido.") }
		val size = if (limit == null) MAX_LIMIT else limit.toIntOrNull()?.takeIf { it in 1..MAX_LIMIT }
			?: throw ApiErrors.validation("limit", "O limite tem de estar entre 1 e $MAX_LIMIT.")
		if (users.findById(id) == null) throw ApiErrors.notFound()
		val rows = followers.page(id, before, after, size)
		val page = rows.take(size)
		val next = if (rows.size > size) page.last().encode() else null
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(FollowerIdsPage(page.map { it.followerId }, next))
	}

	private fun validKey(received: String?): Boolean {
		val expected = properties.followersKey
		if (expected.isBlank() || received.isNullOrEmpty()) return false
		return MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), received.toByteArray(Charsets.UTF_8))
	}

	private companion object {
		const val INTERNAL_KEY = "X-Internal-Key"
		const val MAX_LIMIT = 500
	}
}
