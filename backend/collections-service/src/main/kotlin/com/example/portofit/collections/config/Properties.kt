package com.example.portofit.collections.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** Validação local dos access tokens do users-service (docs/collections-api.md §3). Só a chave pública. */
@ConfigurationProperties("portofit.security")
data class SecurityProperties(
	val issuer: String,
	val audience: String,
	val publicKeyLocation: String,
)

/**
 * Consulta interna em lote no experiences-service (docs/collections-api.md §7). [internalKey] vem do `.env`
 * (`COLLECTIONS_INTERNAL_KEY`); vazia = nenhuma consulta é feita (guardar → 503, itens → UNKNOWN).
 */
@ConfigurationProperties("portofit.experiences")
data class ExperiencesProperties(
	val baseUrl: String,
	val connectTimeout: Duration,
	val timeout: Duration,
	val internalKey: String = "",
)

/** Limites das listas (docs/collections-api.md §9). */
@ConfigurationProperties("portofit.collections")
data class CollectionsProperties(
	val maxPerOwner: Int,
	val maxItems: Int,
	val idempotencyRetention: Duration,
	val maxBodyBytes: Long,
)
