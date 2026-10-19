package com.example.portofit.users.user

import com.example.portofit.users.api.ApiErrors
import com.example.portofit.users.api.ApiException
import com.example.portofit.users.api.PageResponse
import com.example.portofit.users.config.FollowProperties
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/** Pessoa numa lista de seguidores ou seguidos: só dados públicos e a data da relação. */
data class FollowEntry(
	val userId: UUID,
	val username: String,
	val description: String?,
	val since: Instant,
)

data class FollowCounts(val followers: Long, val following: Long)

/** Tabela follow. Seguir e deixar de seguir são idempotentes. */
@Repository
class FollowRepository(private val jdbc: JdbcClient) {

	/** Cria a relação se ainda não existir; pedidos repetidos ou simultâneos não criam duplicados. */
	fun follow(followerId: UUID, followedId: UUID) {
		jdbc.sql(
			"""
			INSERT INTO follow (follower_id, followed_id) VALUES (:follower, :followed)
			ON CONFLICT (follower_id, followed_id) DO NOTHING
			""",
		).param("follower", followerId).param("followed", followedId).update()
	}

	/** Bloqueia a linha do seguidor até ao fim da transação (seguir em série, para o limite não ser ultrapassado). */
	fun lockFollower(followerId: UUID) {
		jdbc.sql("SELECT id FROM app_user WHERE id = :id FOR UPDATE").param("id", followerId).query(UUID::class.java).optional()
	}

	fun followingCount(followerId: UUID): Long =
		jdbc.sql("SELECT count(*) FROM follow WHERE follower_id = :id").param("id", followerId).query(Long::class.javaObjectType).single()

	/** Todos os ids seguidos por [followerId], mais recentes primeiro (contexto do feed; no máximo o limite de produto). */
	fun followingIds(followerId: UUID): List<UUID> =
		jdbc.sql("SELECT followed_id FROM follow WHERE follower_id = :id ORDER BY created_at DESC, followed_id")
			.param("id", followerId).query { rs, _ -> rs.getObject(1, UUID::class.java) }.list()

	fun unfollow(followerId: UUID, followedId: UUID) {
		jdbc.sql("DELETE FROM follow WHERE follower_id = :follower AND followed_id = :followed")
			.param("follower", followerId).param("followed", followedId).update()
	}

	fun isFollowing(followerId: UUID, followedId: UUID): Boolean =
		jdbc.sql("SELECT EXISTS (SELECT 1 FROM follow WHERE follower_id = :follower AND followed_id = :followed)")
			.param("follower", followerId).param("followed", followedId).query(Boolean::class.javaObjectType).single()

	fun counts(userId: UUID): FollowCounts =
		jdbc.sql(
			"""
			SELECT (SELECT count(*) FROM follow WHERE followed_id = :id) AS followers,
			       (SELECT count(*) FROM follow WHERE follower_id = :id) AS following
			""",
		).param("id", userId).query { rs, _ -> FollowCounts(rs.getLong("followers"), rs.getLong("following")) }.single()

	/** Seguidores de [userId], relação mais antiga primeiro, desempate pelo id. */
	fun followers(userId: UUID, page: Int, size: Int): Pair<List<FollowEntry>, Long> =
		list(userId, page, size, matchColumn = "followed_id", otherColumn = "follower_id")

	/** Pessoas que [userId] segue, relação mais antiga primeiro, desempate pelo id. */
	fun following(userId: UUID, page: Int, size: Int): Pair<List<FollowEntry>, Long> =
		list(userId, page, size, matchColumn = "follower_id", otherColumn = "followed_id")

	// As colunas vêm só das duas funções acima (texto fixo); os valores do pedido são sempre parâmetros.
	private fun list(userId: UUID, page: Int, size: Int, matchColumn: String, otherColumn: String): Pair<List<FollowEntry>, Long> {
		val total = jdbc.sql("SELECT count(*) FROM follow WHERE $matchColumn = :id")
			.param("id", userId).query(Long::class.javaObjectType).single()
		val rows = jdbc.sql(
			"""
			SELECT u.id, u.username, u.description, f.created_at
			FROM follow f JOIN app_user u ON u.id = f.$otherColumn
			WHERE f.$matchColumn = :id
			ORDER BY f.created_at, u.id
			LIMIT :limit OFFSET :offset
			""",
		)
			.param("id", userId).param("limit", size).param("offset", page.toLong() * size)
			.query { rs, _ ->
				FollowEntry(
					rs.getObject("id", UUID::class.java), rs.getString("username"), rs.getString("description"),
					rs.getTimestamp("created_at").toInstant(),
				)
			}
			.list()
		return rows to total
	}
}

