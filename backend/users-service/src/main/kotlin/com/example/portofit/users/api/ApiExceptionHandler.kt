package com.example.portofit.users.api

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.TransientDataAccessException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.resource.NoResourceFoundException
import java.net.URI

/** Erros em Problem Details com `code` estável (docs/users-api.md §4); sem detalhes internos. */
@RestControllerAdvice
class ApiExceptionHandler {

	private val log = LoggerFactory.getLogger(javaClass)

	@ExceptionHandler(ApiException::class)
	fun api(ex: ApiException, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
		val response = problem(ex.status, ex.code, ex.title, ex.detail, request, ex.errors)
		return if (ex.retryAfterSeconds != null) {
			ResponseEntity.status(ex.status).header(HttpHeaders.RETRY_AFTER, ex.retryAfterSeconds.toString()).body(response)
		} else {
			ResponseEntity.status(ex.status).body(response)
		}
	}

	@ExceptionHandler(HttpMessageNotReadableException::class, HttpMediaTypeNotSupportedException::class)
	fun unreadable(request: HttpServletRequest) = ResponseEntity.badRequest().body(
		problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Pedido inválido", "O corpo do pedido tem de ser JSON válido.", request),
	)

	@ExceptionHandler(NoResourceFoundException::class)
	fun noResource(request: HttpServletRequest) = ResponseEntity.status(HttpStatus.NOT_FOUND).body(
		problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "Não encontrado", "O recurso pedido não existe.", request),
	)

	@ExceptionHandler(HttpRequestMethodNotSupportedException::class)
	fun methodNotAllowed(request: HttpServletRequest) = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(
		problem(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Método não permitido", "Este recurso não aceita este método.", request),
	)

	@ExceptionHandler(DataAccessResourceFailureException::class, TransientDataAccessException::class)
	fun databaseUnavailable(ex: Exception, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
		log.warn("Base de dados indisponível ao processar {}: {}", request.requestURI, ex.javaClass.simpleName)
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
			problem(
				HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "Serviço indisponível",
				"O serviço de contas está temporariamente indisponível. Tenta novamente mais tarde.", request,
			),
		)
	}

	@ExceptionHandler(Exception::class)
	fun unexpected(ex: Exception, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
		// Só o tipo e o caminho: a mensagem de uma exceção pode conter dados do pedido.
		log.error("Erro inesperado ao processar {}: {}", request.requestURI, ex.javaClass.name)
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
			problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Erro interno", "Ocorreu um erro inesperado.", request),
		)
	}

	companion object {
		fun problem(
			status: HttpStatus,
			code: String,
			title: String,
			detail: String,
			request: HttpServletRequest,
			errors: List<FieldError> = emptyList(),
		): ProblemDetail = ProblemDetail.forStatusAndDetail(status, detail).apply {
			this.title = title
			this.instance = URI.create(request.requestURI)
			setProperty("code", code)
			if (errors.isNotEmpty()) setProperty("errors", errors)
		}
	}
}
