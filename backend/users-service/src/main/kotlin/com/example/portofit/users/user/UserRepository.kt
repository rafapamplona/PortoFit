package com.example.portofit.users.user

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

data class UserRow(
	val id: UUID,
	val username: String,
	val email: String,
	val passwordHash: String,
	val description: String?,
	val createdAt: Instant,
)

data class UserSummaryRow(
	val id: UUID,
	val username: String,
	val description: String?,
)

/** Acesso às tabelas app_user e user_interest com consultas parametrizadas. */
@Repository
class UserRepository(private val jdbc: JdbcClient) {

	fun insert(id: UUID, username: String, email: String, passwordHash: String) {
		jdbc.sql("INSERT INTO app_user (id, username, email, password_hash) VALUES (:id, :username, :email, :hash)")
			.param("id", id).param("username", username).param("email", email).param("hash", passwordHash)
			.update()
	}

	fun usernameExists(username: String): Boolean =
		jdbc.sql("SELECT EXISTS (SELECT 1 FROM app_user WHERE username = :username)")
			.param("username", username).query(Boolean::class.javaObjectType).single()

	fun emailExists(email: String): Boolean =
		jdbc.sql("SELECT EXISTS (SELECT 1 FROM app_user WHERE email = :email)")
			.param("email", email).query(Boolean::class.javaObjectType).single()

	fun findByEmail(email: String): UserRow? =
		jdbc.sql("SELECT * FROM app_user WHERE email = :email").param("email", email)
			.query { rs, _ -> rs.toUserRow() }.optional().orElse(null)

	fun findById(id: UUID): UserRow? =
		jdbc.sql("SELECT * FROM app_user WHERE id = :id").param("id", id)
			.query { rs, _ -> rs.toUserRow() }.optional().orElse(null)

	fun updateDescription(id: UUID, description: String?) {
		jdbc.sql("UPDATE app_user SET description = :description, updated_at = now() WHERE id = :id")
			.param("id", id).param("description", description).update()
	}

	fun interests(id: UUID): List<String> =
		jdbc.sql("SELECT sport_id FROM user_interest WHERE user_id = :id ORDER BY position")
			.param("id", id).query(String::class.java).list().filterNotNull()

	/** Substitui a lista completa, mantendo a ordem enviada. Chamar dentro de uma transação. */
	fun replaceInterests(id: UUID, interests: List<String>) {
		jdbc.sql("DELETE FROM user_interest WHERE user_id = :id").param("id", id).update()
		interests.forEachIndexed { index, sportId ->
			jdbc.sql("INSERT INTO user_interest (user_id, sport_id, position) VALUES (:id, :sport, :position)")
				.param("id", id).param("sport", sportId).param("position", index).update()
		}
		jdbc.sql("UPDATE app_user SET updated_at = now() WHERE id = :id").param("id", id).update()
	}

	/**
	 * Pesquisa no username (já em minúsculas). Ordem total: exato, começa por, contém; depois username e id.
	 * Os caracteres especiais do LIKE escritos pelo utilizador são procurados literalmente.
	 */
	fun search(query: String, page: Int, size: Int): Pair<List<UserSummaryRow>, Long> {
		val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
		val contains = "%$escaped%"
		val total = jdbc.sql("SELECT count(*) FROM app_user WHERE username LIKE :contains ESCAPE '\\'")
			.param("contains", contains).query(Long::class.javaObjectType).single()
		val rows = jdbc.sql(
			"""
			SELECT id, username, description FROM app_user
			WHERE username LIKE :contains ESCAPE '\'
			ORDER BY CASE WHEN username = :exact THEN 0 WHEN username LIKE :prefix ESCAPE '\' THEN 1 ELSE 2 END,
			         username, id
			LIMIT :limit OFFSET :offset
			""",
		)
			.param("contains", contains).param("exact", query).param("prefix", "$escaped%")
			.param("limit", size).param("offset", page.toLong() * size)
			.query { rs, _ -> UserSummaryRow(rs.getObject("id", UUID::class.java), rs.getString("username"), rs.getString("description")) }
			.list()
		return rows to total
	}

	private fun ResultSet.toUserRow() = UserRow(
		id = getObject("id", UUID::class.java),
		username = getString("username"),
		email = getString("email"),
		passwordHash = getString("password_hash"),
		description = getString("description"),
		createdAt = getTimestamp("created_at").toInstant(),
	)
}