@Service
class FollowService(
	private val follows: FollowRepository,
	private val users: UserRepository,
	private val properties: FollowProperties,
	private val transactions: TransactionTemplate,
) {

	/**
	 * Idempotente. Limite de produto (docs/users-api.md §3.9): seguir alguém novo com o máximo já atingido → 409;
	 * seguir alguém já seguido continua 204. Contagem e inserção na mesma transação, com o seguidor bloqueado.
	 */
	fun follow(followerId: UUID, followedId: UUID) {
		checkTarget(followerId, followedId)
		try {
			transactions.executeWithoutResult {
				follows.lockFollower(followerId)
				if (follows.isFollowing(followerId, followedId)) return@executeWithoutResult
				if (follows.followingCount(followerId) >= properties.maxFollowing) throw ApiErrors.followLimitReached(properties.maxFollowing)
				follows.follow(followerId, followedId)
			}
		} catch (e: DataIntegrityViolationException) {
			// A pessoa foi apagada entre a verificação e a inserção (chave estrangeira).
			throw ApiErrors.notFound()
		}
	}

	fun unfollow(followerId: UUID, followedId: UUID) {
		checkTarget(followerId, followedId)
		follows.unfollow(followerId, followedId)
	}

	fun followers(userId: UUID, page: String?, size: String?) = page(userId, page, size, follows::followers)

	fun following(userId: UUID, page: String?, size: String?) = page(userId, page, size, follows::following)

	private fun page(
		userId: UUID,
		page: String?,
		size: String?,
		query: (UUID, Int, Int) -> Pair<List<FollowEntry>, Long>,
	): PageResponse<FollowEntry> {
		val (pageValue, sizeValue) = PageResponse.parse(page, size)
		if (users.findById(userId) == null) throw ApiErrors.notFound()
		val (items, total) = query(userId, pageValue, sizeValue)
		return PageResponse.of(items, pageValue, sizeValue, total)
	}

	private fun checkTarget(followerId: UUID, followedId: UUID) {
		if (followerId == followedId) {
			throw ApiException(HttpStatus.BAD_REQUEST, "CANNOT_FOLLOW_SELF", "Pedido inválido", "Não te podes seguir a ti próprio.")
		}
		if (users.findById(followedId) == null) throw ApiErrors.notFound()
	}
}

/** Seguir exige sessão (o seguidor vem sempre do token); as listas são públicas. */
@RestController
@RequestMapping("/api/v1/users/{userId}")
class FollowController(private val follows: FollowService) {

	@PutMapping("/follow")
	fun follow(@AuthenticationPrincipal jwt: Jwt, @PathVariable("userId") userId: String): ResponseEntity<Void> {
		follows.follow(jwt.userId(), parseUserId(userId))
		return ResponseEntity.noContent().build()
	}

	@DeleteMapping("/follow")
	fun unfollow(@AuthenticationPrincipal jwt: Jwt, @PathVariable("userId") userId: String): ResponseEntity<Void> {
		follows.unfollow(jwt.userId(), parseUserId(userId))
		return ResponseEntity.noContent().build()
	}

	@GetMapping("/followers")
	fun followers(
		@PathVariable("userId") userId: String,
		@RequestParam("page", required = false) page: String?,
		@RequestParam("size", required = false) size: String?,
	) = follows.followers(parseUserId(userId), page, size)

	@GetMapping("/following")
	fun following(
		@PathVariable("userId") userId: String,
		@RequestParam("page", required = false) page: String?,
		@RequestParam("size", required = false) size: String?,
	) = follows.following(parseUserId(userId), page, size)
}

/** UUID canónico do caminho; caso contrário 400. */
fun parseUserId(value: String): UUID =
	runCatching { UUID.fromString(value) }.getOrNull()?.takeIf { it.toString().equals(value, ignoreCase = true) }
		?: throw ApiErrors.validation("userId", "Identificador de utilizador inválido.")
