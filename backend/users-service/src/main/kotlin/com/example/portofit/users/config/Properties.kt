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

/** Ligação ao catalog-service para validar interesses (docs/users-api.md §7). */
@ConfigurationProperties("portofit.catalog")
data class CatalogProperties(
	val baseUrl: String,
	val timeout: Duration,
	val cacheTtl: Duration,
)
