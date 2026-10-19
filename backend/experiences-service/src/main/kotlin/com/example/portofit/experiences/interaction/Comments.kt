package com.example.portofit.experiences.interaction

import com.example.portofit.experiences.api.ApiErrors
import com.example.portofit.experiences.api.ApiException
import com.example.portofit.experiences.api.FieldError
import com.example.portofit.experiences.api.PageResponse
import com.example.portofit.experiences.config.PublishingProperties
import com.example.portofit.experiences.experience.AuthorRef
import com.example.portofit.experiences.experience.ExperienceController
import com.example.portofit.experiences.experience.ExperienceRepository
import com.example.portofit.experiences.experience.ExperienceRules
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/** Linha da tabela comment (inclui os eliminados, que só têm ids e datas). */
data class CommentRow(
	val id: UUID,
	val experienceId: UUID,
	val authorId: UUID,
	val authorUsername: String,
	val text: String?,
	val createdAt: Instant,
	val editedAt: Instant?,
	val deletedAt: Instant?,
) {
	val deleted: Boolean get() = deletedAt != null
}

/** Formato `Comment` das respostas (docs/experiences-api.md §13.1). */
data class CommentView(
	val id: UUID,
	val experienceId: UUID,
	val author: AuthorRef,
	val text: String,
	val createdAt: Instant,
	val editedAt: Instant?,
)

fun CommentRow.toView() = CommentView(
	id, experienceId, AuthorRef(authorId, authorUsername), checkNotNull(text) { "comentário eliminado" }, createdAt, editedAt,
)

data class CommentRequestRow(val requestHash: String, val commentId: UUID)

data class CommentResult(val comment: CommentView, val replayed: Boolean)

@Repository
class CommentRepository(private val jdbc: JdbcClient) {

	fun findRequest(authorId: UUID, key: UUID): CommentRequestRow? =
		jdbc.sql("SELECT request_hash, comment_id FROM comment_request WHERE author_id = :author AND idempotency_key = :key")
			.param("author", authorId).param("key", key)
			.query { rs, _ -> CommentRequestRow(rs.getString("request_hash"), rs.getObject("comment_id", UUID::class.java)) }
			.optional().orElse(null)

	/**
	 * Regista a tentativa e cria o comentário, na transação em curso; devolve o id, ou null se a chave já existir
	 * (como ExperienceRepository.insertWithKey: um pedido simultâneo com a mesma chave espera e não insere nada).
	 */
	fun insertWithKey(authorId: UUID, authorUsername: String, key: UUID, hash: String, experienceId: UUID, text: String): UUID? {
		val id = jdbc.sql("SELECT uuidv7()").query(UUID::class.java).single()
		val registered = jdbc.sql(
			"""
			INSERT INTO comment_request (author_id, idempotency_key, request_hash, comment_id)
			VALUES (:author, :key, :hash, :id)
			ON CONFLICT (author_id, idempotency_key) DO NOTHING
			""",
		).param("author", authorId).param("key", key).param("hash", hash).param("id", id).update()
		if (registered == 0) return null
		jdbc.sql("INSERT INTO comment (id, experience_id, author_id, author_username, text) VALUES (:id, :experience, :author, :username, :text)")
			.param("id", id).param("experience", experienceId).param("author", authorId).param("username", authorUsername).param("text", text)
			.update()
		return id
	}

	fun find(id: UUID, lock: Boolean = false): CommentRow? =
		jdbc.sql("SELECT * FROM comment WHERE id = :id" + if (lock) " FOR UPDATE" else "")
			.param("id", id)
			.query { rs, _ -> rs.toRow() }
			.optional().orElse(null)

	/** Só os ativos, mais antigos primeiro; o id (uuidv7) desempata, por isso a ordem é total. */
	fun list(experienceId: UUID, page: Int, size: Int): Pair<List<CommentRow>, Long> {
		val total = jdbc.sql("SELECT count(*) FROM comment WHERE experience_id = :experience AND deleted_at IS NULL")
			.param("experience", experienceId).query(Long::class.javaObjectType).single()
		val rows = jdbc.sql(
			"""
			SELECT * FROM comment WHERE experience_id = :experience AND deleted_at IS NULL
			ORDER BY created_at, id LIMIT :limit OFFSET :offset
			""",
		)
			.param("experience", experienceId).param("limit", size).param("offset", page.toLong() * size)
			.query { rs, _ -> rs.toRow() }
			.list()
		return rows to total
	}

