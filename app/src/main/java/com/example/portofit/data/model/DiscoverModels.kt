package com.example.portofit.data.model

import java.time.Instant

/** Modo de uma página de feed (docs/experiences-api.md §14.1–14.2). */
enum class FeedMode { PERSONAL, POPULAR, POPULAR_FALLBACK }

/** Porque é que um feed pessoal não é pessoal. Desconhecido → tratado como indisponibilidade. */
enum class FeedFallbackReason { NO_PREFERENCES, NO_MATCHES, USERS_UNAVAILABLE }

/** Porque é que uma experiência aparece no feed pessoal. */
enum class FeedMatch { FOLLOWING, INTEREST }

/** Uma experiência do feed, com o motivo (só no feed pessoal). */
data class FeedItem(val experience: Experience, val matchedBy: Set<FeedMatch> = emptySet())

/**
 * Uma página de feed, tal como o servidor a decidiu. [mode] e [fallbackReason] valem para a página inteira: uma página
 * popular nunca é apresentada como personalizada.
 */
data class FeedPage(
    val page: Page<FeedItem>,
    val mode: FeedMode,
    val fallbackReason: FeedFallbackReason?,
    /** Início do bloco recente dos populares (null no modo pessoal). */
    val recentSince: Instant?,
    val generatedAt: Instant,
)

/** Períodos do dashboard, em dias UTC (docs/experiences-api.md §14.4). */
enum class StatsPeriod(val apiValue: String) { ALL("ALL"), LAST_7_DAYS("LAST_7_DAYS"), LAST_30_DAYS("LAST_30_DAYS") }

enum class StatsSort(val apiValue: String) { RECENT("recent"), INTERACTIONS("interactions"), VIEWS("views") }

/** Intervalo devolvido pelo servidor; a app nunca o recalcula com o relógio do telemóvel. */
data class StatsWindow(val period: StatsPeriod, val from: Instant?, val to: Instant, val generatedAt: Instant)

/**
 * Totais do dashboard. [likes], [comments] e [views] são o que as publicações **receberam**; [starsGiven] são as estrelas
 * que o autor **deu** aos locais/atividades (contagem por 1–5), nas publicações criadas no período.
 */
data class StatsTotals(
    val window: StatsWindow,
    val publications: Long,
    val likes: Long,
    val comments: Long,
    val interactions: Long,
    val views: Long,
    /** Os comentários incluem as respostas do próprio autor. */
    val commentsIncludeOwn: Boolean,
    val starsGiven: Map<Int, Long>,
)

/** Uma publicação no dashboard, com o que recebeu no período. */
data class ExperienceStats(
    val experienceId: String,
    val placeName: String,
    val placeDemo: Boolean,
    val offeringName: String,
    val sportName: String,
    val stars: Int,
    val createdAt: Instant,
    val likes: Long,
    val comments: Long,
    val interactions: Long,
    val views: Long,
)

data class StatsPage(val page: Page<ExperienceStats>, val window: StatsWindow)
