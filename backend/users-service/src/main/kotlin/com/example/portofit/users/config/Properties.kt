package com.example.portofit.users.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** Tokens e palavras-passe (docs/users-api.md §6). */
@ConfigurationProperties("portofit.security")
data class SecurityProperties(
	val issuer: String,
	val audience: String,
	val accessTokenTtl: Duration,
	/** Prazo máximo de uma sessão, desde o login. */
	val sessionTtl: Duration,
	val bcryptStrength: Int,
	val privateKeyLocation: String,
	val publicKeyLocation: String,
)

/** Limites de tentativas por conta e por origem (docs/users-api.md §6.8). */
@ConfigurationProperties("portofit.rate-limit")
data class RateLimitProperties(
	val window: Duration,
	val loginFailuresPerAccount: Int,
	val loginAttemptsPerOrigin: Int,
	val registerPerOrigin: Int,
	val refreshPerOrigin: Int,
)

/** Limite de produto de pessoas seguidas (docs/users-api.md §3.9). */
@ConfigurationProperties("portofit.follows")
data class FollowProperties(val maxFollowing: Int)

/**
 * Endpoints internos (docs/users-api.md §3.11). [feedContextKey] é a chave do experiences-service, vinda do `.env`
 * (`USERS_INTERNAL_KEY`); vazia = o endpoint recusa sempre.
 */
@ConfigurationProperties("portofit.internal")
data class InternalProperties(val feedContextKey: String = "")

/** Ligação ao catalog-service para validar interesses (docs/users-api.md §7). */
@ConfigurationProperties("portofit.catalog")
data class CatalogProperties(
	val baseUrl: String,
	val timeout: Duration,
	val cacheTtl: Duration,
)
