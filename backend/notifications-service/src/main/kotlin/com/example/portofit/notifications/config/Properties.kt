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
	/** Seguir de novo o mesmo par dentro desta janela não gera outra notificação (§6.3). */
	val followDedupWindow: Duration = Duration.ofHours(24),
	/** Resolução dos seguidores (§8): liga o envio periódico (desligado nos testes, que o chamam diretamente). */
	val fanOutEnabled: Boolean = true,
	/** Limite de proteção de destinatários por publicação; acima → TRUNCATED (§8.3). */
	val maxFanOut: Int = 10_000,
	val fanOutPage: Int = 500,
	/** Uma resolução que não conclua neste prazo passa a FAILED (§8.3). */
	val fanOutMaxAge: Duration = Duration.ofHours(48),
) {
	init {
		// A deduplicação tem de durar mais do que a idade máxima de um evento aceite (§6.4).
		require(eventRetention > eventMaxAge) { "portofit.notifications.event-retention tem de ser maior do que event-max-age" }
		require(maxPerRecipient > 0) { "portofit.notifications.max-per-recipient tem de ser positivo" }
		require(maxFanOut > 0) { "portofit.notifications.max-fan-out tem de ser positivo" }
		require(fanOutPage in 1..500) { "portofit.notifications.fan-out-page tem de estar entre 1 e 500" }
	}
}

/**
 * Seguidores do autor de uma nova experiência (docs/notifications-api.md §8.1): endpoint interno do users-service,
 * com a chave `USERS_FOLLOWERS_KEY`, só do `.env`; vazia = erro de configuração (repete sem gastar tentativas).
 */
@ConfigurationProperties("portofit.users")
data class UsersProperties(
	val baseUrl: String,
	val connectTimeout: Duration,
	val timeout: Duration,
	val followersKey: String = "",
)
