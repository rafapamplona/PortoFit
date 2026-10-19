package com.example.portofit.experiences.experience

import com.example.portofit.experiences.catalog.OfferingInfo
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Linha da tabela experience (inclui as eliminadas, que só têm ids e datas). */
data class ExperienceRow(
	val id: UUID,
	val authorId: UUID,
	val authorUsername: String,
	val placeId: UUID,
	val placeName: String,
	val placeDemo: Boolean,
	val offeringId: UUID,
	val offeringName: String,
	val sportId: String,
	val sportName: String,
	val text: String?,
	val stars: Int?,
	val createdAt: Instant,
	val editedAt: Instant?,
	val deletedAt: Instant?,
	val photoMediaId: UUID? = null,
	val photoWidth: Int? = null,
	val photoHeight: Int? = null,
) {
	val deleted: Boolean get() = deletedAt != null
}

/** Tentativa de publicação já registada (docs/experiences-api.md §2.2). */
data class PublishRequestRow(val requestHash: String, val experienceId: UUID)

enum class ExperienceSort { RECENT, STARS }

data class ExperienceCriteria(
	val placeId: UUID?,
	val offeringId: UUID?,
	val sportId: String?,
	val authorId: UUID?,
	val text: String?,
	val sort: ExperienceSort,
	val page: Int,
	val size: Int,
)

/**
 * Acesso a experiências com Spring JDBC. Os valores do pedido são sempre parâmetros; o texto SQL só é
 * montado a partir de fragmentos fixos deste ficheiro.
 */
@Repository
class ExperienceRepository(private val jdbc: JdbcClient) {

	fun findPublishRequest(authorId: UUID, key: UUID): PublishRequestRow? =
		jdbc.sql("SELECT request_hash, experience_id FROM publish_request WHERE author_id = :author AND idempotency_key = :key")
			.param("author", authorId).param("key", key)
			.query { rs, _ -> PublishRequestRow(rs.getString("request_hash"), rs.getObject("experience_id", UUID::class.java)) }
			.optional().orElse(null)

	/**
	 * Regista a tentativa e cria a experiência (sem fotografia), na transação em curso; devolve o id. Devolve null se a chave já existir:
	 * com outra transação a usar a mesma chave, a inserção espera que ela termine (chave primária) e, se
	 * ela confirmar, não insere nada.
	 */
	fun insertWithKey(authorId: UUID, authorUsername: String, key: UUID, hash: String, input: PublishInput, offering: OfferingInfo): UUID? {
		val id = jdbc.sql("SELECT uuidv7()").query(UUID::class.java).single()
		val registered = jdbc.sql(
			"""
			INSERT INTO publish_request (author_id, idempotency_key, request_hash, experience_id)
			VALUES (:author, :key, :hash, :id)
			ON CONFLICT (author_id, idempotency_key) DO NOTHING
			""",
		).param("author", authorId).param("key", key).param("hash", hash).param("id", id).update()
		if (registered == 0) return null

		jdbc.sql(
			"""
			INSERT INTO experience (id, author_id, author_username, place_id, place_name, place_demo,
			                        offering_id, offering_name, sport_id, sport_name, text, stars)
			VALUES (:id, :author, :username, :placeId, :placeName, :placeDemo,
			        :offeringId, :offeringName, :sportId, :sportName, :text, :stars)
			""",
		)
			.param("id", id).param("author", authorId).param("username", authorUsername)
			.param("placeId", offering.placeId).param("placeName", offering.placeName).param("placeDemo", offering.placeDemo)
			.param("offeringId", offering.id).param("offeringName", offering.name)
			.param("sportId", offering.sportId).param("sportName", offering.sportName)
			.param("text", input.text).param("stars", input.stars)
			.update()
		return id
	}

	/** Qualquer experiência, incluindo eliminadas; com [lock], bloqueia a linha até ao fim da transação. */
	fun findAny(id: UUID, lock: Boolean = false): ExperienceRow? =
		// FOR UPDATE OF e: bloqueia só a experiência (a fotografia vem de um LEFT JOIN).
		jdbc.sql("$SELECT_WITH_PHOTO WHERE e.id = :id" + if (lock) " FOR UPDATE OF e" else "")
			.param("id", id)
			.query { rs, _ -> rs.toRow() }
			.optional().orElse(null)

	/** Grava os valores finais (texto, estrelas e fotografia atual) e marca a edição. */
	fun update(id: UUID, text: String, stars: Int, photoMediaId: UUID?) {
		jdbc.sql("UPDATE experience SET text = :text, stars = :stars, photo_media_id = :photo, edited_at = now() WHERE id = :id")
			.param("id", id).param("text", text).param("stars", stars).param("photo", photoMediaId)
			.update()
	}

