package com.example.portofit.users.catalog

import com.example.portofit.users.api.ApiErrors
import com.example.portofit.users.config.CatalogProperties
import org.slf4j.LoggerFactory
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.net.http.HttpClient
import java.time.Clock
import java.time.Instant

/**
 * Lista de ids de modalidade do catalog-service (GET /api/v1/sports), usada só para validar interesses
 * novos. Resultado guardado em memória durante `cacheTtl`; sem resposta e sem lista recente, falha com
 * CATALOG_UNAVAILABLE (docs/users-api.md §7).
 */
@Component
class CatalogClient(private val properties: CatalogProperties, private val mapper: JsonMapper) {

	private val log = LoggerFactory.getLogger(javaClass)
	private val clock = Clock.systemUTC()

	private val restClient = RestClient.builder()
		.baseUrl(properties.baseUrl)
		.requestFactory(
			JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(properties.timeout).build())
				.apply { setReadTimeout(properties.timeout) },
		)
		.build()

	@Volatile
	private var cached: Pair<Instant, Set<String>>? = null

	fun sportIds(): Set<String> {
		cached?.let { (fetchedAt, ids) ->
			if (clock.instant().isBefore(fetchedAt.plus(properties.cacheTtl))) return ids
		}
		val ids = try {
			val body = restClient.get().uri("/api/v1/sports").retrieve().body(String::class.java) ?: error("resposta vazia")
			val items = mapper.readTree(body).get("items")
			(0 until items.size()).map { items.get(it).get("id").asString() }.toSet()
		} catch (e: Exception) {
			log.warn("catalog-service indisponível ao validar interesses: {}", e.javaClass.simpleName)
			throw ApiErrors.catalogUnavailable()
		}
		cached = clock.instant() to ids
		return ids
	}
}
