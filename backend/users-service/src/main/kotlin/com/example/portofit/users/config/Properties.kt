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
 * Endpoints internos, uma chave por cliente, só do `.env`; vazia = o endpoint recusa sempre.
 * - [feedContextKey]: experiences-service, feed-context (docs/users-api.md §3.11; `USERS_INTERNAL_KEY`);
 * - [followersKey]: notifications-service, seguidores de uma pessoa (docs/notifications-api.md §8.1; `USERS_FOLLOWERS_KEY`).
 */
@ConfigurationProperties("portofit.internal")
data class InternalProperties(val feedContextKey: String = "", val followersKey: String = "")

/** Ligação ao catalog-service para validar interesses (docs/users-api.md §7). */
@ConfigurationProperties("portofit.catalog")
data class CatalogProperties(
	val baseUrl: String,
	val timeout: Duration,
	val cacheTtl: Duration,
)

/**
 * Entrega de eventos ao notifications-service (docs/notifications-api.md §6.1). [internalKey] vem do `.env`
 * (`NOTIFY_USERS_KEY`, exclusiva deste produtor); vazia = falha de configuração, os eventos esperam na outbox.
 */
@ConfigurationProperties("portofit.notifications")
data class NotificationsProperties(
	val baseUrl: String,
	val connectTimeout: Duration,
	val timeout: Duration,
	val internalKey: String = "",
)

/** Outbox (docs/notifications-api.md §7): envio periódico, recuo, falha persistente e limpeza. */
@ConfigurationProperties("portofit.outbox")
data class OutboxProperties(
	val enabled: Boolean = true,
	val batchSize: Int,
	val initialBackoff: Duration,
	val maxBackoff: Duration,
	val maxAttempts: Int,
	val maxAge: Duration,
	val sentRetention: Duration,
	val failedRetention: Duration,
)
