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

/** Fotografias (docs/experiences-api.md §12). */
@ConfigurationProperties("portofit.media")
data class MediaProperties(
	val storageDir: String,
	val maxFileBytes: Long,
	val minSide: Int,
	val maxSide: Int,
	val maxPixels: Long,
	val outputMaxSide: Int,
	/** Lado maior a que a imagem é descodificada (subamostragem): limita a memória usada. */
	val decodeMaxSide: Int,
	val jpegQuality: Float,
	val maxConcurrentProcessing: Int,
	val processingWait: Duration,
	val maxPendingPerOwner: Int,
	val pendingTtl: Duration,
	/** Idade a partir da qual ficheiros sem linha e temporários são apagados. */
	val orphanAge: Duration,
)

/** Comentários, gostos e visualizações (docs/experiences-api.md §13.5, §13.6). */
@ConfigurationProperties("portofit.interactions")
data class InteractionProperties(
	val maxCommentsPerAuthor: Int,
	val commentWindow: Duration,
	/** Só contam pedidos que criam ou retiram um gosto, não as repetições. */
	val maxLikeChangesPerUser: Int,
	val likeWindow: Duration,
	/** Período fixo (alinhado a 00:00 UTC) em que cada pessoa ou chave conta uma visualização. */
	val viewPeriod: Duration,
)
