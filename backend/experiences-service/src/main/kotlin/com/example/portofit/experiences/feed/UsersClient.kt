package com.example.portofit.experiences.feed

import com.example.portofit.experiences.config.UsersProperties
import com.example.portofit.experiences.experience.ExperienceRules
import org.slf4j.LoggerFactory
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.net.http.HttpClient
import java.util.UUID

/** Resultado do pedido do contexto do feed (docs/experiences-api.md §14.3). */
sealed interface FeedContextResult {
	data class Found(val following: List<UUID>, val interests: List<String>) : FeedContextResult {
		val empty: Boolean get() = following.isEmpty() && interests.isEmpty()
	}

	/** O users-service recusou o token (401): chega à app como 401, não é degradação. */
	data object TokenRejected : FeedContextResult

	/** Indisponível ou chave interna recusada: o feed degrada para o popular, nunca como "sem preferências". */
	data object Unavailable : FeedContextResult
}

/**
 * `GET /api/v1/internal/me/feed-context` no users-service, com o token do utilizador reencaminhado e `X-Internal-Key`.
 * Prazos curtos (0,5 s de ligação, 1 s de resposta), sem novas tentativas e sem cache. Os registos nunca têm o token
 * nem a chave. Nenhum acesso à base de dados do users-service.
 */
@Component
class UsersClient(private val properties: UsersProperties, private val mapper: JsonMapper) {

	private val log = LoggerFactory.getLogger(javaClass)

	private val restClient = RestClient.builder()
		.baseUrl(properties.baseUrl)
		.requestFactory(
			JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(properties.connectTimeout).build())
				.apply { setReadTimeout(properties.timeout) },
		)
		.build()

	fun feedContext(accessToken: String): FeedContextResult {
		if (properties.internalKey.isBlank()) {
			log.error("Chave interna do users-service não configurada (USERS_INTERNAL_KEY): o feed pessoal degrada para o popular")
			return FeedContextResult.Unavailable
		}
		return try {
			restClient.get()
				.uri("/api/v1/internal/me/feed-context")
				.header("Authorization", "Bearer $accessToken")
				.header("X-Internal-Key", properties.internalKey)
				.exchange { _, response ->
					when (val status = response.statusCode.value()) {
						200 -> parse(response.body.readAllBytes())
						401 -> {
							log.info("users-service recusou o token ao pedir o contexto do feed")
							FeedContextResult.TokenRejected
						}
						403 -> {
							log.error("Chave interna recusada pelo users-service (configuração): o feed pessoal degrada para o popular")
							FeedContextResult.Unavailable
						}
						else -> {
							log.warn("users-service respondeu {} ao pedir o contexto do feed", status)
							FeedContextResult.Unavailable
						}
					}
				}
		} catch (e: Exception) {
			// Só os tipos (ex.: HttpTimeoutException vs ConnectException).
			log.warn("users-service indisponível ao pedir o contexto do feed: {} ({})", e.javaClass.simpleName, e.cause?.javaClass?.simpleName)
			FeedContextResult.Unavailable
		}
	}

	/** Só ids válidos; um corpo inesperado é tratado como indisponibilidade, nunca como "sem preferências". */
	private fun parse(body: ByteArray): FeedContextResult {
		val node = runCatching { mapper.readTree(body) }.getOrNull()
		val following = node?.get("following")
		val interests = node?.get("interests")
		if (following == null || !following.isArray || interests == null || !interests.isArray) return invalid()
		val ids = (0 until following.size()).map { i ->
			following[i].takeIf { it.isString }?.let { ExperienceRules.parseUuid(it.stringValue()) } ?: return invalid()
		}
		val sports = (0 until interests.size()).map { i ->
			interests[i].takeIf { it.isString && ExperienceRules.isSportId(it.stringValue()) }?.stringValue() ?: return invalid()
		}
		return FeedContextResult.Found(ids, sports)
	}

	private fun invalid(): FeedContextResult {
		log.warn("users-service devolveu um contexto do feed inválido")
		return FeedContextResult.Unavailable
	}
}
