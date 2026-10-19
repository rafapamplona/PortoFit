package com.example.portofit.experiences.stats

import com.example.portofit.experiences.api.ApiErrors
import com.example.portofit.experiences.api.FieldError
import com.example.portofit.experiences.api.PageResponse
import com.example.portofit.experiences.experience.ExperienceController
import com.example.portofit.experiences.experience.OfferingRef
import com.example.portofit.experiences.experience.PhotoView
import com.example.portofit.experiences.experience.PlaceRef
import com.example.portofit.experiences.experience.SportRef
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Períodos do dashboard em dias UTC (docs/experiences-api.md §14.4). [days] = null → "Sempre". */
enum class StatsPeriod(val days: Long?) { ALL(null), LAST_7_DAYS(7), LAST_30_DAYS(30) }

enum class StatsSort { RECENT, INTERACTIONS, VIEWS }

/** O que as publicações receberam no período; separado do que o autor deu (as estrelas). */
data class ReceivedTotals(
	val likes: Long,
	val comments: Long,
	val interactions: Long,
	val views: Long,
	/** Soma de visualizações contadas (uma por pessoa ou instalação, por publicação e dia UTC): não são pessoas únicas. */
	val viewsRule: String = "ONE_PER_VIEWER_PER_UTC_DAY",
	/** Os comentários do próprio autor contam (batem com o `commentsCount` de cada publicação). */
	val commentsIncludeOwn: Boolean = true,
)

data class StatsResponse(
	val period: StatsPeriod,
	val from: Instant?,
	val to: Instant,
	val generatedAt: Instant,
	val timezone: String,
	val received: ReceivedTotals,
	/** Publicações ativas criadas no período. */
	val publications: Long,
	/** Estrelas que o autor deu aos locais/atividades nas publicações ativas criadas no período (chaves "1"–"5"). */
	val starsGiven: Map<String, Long>,
)

/** Resumo da publicação numa linha do dashboard (sem o texto). */
data class StatsExperience(
	val id: UUID,
	val place: PlaceRef,
	val offering: OfferingRef,
	val sport: SportRef,
	val stars: Int,
	val createdAt: Instant,
	val photo: PhotoView?,
)

data class ExperienceStats(val experience: StatsExperience, val likes: Long, val comments: Long, val interactions: Long, val views: Long)

data class StatsPage(
	val items: List<ExperienceStats>,
	val page: Int,
	val size: Int,
	val totalItems: Long,
	val totalPages: Int,
	val period: StatsPeriod,
	val from: Instant?,
	val to: Instant,
	val generatedAt: Instant,
	val timezone: String,
)

/**
 * Contagens do dashboard, sempre de publicações **ativas** do autor. Gostos e comentários pela data da interação,
 * visualizações pelo dia UTC em que foram contadas, qualquer que seja a data da publicação. Os totais e as linhas usam
 * as mesmas subconsultas, por isso a soma das linhas é igual aos totais. Valores sempre parâmetros.
 */
@Repository
class StatsRepository(private val jdbc: JdbcClient) {

	fun received(authorId: UUID, from: Instant): ReceivedTotals =
		jdbc.sql(
			"""
			SELECT coalesce(sum(lc.n), 0) AS likes, coalesce(sum(cc.n), 0) AS comments, coalesce(sum(vc.n), 0) AS views
			FROM experience e $COUNTS
			WHERE e.author_id = :author AND e.deleted_at IS NULL
			""",
		)
			.param("author", authorId).param("from", Timestamp.from(from))
			.query { rs, _ ->
				val likes = rs.getLong("likes")
				val comments = rs.getLong("comments")
				ReceivedTotals(likes, comments, likes + comments, rs.getLong("views"))
			}
			.single()

	/** Publicações ativas criadas no período e o histograma das estrelas dadas nelas (uma consulta). */
	fun publicationsAndStars(authorId: UUID, from: Instant): Pair<Long, Map<String, Long>> {
		val byStars = jdbc.sql(
			"""
			SELECT stars, count(*) AS n FROM experience
			WHERE author_id = :author AND deleted_at IS NULL AND created_at >= :from
			GROUP BY stars
			""",
		)
			.param("author", authorId).param("from", Timestamp.from(from))
			.query { rs, _ -> rs.getInt("stars") to rs.getLong("n") }
			.list().toMap()
		val histogram = (1..5).associate { it.toString() to (byStars[it] ?: 0L) }
		return histogram.values.sum() to histogram
	}

