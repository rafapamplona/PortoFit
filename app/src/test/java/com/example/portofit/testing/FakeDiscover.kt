package com.example.portofit.testing

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
import com.example.portofit.data.repository.DataResult
import com.example.portofit.data.repository.DiscoverRepository
import com.example.portofit.data.repository.SessionResult
import java.time.Instant

data class FeedCall(val personal: Boolean, val sportId: String?, val page: Int)

data class StatsCall(val period: StatsPeriod, val sort: StatsSort?, val page: Int?)

/** Feeds e dashboard controlados pelos testes (cada resposta é uma função, que pode suspender). */
class FakeDiscoverRepository : DiscoverRepository {
    val feedCalls = mutableListOf<FeedCall>()
    val statsCalls = mutableListOf<StatsCall>()

    var onPopular: suspend (FeedCall) -> DataResult<FeedPage> = { DataResult.Success(feedPage(it.page, 1, FeedMode.POPULAR)) }
    var onPersonal: suspend (FeedCall) -> SessionResult<FeedPage> = { SessionResult.Success(feedPage(it.page, 1, FeedMode.PERSONAL)) }
    var onStats: suspend (StatsCall) -> SessionResult<StatsTotals> = { SessionResult.Success(totals(it.period)) }
    var onStatsPage: suspend (StatsCall) -> SessionResult<StatsPage> = { SessionResult.Success(statsPage(it.period, it.page ?: 0, 1)) }

    override suspend fun popularFeed(sportId: String?, page: Int, pageSize: Int): DataResult<FeedPage> {
        val call = FeedCall(false, sportId, page)
        feedCalls += call
        return onPopular(call)
    }

    override suspend fun personalFeed(sportId: String?, page: Int, pageSize: Int): SessionResult<FeedPage> {
        val call = FeedCall(true, sportId, page)
        feedCalls += call
        return onPersonal(call)
    }

    override suspend fun stats(period: StatsPeriod): SessionResult<StatsTotals> {
        val call = StatsCall(period, null, null)
        statsCalls += call
        return onStats(call)
    }

    override suspend fun statsExperiences(period: StatsPeriod, sort: StatsSort, page: Int, pageSize: Int): SessionResult<StatsPage> {
        val call = StatsCall(period, sort, page)
        statsCalls += call
        return onStatsPage(call)
    }

    companion object {
        val GENERATED: Instant = Instant.parse("2026-10-19T21:30:00Z")

        /** Página [page] de [totalPages], com experiências "<prefixo><página>-<n>". */
        fun feedPage(
            page: Int,
            totalPages: Int,
            mode: FeedMode,
            reason: FeedFallbackReason? = null,
            prefix: String = "f",
            perPage: Int = 2,
        ) = FeedPage(
            Page(
                (1..perPage).map {
                    FeedItem(
                        FakeExperiencesRepository.experience("$prefix$page-$it"),
                        if (mode == FeedMode.PERSONAL) setOf(FeedMatch.FOLLOWING) else emptySet(),
                    )
                },
                page,
                (totalPages * perPage).toLong(),
                totalPages,
            ),
            mode,
            reason,
            if (mode == FeedMode.PERSONAL) null else Instant.parse("2026-09-19T00:00:00Z"),
            GENERATED,
        )

        fun window(period: StatsPeriod, from: Instant? = fromOf(period)) = StatsWindow(period, from, GENERATED, GENERATED)

        private fun fromOf(period: StatsPeriod): Instant? = when (period) {
            StatsPeriod.ALL -> null
            StatsPeriod.LAST_7_DAYS -> Instant.parse("2026-10-13T00:00:00Z")
            StatsPeriod.LAST_30_DAYS -> Instant.parse("2026-09-20T00:00:00Z")
        }

        fun totals(period: StatsPeriod, likes: Long = 3) =
            StatsTotals(window(period), publications = 2, likes = likes, comments = 1, interactions = likes + 1, views = 5, commentsIncludeOwn = true, starsGiven = mapOf(1 to 0L, 2 to 0L, 3 to 1L, 4 to 1L, 5 to 0L))

        fun statsPage(period: StatsPeriod, page: Int, totalPages: Int, prefix: String = "s", from: Instant? = fromOf(period)) = StatsPage(
            Page(
                (1..2).map {
                    ExperienceStats("$prefix$page-$it", "Local", true, "Boxe iniciação", "Boxe", 4, Instant.parse("2026-10-10T10:00:00Z"), 1, 0, 1, 2)
                },
                page,
                (totalPages * 2).toLong(),
                totalPages,
            ),
            window(period, from),
        )
    }
}
