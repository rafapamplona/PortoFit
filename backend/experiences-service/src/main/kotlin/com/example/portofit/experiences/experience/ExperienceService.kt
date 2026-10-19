package com.example.portofit.experiences.experience

import com.example.portofit.experiences.api.ApiErrors
import com.example.portofit.experiences.api.PageResponse
import com.example.portofit.experiences.catalog.CatalogClient
import com.example.portofit.experiences.config.PublishingProperties
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

		val created = tx.execute { experiences.insertWithKey(authorId, authorUsername, key, hash, input, offering) }
		if (created == null) {
			// Outro pedido com a mesma chave confirmou primeiro: esta tentativa é uma repetição.
			val existing = experiences.findPublishRequest(authorId, key) ?: error("tentativa registada sem linha")
			return replay(existing, hash)
		}
		limiter.record(authorId)
		return PublishResult(created.toView(), replayed = false)
	}

	fun detail(id: UUID): ExperienceView = experiences.findAny(id)?.takeUnless { it.deleted }?.toView() ?: throw ApiErrors.notFound()

	fun search(criteria: ExperienceCriteria): PageResponse<ExperienceView> {
		val (rows, total) = experiences.search(criteria)
		return PageResponse.of(rows.map { it.toView() }, criteria.page, criteria.size, total)
	}

	/** Só o autor; `editedAt` só muda se algum valor mudar; `createdAt` nunca muda. */
	fun edit(id: UUID, userId: UUID, input: EditInput): ExperienceView = tx.execute {
		val row = experiences.findAny(id, lock = true)?.takeUnless { it.deleted } ?: throw ApiErrors.notFound()
		if (row.authorId != userId) throw ApiErrors.forbidden()
		val text = input.text ?: row.text!!
		val stars = input.stars ?: row.stars!!
		if (text == row.text && stars == row.stars) row.toView() else experiences.update(id, text, stars).toView()
	}!!

	/** Só o autor; repetir é seguro (204). Para outra pessoa, uma experiência eliminada não existe. */
	fun delete(id: UUID, userId: UUID) {
		tx.executeWithoutResult {
			val row = experiences.findAny(id, lock = true) ?: throw ApiErrors.notFound()
			when {
				row.deleted && row.authorId == userId -> Unit
				row.deleted -> throw ApiErrors.notFound()
				row.authorId != userId -> throw ApiErrors.forbidden()
				else -> experiences.markDeleted(id)
			}
		}
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
