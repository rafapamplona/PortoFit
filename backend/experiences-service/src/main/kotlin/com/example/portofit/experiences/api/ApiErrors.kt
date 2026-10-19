package com.example.portofit.experiences.api

import org.springframework.http.HttpStatus
import java.util.UUID

/** Erro de um campo do pedido, devolvido na lista `errors`. */
data class FieldError(
	val field: String,
	val message: String,
)

/**
 * Erro com resposta já definida (docs/experiences-api.md §8). `code` é estável e em inglês; `title` e
 * `detail` são textos para o utilizador e nunca incluem tokens nem o texto enviado.
 */
class ApiException(
	val status: HttpStatus,
	val code: String,
	val title: String,
	val detail: String,
	val errors: List<FieldError> = emptyList(),
	val retryAfterSeconds: Long? = null,
	/** Campos extra no nível de topo (ex.: `reason`, `experienceId`). */
	val extra: Map<String, Any> = emptyMap(),
) : RuntimeException(code)

object ApiErrors {
	fun validation(errors: List<FieldError>) = ApiException(
		HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Pedido inválido", "Um ou mais campos são inválidos.", errors,
	)

	fun validation(field: String, message: String) = validation(listOf(FieldError(field, message)))

	fun unknownOffering() = ApiException(
		HttpStatus.BAD_REQUEST, "UNKNOWN_OFFERING", "Pedido inválido", "Esta atividade não existe no catálogo.",
		listOf(FieldError("offeringId", "Esta atividade não existe no catálogo.")),
	)

	fun offeringPlaceMismatch(): ApiException {
		val message = "Esta atividade não pertence ao local indicado."
		return ApiException(
			HttpStatus.BAD_REQUEST, "OFFERING_PLACE_MISMATCH", "Pedido inválido", message,
			listOf(FieldError("placeId", message), FieldError("offeringId", message)),
		)
	}

	fun offeringRetired() = ApiException(
		HttpStatus.BAD_REQUEST, "OFFERING_RETIRED", "Pedido inválido", "Esta atividade já não está disponível no catálogo.",
		listOf(FieldError("offeringId", "Esta atividade já não está disponível no catálogo.")),
	)

	fun forbidden() = ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Sem permissão", "Só o autor pode alterar esta experiência.")

	fun notFound() = ApiException(
		HttpStatus.NOT_FOUND, "NOT_FOUND", "Não encontrado", "Não existe nenhuma experiência com este identificador.",
	)

	fun differentContent(experienceId: UUID) = ApiException(
		HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "Publicação já feita",
		"Esta tentativa de publicação já tinha sido aceite com outro conteúdo.",
		extra = mapOf("reason" to "DIFFERENT_CONTENT", "experienceId" to experienceId),
	)

	fun experienceDeleted() = ApiException(
		HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "Publicação já feita", "Esta publicação já tinha sido feita e foi apagada.",
		extra = mapOf("reason" to "EXPERIENCE_DELETED"),
	)

	fun tooManyPublications(retryAfterSeconds: Long) = ApiException(
		HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS", "Demasiadas publicações",
		"Atingiste o limite de publicações por agora. Tenta novamente mais tarde.", retryAfterSeconds = retryAfterSeconds,
	)

	fun catalogUnavailable() = ApiException(
		HttpStatus.SERVICE_UNAVAILABLE, "CATALOG_UNAVAILABLE", "Serviço indisponível",
		"Não é possível publicar agora: o catálogo está indisponível. Tenta novamente daqui a pouco.",
	)

	/** Token válido mas sem a claim `username`: o autor não é guardado sem nome (docs/experiences-api.md §2.3). */
	fun tokenWithoutUsername() = ApiException(
		HttpStatus.UNAUTHORIZED, "INVALID_ACCESS_TOKEN", "Sessão inválida", "O token de acesso é inválido ou expirou.",
	)
}
