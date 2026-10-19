package com.example.portofit.data.repository

import com.example.portofit.data.auth.SessionManager
import com.example.portofit.data.auth.SessionState
import com.example.portofit.data.model.ExperienceStats
import com.example.portofit.data.model.FeedFallbackReason
import com.example.portofit.data.model.FeedItem
import com.example.portofit.data.model.FeedMatch
import com.example.portofit.data.model.FeedMode
import com.example.portofit.data.model.FeedPage
import com.example.portofit.data.model.Page
import com.example.portofit.data.model.StatsPage
import com.example.portofit.data.model.StatsPeriod
import com.example.portofit.data.model.StatsSort
import com.example.portofit.data.model.StatsTotals
import com.example.portofit.data.model.StatsWindow
import com.example.portofit.data.remote.ExperiencesApi
import com.example.portofit.data.remote.FeedPageDto
import com.example.portofit.data.remote.StatsDto
import com.example.portofit.data.remote.StatsPageDto
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import retrofit2.Response
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Resultado de uma leitura que exige sessão. [SessionEnded] = é preciso entrar de novo (sem sessão, ou renovação
 * recusada); [SessionUnverified] = a sessão existe mas não foi possível confirmá-la agora (falha temporária).
 */
sealed interface SessionResult<out T> {
    data class Success<T>(val value: T) : SessionResult<T>
    data class Failure(val error: DataError) : SessionResult<Nothing>
    data object SessionEnded : SessionResult<Nothing>
    data object SessionUnverified : SessionResult<Nothing>
}

/** Feeds e dashboard (E4, docs/experiences-api.md §14). */
interface DiscoverRepository {
    /** Público; com sessão acrescenta `likedByMe`. */
    suspend fun popularFeed(sportId: String?, page: Int, pageSize: Int): DataResult<FeedPage>

    suspend fun personalFeed(sportId: String?, page: Int, pageSize: Int): SessionResult<FeedPage>

    suspend fun stats(period: StatsPeriod): SessionResult<StatsTotals>

    suspend fun statsExperiences(period: StatsPeriod, sort: StatsSort, page: Int, pageSize: Int): SessionResult<StatsPage>
}

/**
 * Leituras com o access token do [SessionManager]: um 401 renova uma vez e repete. O feed popular, sem token disponível,
 * é pedido como visitante (como o detalhe). Respostas com `mode` desconhecido são recusadas: nunca se apresenta como
 * personalizado algo que não se sabe o que é.
 */
class NetworkDiscoverRepository(private val api: ExperiencesApi, private val session: SessionManager) : DiscoverRepository {

    override suspend fun popularFeed(sportId: String?, page: Int, pageSize: Int): DataResult<FeedPage> = apiCall {
        val token = if (session.state.value is SessionState.SignedIn) session.accessToken() else null
        var response = api.popularFeed(sportId, page, pageSize, token?.let { "Bearer $it" })
        if (response.code() == 401 && token != null) {
            val fresh = session.accessTokenAfterUnauthorized(token)
            response = api.popularFeed(sportId, page, pageSize, fresh?.let { "Bearer $it" })
        }
        if (!response.isSuccessful) throw retrofit2.HttpException(response)
        response.body()!!.toModel()
    }

    override suspend fun personalFeed(sportId: String?, page: Int, pageSize: Int) =
        authorizedRead({ api.personalFeed(it, sportId, page, pageSize) }) { it.toModel() }

    override suspend fun stats(period: StatsPeriod) = authorizedRead({ api.stats(it, period.apiValue) }) { it.toModel() }

    override suspend fun statsExperiences(period: StatsPeriod, sort: StatsSort, page: Int, pageSize: Int) =
        authorizedRead({ api.statsExperiences(it, period.apiValue, sort.apiValue, page, pageSize) }) { it.toModel() }

