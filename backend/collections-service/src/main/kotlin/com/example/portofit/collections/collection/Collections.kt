package com.example.portofit.collections.collection

import com.example.portofit.collections.api.ApiErrors
import com.example.portofit.collections.api.ApiException
import com.example.portofit.collections.api.FieldError
import com.example.portofit.collections.api.PageResponse
import com.example.portofit.collections.config.CollectionsProperties
import com.example.portofit.collections.experiences.ExperienceStatus
import com.example.portofit.collections.experiences.ExperiencesClient
import com.example.portofit.collections.experiences.LookupResult
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
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
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.security.MessageDigest
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

// ------------------------------------------------------------------ Modelos

/** Formato `Collection` das respostas (docs/collections-api.md §4). Nunca inclui o dono. */
data class CollectionView(val id: UUID, val name: String, val itemCount: Long, val createdAt: Instant, val updatedAt: Instant)

enum class Availability { AVAILABLE, DELETED, UNKNOWN }

/** Um item guardado (§6.3): conteúdo da experiência só quando AVAILABLE. */
data class ItemView(val experienceId: UUID, val addedAt: Instant, val availability: Availability, val experience: JsonNode?)

data class ItemsPage(
	val items: List<ItemView>,
	val page: Int,
	val size: Int,
	val totalItems: Long,
	val totalPages: Int,
	/** UNAVAILABLE = o experiences-service não respondeu: os itens estão como UNKNOWN, não eliminados. */
	val experiencesStatus: String,
)

data class CreateResult(val collection: CollectionView, val replayed: Boolean)

internal data class RequestRow(val requestHash: String, val collectionId: UUID?)

internal data class ItemRow(val experienceId: UUID, val addedAt: Instant)

// ------------------------------------------------------------------ Regras

/** Regras do nome e dos ids (docs/collections-api.md §5.1). */
object CollectionRules {
	const val MAX_NAME = 60
	private const val NAME_MESSAGE = "O nome deve ter entre 1 e 60 caracteres."

	/** Pontas removidas; 1–60 pontos de código, sem caracteres de controlo. Inválido → null. */
	fun cleanName(raw: String): String? {
		val name = raw.trim()
		val length = name.codePointCount(0, name.length)
		val invalid = name.codePoints().anyMatch { c ->
			val type = Character.getType(c)
			type == Character.CONTROL.toInt() || type == Character.SURROGATE.toInt()
		}
		return name.takeUnless { it.isEmpty() || length > MAX_NAME || invalid }
	}

	/** `{"name": "…"}` e só esse campo. */
	fun parseName(body: String?, mapper: JsonMapper): String {
		val node = try {
			body?.takeIf { it.isNotBlank() }?.let { mapper.readTree(it) }
		} catch (_: JacksonException) {
			null
		}
		if (node == null || !node.isObject) throw ApiErrors.validation("body", "O corpo do pedido tem de ser um objeto JSON.")
		val errors = mutableListOf<FieldError>()
		node.propertyNames().filter { it != "name" }.forEach { errors += FieldError(it, "Este campo não pode ser enviado.") }
		val value = node.get("name")
		val name = if (value != null && value.isString) cleanName(value.stringValue()) else null
		if (name == null) errors += FieldError("name", NAME_MESSAGE)
		if (errors.isNotEmpty()) throw ApiErrors.validation(errors)
		return name!!
	}

	/** UUID na forma canónica, sem distinguir maiúsculas; outra forma → null. */
	fun parseUuid(value: String?): UUID? {
		val text = value?.trim() ?: return null
		return runCatching { UUID.fromString(text) }.getOrNull()?.takeIf { it.toString().equals(text, ignoreCase = true) }
	}

	fun requestHash(name: String): String =
		HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(name.toByteArray(Charsets.UTF_8)))
}

// ------------------------------------------------------------------ Repositório

/** Spring JDBC; valores do pedido sempre como parâmetros. Todas as consultas de listas filtram pelo dono. */
@Repository
class CollectionRepository(private val jdbc: JdbcClient) {

