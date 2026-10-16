package com.example.portofit.catalog.api

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.TransientDataAccessException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.resource.NoResourceFoundException
import java.net.URI

/**
 * Converte erros em respostas Problem Details (application/problem+json) com textos em português.
 * Nunca inclui mensagens internas, nomes de tabelas ou stack traces.
 */
@RestControllerAdvice
class ApiExceptionHandler {

	private val log = LoggerFactory.getLogger(javaClass)

	@ExceptionHandler(InvalidParametersException::class)
	fun invalidParameters(ex: InvalidParametersException, request: HttpServletRequest): ResponseEntity<ProblemDetail> =
		problem(HttpStatus.BAD_REQUEST, "Pedido inválido", "Um ou mais parâmetros são inválidos.", request)
			.also { it.body!!.setProperty("errors", ex.errors) }

	@ExceptionHandler(NotFoundException::class)
	fun notFound(ex: NotFoundException, request: HttpServletRequest): ResponseEntity<ProblemDetail> =
		problem(HttpStatus.NOT_FOUND, "Não encontrado", ex.message ?: "Recurso inexistente.", request)

	@ExceptionHandler(NoResourceFoundException::class)
	fun noResource(request: HttpServletRequest): ResponseEntity<ProblemDetail> =
		problem(HttpStatus.NOT_FOUND, "Não encontrado", "O recurso pedido não existe.", request)

	@ExceptionHandler(HttpRequestMethodNotSupportedException::class)
	fun methodNotAllowed(request: HttpServletRequest): ResponseEntity<ProblemDetail> =
		problem(HttpStatus.METHOD_NOT_ALLOWED, "Método não permitido", "Este recurso só aceita pedidos GET.", request)

	@ExceptionHandler(DataAccessResourceFailureException::class, TransientDataAccessException::class)
	fun databaseUnavailable(ex: Exception, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
		log.warn("Base de dados indisponível ao processar {}: {}", request.requestURI, ex.javaClass.simpleName)
		return problem(
			HttpStatus.SERVICE_UNAVAILABLE,
			"Serviço indisponível",
			"O catálogo está temporariamente indisponível. Tenta novamente mais tarde.",
			request,
		)
	}

	@ExceptionHandler(Exception::class)
	fun unexpected(ex: Exception, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
		log.error("Erro inesperado ao processar {}", request.requestURI, ex)
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno", "Ocorreu um erro inesperado.", request)
	}

	private fun problem(
		status: HttpStatus,
		title: String,
		detail: String,
		request: HttpServletRequest,
	): ResponseEntity<ProblemDetail> {
		val body = ProblemDetail.forStatusAndDetail(status, detail).apply {
			this.title = title
			this.instance = URI.create(request.requestURI)
		}
		return ResponseEntity.status(status).body(body)
	}
}
