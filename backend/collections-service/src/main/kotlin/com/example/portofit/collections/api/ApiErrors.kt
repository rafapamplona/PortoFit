package com.example.portofit.collections.api

import org.springframework.http.HttpStatus
import java.util.UUID

/** Erro de um campo do pedido, devolvido na lista `errors`. */
data class FieldError(
	val field: String,
	val message: String,
)

/**
 * Erro com resposta já definida (docs/collections-api.md §8). `code` é estável e em inglês; `title` e `detail` são
 * textos para o utilizador e nunca incluem tokens, nomes de listas de outras pessoas nem dados internos.
 */
class ApiException(
	val status: HttpStatus,
	val code: String,
	val title: String,
	val detail: String,
	val errors: List<FieldError> = emptyList(),
	val retryAfterSeconds: Long? = null,
	/** Campos extra no nível de topo (ex.: `reason`, `collectionId`). */
	val extra: Map<String, Any> = emptyMap(),
) : RuntimeException(code)

object ApiErrors {
	fun validation(errors: List<FieldError>) = ApiException(
		HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Pedido inválido", "Um ou mais campos são inválidos.", errors,
	)

	fun validation(field: String, message: String) = validation(listOf(FieldError(field, message)))

	/** Lista inexistente **ou de outra pessoa**: a mesma resposta, para não revelar que existe. */
	fun notFound() = ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Não encontrado", "Não existe nenhuma lista tua com este identificador.")

	fun nameTaken() = ApiException(
		HttpStatus.CONFLICT, "NAME_TAKEN", "Nome já usado", "Já tens uma lista com este nome.",
		listOf(FieldError("name", "Já tens uma lista com este nome.")),
	)

	fun collectionLimit(max: Int) = ApiException(
		HttpStatus.CONFLICT, "COLLECTION_LIMIT_REACHED", "Limite atingido", "Já tens $max listas, o máximo permitido.",
	)

	fun itemLimit(max: Int) = ApiException(
		HttpStatus.CONFLICT, "ITEM_LIMIT_REACHED", "Limite atingido", "Esta lista já tem $max experiências, o máximo permitido.",
	)

	fun differentContent(collectionId: UUID) = ApiException(
		HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "Lista já criada", "Esta tentativa já tinha criado uma lista com outro nome.",
		extra = mapOf("reason" to "DIFFERENT_CONTENT", "collectionId" to collectionId),
	)

	fun collectionDeleted() = ApiException(
		HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "Lista já criada", "Esta lista já tinha sido criada e foi eliminada.",
		extra = mapOf("reason" to "COLLECTION_DELETED"),
	)

	fun experienceNotFound() = ApiException(
		HttpStatus.NOT_FOUND, "EXPERIENCE_NOT_FOUND", "Experiência inexistente", "Esta experiência não existe ou foi eliminada.",
	)

	fun experiencesUnavailable() = ApiException(
		HttpStatus.SERVICE_UNAVAILABLE, "EXPERIENCES_UNAVAILABLE", "Serviço indisponível",
		"Não foi possível confirmar a experiência agora. Nada foi guardado; tenta novamente daqui a pouco.",
	)
}
