package com.example.portofit.notifications.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** Validação local dos access tokens do users-service (docs/notifications-api.md §4). Só a chave pública. */
@ConfigurationProperties("portofit.security")
data class SecurityProperties(
	val issuer: String,
	val audience: String,
	val publicKeyLocation: String,
)

/**
 * Chaves dos produtores para a entrega interna (docs/notifications-api.md §6.1), uma por serviço, só do `.env`.
 * Uma chave vazia não autoriza ninguém; duas iguais também não (não se saberia quem entrega).
 */
@ConfigurationProperties("portofit.producers")
data class ProducerKeys(
	val usersKey: String = "",
	val experiencesKey: String = "",
	val messagingKey: String = "",
)

/** Limites e retenção (docs/notifications-api.md §12). */
@ConfigurationProperties("portofit.notifications")
data class NotificationsProperties(
	val retention: Duration,
	val maxPerRecipient: Int,
	val eventMaxAge: Duration,
	val eventRetention: Duration,
	val maxFutureSkew: Duration,
	val maxBodyBytes: Long,
) {
	init {
		// A deduplicação tem de durar mais do que a idade máxima de um evento aceite (§6.4).
		require(eventRetention > eventMaxAge) { "portofit.notifications.event-retention tem de ser maior do que event-max-age" }
		require(maxPerRecipient > 0) { "portofit.notifications.max-per-recipient tem de ser positivo" }
	}
}
