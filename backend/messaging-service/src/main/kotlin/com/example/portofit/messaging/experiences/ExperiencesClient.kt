package com.example.portofit.messaging.experiences

import com.example.portofit.messaging.config.ExperiencesProperties
import org.slf4j.LoggerFactory
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.http.HttpClient
import java.util.UUID

enum class ExperienceStatus { AVAILABLE, DELETED, NOT_FOUND }

/** Uma experiência consultada: conteúdo (formato público do experiences-service) só quando AVAILABLE. */
data class ExperienceEntry(val status: ExperienceStatus, val experience: JsonNode?)

sealed interface LookupResult {
	data class Found(val entries: Map<UUID, ExperienceEntry>) : LookupResult

	/** Sem resposta válida: o estado das experiências é desconhecido (nunca "eliminada"). */
	data object Unavailable : LookupResult
}

/**
 * `GET /api/v1/internal/experiences?ids=…` no experiences-service (docs/messaging-api.md §7.4), com `X-Internal-Key`.
 * Um pedido por lote (até 50 ids), ligação 0,5 s e resposta 2 s, sem novas tentativas nem cache. Os registos nunca
 * têm a chave. Nenhum acesso à base de dados do experiences-service.
 */
@Component
class ExperiencesClient(private val properties: ExperiencesProperties, private val mapper: JsonMapper) {

	private val log = LoggerFactory.getLogger(javaClass)

	private val restClient = RestClient.builder()
		.baseUrl(properties.baseUrl)
		.requestFactory(
			JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(properties.connectTimeout).build())
				.apply { setReadTimeout(properties.timeout) },
		)
		.build()

	fun lookup(ids: Collection<UUID>): LookupResult {
		val distinct = ids.distinct()
		if (distinct.isEmpty()) return LookupResult.Found(emptyMap())
		require(distinct.size <= MAX_IDS) { "no máximo $MAX_IDS ids por consulta" }
		if (properties.internalKey.isBlank()) {
			log.error("Chave interna do experiences-service não configurada (MESSAGING_INTERNAL_KEY)")
			return LookupResult.Unavailable
		}
		return try {
			restClient.get()
				.uri { it.path("/api/v1/internal/experiences").queryParam("ids", distinct.joinToString(",")).build() }
				.header("X-Internal-Key", properties.internalKey)
				.exchange { _, response ->
					when (val status = response.statusCode.value()) {
						200 -> parse(response.body.readAllBytes(), distinct)
						403 -> {
							log.error("Chave interna recusada pelo experiences-service (configuração)")
							LookupResult.Unavailable
						}
						else -> {
							log.warn("experiences-service respondeu {} à consulta em lote", status)
							LookupResult.Unavailable
						}
					}
				}
		} catch (e: Exception) {
			// Só os tipos (ex.: HttpTimeoutException vs ConnectException).
			log.warn("experiences-service indisponível na consulta em lote: {} ({})", e.javaClass.simpleName, e.cause?.javaClass?.simpleName)
			LookupResult.Unavailable
		}
	}

	/** Uma resposta incompleta ou com estados desconhecidos é tratada como indisponibilidade, nunca como eliminação. */
	private fun parse(body: ByteArray, requested: List<UUID>): LookupResult {
		val items = runCatching { mapper.readTree(body) }.getOrNull()?.get("items")
		if (items == null || !items.isArray) return invalid()
		val entries = mutableMapOf<UUID, ExperienceEntry>()
		for (i in 0 until items.size()) {
			val node = items[i]
			val id = node.get("id")?.takeIf { it.isString }?.let { runCatching { UUID.fromString(it.stringValue()) }.getOrNull() } ?: return invalid()
			val status = ExperienceStatus.entries.firstOrNull { it.name == node.get("status")?.stringValue() } ?: return invalid()
			val experience = node.get("experience")?.takeIf { it.isObject }
			if ((status == ExperienceStatus.AVAILABLE) != (experience != null)) return invalid()
			entries[id] = ExperienceEntry(status, experience)
		}
		if (!entries.keys.containsAll(requested)) return invalid()
		return LookupResult.Found(entries)
	}

	private fun invalid(): LookupResult {
		log.warn("experiences-service devolveu uma consulta em lote inválida")
		return LookupResult.Unavailable
	}

	companion object {
		const val MAX_IDS = 50
	}
}
