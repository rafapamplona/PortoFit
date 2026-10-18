package com.example.portofit.users.api

import org.springframework.http.HttpStatus

/** Erro de um campo do pedido, devolvido na lista `errors`. */
data class FieldError(
	val field: String,
	val message: String,
)

/**
 * Erro com resposta já definida (docs/users-api.md §4). `code` é estável e em inglês; `title` e `detail`
 * são textos para o utilizador e nunca incluem dados recebidos sensíveis (palavras-passe, tokens, emails).
 */
class ApiException(
	val status: HttpStatus,
	val code: String,
	val title: String,
	val detail: String,
	val errors: List<FieldError> = emptyList(),
	val retryAfterSeconds: Long? = null,
) : RuntimeException(code)

object ApiErrors {
	fun validation(errors: List<FieldError>) = ApiException(
		HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Pedido inválido", "Um ou mais campos são inválidos.", errors,
	)

	fun validation(field: String, message: String) = validation(listOf(FieldError(field, message)))

	fun unknownInterests(errors: List<FieldError>) = ApiException(
		HttpStatus.BAD_REQUEST, "UNKNOWN_INTERESTS", "Pedido inválido", "Há modalidades que não existem no catálogo.", errors,
	)

	fun conflict(errors: List<FieldError>) = ApiException(
		HttpStatus.CONFLICT, "CONFLICT", "Não foi possível criar a conta", "Um ou mais dados já estão a ser usados.", errors,
	)

	fun invalidCredentials() = ApiException(
		HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Não foi possível iniciar sessão", "Email ou palavra-passe incorretos.",
	)

	fun invalidRefreshToken() = ApiException(
		HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "Sessão inválida", "A sessão terminou ou não é válida. Inicia sessão novamente.",
	)

	fun tooManyAttempts(retryAfterSeconds: Long) = ApiException(
		HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS", "Demasiadas tentativas",
		"Demasiadas tentativas. Tenta novamente mais tarde.", retryAfterSeconds = retryAfterSeconds,
	)

	fun notFound() = ApiException(
		HttpStatus.NOT_FOUND, "NOT_FOUND", "Não encontrado", "Não existe nenhum utilizador com este identificador.",
	)

	fun catalogUnavailable() = ApiException(
		HttpStatus.SERVICE_UNAVAILABLE, "CATALOG_UNAVAILABLE", "Serviço indisponível",
		"Não foi possível validar as modalidades agora. Os interesses não foram alterados; tenta novamente.",
	)
}
