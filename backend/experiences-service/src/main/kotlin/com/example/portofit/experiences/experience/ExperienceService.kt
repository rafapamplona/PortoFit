package com.example.portofit.experiences.experience

import com.example.portofit.experiences.api.ApiErrors
import com.example.portofit.experiences.api.PageResponse
import com.example.portofit.experiences.catalog.CatalogClient
import com.example.portofit.experiences.config.PublishingProperties
import com.example.portofit.experiences.media.MediaService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/** Resultado de uma publicação: criada agora, ou repetição de uma tentativa já aceite. */
data class PublishResult(val experience: ExperienceView, val replayed: Boolean)

@Service
class ExperienceService(
	private val experiences: ExperienceRepository,
	private val catalog: CatalogClient,
	private val limiter: PublishLimiter,
	private val media: MediaService,
	private val properties: PublishingProperties,
	private val tx: TransactionTemplate,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	/**
	 * Publicação idempotente (docs/experiences-api.md §5). A repetição de uma chave já usada responde sem
	 * consultar o catálogo nem contar para o limite. O catálogo é consultado fora da transação.
	 */
	fun publish(authorId: UUID, authorUsername: String, key: UUID, input: PublishInput): PublishResult {
		val hash = input.requestHash()
		experiences.findPublishRequest(authorId, key)?.let { return replay(it, hash) }

		limiter.check(authorId)
		val offering = catalog.offering(input.offeringId) ?: throw ApiErrors.unknownOffering()
		if (offering.placeId != input.placeId) throw ApiErrors.offeringPlaceMismatch()
		if (offering.retired) throw ApiErrors.offeringRetired()

		val created = tx.execute {
			val id = experiences.insertWithKey(authorId, authorUsername, key, hash, input, offering) ?: return@execute null
			// Na mesma transação: uma fotografia inutilizável desfaz a publicação (a chave não fica gasta).
			input.mediaId?.let { mediaId ->
				media.attach(mediaId, id, authorId)
				experiences.setPhoto(id, mediaId)
			}
			experiences.findAny(id)
		}
		if (created == null) {
			// Outro pedido com a mesma chave confirmou primeiro: esta tentativa é uma repetição.
			val existing = experiences.findPublishRequest(authorId, key) ?: error("tentativa registada sem linha")
			return replay(existing, hash)
		}
		limiter.record(authorId)
		return PublishResult(created.toView(), replayed = false)
	}

	/** [viewerId]: quem pede, com sessão; null para visitantes (sem `likedByMe`). */
	fun detail(id: UUID, viewerId: UUID?): ExperienceView {
		val row = experiences.findAny(id)?.takeUnless { it.deleted } ?: throw ApiErrors.notFound()
		return views(listOf(row), viewerId).single()
	}

	fun search(criteria: ExperienceCriteria, viewerId: UUID?): PageResponse<ExperienceView> {
		val (rows, total) = experiences.search(criteria)
		return PageResponse.of(views(rows, viewerId), criteria.page, criteria.size, total)
	}

	/** `likedByMe` só para outra pessoa com sessão; numa só consulta por página (docs/experiences-api.md §13.3). */
	fun views(rows: List<ExperienceRow>, viewerId: UUID?): List<ExperienceView> {
		if (viewerId == null) return rows.map { it.toView() }
		val liked = experiences.likedBy(viewerId, rows.filter { it.authorId != viewerId }.map { it.id })
		return rows.map { row -> row.toView(likedByMe = if (row.authorId == viewerId) null else row.id in liked) }
	}

	/**
	 * Só o autor; `editedAt` só muda se algum valor mudar; `createdAt` nunca muda. Substituir ou remover a
	 * fotografia acontece na mesma transação, com a experiência bloqueada: a anterior continua a ser servida
	 * até a nova estar confirmada, e o seu ficheiro só é apagado depois (docs/experiences-api.md §12.4).
	 */
	fun edit(id: UUID, userId: UUID, input: EditInput): ExperienceView {
		val replaced = mutableListOf<UUID>()
		val view = tx.execute {
			val row = experiences.findAny(id, lock = true)?.takeUnless { it.deleted } ?: throw ApiErrors.notFound()
			if (row.authorId != userId) throw ApiErrors.forbidden()
			val text = input.text ?: row.text!!
			val stars = input.stars ?: row.stars!!
			val photo = if (input.mediaSpecified) input.mediaId else row.photoMediaId
			if (text == row.text && stars == row.stars && photo == row.photoMediaId) return@execute row.toView()
			if (photo != row.photoMediaId) {
				photo?.let { media.attach(it, id, userId) }
				row.photoMediaId?.let {
					media.release(it)
					replaced += it
				}
			}
			experiences.update(id, text, stars, photo)
			experiences.findAny(id)!!.toView()
		}!!
		media.purge(replaced)
		return view
	}

	/**
	 * Só o autor; repetir é seguro (204). Para outra pessoa, uma experiência eliminada não existe. A fotografia
	 * deixa de ser servida na mesma transação; o ficheiro é apagado depois (ou pela limpeza). Comentários, gostos e
	 * visualizações saem na mesma transação; o bloqueio da linha espera pelos que estejam a ser gravados.
	 */
	fun delete(id: UUID, userId: UUID) {
		val released = mutableListOf<UUID>()
		tx.executeWithoutResult {
			val row = experiences.findAny(id, lock = true) ?: throw ApiErrors.notFound()
			when {
				row.deleted && row.authorId == userId -> Unit
				row.deleted -> throw ApiErrors.notFound()
				row.authorId != userId -> throw ApiErrors.forbidden()
				else -> {
					row.photoMediaId?.let {
						media.release(it)
						released += it
					}
					experiences.purgeInteractions(id)
					experiences.markDeleted(id)
				}
			}
		}
		media.purge(released)
	}

	/** Fora da retenção já não há garantia contra duplicação (docs/experiences-api.md §5). */
	@Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
	fun removeOldPublishRequests() {
		val removed = experiences.deletePublishRequestsOlderThan(properties.idempotencyRetention)
		if (removed > 0) log.info("Removidos {} registos de tentativas de publicação antigos", removed)
	}

	/** Nunca recria nem devolve o conteúdo de uma experiência eliminada. */
	private fun replay(request: PublishRequestRow, hash: String): PublishResult {
		val row = experiences.findAny(request.experienceId)
		if (row == null || row.deleted) throw ApiErrors.experienceDeleted()
		if (request.requestHash != hash) throw ApiErrors.differentContent(row.id)
		return PublishResult(row.toView(), replayed = true)
	}
}
