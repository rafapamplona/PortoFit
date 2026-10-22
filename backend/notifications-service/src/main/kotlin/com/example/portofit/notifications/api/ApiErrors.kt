package com.example.portofit.notifications.api

import org.springframework.http.HttpStatus

/** Erro de um campo do pedido, devolvido na lista `errors`. */
data class FieldError(
	val field: String,
	val message: String,
)

/** Erro com resposta já definida (docs/notifications-api.md §11). Nunca inclui conteúdos, tokens nem chaves. */
class ApiException(
	val status: HttpStatus,
	val code: String,
	val title: String,
	val detail: String,
	val errors: List<FieldError> = emptyList(),
	val retryAfterSeconds: Long? = null,
	val extra: Map<String, Any> = emptyMap(),
) : RuntimeException(code)

object ApiErrors {
	fun validation(errors: List<FieldError>) = ApiException(
		HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Pedido inválido", "Um ou mais campos são inválidos.", errors,
	)

	fun validation(field: String, message: String) = validation(listOf(FieldError(field, message)))

	/** Notificação inexistente **ou de outra pessoa**: a mesma resposta. */
	fun notFound() = ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Não encontrado", "Não existe nenhuma notificação tua com este identificador.")

	fun forbidden() = ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Acesso negado", "Não tens permissão para este pedido.")

	fun invalidToken() = ApiException(HttpStatus.UNAUTHORIZED, "INVALID_ACCESS_TOKEN", "Sessão inválida", "O token de acesso é inválido ou expirou.")
}