	/** Serializa as criações de um dono até ao fim da transação (limite de listas sem corridas). */
	fun lockOwner(ownerId: UUID) {
		jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:owner, 0))").param("owner", ownerId.toString()).query { _, _ -> 1 }.list()
	}

	fun countByOwner(ownerId: UUID): Long =
		jdbc.sql("SELECT count(*) FROM collection WHERE owner_id = :owner").param("owner", ownerId).query(Long::class.javaObjectType).single()

	internal fun findRequest(ownerId: UUID, key: UUID): RequestRow? =
		jdbc.sql("SELECT request_hash, collection_id FROM collection_request WHERE owner_id = :owner AND idempotency_key = :key")
			.param("owner", ownerId).param("key", key)
			.query { rs, _ -> RequestRow(rs.getString("request_hash"), rs.getObject("collection_id", UUID::class.java)) }
			.optional().orElse(null)

	/**
	 * Regista a tentativa e cria a lista, na transação em curso; devolve o id, ou null se a chave já existir (um pedido
	 * simultâneo com a mesma chave espera e não insere nada). Nome repetido → DuplicateKeyException (desfaz tudo).
	 */
	fun insertWithKey(ownerId: UUID, key: UUID, hash: String, name: String): UUID? {
		val id = jdbc.sql("SELECT uuidv7()").query(UUID::class.java).single()
		val registered = jdbc.sql(
			"""
			INSERT INTO collection_request (owner_id, idempotency_key, request_hash, collection_id)
			VALUES (:owner, :key, :hash, :id)
			ON CONFLICT (owner_id, idempotency_key) DO NOTHING
			""",
		).param("owner", ownerId).param("key", key).param("hash", hash).param("id", id).update()
		if (registered == 0) return null
		jdbc.sql("INSERT INTO collection (id, owner_id, name) VALUES (:id, :owner, :name)")
			.param("id", id).param("owner", ownerId).param("name", name).update()
		return id
	}

	/** A lista se for de [ownerId]; de outra pessoa ou inexistente → null (indistinguíveis). */
	fun find(id: UUID, ownerId: UUID, lock: Boolean = false): CollectionView? =
		jdbc.sql("$SELECT_VIEW WHERE c.id = :id AND c.owner_id = :owner" + if (lock) " FOR UPDATE OF c" else "")
			.param("id", id).param("owner", ownerId)
			.query { rs, _ -> rs.toView() }
			.optional().orElse(null)

	/** Só para repetições de criação (a lista é do próprio dono, pelo registo da tentativa). */
	fun findById(id: UUID): CollectionView? =
		jdbc.sql("$SELECT_VIEW WHERE c.id = :id").param("id", id).query { rs, _ -> rs.toView() }.optional().orElse(null)

	fun list(ownerId: UUID, page: Int, size: Int): Pair<List<CollectionView>, Long> {
		val total = countByOwner(ownerId)
		val rows = jdbc.sql("$SELECT_VIEW WHERE c.owner_id = :owner ORDER BY c.created_at DESC, c.id DESC LIMIT :limit OFFSET :offset")
			.param("owner", ownerId).param("limit", size).param("offset", page.toLong() * size)
			.query { rs, _ -> rs.toView() }
			.list()
		return rows to total
	}

	/** Muda o nome se for diferente (também só maiúsculas/minúsculas). Nome de outra lista → DuplicateKeyException. */
	fun rename(id: UUID, ownerId: UUID, name: String) {
		jdbc.sql("UPDATE collection SET name = :name, updated_at = now() WHERE id = :id AND owner_id = :owner AND name <> :name")
			.param("id", id).param("owner", ownerId).param("name", name).update()
	}

	fun delete(id: UUID, ownerId: UUID): Boolean =
		jdbc.sql("DELETE FROM collection WHERE id = :id AND owner_id = :owner").param("id", id).param("owner", ownerId).update() > 0

	fun hasItem(collectionId: UUID, experienceId: UUID): Boolean =
		jdbc.sql("SELECT EXISTS (SELECT 1 FROM collection_item WHERE collection_id = :c AND experience_id = :e)")
			.param("c", collectionId).param("e", experienceId).query(Boolean::class.javaObjectType).single()

	fun countItems(collectionId: UUID): Long =
		jdbc.sql("SELECT count(*) FROM collection_item WHERE collection_id = :c").param("c", collectionId).query(Long::class.javaObjectType).single()

	/** true se guardou; a chave primária faz de um pedido simultâneo igual uma repetição. */
	fun addItem(collectionId: UUID, experienceId: UUID): Boolean {
		val added = jdbc.sql(
			"INSERT INTO collection_item (collection_id, experience_id) VALUES (:c, :e) ON CONFLICT DO NOTHING",
		).param("c", collectionId).param("e", experienceId).update() > 0
		if (added) touch(collectionId)
		return added
	}

	fun removeItem(collectionId: UUID, experienceId: UUID): Boolean {
		val removed = jdbc.sql("DELETE FROM collection_item WHERE collection_id = :c AND experience_id = :e")
			.param("c", collectionId).param("e", experienceId).update() > 0
		if (removed) touch(collectionId)
		return removed
	}

	internal fun items(collectionId: UUID, page: Int, size: Int): Pair<List<ItemRow>, Long> {
		val total = countItems(collectionId)
		val rows = jdbc.sql(
			"""
			SELECT experience_id, added_at FROM collection_item WHERE collection_id = :c
			ORDER BY added_at DESC, experience_id DESC LIMIT :limit OFFSET :offset
			""",
		)
			.param("c", collectionId).param("limit", size).param("offset", page.toLong() * size)
			.query { rs, _ -> ItemRow(rs.getObject("experience_id", UUID::class.java), rs.getTimestamp("added_at").toInstant()) }
			.list()
		return rows to total
	}

	fun deleteRequestsOlderThan(retention: Duration): Int =
		jdbc.sql("DELETE FROM collection_request WHERE created_at < now() - CAST(:seconds || ' seconds' AS interval)")
			.param("seconds", retention.toSeconds().toString())
			.update()

	private fun touch(collectionId: UUID) {
		jdbc.sql("UPDATE collection SET updated_at = now() WHERE id = :c").param("c", collectionId).update()
	}

	private fun ResultSet.toView() = CollectionView(
		id = getObject("id", UUID::class.java),
		name = getString("name"),
		itemCount = getLong("item_count"),
		createdAt = getTimestamp("created_at").toInstant(),
		updatedAt = getTimestamp("updated_at").toInstant(),
	)

	private companion object {
		const val SELECT_VIEW = """
			SELECT c.id, c.name, c.created_at, c.updated_at, ic.n AS item_count
			FROM collection c
			CROSS JOIN LATERAL (SELECT count(*) AS n FROM collection_item i WHERE i.collection_id = c.id) ic
		"""
	}
}

