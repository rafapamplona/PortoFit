package com.example.portofit.experiences.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** Validação local dos access tokens do users-service (docs/experiences-api.md §3). Só a chave pública. */
@ConfigurationProperties("portofit.security")
data class SecurityProperties(
	val issuer: String,
	val audience: String,
	val publicKeyLocation: String,
)

/** Validação de atividades no catalog-service (docs/experiences-api.md §6). */
@ConfigurationProperties("portofit.catalog")
data class CatalogProperties(
	val baseUrl: String,
	val connectTimeout: Duration,
	val timeout: Duration,
	/** Atraso máximo aceite nos dados do catálogo usados para validar (§6.2). */
	val cacheTtl: Duration,
)

/** Limites da publicação (docs/experiences-api.md §4.1, §5). */
@ConfigurationProperties("portofit.publishing")
data class PublishingProperties(
	val maxPerAuthor: Int,
	val window: Duration,
	val idempotencyRetention: Duration,
	val maxBodyBytes: Long,
)
