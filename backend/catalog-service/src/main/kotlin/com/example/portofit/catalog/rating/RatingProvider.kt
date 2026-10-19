package com.example.portofit.catalog.rating

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.net.http.HttpClient
import java.time.Duration
import java.util.UUID

enum class RatingStatus { AVAILABLE, NO_RATINGS, UNAVAILABLE }

/**
 * Avaliação agregada de um local ou atividade. Pertence ao experiences-service;
 * o catálogo só a mostra como complemento (ver docs/catalog-api.md §2.4).
 */
data class Rating(
	val status: RatingStatus,
	val average: BigDecimal?,
	val ratingsCount: Int?,
) {
	companion object {
		/** Avaliação que não foi possível obter: nunca é apresentada como zero estrelas. */
		val UNAVAILABLE = Rating(RatingStatus.UNAVAILABLE, average = null, ratingsCount = null)

		val NO_RATINGS = Rating(RatingStatus.NO_RATINGS, average = null, ratingsCount = 0)
	}
}

/**
 * Avaliações obtidas num único pedido. Se o pedido falhou, tudo é UNAVAILABLE; se correu bem, um local
 * ou atividade ausente da resposta é NO_RATINGS (docs/catalog-api.md §5).
 */
class RatingSet(
	private val available: Boolean,
	private val places: Map<UUID, Rating>,
	private val offerings: Map<UUID, Rating>,
) {
	fun place(id: UUID): Rating = places[id] ?: fallback()

	fun offering(id: UUID): Rating = offerings[id] ?: fallback()

	private fun fallback() = if (available) Rating.NO_RATINGS else Rating.UNAVAILABLE

	companion object {
		val UNAVAILABLE = RatingSet(false, emptyMap(), emptyMap())
	}
}

/** Fonte das avaliações agregadas: um pedido por resposta do catálogo, nunca um por local. */
interface RatingProvider {
	fun ratings(placeIds: Collection<UUID>, includeOfferings: Boolean): RatingSet
}

/** Ligação ao experiences-service; sem `base-url`, as avaliações ficam UNAVAILABLE sem fazer pedidos. */
@ConfigurationProperties("portofit.experiences")
data class ExperiencesProperties(
	val baseUrl: String = "",
	val connectTimeout: Duration = Duration.ofMillis(500),
	val timeout: Duration = Duration.ofSeconds(1),
)

/**
 * Pede `GET /api/v1/ratings/places` (docs/experiences-api.md §4.6) com timeout curto e sem novas tentativas.
 * Qualquer falha (sem resposta no prazo, erro de ligação, 4xx/5xx ou resposta inesperada) dá UNAVAILABLE:
 * a pesquisa e o detalhe do catálogo nunca falham por causa das avaliações.
 */
@Component
class ExperiencesRatingProvider(private val properties: ExperiencesProperties, private val mapper: JsonMapper) : RatingProvider {

	private val log = LoggerFactory.getLogger(javaClass)

	private val restClient = properties.baseUrl.takeIf { it.isNotBlank() }?.let { baseUrl ->
		RestClient.builder()
			.baseUrl(baseUrl)
			.requestFactory(
				JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(properties.connectTimeout).build())
					.apply { setReadTimeout(properties.timeout) },
			)
			.build()
	}

	override fun ratings(placeIds: Collection<UUID>, includeOfferings: Boolean): RatingSet {
		val client = restClient ?: return RatingSet.UNAVAILABLE
		val ids = placeIds.distinct()
		if (ids.isEmpty()) return RatingSet(true, emptyMap(), emptyMap())
		return try {
			val body = client.get()
				.uri { it.path("/api/v1/ratings/places").queryParam("ids", ids.joinToString(",")).queryParam("includeOfferings", includeOfferings).build() }
				.retrieve()
				.body(String::class.java) ?: error("resposta vazia")
			parse(mapper.readTree(body))
		} catch (e: Exception) {
			log.warn("Avaliações indisponíveis (experiences-service): {} ({})", e.javaClass.simpleName, e.cause?.javaClass?.simpleName)
			RatingSet.UNAVAILABLE
		}
	}

	private fun parse(root: JsonNode): RatingSet {
		val places = mutableMapOf<UUID, Rating>()
		val offerings = mutableMapOf<UUID, Rating>()
		val items = root.get("items") ?: error("sem items")
		for (i in 0 until items.size()) {
			val item = items.get(i)
			places[UUID.fromString(item.get("placeId").asString())] = rating(item.get("rating"))
			item.get("offerings")?.let { list ->
				for (j in 0 until list.size()) {
					val offering = list.get(j)
					offerings[UUID.fromString(offering.get("offeringId").asString())] = rating(offering.get("rating"))
				}
			}
		}
		return RatingSet(true, places, offerings)
	}

	/** Só AVAILABLE (com média 1–5 e pessoas ≥ 1) e NO_RATINGS são válidos vindos do experiences-service. */
	private fun rating(node: JsonNode): Rating = when (node.get("status").asString()) {
		"AVAILABLE" -> {
			val average = node.get("average").decimalValue()
			val count = node.get("ratingsCount").intValue()
			check(average >= BigDecimal.ONE && average <= BigDecimal(5) && count >= 1) { "avaliação inválida" }
			Rating(RatingStatus.AVAILABLE, average, count)
		}
		"NO_RATINGS" -> Rating.NO_RATINGS
		else -> error("estado inválido")
	}
}