// ------------------------------------------------------------------ Serviço

/** Listas e itens (docs/collections-api.md §5–§6). O dono vem sempre do token. */
@Service
class CollectionService(
	private val collections: CollectionRepository,
	private val experiences: ExperiencesClient,
	private val properties: CollectionsProperties,
	private val tx: TransactionTemplate,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	/** Idempotente pela chave (§5.2). Uma recusa (nome repetido, limite) não gasta a chave. */
	fun create(ownerId: UUID, key: UUID, name: String): CreateResult {
		val hash = CollectionRules.requestHash(name)
		collections.findRequest(ownerId, key)?.let { return replay(it, hash) }
		val created = try {
			tx.execute {
				collections.lockOwner(ownerId)
				// Uma tentativa simultânea com a mesma chave pode ter confirmado enquanto esperávamos.
				if (collections.findRequest(ownerId, key) != null) return@execute null
				if (collections.countByOwner(ownerId) >= properties.maxPerOwner) throw ApiErrors.collectionLimit(properties.maxPerOwner)
				val id = collections.insertWithKey(ownerId, key, hash, name) ?: return@execute null
				collections.findById(id)
			}
		} catch (e: DuplicateKeyException) {
			throw ApiErrors.nameTaken()
		}
		if (created == null) {
			val existing = collections.findRequest(ownerId, key) ?: error("tentativa registada sem linha")
			return replay(existing, hash)
		}
		return CreateResult(created, replayed = false)
	}

	fun list(ownerId: UUID, page: Int, size: Int): PageResponse<CollectionView> {
		val (rows, total) = collections.list(ownerId, page, size)
		return PageResponse.of(rows, page, size, total)
	}

	fun get(id: UUID, ownerId: UUID): CollectionView = collections.find(id, ownerId) ?: throw ApiErrors.notFound()

	fun rename(id: UUID, ownerId: UUID, name: String): CollectionView = try {
		tx.execute {
			collections.find(id, ownerId, lock = true) ?: throw ApiErrors.notFound()
			collections.rename(id, ownerId, name)
			collections.find(id, ownerId)!!
		}!!
	} catch (e: DuplicateKeyException) {
		throw ApiErrors.nameTaken()
	}

	fun delete(id: UUID, ownerId: UUID) {
		if (!collections.delete(id, ownerId)) throw ApiErrors.notFound()
	}

	/**
	 * Guardar (§6.1): idempotente; já guardada → sem consultar o experiences-service. A experiência é confirmada fora
	 * da transação; a falha do serviço dá 503 (nada guardado), nunca "inexistente".
	 */
	fun addItem(id: UUID, ownerId: UUID, experienceId: UUID) {
		collections.find(id, ownerId) ?: throw ApiErrors.notFound()
		if (collections.hasItem(id, experienceId)) return
		when (val result = experiences.lookup(listOf(experienceId))) {
			LookupResult.Unavailable -> throw ApiErrors.experiencesUnavailable()
			is LookupResult.Found -> if (result.entries[experienceId]?.status != ExperienceStatus.AVAILABLE) throw ApiErrors.experienceNotFound()
		}
		tx.executeWithoutResult {
			collections.find(id, ownerId, lock = true) ?: throw ApiErrors.notFound()
			if (collections.hasItem(id, experienceId)) return@executeWithoutResult
			if (collections.countItems(id) >= properties.maxItems) throw ApiErrors.itemLimit(properties.maxItems)
			collections.addItem(id, experienceId)
		}
	}

	/** Remover (§6.2): idempotente e sem consultar o experiences-service. */
	fun removeItem(id: UUID, ownerId: UUID, experienceId: UUID) {
		tx.executeWithoutResult {
			collections.find(id, ownerId, lock = true) ?: throw ApiErrors.notFound()
			collections.removeItem(id, experienceId)
		}
	}

	/** Itens (§6.3): um único pedido em lote ao experiences-service por página. */
	fun items(id: UUID, ownerId: UUID, page: Int, size: Int): ItemsPage {
		collections.find(id, ownerId) ?: throw ApiErrors.notFound()
		val (rows, total) = collections.items(id, page, size)
		val lookup = experiences.lookup(rows.map { it.experienceId })
		val items = rows.map { row ->
			when (lookup) {
				LookupResult.Unavailable -> ItemView(row.experienceId, row.addedAt, Availability.UNKNOWN, null)
				is LookupResult.Found -> {
					val entry = lookup.entries[row.experienceId]
					if (entry?.status == ExperienceStatus.AVAILABLE) {
						ItemView(row.experienceId, row.addedAt, Availability.AVAILABLE, entry.experience)
					} else {
						// Eliminada ou inexistente: nenhum conteúdo; o dono pode removê-la.
						ItemView(row.experienceId, row.addedAt, Availability.DELETED, null)
					}
				}
			}
		}
		val envelope = PageResponse.of(items, page, size, total)
		val status = if (lookup is LookupResult.Unavailable) "UNAVAILABLE" else "AVAILABLE"
		return ItemsPage(items, page, size, total, envelope.totalPages, status)
	}

	/** Fora da retenção já não há garantia contra duplicação (§5.2). */
	@Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
	fun removeOldRequests() {
		val removed = collections.deleteRequestsOlderThan(properties.idempotencyRetention)
		if (removed > 0) log.info("Removidos {} registos de tentativas de criação de listas antigos", removed)
	}

	/** Nunca recria uma lista eliminada nem revela outra coisa além do id da criada. */
	private fun replay(request: RequestRow, hash: String): CreateResult {
		val collection = request.collectionId?.let { collections.findById(it) } ?: throw ApiErrors.collectionDeleted()
		if (request.requestHash != hash) throw ApiErrors.differentContent(collection.id)
		return CreateResult(collection, replayed = true)
	}
}

