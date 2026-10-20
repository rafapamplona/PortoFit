package com.example.portofit.messaging.users

import com.example.portofit.messaging.config.UsersProperties
import org.slf4j.LoggerFactory
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.net.http.HttpClient
import java.util.UUID

sealed interface UserLookup {
	/** O destinatário existe; [username] é só para apresentação (cópia, docs/messaging-api.md §8.2). */
	data class Found(val username: String) : UserLookup

	data object NotFound : UserLookup

	/** Sem resposta válida: nada se conclui sobre a pessoa (nunca "inexistente"). */
	data object Unavailable : UserLookup
}

/**
 * `GET /api/v1/users/{userId}` (perfil público) no users-service, sem token (docs/messaging-api.md §8.1). Só é usado ao
 * criar uma conversa nova. Ligação 0,5 s e resposta 1 s, sem novas tentativas. Nenhum acesso à base do users-service.
 */
@Component
class UsersClient(properties: UsersProperties, private val mapper: JsonMapper) {

	private val log = LoggerFactory.getLogger(javaClass)

	private val restClient = RestClient.builder()
		.baseUrl(properties.baseUrl)
		.requestFactory(
			JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(properties.connectTimeout).build())
				.apply { setReadTimeout(properties.timeout) },
		)
		.build()

	fun lookup(userId: UUID): UserLookup = try {
		restClient.get()
			.uri("/api/v1/users/{id}", userId)
			.exchange { _, response ->
				when (val status = response.statusCode.value()) {
					200 -> parse(response.body.readAllBytes(), userId)
					404 -> UserLookup.NotFound
					else -> {
						log.warn("users-service respondeu {} ao validar o destinatário", status)
						UserLookup.Unavailable
					}
				}
			}
	} catch (e: Exception) {
		log.warn("users-service indisponível ao validar o destinatário: {} ({})", e.javaClass.simpleName, e.cause?.javaClass?.simpleName)
		UserLookup.Unavailable
	}

	/** O perfil tem de ser o pedido e ter um username válido; outra coisa é tratada como indisponibilidade. */
	private fun parse(body: ByteArray, requested: UUID): UserLookup {
		val node = runCatching { mapper.readTree(body) }.getOrNull()
		val id = node?.get("userId")?.takeIf { it.isString }?.stringValue()
		val username = node?.get("username")?.takeIf { it.isString }?.stringValue()
		if (id == null || !id.equals(requested.toString(), ignoreCase = true) || username == null || !USERNAME.matches(username)) {
			log.warn("users-service devolveu um perfil inválido ao validar o destinatário")
			return UserLookup.Unavailable
		}
		return UserLookup.Found(username)
	}

	private companion object {
		/** Regras do username no users-service (docs/users-api.md §2.1). */
		val USERNAME = Regex("^[a-z0-9](?:[a-z0-9._]{1,28})[a-z0-9]$")
	}
}
