package com.example.portofit.experiences.media

import com.example.portofit.experiences.api.ApiErrors
import com.example.portofit.experiences.api.FieldError
import com.example.portofit.experiences.config.MediaProperties
import org.slf4j.LoggerFactory
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Resposta de `POST /media`. */
data class MediaView(val mediaId: UUID, val width: Int, val height: Int)

/** Linha da tabela media, bloqueada para associar a uma experiência. */
data class MediaRow(val id: UUID, val ownerId: UUID, val status: String, val expired: Boolean)

@Repository
class MediaRepository(private val jdbc: JdbcClient) {

	fun insertPending(id: UUID, ownerId: UUID, width: Int, height: Int, sizeBytes: Int) {
		jdbc.sql("INSERT INTO media (id, owner_id, status, width, height, size_bytes) VALUES (:id, :owner, 'PENDING', :w, :h, :size)")
			.param("id", id).param("owner", ownerId).param("w", width).param("h", height).param("size", sizeBytes)
			.update()
	}

	/** Pendentes ainda válidas do dono e segundos até a mais antiga expirar. */
	fun pending(ownerId: UUID, ttl: Duration): Pair<Int, Long> =
		jdbc.sql(
			"""
			SELECT count(*) AS n,
			       coalesce(ceil(extract(epoch FROM (min(created_at) + CAST(:ttl || ' seconds' AS interval) - now()))), 0) AS wait
			FROM media WHERE owner_id = :owner AND status = 'PENDING' AND created_at > now() - CAST(:ttl || ' seconds' AS interval)
			""",
		).param("owner", ownerId).param("ttl", ttl.toSeconds().toString())
			.query { rs, _ -> rs.getInt("n") to rs.getLong("wait") }.single()

	/** Só fotografias associadas a uma experiência não eliminada, que ainda a tem como fotografia atual. */
	fun isServable(id: UUID): Boolean =
		jdbc.sql(
			"""
			SELECT EXISTS (
				SELECT 1 FROM media m JOIN experience e ON e.id = m.experience_id
				WHERE m.id = :id AND m.status = 'ATTACHED' AND e.deleted_at IS NULL AND e.photo_media_id = m.id)
			""",
		).param("id", id).query(Boolean::class.javaObjectType).single()

	fun lock(id: UUID, ttl: Duration): MediaRow? =
		jdbc.sql(
			"""
			SELECT id, owner_id, status, created_at <= now() - CAST(:ttl || ' seconds' AS interval) AS expired
			FROM media WHERE id = :id FOR UPDATE
			""",
		).param("id", id).param("ttl", ttl.toSeconds().toString())
			.query { rs, _ -> MediaRow(rs.getObject("id", UUID::class.java), rs.getObject("owner_id", UUID::class.java), rs.getString("status"), rs.getBoolean("expired")) }
			.optional().orElse(null)

	fun markAttached(id: UUID, experienceId: UUID) {
		jdbc.sql("UPDATE media SET status = 'ATTACHED', experience_id = :experience, attached_at = now() WHERE id = :id")
			.param("id", id).param("experience", experienceId).update()
	}

	fun markDeleted(id: UUID) {
		jdbc.sql("UPDATE media SET status = 'DELETED', deleted_at = now() WHERE id = :id AND status <> 'DELETED'").param("id", id).update()
	}

	/** Linhas a limpar: eliminadas, e pendentes expiradas. */
	fun cleanable(ttl: Duration): List<UUID> =
		jdbc.sql(
			"""
			SELECT id FROM media
			WHERE status = 'DELETED' OR (status = 'PENDING' AND created_at <= now() - CAST(:ttl || ' seconds' AS interval))
			""",
		).param("ttl", ttl.toSeconds().toString()).query(UUID::class.java).list().filterNotNull()

	/** Apaga a linha só se ainda puder ser limpa (nunca uma fotografia associada). */
	fun deleteRow(id: UUID, ttl: Duration): Boolean =
		jdbc.sql(
			"""
			DELETE FROM media WHERE id = :id
			  AND (status = 'DELETED' OR (status = 'PENDING' AND created_at <= now() - CAST(:ttl || ' seconds' AS interval)))
			""",
		).param("id", id).param("ttl", ttl.toSeconds().toString()).update() == 1

	fun existing(ids: Collection<UUID>): Set<UUID> =
		if (ids.isEmpty()) emptySet() else jdbc.sql("SELECT id FROM media WHERE id IN (:ids)").param("ids", ids).query(UUID::class.java).set().filterNotNull().toSet()
}

/**
 * Fotografias (docs/experiences-api.md §12). Associar, substituir e remover acontecem dentro da transação da
 * experiência ([attach], [release]); os ficheiros só são apagados depois de confirmada ([purge]) ou pela
 * limpeza periódica ([cleanup]).
 */
