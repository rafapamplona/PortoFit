package com.example.portofit.ui.feature.search

import com.example.portofit.data.model.PlaceSummary
import com.example.portofit.data.model.Sport
import com.example.portofit.data.repository.DataError

data class SearchUiState(
    /** Texto no campo de pesquisa (ainda não submetido). */
    val queryInput: String = "",
    /** Pesquisa atualmente aplicada aos resultados; null = todos os locais. */
    val activeQuery: String? = null,
    val showQueryTooShort: Boolean = false,
    val sports: SportsState = SportsState.Loading,
    val selectedSportId: String? = null,
    val results: ResultsState = ResultsState.Loading,
)

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
