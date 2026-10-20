package com.example.portofit.experiences.experience

import com.example.portofit.experiences.api.ApiErrors
import com.example.portofit.experiences.api.ApiException
import com.example.portofit.experiences.config.InternalProperties
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
import java.util.UUID

enum class LookupStatus { AVAILABLE, DELETED, NOT_FOUND }

/** Uma entrada da consulta em lote: conteúdo só quando AVAILABLE (nunca de uma eliminada). */
data class ExperienceLookup(val id: UUID, val status: LookupStatus, val experience: ExperienceView?)

data class ExperienceLookupResponse(val items: List<ExperienceLookup>)

/**
 * `GET /api/v1/internal/experiences?ids=…` (docs/experiences-api.md §4.7): só para o collections-service e o
 * messaging-service, cada um com a sua `X-Internal-Key`. Até 50 ids por pedido, uma consulta à base de dados.
 */
@RestController
class InternalExperienceController(
	private val experiences: ExperienceRepository,
	private val properties: InternalProperties,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	@GetMapping("/api/v1/internal/experiences")
	fun lookup(
		@RequestHeader("X-Internal-Key", required = false) key: String?,
		@RequestParam("ids", required = false) ids: String?,
	): ExperienceLookupResponse {
		val client = client(key)
		if (client == null) {
			// Nunca regista a chave recebida.
			log.warn("Consulta interna em lote recusada: chave interna em falta ou errada")
			throw ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Acesso negado", "Não tens permissão para este pedido.")
		}
		val requested = parseIds(ids)
		log.debug("Consulta interna em lote de {} ({} ids)", client, requested.size)
		val rows = experiences.findAnyByIds(requested).associateBy { it.id }
		return ExperienceLookupResponse(
			requested.map { id ->
				val row = rows[id]
				when {
					row == null -> ExperienceLookup(id, LookupStatus.NOT_FOUND, null)
					row.deleted -> ExperienceLookup(id, LookupStatus.DELETED, null)
					else -> ExperienceLookup(id, LookupStatus.AVAILABLE, row.toView())
				}
			},
		)
	}

	private fun parseIds(value: String?): List<UUID> {
		val parts = value?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
		val ids = parts.map { ExperienceRules.parseUuid(it) ?: throw ApiErrors.validation("ids", MESSAGE) }.distinct()
		if (ids.isEmpty() || ids.size > MAX_IDS) throw ApiErrors.validation("ids", MESSAGE)
		return ids
	}

	/** O serviço dono da chave recebida, ou null. Compara com todas as chaves, em tempo constante. */
	private fun client(received: String?): String? {
		if (received.isNullOrEmpty()) return null
		val bytes = received.toByteArray(Charsets.UTF_8)
		val matches = listOf("collections" to properties.collectionsKey, "messaging" to properties.messagingKey)
			.filter { (_, expected) -> expected.isNotBlank() && MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), bytes) }
		// Duas chaves iguais seriam um erro de configuração: não se sabe qual dos serviços está a pedir.
		return matches.singleOrNull()?.first
	}

	private companion object {
		const val MAX_IDS = 50
		const val MESSAGE = "Indica de 1 a 50 identificadores de experiência válidos, separados por vírgulas."
	}
}
