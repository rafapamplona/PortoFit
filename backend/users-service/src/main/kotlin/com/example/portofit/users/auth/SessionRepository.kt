package com.example.portofit.users.auth

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** Token consumido com sucesso: dados para emitir o seguinte da mesma família. */
data class ConsumedToken(val userId: UUID, val familyId: UUID, val expiresAt: Instant)

/** Tabela refresh_token. Só guarda hashes. */
@Repository
class SessionRepository(private val jdbc: JdbcClient) {

	fun insert(userId: UUID, familyId: UUID, tokenHash: String, expiresAt: Instant) {
		jdbc.sql(
			"""
			INSERT INTO refresh_token (id, user_id, family_id, token_hash, expires_at)
			VALUES (:id, :userId, :familyId, :hash, :expiresAt)
			""",
		)
			.param("id", UUID.randomUUID()).param("userId", userId).param("familyId", familyId)
			.param("hash", tokenHash).param("expiresAt", Timestamp.from(expiresAt))
			.update()
	}

	/**
	 * Consome o token numa única instrução condicional: só um pedido concorrente o consegue fazer.
	 * Devolve null se o token não existir, já tiver sido usado, revogado ou expirado.
	 */
	fun consume(tokenHash: String): ConsumedToken? =
		jdbc.sql(
			"""
			UPDATE refresh_token SET used_at = now()
			WHERE token_hash = :hash AND used_at IS NULL AND revoked_at IS NULL AND expires_at > now()
			RETURNING user_id, family_id, expires_at
			""",
		)
			.param("hash", tokenHash)
			.query { rs, _ ->
				ConsumedToken(
					rs.getObject("user_id", UUID::class.java),
					rs.getObject("family_id", UUID::class.java),
					rs.getTimestamp("expires_at").toInstant(),
				)
			}
			.optional().orElse(null)

	/** Se o token já tinha sido consumido, é uma reutilização: revoga a família inteira e devolve-a. */
	fun revokeFamilyIfReused(tokenHash: String): UUID? {
		val family = jdbc.sql("SELECT family_id FROM refresh_token WHERE token_hash = :hash AND used_at IS NOT NULL")
			.param("hash", tokenHash).query(UUID::class.java).optional().orElse(null) ?: return null
		revokeFamily(family)
		return family
	}

	/** Logout: revoga a família do token, se existir. */
	fun revokeFamilyOf(tokenHash: String) {
		jdbc.sql(
			"""
			UPDATE refresh_token SET revoked_at = now()
			WHERE revoked_at IS NULL AND family_id = (SELECT family_id FROM refresh_token WHERE token_hash = :hash)
			""",
		).param("hash", tokenHash).update()
	}

	private fun revokeFamily(familyId: UUID) {
		jdbc.sql("UPDATE refresh_token SET revoked_at = now() WHERE family_id = :family AND revoked_at IS NULL")
			.param("family", familyId).update()
	}

	/**
	 * Apaga sessões expiradas ou revogadas há mais de um dia. Tokens já usados de sessões ainda válidas
	 * ficam guardados: são precisos para detetar reutilização.
	 */
	fun deleteFinished(): Int =
		jdbc.sql(
			"""
			DELETE FROM refresh_token
			WHERE expires_at < now() - interval '1 day' OR revoked_at < now() - interval '1 day'
			""",
		).update()
}

@Component
class SessionCleanup(private val sessions: SessionRepository) {
	@Scheduled(initialDelayString = "PT10M", fixedDelayString = "PT6H")
	fun run() {
		sessions.deleteFinished()
	}
}
