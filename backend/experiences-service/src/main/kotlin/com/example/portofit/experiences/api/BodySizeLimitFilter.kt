package com.example.portofit.experiences.api

import com.example.portofit.experiences.config.PublishingProperties
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.json.JsonMapper
import java.io.IOException

/** Corpo acima do limite: lançado ao ler o pedido, convertido em 413 PAYLOAD_TOO_LARGE. */
class PayloadTooLargeException : IOException("Pedido demasiado grande")

/**
 * Limita o corpo dos pedidos JSON (16 KiB por omissão; docs/experiences-api.md §4.1). Recusa logo pelo
 * Content-Length e, sem ele (envio por partes), interrompe a leitura quando o limite é ultrapassado.
 */
@Component
class BodySizeLimitFilter(private val properties: PublishingProperties, private val mapper: JsonMapper) : OncePerRequestFilter() {

	/** Os uploads de fotografias têm limites próprios (multipart, docs/experiences-api.md §12.2). */
	override fun shouldNotFilter(request: HttpServletRequest): Boolean = request.requestURI.startsWith("/api/v1/media")

	override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
		if (request.contentLengthLong > properties.maxBodyBytes) {
			response.status = HttpStatus.PAYLOAD_TOO_LARGE.value()
			response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
			response.characterEncoding = "UTF-8"
			val problem = ApiExceptionHandler.tooLargeProblem(request)
			val body = linkedMapOf<String, Any?>(
				"title" to problem.title, "status" to problem.status, "detail" to problem.detail, "instance" to problem.instance?.toString(),
			)
			problem.properties?.let { body.putAll(it) }
			mapper.writeValue(response.outputStream, body)
			return
		}
		chain.doFilter(LimitedRequest(request, properties.maxBodyBytes), response)
	}

	private class LimitedRequest(request: HttpServletRequest, private val limit: Long) : HttpServletRequestWrapper(request) {
		private val stream by lazy { LimitedInputStream(request.inputStream, limit) }

		override fun getInputStream(): ServletInputStream = stream
	}

	private class LimitedInputStream(private val delegate: ServletInputStream, private val limit: Long) : ServletInputStream() {
		private var count = 0L

		override fun read(): Int {
			val value = delegate.read()
			if (value >= 0) count(1)
			return value
		}

		override fun read(b: ByteArray, off: Int, len: Int): Int {
			val n = delegate.read(b, off, len)
			if (n > 0) count(n.toLong())
			return n
		}

		private fun count(n: Long) {
			count += n
			if (count > limit) throw PayloadTooLargeException()
		}

		override fun isFinished() = delegate.isFinished

		override fun isReady() = delegate.isReady

		override fun setReadListener(listener: ReadListener) = delegate.setReadListener(listener)
	}
}