	fun updateText(id: UUID, text: String) {
		jdbc.sql("UPDATE comment SET text = :text, edited_at = now() WHERE id = :id").param("id", id).param("text", text).update()
	}

	/** Fica só a marca (ids e datas), nunca exposta. */
	fun markDeleted(id: UUID) {
		jdbc.sql("UPDATE comment SET deleted_at = now(), text = NULL WHERE id = :id AND deleted_at IS NULL").param("id", id).update()
	}

	fun deleteRequestsOlderThan(retention: Duration): Int =
		jdbc.sql("DELETE FROM comment_request WHERE created_at < now() - CAST(:seconds || ' seconds' AS interval)")
			.param("seconds", retention.toSeconds().toString())
			.update()

	private fun ResultSet.toRow() = CommentRow(
		id = getObject("id", UUID::class.java),
		experienceId = getObject("experience_id", UUID::class.java),
		authorId = getObject("author_id", UUID::class.java),
		authorUsername = getString("author_username"),
		text = getString("text"),
		createdAt = getTimestamp("created_at").toInstant(),
		editedAt = getTimestamp("edited_at")?.toInstant(),
		deletedAt = getTimestamp("deleted_at")?.toInstant(),
	)
}

@Service
class CommentService(
	private val comments: CommentRepository,
	private val experiences: ExperienceRepository,
	private val limits: InteractionLimits,
	private val publishing: PublishingProperties,
	private val tx: TransactionTemplate,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	fun list(experienceId: UUID, page: Int, size: Int): PageResponse<CommentView> {
		experiences.activeAuthor(experienceId) ?: throw ApiErrors.notFound()
		val (rows, total) = comments.list(experienceId, page, size)
		return PageResponse.of(rows.map { it.toView() }, page, size, total)
	}

	/** Idempotente pela chave, como a publicação (docs/experiences-api.md §5, §13.1). */
	fun create(authorId: UUID, authorUsername: String, key: UUID, experienceId: UUID, text: String): CommentResult {
		val hash = requestHash(experienceId, text)
		comments.findRequest(authorId, key)?.let { return replay(it, hash) }

		limits.comments.check(authorId)
		val created = tx.execute {
			experiences.activeAuthor(experienceId, lock = true) ?: throw ApiErrors.notFound()
			val id = comments.insertWithKey(authorId, authorUsername, key, hash, experienceId, text) ?: return@execute null
			comments.find(id)
		}
		if (created == null) {
			// Outro pedido com a mesma chave confirmou primeiro: esta tentativa é uma repetição.
			val existing = comments.findRequest(authorId, key) ?: error("tentativa registada sem linha")
			return replay(existing, hash)
		}
		limits.comments.record(authorId)
		return CommentResult(created.toView(), replayed = false)
	}

	/** Só o autor; `editedAt` só muda se o texto mudar. */
	fun edit(id: UUID, userId: UUID, text: String): CommentView = tx.execute {
		val row = comments.find(id, lock = true)?.takeUnless { it.deleted } ?: throw ApiErrors.commentNotFound()
		if (row.authorId != userId) throw ApiErrors.commentForbidden()
		if (text == row.text) return@execute row.toView()
		comments.updateText(id, text)
		comments.find(id)!!.toView()
	}!!

	/** Só o autor; repetir é seguro (204). Para outra pessoa, um comentário eliminado não existe. */
	fun delete(id: UUID, userId: UUID) {
		tx.executeWithoutResult {
			val row = comments.find(id, lock = true) ?: throw ApiErrors.commentNotFound()
			when {
				row.deleted && row.authorId == userId -> Unit
				row.deleted -> throw ApiErrors.commentNotFound()
				row.authorId != userId -> throw ApiErrors.commentForbidden()
				else -> comments.markDeleted(id)
			}
		}
	}

	@Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT2M")
	fun removeOldRequests() {
		val removed = comments.deleteRequestsOlderThan(publishing.idempotencyRetention)
		if (removed > 0) log.info("Removidos {} registos de tentativas de comentário antigos", removed)
	}

	/** Nunca recria nem devolve o texto de um comentário eliminado. */
	private fun replay(request: CommentRequestRow, hash: String): CommentResult {
		val row = comments.find(request.commentId)
		if (row == null || row.deleted) throw ApiErrors.commentDeleted()
		if (request.requestHash != hash) throw ApiErrors.commentDifferentContent(row.id)
		return CommentResult(row.toView(), replayed = true)
	}

	private fun requestHash(experienceId: UUID, text: String): String {
		val canonical = experienceId.toString() + "\u0000" + text
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)))
	}
}