	/** Fotografia de uma experiência acabada de criar (sem marcar edição). */
	fun setPhoto(id: UUID, photoMediaId: UUID) {
		jdbc.sql("UPDATE experience SET photo_media_id = :photo WHERE id = :id").param("id", id).param("photo", photoMediaId).update()
	}

	/** Eliminação com marca: o conteúdo é apagado e ficam só os ids e as datas (docs/experiences-api.md §4.5). */
	fun markDeleted(id: UUID) {
		jdbc.sql("UPDATE experience SET deleted_at = now(), text = NULL, stars = NULL, photo_media_id = NULL WHERE id = :id AND deleted_at IS NULL")
			.param("id", id).update()
	}

	fun search(criteria: ExperienceCriteria): Pair<List<ExperienceRow>, Long> {
		val params = mutableMapOf<String, Any>()
		val conditions = mutableListOf("e.deleted_at IS NULL")
		criteria.placeId?.let { params["placeId"] = it; conditions += "e.place_id = :placeId" }
		criteria.offeringId?.let { params["offeringId"] = it; conditions += "e.offering_id = :offeringId" }
		criteria.sportId?.let { params["sportId"] = it; conditions += "e.sport_id = :sportId" }
		criteria.authorId?.let { params["authorId"] = it; conditions += "e.author_id = :authorId" }
		criteria.text?.let { text ->
			params["pattern"] = "%" + escapeLike(text) + "%"
			// Texto e cópias dos nomes, sem maiúsculas nem acentos (docs/experiences-api.md §4.2).
			conditions += """
				(portofit_normalize(e.text) LIKE portofit_normalize(:pattern) ESCAPE '\'
				 OR portofit_normalize(e.place_name) LIKE portofit_normalize(:pattern) ESCAPE '\'
				 OR portofit_normalize(e.offering_name) LIKE portofit_normalize(:pattern) ESCAPE '\'
				 OR portofit_normalize(e.sport_name) LIKE portofit_normalize(:pattern) ESCAPE '\')
			"""
		}
		val where = conditions.joinToString(" AND ", prefix = "WHERE ")
		val total = jdbc.sql("SELECT count(*) FROM experience e $where").params(params).query(Long::class.javaObjectType).single()

		// Ordenações totais (terminam no id): a mesma consulta devolve sempre a mesma ordem.
		val orderBy = when (criteria.sort) {
			ExperienceSort.RECENT -> "e.created_at DESC, e.id DESC"
			ExperienceSort.STARS -> "e.stars DESC, e.created_at DESC, e.id DESC"
		}
		val rows = jdbc.sql("$SELECT_WITH_PHOTO $where ORDER BY $orderBy LIMIT :limit OFFSET :offset")
			.params(params)
			.param("limit", criteria.size)
			.param("offset", criteria.page.toLong() * criteria.size)
			.query { rs, _ -> rs.toRow() }
			.list()
		return rows to total
	}

	/** Apaga os registos de tentativas mais antigos do que [retention]; devolve quantos. */
	fun deletePublishRequestsOlderThan(retention: Duration): Int =
		jdbc.sql("DELETE FROM publish_request WHERE created_at < now() - CAST(:seconds || ' seconds' AS interval)")
			.param("seconds", retention.toSeconds().toString())
			.update()

	private fun ResultSet.toRow() = ExperienceRow(
		id = getObject("id", UUID::class.java),
		authorId = getObject("author_id", UUID::class.java),
		authorUsername = getString("author_username"),
		placeId = getObject("place_id", UUID::class.java),
		placeName = getString("place_name"),
		placeDemo = getBoolean("place_demo"),
		offeringId = getObject("offering_id", UUID::class.java),
		offeringName = getString("offering_name"),
		sportId = getString("sport_id"),
		sportName = getString("sport_name"),
		text = getString("text"),
		stars = getInt("stars").takeUnless { wasNull() },
		createdAt = getTimestamp("created_at").toInstant(),
		editedAt = getTimestamp("edited_at")?.toInstant(),
		deletedAt = getTimestamp("deleted_at")?.toInstant(),
		photoMediaId = getObject("photo_media_id", UUID::class.java),
		photoWidth = getInt("photo_width").takeUnless { wasNull() },
		photoHeight = getInt("photo_height").takeUnless { wasNull() },
	)

	private companion object {
		const val SELECT_WITH_PHOTO =
			"SELECT e.*, m.width AS photo_width, m.height AS photo_height FROM experience e LEFT JOIN media m ON m.id = e.photo_media_id"

		/** Os caracteres especiais do LIKE (\ % _) escritos pelo utilizador são procurados literalmente. */
		fun escapeLike(text: String): String = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
	}
}
