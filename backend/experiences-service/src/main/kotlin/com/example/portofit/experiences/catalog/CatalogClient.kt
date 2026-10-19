package com.example.portofit.experiences.catalog

import com.example.portofit.experiences.api.ApiErrors
import com.example.portofit.experiences.config.CatalogProperties
import org.slf4j.LoggerFactory
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.net.http.HttpClient
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Dados de uma atividade usados para validar e copiar ao publicar (docs/experiences-api.md §6.1). */
data class OfferingInfo(
	val id: UUID,
	val name: String,
	/** ACTIVE ou RETIRED (estado efetivo: atividade ou local retirados). */
	val status: String,
	val sportId: String,
	val sportName: String,
	val placeId: UUID,
	val placeName: String,
	val placeDemo: Boolean,
) {
	val retired: Boolean get() = status == "RETIRED"
}

/**
 * `GET /api/v1/offerings/{id}?includeRating=false` no catalog-service: sem avaliações, para que o catálogo
 * não chame de volta este serviço. Prazos curtos, sem novas tentativas.
 *
 * As respostas 200 ficam em memória durante `cacheTtl` (10 min): aceita-se usar dados com até esse atraso
 * (docs/experiences-api.md §6.2). Os 404 e as falhas nunca ficam em memória.
 */
@Component
class CatalogClient(
	private val properties: CatalogProperties,
	private val mapper: JsonMapper,
	private val clock: Clock,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	private val restClient = RestClient.builder()
		.baseUrl(properties.baseUrl)
		.requestFactory(
			JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(properties.connectTimeout).build())
				.apply { setReadTimeout(properties.timeout) },
		)
		.build()

	private val cache = ConcurrentHashMap<UUID, Pair<Instant, OfferingInfo>>()

	/** A atividade, ou null se o catálogo responder 404. Sem resposta válida → CATALOG_UNAVAILABLE. */
	fun offering(id: UUID): OfferingInfo? {
		cache[id]?.let { (fetchedAt, info) ->
			if (clock.instant().isBefore(fetchedAt.plus(properties.cacheTtl))) return info
			cache.remove(id)
		}
		val info = try {
			fetch(id)
		} catch (e: Exception) {
			// Só os tipos (ex.: HttpTimeoutException vs ConnectException), para distinguir prazo esgotado de ligação recusada.
			log.warn("catalog-service indisponível ao validar uma atividade: {} ({})", e.javaClass.simpleName, e.cause?.javaClass?.simpleName)
			throw ApiErrors.catalogUnavailable()
		} ?: return null
		cache[id] = clock.instant() to info
		return info
	}

	private fun fetch(id: UUID): OfferingInfo? = restClient.get()
		.uri("/api/v1/offerings/{id}?includeRating=false", id)
		.exchange { _, response ->
			when {
				response.statusCode.value() == 404 -> null
				!response.statusCode.is2xxSuccessful -> error("resposta ${response.statusCode.value()}")
				else -> {
					val node = mapper.readTree(response.body)
					val status = node.get("status").asString()
					check(status == "ACTIVE" || status == "RETIRED") { "estado desconhecido" }
					val info = OfferingInfo(
						id = UUID.fromString(node.get("id").asString()),
						name = node.get("name").asString(),
						status = status,
						sportId = node.get("sport").get("id").asString(),
						sportName = node.get("sport").get("name").asString(),
						placeId = UUID.fromString(node.get("place").get("id").asString()),
						placeName = node.get("place").get("name").asString(),
						placeDemo = node.get("place").get("demo").booleanValue(),
					)
					check(info.id == id) { "atividade diferente da pedida" }
					info
				}
			}
		}
}
