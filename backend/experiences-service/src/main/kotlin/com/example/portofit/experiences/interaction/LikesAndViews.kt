package com.example.portofit.experiences.interaction

import com.example.portofit.experiences.api.ApiErrors
import com.example.portofit.experiences.config.InteractionProperties
import com.example.portofit.experiences.experience.ExperienceController
import com.example.portofit.experiences.experience.ExperienceRepository
import com.example.portofit.experiences.experience.ExperienceRules
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

@Repository
class InteractionRepository(private val jdbc: JdbcClient) {

	/** true se criou o gosto; a chave primária faz de um pedido simultâneo igual uma repetição. */
	fun addLike(experienceId: UUID, userId: UUID): Boolean =
		jdbc.sql("INSERT INTO experience_like (experience_id, user_id) VALUES (:experience, :user) ON CONFLICT DO NOTHING")
			.param("experience", experienceId).param("user", userId).update() > 0

	fun removeLike(experienceId: UUID, userId: UUID): Boolean =
		jdbc.sql("DELETE FROM experience_like WHERE experience_id = :experience AND user_id = :user")
			.param("experience", experienceId).param("user", userId).update() > 0

	/** true se contou; uma linha por (experiência, quem vê, período), também com pedidos simultâneos. */
	fun addView(experienceId: UUID, viewerHash: String, periodStart: Instant): Boolean =
		jdbc.sql(
			"""
			INSERT INTO experience_view (experience_id, viewer_hash, period_start) VALUES (:experience, :viewer, :period)
			ON CONFLICT DO NOTHING
			""",
		).param("experience", experienceId).param("viewer", viewerHash).param("period", Timestamp.from(periodStart)).update() > 0
}

/** Gostos (docs/experiences-api.md §13.2) e visualizações (§13.5). */
@Service
class InteractionService(
	private val interactions: InteractionRepository,
	private val experiences: ExperienceRepository,
	private val limits: InteractionLimits,
	private val properties: InteractionProperties,
	private val clock: Clock,
	private val tx: TransactionTemplate,
) {

	/** Idempotente; não na própria experiência. Só um gosto criado conta para o limite. */
	fun like(experienceId: UUID, userId: UUID) {
		limits.likes.check(userId)
		val changed = tx.execute {
			val author = experiences.activeAuthor(experienceId, lock = true) ?: throw ApiErrors.notFound()
			if (author == userId) throw ApiErrors.cannotLikeOwn()
			interactions.addLike(experienceId, userId)
		}!!
		if (changed) limits.likes.record(userId)
	}

	fun unlike(experienceId: UUID, userId: UUID) {
		limits.likes.check(userId)
		val changed = tx.execute {
			experiences.activeAuthor(experienceId, lock = true) ?: throw ApiErrors.notFound()
			interactions.removeLike(experienceId, userId)
		}!!
		if (changed) limits.likes.record(userId)
	}

	/**
	 * Conta no máximo uma vez por pessoa (ou chave de instalação) em cada período fixo; o autor e os visitantes
	 * sem chave não contam. Responde igual quer conte quer não. Devolve se contou (só para os testes e logs).
	 */
	fun registerView(experienceId: UUID, userId: UUID?, viewerKey: UUID?): Boolean = tx.execute {
		val author = experiences.activeAuthor(experienceId, lock = true) ?: throw ApiErrors.notFound()
		val viewer = when {
			userId != null -> if (userId == author) null else "user:$userId"
			viewerKey != null -> "key:$viewerKey"
			else -> null
		} ?: return@execute false
		interactions.addView(experienceId, sha256(viewer), periodStart())
	}!!

	/** Início do período fixo atual; com 24 h, alinhado a 00:00 UTC (a época é meia-noite UTC). */
	private fun periodStart(): Instant {
		val seconds = properties.viewPeriod.toSeconds()
		return Instant.ofEpochSecond(Math.floorDiv(clock.instant().epochSecond, seconds) * seconds)
	}

	private fun sha256(text: String): String =
		HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))
}

@RestController
@RequestMapping("/api/v1/experiences/{experienceId}")
class InteractionController(private val service: InteractionService) {

	@PutMapping("/like")
	fun like(@AuthenticationPrincipal jwt: Jwt, @PathVariable("experienceId") experienceId: String): ResponseEntity<Void> {
		val id = ExperienceRules.experienceId(experienceId)
		service.like(id, ExperienceController.author(jwt).first)
		return ResponseEntity.noContent().build()
	}

	@DeleteMapping("/like")
	fun unlike(@AuthenticationPrincipal jwt: Jwt, @PathVariable("experienceId") experienceId: String): ResponseEntity<Void> {
		val id = ExperienceRules.experienceId(experienceId)
		service.unlike(id, ExperienceController.author(jwt).first)
		return ResponseEntity.noContent().build()
	}

	/** Token opcional; sem token, a chave de instalação `X-Viewer-Key` (UUID). Com token, a chave é ignorada. */
	@PostMapping("/views")
	fun view(
		@AuthenticationPrincipal jwt: Jwt?,
		@PathVariable("experienceId") experienceId: String,
		@RequestHeader(VIEWER_KEY, required = false) viewerKey: String?,
	): ResponseEntity<Void> {
		val id = ExperienceRules.experienceId(experienceId)
		val userId = ExperienceController.viewer(jwt)
		val key = if (userId == null) {
			viewerKey?.takeIf { it.isNotBlank() }?.let {
				ExperienceRules.parseUuid(it) ?: throw ApiErrors.validation(VIEWER_KEY, "A chave de visualização deve ser um UUID.")
			}
		} else {
			null
		}
		service.registerView(id, userId, key)
		return ResponseEntity.noContent().build()
	}

	private companion object {
		const val VIEWER_KEY = "X-Viewer-Key"
	}
}