@Service
class MediaService(
	private val repository: MediaRepository,
	private val storage: MediaStorage,
	private val processor: MediaProcessor,
	private val properties: MediaProperties,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	fun upload(ownerId: UUID, bytes: ByteArray): MediaView {
		if (bytes.size > properties.maxFileBytes) throw ApiErrors.payloadTooLarge()
		val (pending, wait) = repository.pending(ownerId, properties.pendingTtl)
		if (pending >= properties.maxPendingPerOwner) throw ApiErrors.tooManyPendingPhotos(maxOf(1, wait))

		val image = processor.process(bytes)
		val id = UUID.randomUUID()
		storage.write(id, image.jpeg)
		try {
			repository.insertPending(id, ownerId, image.width, image.height, image.jpeg.size)
		} catch (e: RuntimeException) {
			// Sem linha, o ficheiro nunca seria servido: apaga-se já (se falhar, a limpeza apaga-o ao fim de 1 h).
			storage.delete(id)
			throw e
		}
		return MediaView(id, image.width, image.height)
	}

	/** 404 se não estiver associada a uma experiência não eliminada (ex.: pendente, substituída, removida). */
	fun ensureServable(id: UUID) {
		if (!repository.isServable(id)) throw ApiErrors.photoNotFound()
	}

	fun serve(id: UUID): ByteArray {
		ensureServable(id)
		return storage.read(id) ?: run {
			log.warn("Fotografia associada sem ficheiro: {}", id)
			throw ApiErrors.photoUnavailable()
		}
	}

	/** Na transação da experiência: a fotografia tem de ser do autor, pendente e não expirada. */
	fun attach(mediaId: UUID, experienceId: UUID, authorId: UUID) {
		val row = repository.lock(mediaId, properties.pendingTtl)
		if (row == null || row.ownerId != authorId || row.status != "PENDING" || row.expired) throw ApiErrors.mediaNotUsable()
		repository.markAttached(mediaId, experienceId)
	}

	/** Na transação da experiência: deixa de ser servida de imediato. */
	fun release(mediaId: UUID) = repository.markDeleted(mediaId)

	/** Depois de confirmada a transação: apaga os ficheiros e as linhas; o que falhar fica para a limpeza. */
	fun purge(ids: Collection<UUID>) {
		for (id in ids) {
			if (storage.delete(id)) runCatching { repository.deleteRow(id, properties.pendingTtl) }
		}
	}

	/**
	 * Limpeza (docs/experiences-api.md §12.4): eliminadas e pendentes expiradas (ficheiro e depois linha);
	 * ficheiros sem linha e temporários mais antigos do que [MediaProperties.orphanAge].
	 */
	@Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT2M")
	fun cleanup(): CleanupReport {
		val rows = repository.cleanable(properties.pendingTtl)
		var rowsRemoved = 0
		for (id in rows) if (storage.delete(id) && repository.deleteRow(id, properties.pendingTtl)) rowsRemoved++

		val cutoff = Instant.now().minus(properties.orphanAge)
		val oldFiles = storage.list().filter { it.modifiedAt.isBefore(cutoff) }
		val known = repository.existing(oldFiles.map { it.mediaId })
		var orphans = 0
		for (file in oldFiles) if (file.mediaId !in known && storage.delete(file.mediaId)) orphans++
		val temporaries = storage.deleteTemporariesOlderThan(cutoff)

		val report = CleanupReport(rowsRemoved, orphans, temporaries)
		if (report.total > 0) log.info("Limpeza de fotografias: {}", report)
		return report
	}
}

data class CleanupReport(val rowsRemoved: Int, val orphanFiles: Int, val temporaryFiles: Int) {
	val total: Int get() = rowsRemoved + orphanFiles + temporaryFiles
}

@RestController
@RequestMapping("/api/v1/media")
class MediaController(private val service: MediaService) {

	/** Qualquer sessão pode enviar; a fotografia fica pendente e pertence a quem a enviou. */
	@PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
	fun upload(
		@AuthenticationPrincipal jwt: Jwt,
		@RequestPart("file", required = false) file: MultipartFile?,
	): ResponseEntity<MediaView> {
		val owner = runCatching { UUID.fromString(jwt.subject) }.getOrNull() ?: throw ApiErrors.tokenWithoutUsername()
		if (file == null) throw ApiErrors.validation(listOf(FieldError("file", "Envia a fotografia no campo file.")))
		// O nome e o Content-Type enviados são ignorados: o formato decide-se pelo conteúdo.
		return ResponseEntity.status(HttpStatus.CREATED).body(service.upload(owner, file.bytes))
	}

	/**
	 * Público, mas só para fotografias associadas a experiências não eliminadas. O conteúdo de um `mediaId`
	 * nunca muda (uma substituição cria outro id): cache privada de 24 h com ETag.
	 */
	@GetMapping("/{mediaId}")
	fun get(
		@PathVariable("mediaId") mediaId: String,
		@RequestHeader(HttpHeaders.IF_NONE_MATCH, required = false) ifNoneMatch: String?,
	): ResponseEntity<ByteArray> {
		val id = runCatching { UUID.fromString(mediaId) }.getOrNull()?.takeIf { it.toString().equals(mediaId, ignoreCase = true) }
			?: throw ApiErrors.photoNotFound()
		val etag = "\"$id\""
		val cache = CacheControl.maxAge(Duration.ofDays(1)).cachePrivate()
		if (ifNoneMatch?.split(",")?.any { it.trim() == etag } == true) {
			service.ensureServable(id)
			return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).cacheControl(cache).build()
		}
		val bytes = service.serve(id)
		return ResponseEntity.ok()
			.contentType(MediaType.IMAGE_JPEG)
			.cacheControl(cache)
			.eTag(etag)
			.header("X-Content-Type-Options", "nosniff")
			.header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"$id.jpg\"")
			.body(bytes)
	}
}
