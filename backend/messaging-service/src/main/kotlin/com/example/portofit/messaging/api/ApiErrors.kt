package com.example.portofit.messaging.api

import org.springframework.http.HttpStatus
import java.util.UUID

/** Erro de um campo do pedido, devolvido na lista `errors`. */
data class FieldError(
	val field: String,
	val message: String,
)

/**
 * Erro com resposta já definida (docs/messaging-api.md §9). `code` é estável e em inglês; `title` e `detail` são
 * textos para o utilizador e nunca incluem o texto das mensagens, a existência de conversas alheias, tokens nem
 * dados internos.
 */
class ApiException(
	val status: HttpStatus,
	val code: String,
	val title: String,
	val detail: String,
	val errors: List<FieldError> = emptyList(),
	val retryAfterSeconds: Long? = null,
	/** Campos extra no nível de topo (ex.: `reason`, `messageId`). */
	val extra: Map<String, Any> = emptyMap(),
) : RuntimeException(code)

object ApiErrors {
	fun validation(errors: List<FieldError>) = ApiException(
		HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Pedido inválido", "Um ou mais campos são inválidos.", errors,
	)

	fun validation(field: String, message: String) = validation(listOf(FieldError(field, message)))

	fun cannotMessageSelf() = ApiException(
		HttpStatus.BAD_REQUEST, "CANNOT_MESSAGE_SELF", "Pedido inválido", "Não podes iniciar uma conversa contigo próprio.",
		listOf(FieldError("participantId", "Escolhe outra pessoa.")),
	)

	/** Conversa inexistente **ou em que não participo**: a mesma resposta, para não revelar que existe. */
	fun notFound() = ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Não encontrado", "Não existe nenhuma conversa tua com este identificador.")

	fun userNotFound() = ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Pessoa inexistente", "Não existe nenhuma pessoa com este identificador.")

	fun experienceNotFound() = ApiException(
		HttpStatus.NOT_FOUND, "EXPERIENCE_NOT_FOUND", "Experiência inexistente", "Esta experiência não existe ou foi eliminada. Nada foi enviado.",
	)

	fun differentContent(conversationId: UUID, messageId: UUID) = ApiException(
		HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "Mensagem já enviada", "Esta tentativa já tinha enviado outra mensagem.",
		extra = mapOf("reason" to "DIFFERENT_CONTENT", "conversationId" to conversationId, "messageId" to messageId),
	)

	fun conversationFull(max: Int) = ApiException(
		HttpStatus.CONFLICT, "CONVERSATION_FULL", "Limite atingido", "Esta conversa já tem $max mensagens, o máximo permitido.",
	)

	fun tooManyMessages(retryAfter: Long) = ApiException(
		HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS", "Demasiadas mensagens", "Enviaste muitas mensagens em pouco tempo. Tenta mais tarde.",
		retryAfterSeconds = retryAfter,
	)

	fun tooManyConversations(retryAfter: Long) = ApiException(
		HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS", "Demasiadas conversas novas", "Iniciaste muitas conversas em pouco tempo. Tenta mais tarde.",
		retryAfterSeconds = retryAfter,
	)

	fun usersUnavailable() = ApiException(
		HttpStatus.SERVICE_UNAVAILABLE, "USERS_UNAVAILABLE", "Serviço indisponível",
		"Não é possível iniciar a conversa agora. Nada foi criado; tenta novamente daqui a pouco.",
	)

	fun experiencesUnavailable() = ApiException(
		HttpStatus.SERVICE_UNAVAILABLE, "EXPERIENCES_UNAVAILABLE", "Serviço indisponível",
		"Não foi possível confirmar a experiência agora. Nada foi enviado; tenta novamente daqui a pouco.",
	)

	fun invalidToken() = ApiException(HttpStatus.UNAUTHORIZED, "INVALID_ACCESS_TOKEN", "Sessão inválida", "O token de acesso é inválido ou expirou.")
}
