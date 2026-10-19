package com.example.portofit.users.user

import com.example.portofit.users.api.ApiErrors
import com.example.portofit.users.config.InternalProperties
import org.slf4j.LoggerFactory
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
import java.util.UUID

/** Contexto do feed pessoal: só ids (docs/users-api.md §3.11). */
data class FeedContext(val following: List<UUID>, val interests: List<String>)

/**
 * `GET /api/v1/internal/me/feed-context`, só para o experiences-service. Exige as duas coisas:
 * - o access token do utilizador (validado pelo resource server; sem ele → 401): o contexto é sempre o do `sub`;
 * - `X-Internal-Key` igual à chave configurada (comparação em tempo constante; senão → 403).
 * Não há `userId` no caminho: a chave sozinha não permite ler o contexto de ninguém.
 */
@RestController
class FeedContextController(
	private val follows: FollowRepository,
	private val users: UserRepository,
	private val properties: InternalProperties,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	@GetMapping("/api/v1/internal/me/feed-context")
	fun feedContext(
		@AuthenticationPrincipal jwt: Jwt,
		@RequestHeader(INTERNAL_KEY, required = false) key: String?,
	): ResponseEntity<FeedContext> {
		if (!validKey(key)) {
			// Nunca regista a chave recebida nem o token.
			log.warn("Pedido ao feed-context recusado: chave interna em falta ou errada")
			throw ApiErrors.internalForbidden()
		}
		val userId = jwt.userId()
		val context = FeedContext(follows.followingIds(userId), users.interests(userId))
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(context)
	}

	private fun validKey(received: String?): Boolean {
		val expected = properties.feedContextKey
		if (expected.isBlank() || received.isNullOrEmpty()) return false
		return MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), received.toByteArray(Charsets.UTF_8))
	}

	private companion object {
		const val INTERNAL_KEY = "X-Internal-Key"
	}
}