// ------------------------------------------------------------------ Controlador

@RestController
@RequestMapping("/api/v1/collections")
class CollectionController(private val service: CollectionService, private val mapper: JsonMapper) {

	@GetMapping
	fun list(
		@AuthenticationPrincipal jwt: Jwt,
		@RequestParam("page", required = false) page: String?,
		@RequestParam("size", required = false) size: String?,
	): PageResponse<CollectionView> {
		val (pageValue, sizeValue) = page(page, size)
		return service.list(owner(jwt), pageValue, sizeValue)
	}

	@PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
	fun create(
		@AuthenticationPrincipal jwt: Jwt,
		@RequestHeader(IDEMPOTENCY_KEY, required = false) idempotencyKey: String?,
		@RequestBody(required = false) body: String?,
	): ResponseEntity<CollectionView> {
		val ownerId = owner(jwt)
		val key = CollectionRules.parseUuid(idempotencyKey)
		val keyError = FieldError(IDEMPOTENCY_KEY, "Falta a chave de idempotência do pedido.")
		val name = try {
			CollectionRules.parseName(body, mapper)
		} catch (e: ApiException) {
			if (key == null) throw ApiErrors.validation(e.errors + keyError) else throw e
		}
		if (key == null) throw ApiErrors.validation(listOf(keyError))
		val result = service.create(ownerId, key, name)
		val location = URI.create("/api/v1/collections/${result.collection.id}")
		return if (result.replayed) {
			ResponseEntity.ok().location(location).header("Idempotent-Replayed", "true").body(result.collection)
		} else {
			ResponseEntity.status(HttpStatus.CREATED).location(location).body(result.collection)
		}
	}