/** Comentários (docs/experiences-api.md §13.1). O autor vem sempre do token. */
@RestController
@RequestMapping("/api/v1")
class CommentController(private val service: CommentService, private val mapper: JsonMapper) {

	@GetMapping("/experiences/{experienceId}/comments")
	fun list(
		@PathVariable("experienceId") experienceId: String,
		@RequestParam("page", required = false) page: String?,
		@RequestParam("size", required = false) size: String?,
	): PageResponse<CommentView> {
		val id = ExperienceRules.experienceId(experienceId)
		val errors = mutableListOf<FieldError>()
		val (pageValue, sizeValue) = PageResponse.parse(page, size, errors)
		if (errors.isNotEmpty()) throw ApiErrors.validation(errors)
		return service.list(id, pageValue, sizeValue)
	}

	@PostMapping("/experiences/{experienceId}/comments", consumes = [MediaType.APPLICATION_JSON_VALUE])
	fun create(
		@AuthenticationPrincipal jwt: Jwt,
		@PathVariable("experienceId") experienceId: String,
		@RequestHeader(ExperienceController.IDEMPOTENCY_KEY, required = false) idempotencyKey: String?,
		@RequestBody(required = false) body: String?,
	): ResponseEntity<CommentView> {
		val (authorId, username) = ExperienceController.author(jwt)
		val id = ExperienceRules.experienceId(experienceId)
		val key = ExperienceRules.parseUuid(idempotencyKey)
		val keyError = FieldError(ExperienceController.IDEMPOTENCY_KEY, ExperienceController.KEY_MESSAGE)
		val text = try {
			parseText(body)
		} catch (e: ApiException) {
			if (key == null) throw ApiErrors.validation(e.errors + keyError) else throw e
		}
		if (key == null) throw ApiErrors.validation(listOf(keyError))

		val result = service.create(authorId, username, key, id, text)
		return if (result.replayed) {
			ResponseEntity.ok().header(ExperienceController.REPLAYED_HEADER, "true").body(result.comment)
		} else {
			ResponseEntity.status(HttpStatus.CREATED).body(result.comment)
		}
	}

	@PatchMapping("/comments/{commentId}", consumes = [MediaType.APPLICATION_JSON_VALUE])
	fun edit(
		@AuthenticationPrincipal jwt: Jwt,
		@PathVariable("commentId") commentId: String,
		@RequestBody(required = false) body: String?,
	): CommentView {
		val id = commentId(commentId)
		return service.edit(id, ExperienceController.author(jwt).first, parseText(body))
	}

	@DeleteMapping("/comments/{commentId}")
	fun delete(@AuthenticationPrincipal jwt: Jwt, @PathVariable("commentId") commentId: String): ResponseEntity<Void> {
		service.delete(commentId(commentId), ExperienceController.author(jwt).first)
		return ResponseEntity.noContent().build()
	}

	private fun commentId(value: String): UUID =
		ExperienceRules.parseUuid(value) ?: throw ApiErrors.validation("commentId", "Identificador de comentário inválido.")

	/** `{"text": "…"}`, só esse campo; texto simples de 1 a 1000 caracteres. */
	private fun parseText(body: String?): String {
		val node = ExperienceRules.parseObject(body, mapper)
		val errors = mutableListOf<FieldError>()
		node.propertyNames().filter { it != "text" }.forEach { errors += FieldError(it, "Este campo não pode ser enviado.") }
		val value = node.get("text")
		val text = if (value != null && value.isString) ExperienceRules.cleanText(value.stringValue(), MAX_LENGTH) else null
		if (text == null) errors += FieldError("text", TEXT_MESSAGE)
		if (errors.isNotEmpty()) throw ApiErrors.validation(errors)
		return text!!
	}

	private companion object {
		const val MAX_LENGTH = 1000
		const val TEXT_MESSAGE = "O comentário deve ter entre 1 e 1000 caracteres."
	}
}
