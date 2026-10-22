package com.example.portofit.messaging.conversation

import com.example.portofit.messaging.api.ApiErrors
import com.example.portofit.messaging.api.ApiException
import com.example.portofit.messaging.api.FieldError
import com.example.portofit.messaging.config.MessagingProperties
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.util.UUID

@RestController
@RequestMapping("/api/v1/conversations")
class ConversationController(
	private val service: ConversationService,
	private val properties: MessagingProperties,
	private val mapper: JsonMapper,
) {

	@PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
	fun open(@AuthenticationPrincipal jwt: Jwt, @RequestBody(required = false) body: String?): ResponseEntity<ConversationView> {
		val me = me(jwt)
		val other = MessagingRules.parseParticipant(body, mapper)
		val result = service.open(me, username(jwt), other)
		val location = URI.create("/api/v1/conversations/${result.conversation.id}")
		val status = if (result.created) HttpStatus.CREATED else HttpStatus.OK
		return ResponseEntity.status(status).location(location).body(result.conversation)
	}

	@GetMapping
	fun inbox(
		@AuthenticationPrincipal jwt: Jwt,
		@RequestParam("cursor", required = false) cursor: String?,
		@RequestParam("limit", required = false) limit: String?,
	): InboxPage {
		val me = me(jwt)
		val (parsed, limitValue) = MessagingRules.parseInboxQuery(cursor, limit)
		return service.inbox(me, parsed, limitValue)
	}

	@GetMapping("/{conversationId}")
	fun get(@AuthenticationPrincipal jwt: Jwt, @PathVariable("conversationId") conversationId: String): ConversationView {
		val me = me(jwt)
		return service.get(conversationId(conversationId), me)
	}

	@GetMapping("/{conversationId}/messages")
	fun messages(
		@AuthenticationPrincipal jwt: Jwt,
		@PathVariable("conversationId") conversationId: String,
		@RequestParam("before", required = false) before: String?,
		@RequestParam("after", required = false) after: String?,
		@RequestParam("limit", required = false) limit: String?,
	): MessagesPage {
		val me = me(jwt)
		val id = conversationId(conversationId)
		return service.messages(id, me, MessagingRules.parseMessagesQuery(before, after, limit))
	}

	@PostMapping("/{conversationId}/messages", consumes = [MediaType.APPLICATION_JSON_VALUE])
	fun send(
		@AuthenticationPrincipal jwt: Jwt,
		@PathVariable("conversationId") conversationId: String,
		@RequestHeader(IDEMPOTENCY_KEY, required = false) idempotencyKey: String?,
		@RequestBody(required = false) body: String?,
	): ResponseEntity<MessageView> {
		val me = me(jwt)
		val id = conversationId(conversationId)
		val key = MessagingRules.parseUuid(idempotencyKey)
		val keyError = FieldError(IDEMPOTENCY_KEY, "Falta a chave de idempotência do pedido.")
		val content = try {
			MessagingRules.parseContent(body, mapper, properties.maxTextLength)
		} catch (e: ApiException) {
			if (key == null) throw ApiErrors.validation(e.errors + keyError) else throw e
		}
		if (key == null) throw ApiErrors.validation(listOf(keyError))
		val result = service.send(id, me, username(jwt), key, content)
		val location = URI.create("/api/v1/conversations/${result.conversationId}/messages/${result.message.id}")
		return if (result.replayed) {
			ResponseEntity.ok().location(location).header("Idempotent-Replayed", "true").body(result.message)
		} else {
			ResponseEntity.status(HttpStatus.CREATED).location(location).body(result.message)
		}
	}

	@GetMapping("/{conversationId}/shares")
	fun shares(
		@AuthenticationPrincipal jwt: Jwt,
		@PathVariable("conversationId") conversationId: String,
		@RequestParam("seqs", required = false) seqs: String?,
	): SharesPage {
		val me = me(jwt)
		val id = conversationId(conversationId)
		return service.shares(id, me, MessagingRules.parseSeqs(seqs))
	}

	@PutMapping("/{conversationId}/read", consumes = [MediaType.APPLICATION_JSON_VALUE])
	fun markRead(
		@AuthenticationPrincipal jwt: Jwt,
		@PathVariable("conversationId") conversationId: String,
		@RequestBody(required = false) body: String?,
	): ReadState {
		val me = me(jwt)
		val id = conversationId(conversationId)
		return service.markRead(id, me, MessagingRules.parseReadSeq(body, mapper))
	}

	/** Quem pede é o `sub` do token; um `sub` que não seja UUID é um token inválido. */
	private fun me(jwt: Jwt): UUID = MessagingRules.parseUuid(jwt.subject) ?: throw ApiErrors.invalidToken()

	/** O username do token (claim emitida pelo users-service), só para a cópia de apresentação. */
	private fun username(jwt: Jwt): String = jwt.getClaimAsString("username")?.takeIf { it.isNotBlank() && it.length <= 30 }
		?: throw ApiErrors.invalidToken()

	private fun conversationId(value: String): UUID =
		MessagingRules.parseUuid(value) ?: throw ApiErrors.validation("conversationId", "Identificador de conversa inválido.")

	private companion object {
		const val IDEMPOTENCY_KEY = "Idempotency-Key"
	}
}
