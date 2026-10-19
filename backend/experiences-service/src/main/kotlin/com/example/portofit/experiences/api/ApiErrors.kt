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

	// --- Fotografias (docs/experiences-api.md §12) ---

	/** A mensagem diz o problema (formato, dimensões, ficheiro danificado), nunca dados do ficheiro nem caminhos. */
	fun invalidImage(message: String) = ApiException(
		HttpStatus.BAD_REQUEST, "INVALID_IMAGE", "Fotografia inválida", message, listOf(FieldError("file", message)),
	)

	fun payloadTooLarge() = ApiException(
		HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "Pedido demasiado grande", "A fotografia pode ter no máximo 10 MiB.",
	)

	fun mediaNotUsable() = ApiException(
		HttpStatus.BAD_REQUEST, "MEDIA_NOT_USABLE", "Pedido inválido",
		"Esta fotografia não pode ser usada: tem de ser tua, enviada há menos de 24 horas e ainda não usada.",
		listOf(FieldError("mediaId", "Esta fotografia não pode ser usada.")),
	)

	fun tooManyPendingPhotos(retryAfterSeconds: Long) = ApiException(
		HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS", "Demasiadas fotografias",
		"Tens demasiadas fotografias enviadas por usar. Usa-as ou espera que expirem.", retryAfterSeconds = retryAfterSeconds,
	)

	fun photoNotFound() = ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Não encontrado", "Esta fotografia não existe ou já não está disponível.")

	fun photoUnavailable() = ApiException(
		HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "Serviço indisponível", "A fotografia está temporariamente indisponível.",
	)

	fun storageUnavailable() = ApiException(
		HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "Serviço indisponível",
		"Não é possível guardar fotografias agora. Tenta novamente daqui a pouco.",
	)

	fun mediaBusy() = ApiException(
		HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "Serviço ocupado",
		"Estão a ser processadas demasiadas fotografias. Tenta novamente daqui a pouco.",
	)

	// --- Comentários, gostos e visualizações (docs/experiences-api.md §13) ---

	fun commentNotFound() = ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Não encontrado", "Não existe nenhum comentário com este identificador.")

	fun commentForbidden() = ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Sem permissão", "Só o autor pode alterar este comentário.")

	fun commentDifferentContent(commentId: UUID) = ApiException(
		HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "Comentário já feito",
		"Esta tentativa de comentário já tinha sido aceite com outro conteúdo.",
		extra = mapOf("reason" to "DIFFERENT_CONTENT", "commentId" to commentId),
	)

	fun commentDeleted() = ApiException(
		HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "Comentário já feito", "Este comentário já tinha sido feito e foi apagado.",
		extra = mapOf("reason" to "COMMENT_DELETED"),
	)

	fun cannotLikeOwn() = ApiException(
		HttpStatus.BAD_REQUEST, "CANNOT_LIKE_OWN", "Pedido inválido", "Não podes gostar da tua própria experiência.",
	)

	fun tooManyComments(retryAfterSeconds: Long) = ApiException(
		HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS", "Demasiados comentários",
		"Atingiste o limite de comentários por agora. Tenta novamente mais tarde.", retryAfterSeconds = retryAfterSeconds,
	)

	fun tooManyLikes(retryAfterSeconds: Long) = ApiException(
		HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS", "Demasiados pedidos",
		"Fizeste demasiadas alterações de gostos por agora. Tenta novamente mais tarde.", retryAfterSeconds = retryAfterSeconds,
	)

	/** Token válido mas sem a claim `username`: o autor não é guardado sem nome (docs/experiences-api.md §2.3). */
	fun tokenWithoutUsername() = ApiException(
		HttpStatus.UNAUTHORIZED, "INVALID_ACCESS_TOKEN", "Sessão inválida", "O token de acesso é inválido ou expirou.",
	)
}
