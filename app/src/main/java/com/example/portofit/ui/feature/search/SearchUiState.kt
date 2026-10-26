package com.example.portofit.ui.feature.search

import com.example.portofit.data.model.NearbyFilter
import com.example.portofit.data.model.PlaceSummary
import com.example.portofit.data.model.Sport
import com.example.portofit.data.repository.DataError

/** O que a pesquisa procura. Cada modo tem o seu próprio texto, filtros e resultados. */
enum class SearchMode { PLACES, PEOPLE }

data class SearchUiState(
    val mode: SearchMode = SearchMode.PLACES,
    /** Texto no campo de pesquisa (ainda não submetido). */
    val queryInput: String = "",
    /** Pesquisa atualmente aplicada aos resultados; null = todos os locais. */
    val activeQuery: String? = null,
    val showQueryTooShort: Boolean = false,
    val sports: SportsState = SportsState.Loading,
    val selectedSportId: String? = null,
    val results: ResultsState = ResultsState.Loading,
    /** Pesquisa por proximidade (opcional); desligada, a pesquisa é a normal. */
    val nearby: NearbyState = NearbyState.Off,
    /** Raio escolhido (km), de [SearchViewModel.RADIUS_OPTIONS]. */
    val radiusKm: Int = SearchViewModel.DEFAULT_RADIUS_KM,
)

/** Porque não foi possível usar a localização. A pesquisa por texto e modalidade continua sempre. */
enum class LocationFailure {
    PERMISSION_DENIED,

    /** Recusada com "não voltar a perguntar": só nas definições da app. */
    PERMISSION_BLOCKED,

    /** Localização do aparelho desligada. */
    DISABLED,
    TIMEOUT,

    /** Só havia uma posição demasiado antiga. */
    TOO_OLD,
    UNAVAILABLE,
}

sealed interface NearbyState {
    data object Off : NearbyState

    /** A obter uma posição (com prazo); os resultados atuais continuam visíveis. */
    data object Locating : NearbyState

    /**
     * Resultados por proximidade. [approximate]: localização aproximada (erro de 1 a 3 km); [ageMinutes]: a posição
     * não é atual (anterior, ainda dentro do limite), para o ecrã o dizer.
     */
    data class Active(val filter: NearbyFilter, val approximate: Boolean, val ageMinutes: Int? = null) : NearbyState

    data class Failed(val reason: LocationFailure) : NearbyState
}

/** Estado do filtro de modalidades; independente dos resultados (uma falha aqui não bloqueia a pesquisa). */
sealed interface SportsState {
    data object Loading : SportsState
    data class Loaded(val sports: List<Sport>) : SportsState
    data object Error : SportsState
}

sealed interface ResultsState {
    data object Loading : ResultsState
    data class Error(val error: DataError) : ResultsState
    data class Loaded(
        val places: List<PlaceSummary>,
        /** Última página carregada (começa em 0). */
        val page: Int,
        val totalItems: Long,
        val hasMore: Boolean,
        val isLoadingMore: Boolean = false,
        val loadMoreError: DataError? = null,
    ) : ResultsState
}