	fun perExperience(authorId: UUID, from: Instant, sort: StatsSort, page: Int, size: Int): Pair<List<ExperienceStats>, Long> {
		val total = jdbc.sql("SELECT count(*) FROM experience WHERE author_id = :author AND deleted_at IS NULL")
			.param("author", authorId).query(Long::class.javaObjectType).single()
		val orderBy = when (sort) {
			StatsSort.RECENT -> "e.created_at DESC, e.id DESC"
			StatsSort.INTERACTIONS -> "lc.n + cc.n DESC, e.created_at DESC, e.id DESC"
			StatsSort.VIEWS -> "vc.n DESC, e.created_at DESC, e.id DESC"
		}
		val rows = jdbc.sql(
			"""
			SELECT e.id, e.place_id, e.place_name, e.place_demo, e.offering_id, e.offering_name, e.sport_id, e.sport_name,
			       e.stars, e.created_at, e.photo_media_id, m.width AS photo_width, m.height AS photo_height,
			       lc.n AS likes, cc.n AS comments, vc.n AS views
			FROM experience e LEFT JOIN media m ON m.id = e.photo_media_id $COUNTS
			WHERE e.author_id = :author AND e.deleted_at IS NULL
			ORDER BY $orderBy LIMIT :limit OFFSET :offset
			""",
		)
			.param("author", authorId).param("from", Timestamp.from(from))
			.param("limit", size).param("offset", page.toLong() * size)
			.query { rs, _ ->
				val photo = rs.getObject("photo_media_id", UUID::class.java)?.let {
					PhotoView(it, "/api/v1/media/$it", rs.getInt("photo_width"), rs.getInt("photo_height"))
				}
				val likes = rs.getLong("likes")
				val comments = rs.getLong("comments")
				ExperienceStats(
					StatsExperience(
						id = rs.getObject("id", UUID::class.java),
						place = PlaceRef(rs.getObject("place_id", UUID::class.java), rs.getString("place_name"), rs.getBoolean("place_demo")),
						offering = OfferingRef(rs.getObject("offering_id", UUID::class.java), rs.getString("offering_name")),
						sport = SportRef(rs.getString("sport_id"), rs.getString("sport_name")),
						stars = rs.getInt("stars"),
						createdAt = rs.getTimestamp("created_at").toInstant(),
						photo = photo,
					),
					likes, comments, likes + comments, rs.getLong("views"),
				)
			}
			.list()
		return rows to total
	}

	private companion object {
		/** Contagens no período de cada publicação: gostos e comentários ativos pela data, visualizações pelo dia UTC. */
		const val COUNTS = """
			CROSS JOIN LATERAL (SELECT count(*) AS n FROM experience_like l WHERE l.experience_id = e.id AND l.created_at >= :from) lc
			CROSS JOIN LATERAL (SELECT count(*) AS n FROM comment c WHERE c.experience_id = e.id AND c.deleted_at IS NULL AND c.created_at >= :from) cc
			CROSS JOIN LATERAL (SELECT count(*) AS n FROM experience_view v WHERE v.experience_id = e.id AND v.period_start >= :from) vc
		"""
	}
}

/** Dashboard do próprio autor (docs/experiences-api.md §14.4). Uma falha da base de dados dá 503, nunca zeros. */
@Service
class StatsService(private val stats: StatsRepository, private val clock: Clock) {

	private data class Window(val period: StatsPeriod, val from: Instant?, val generatedAt: Instant) {
		/** Limite inferior das consultas: "Sempre" usa a época (nada é anterior). */
		val queryFrom: Instant get() = from ?: Instant.EPOCH
	}

	fun totals(authorId: UUID, period: StatsPeriod): StatsResponse {
		val window = window(period)
		val received = stats.received(authorId, window.queryFrom)
		val (publications, histogram) = stats.publicationsAndStars(authorId, window.queryFrom)
		return StatsResponse(period, window.from, window.generatedAt, window.generatedAt, UTC, received, publications, histogram)
	}

	fun perExperience(authorId: UUID, period: StatsPeriod, sort: StatsSort, page: Int, size: Int): StatsPage {
		val window = window(period)
		val (items, total) = stats.perExperience(authorId, window.queryFrom, sort, page, size)
		val envelope = PageResponse.of(items, page, size, total)
		return StatsPage(items, page, size, total, envelope.totalPages, period, window.from, window.generatedAt, window.generatedAt, UTC)
	}

	/** 7 dias = o de hoje e os 6 anteriores (dias UTC); 30 dias = o de hoje e os 29 anteriores. */
	private fun window(period: StatsPeriod): Window {
		val now = clock.instant()
		val from = period.days?.let { now.truncatedTo(ChronoUnit.DAYS).minus(it - 1, ChronoUnit.DAYS) }
		return Window(period, from, now)
	}

	private companion object {
		const val UTC = "UTC"
	}
}

@RestController
@RequestMapping("/api/v1/me/stats")
class StatsController(private val service: StatsService) {

	@GetMapping
	fun totals(@AuthenticationPrincipal jwt: Jwt, @RequestParam("period", required = false) period: String?): StatsResponse {
		val errors = mutableListOf<FieldError>()
		val periodValue = parsePeriod(period, errors)
		if (errors.isNotEmpty()) throw ApiErrors.validation(errors)
		return service.totals(ExperienceController.viewer(jwt)!!, periodValue)
	}

	@GetMapping("/experiences")
	fun perExperience(
		@AuthenticationPrincipal jwt: Jwt,
		@RequestParam("period", required = false) period: String?,
		@RequestParam("sort", required = false) sort: String?,
		@RequestParam("page", required = false) page: String?,
		@RequestParam("size", required = false) size: String?,
	): StatsPage {
		val errors = mutableListOf<FieldError>()
		val periodValue = parsePeriod(period, errors)
		val sortValue = when (sort?.trim()?.takeIf { it.isNotEmpty() }) {
			null, "recent" -> StatsSort.RECENT
			"interactions" -> StatsSort.INTERACTIONS
			"views" -> StatsSort.VIEWS
			else -> StatsSort.RECENT.also { errors += FieldError("sort", "A ordenação deve ser recent, interactions ou views.") }
		}
		val (pageValue, sizeValue) = PageResponse.parse(page, size, errors)
		if (errors.isNotEmpty()) throw ApiErrors.validation(errors)
		return service.perExperience(ExperienceController.viewer(jwt)!!, periodValue, sortValue, pageValue, sizeValue)
	}

	private fun parsePeriod(value: String?, errors: MutableList<FieldError>): StatsPeriod {
		val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return StatsPeriod.ALL
		return StatsPeriod.entries.firstOrNull { it.name == text }
			?: StatsPeriod.ALL.also { errors += FieldError("period", "O período deve ser ALL, LAST_7_DAYS ou LAST_30_DAYS.") }
	}
}
