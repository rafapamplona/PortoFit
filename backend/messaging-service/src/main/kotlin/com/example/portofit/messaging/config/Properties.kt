package com.example.portofit.messaging.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** Validação local dos access tokens do users-service (docs/messaging-api.md §3). Só a chave pública. */
@ConfigurationProperties("portofit.security")
data class SecurityProperties(
	val issuer: String,
	val audience: String,
	val publicKeyLocation: String,
)

/**
 * Consulta interna em lote no experiences-service (docs/messaging-api.md §7.4). [internalKey] vem do `.env`
 * (`MESSAGING_INTERNAL_KEY`, exclusiva deste serviço); vazia = nenhuma consulta é feita (partilhar → 503,
 * partilhas → UNKNOWN).
 */
@ConfigurationProperties("portofit.experiences")
data class ExperiencesProperties(
	val baseUrl: String,
	val connectTimeout: Duration,
	val timeout: Duration,
	val internalKey: String = "",
)

/** Perfil público no users-service, para validar o destinatário de uma conversa nova (docs/messaging-api.md §8.1). */
@ConfigurationProperties("portofit.users")
data class UsersProperties(
	val baseUrl: String,
	val connectTimeout: Duration,
	val timeout: Duration,
)

/** Limites das mensagens (docs/messaging-api.md §10). */
@ConfigurationProperties("portofit.messaging")
data class MessagingProperties(
	val maxTextLength: Int,
	val maxMessagesPerWindow: Int,
	val maxNewConversationsPerWindow: Int,
	val rateWindow: Duration,
	val maxMessagesPerConversation: Int,
	val maxBodyBytes: Long,
) {
	init {
		require(maxTextLength in 1..MAX_TEXT_LENGTH_LIMIT) { "portofit.messaging.max-text-length tem de estar entre 1 e $MAX_TEXT_LENGTH_LIMIT" }
		require(maxMessagesPerWindow > 0 && maxNewConversationsPerWindow > 0 && maxMessagesPerConversation > 0) { "os limites têm de ser positivos" }
	}

	companion object {
		/** O contrato aceita configurar o texto até 2000 caracteres (a coluna também). */
		const val MAX_TEXT_LENGTH_LIMIT = 2000
	}
}

/**
 * Entrega de eventos ao notifications-service (docs/notifications-api.md §6.1). [internalKey] vem do `.env`
 * (`NOTIFY_MESSAGING_KEY`, exclusiva deste produtor); vazia = falha de configuração, os eventos esperam na outbox.
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