    private suspend fun <D, T> authorizedRead(call: suspend (String) -> Response<D>, convert: (D) -> T): SessionResult<T> =
        try {
            val token = session.accessToken() ?: return sessionProblem()
            var response = call("Bearer $token")
            if (response.code() == 401) {
                val fresh = session.accessTokenAfterUnauthorized(token) ?: return sessionProblem()
                response = call("Bearer $fresh")
            }
            when {
                response.isSuccessful -> SessionResult.Success(convert(response.body()!!))
                // Um token acabado de renovar recusado de novo: a sessão não serve.
                response.code() == 401 -> SessionResult.SessionEnded
                else -> SessionResult.Failure(
                    when (response.code()) {
                        404 -> DataError.NOT_FOUND
                        400 -> DataError.INVALID_REQUEST
                        in 500..599 -> DataError.SERVER
                        else -> DataError.UNEXPECTED
                    },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            SessionResult.Failure(DataError.NETWORK)
        } catch (e: SerializationException) {
            SessionResult.Failure(DataError.UNEXPECTED)
        } catch (e: IllegalArgumentException) {
            SessionResult.Failure(DataError.UNEXPECTED)
        }

    private fun sessionProblem(): SessionResult<Nothing> =
        if (session.state.value is SessionState.SignedIn) SessionResult.SessionUnverified else SessionResult.SessionEnded
}

private fun instant(value: String): Instant = try {
    Instant.parse(value)
} catch (e: DateTimeParseException) {
    throw SerializationException("Data inválida")
}

fun FeedPageDto.toModel(): FeedPage {
    val feedMode = FeedMode.entries.firstOrNull { it.name == mode } ?: throw SerializationException("Modo desconhecido")
    val reason = when (feedMode) {
        FeedMode.PERSONAL -> null
        // Um motivo desconhecido é tratado como indisponibilidade (nunca como "sem preferências").
        else -> FeedFallbackReason.entries.firstOrNull { it.name == fallbackReason }
            ?: FeedFallbackReason.USERS_UNAVAILABLE.takeIf { feedMode == FeedMode.POPULAR_FALLBACK }
    }
    val feedItems = items.map { dto ->
        FeedItem(dto.toModel(), dto.matchedBy.orEmpty().mapNotNull { m -> FeedMatch.entries.firstOrNull { it.name == m } }.toSet())
    }
    return FeedPage(Page(feedItems, page, totalItems, totalPages), feedMode, reason, recentSince?.let(::instant), instant(generatedAt))
}

private fun window(period: String, from: String?, to: String, generatedAt: String) = StatsWindow(
    StatsPeriod.entries.firstOrNull { it.apiValue == period } ?: throw SerializationException("Período desconhecido"),
    from?.let(::instant),
    instant(to),
    instant(generatedAt),
)

fun StatsDto.toModel() = StatsTotals(
    window = window(period, from, to, generatedAt),
    publications = publications,
    likes = received.likes,
    comments = received.comments,
    interactions = received.interactions,
    views = received.views,
    commentsIncludeOwn = received.commentsIncludeOwn,
    starsGiven = (1..5).associateWith { starsGiven[it.toString()] ?: 0L },
)

fun StatsPageDto.toModel() = StatsPage(
    Page(
        items.map {
            ExperienceStats(
                experienceId = it.experience.id,
                placeName = it.experience.place.name,
                placeDemo = it.experience.place.demo,
                offeringName = it.experience.offering.name,
                sportName = it.experience.sport.name,
                stars = it.experience.stars,
                createdAt = instant(it.experience.createdAt),
                likes = it.likes,
                comments = it.comments,
                interactions = it.interactions,
                views = it.views,
            )
        },
        page, totalItems, totalPages,
    ),
    window(period, from, to, generatedAt),
)

class UnconfiguredDiscoverRepository : DiscoverRepository {
    override suspend fun popularFeed(sportId: String?, page: Int, pageSize: Int) = DataResult.Failure(DataError.NOT_CONFIGURED)
    override suspend fun personalFeed(sportId: String?, page: Int, pageSize: Int) = SessionResult.Failure(DataError.NOT_CONFIGURED)
    override suspend fun stats(period: StatsPeriod) = SessionResult.Failure(DataError.NOT_CONFIGURED)
    override suspend fun statsExperiences(period: StatsPeriod, sort: StatsSort, page: Int, pageSize: Int) = SessionResult.Failure(DataError.NOT_CONFIGURED)
}