	@GetMapping("/{collectionId}")
	fun get(@AuthenticationPrincipal jwt: Jwt, @PathVariable("collectionId") collectionId: String) =
		service.get(collectionId(collectionId), owner(jwt))

	@PatchMapping("/{collectionId}", consumes = [MediaType.APPLICATION_JSON_VALUE])
	fun rename(
		@AuthenticationPrincipal jwt: Jwt,
		@PathVariable("collectionId") collectionId: String,
		@RequestBody(required = false) body: String?,
	): CollectionView {
		val id = collectionId(collectionId)
		return service.rename(id, owner(jwt), CollectionRules.parseName(body, mapper))
	}

	@DeleteMapping("/{collectionId}")
	fun delete(@AuthenticationPrincipal jwt: Jwt, @PathVariable("collectionId") collectionId: String): ResponseEntity<Void> {
		service.delete(collectionId(collectionId), owner(jwt))
		return ResponseEntity.noContent().build()
	}

	@GetMapping("/{collectionId}/items")
	fun items(
		@AuthenticationPrincipal jwt: Jwt,
		@PathVariable("collectionId") collectionId: String,
		@RequestParam("page", required = false) page: String?,
		@RequestParam("size", required = false) size: String?,
	): ItemsPage {
		val id = collectionId(collectionId)
		val (pageValue, sizeValue) = page(page, size)
		return service.items(id, owner(jwt), pageValue, sizeValue)
	}

	@PutMapping("/{collectionId}/items/{experienceId}")
	fun addItem(
		@AuthenticationPrincipal jwt: Jwt,
		@PathVariable("collectionId") collectionId: String,
		@PathVariable("experienceId") experienceId: String,
	): ResponseEntity<Void> {
		val (id, experience) = ids(collectionId, experienceId)
		service.addItem(id, owner(jwt), experience)
		return ResponseEntity.noContent().build()
	}

	@DeleteMapping("/{collectionId}/items/{experienceId}")
	fun removeItem(
		@AuthenticationPrincipal jwt: Jwt,
		@PathVariable("collectionId") collectionId: String,
		@PathVariable("experienceId") experienceId: String,
	): ResponseEntity<Void> {
		val (id, experience) = ids(collectionId, experienceId)
		service.removeItem(id, owner(jwt), experience)
		return ResponseEntity.noContent().build()
	}

	/** O dono é o `sub` do token; um `sub` que não seja UUID é um token inválido. */
	private fun owner(jwt: Jwt): UUID = CollectionRules.parseUuid(jwt.subject)
		?: throw ApiException(HttpStatus.UNAUTHORIZED, "INVALID_ACCESS_TOKEN", "Sessão inválida", "O token de acesso é inválido ou expirou.")

	private fun collectionId(value: String): UUID =
		CollectionRules.parseUuid(value) ?: throw ApiErrors.validation("collectionId", "Identificador de lista inválido.")

	private fun ids(collectionId: String, experienceId: String): Pair<UUID, UUID> {
		val errors = mutableListOf<FieldError>()
		val collection = CollectionRules.parseUuid(collectionId) ?: null.also { errors += FieldError("collectionId", "Identificador de lista inválido.") }
		val experience = CollectionRules.parseUuid(experienceId) ?: null.also { errors += FieldError("experienceId", "Identificador de experiência inválido.") }
		if (errors.isNotEmpty()) throw ApiErrors.validation(errors)
		return collection!! to experience!!
	}

	private fun page(page: String?, size: String?): Pair<Int, Int> {
		val errors = mutableListOf<FieldError>()
		val result = PageResponse.parse(page, size, errors)
		if (errors.isNotEmpty()) throw ApiErrors.validation(errors)
		return result
	}

	private companion object {
		const val IDEMPOTENCY_KEY = "Idempotency-Key"
	}
}
