package com.example.portofit.catalog.api

/** Erro de um parâmetro do pedido, devolvido na lista `errors` da resposta 400. */
data class FieldError(
	val field: String,
	val message: String,
)

/** Um ou mais parâmetros inválidos (HTTP 400). */
class InvalidParametersException(val errors: List<FieldError>) : RuntimeException("Parâmetros inválidos")

/** Recurso inexistente (HTTP 404); a mensagem é mostrada ao cliente. */
class NotFoundException(message: String) : RuntimeException(message)
