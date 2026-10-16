package com.example.portofit.ui.feature.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.portofit.PortoFitApplication
import com.example.portofit.data.repository.CatalogRepository
import com.example.portofit.data.repository.DataResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Pesquisa de locais com filtro de modalidade e paginação "Carregar mais".
 *
 * O texto, a pesquisa aplicada e a modalidade ficam no [SavedStateHandle], por isso sobrevivem
 * à recriação da Activity e à morte do processo. Cada nova pesquisa recebe um número de geração:
 * respostas que chegam de pedidos anteriores são ignoradas.
 */
class SearchViewModel(
    private val repository: CatalogRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SearchUiState(
            queryInput = savedState[KEY_INPUT] ?: "",
            activeQuery = savedState[KEY_QUERY],
            selectedSportId = savedState[KEY_SPORT],
        ),
    )
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var searchGeneration = 0
    private var sportsJob: Job? = null

    init {
        loadSports()
        startSearch()
    }

    fun onQueryInputChange(text: String) {
        val limited = text.take(MAX_QUERY_LENGTH)
        savedState[KEY_INPUT] = limited
        _uiState.update { it.copy(queryInput = limited, showQueryTooShort = false) }
    }

    /** Submissão explícita (botão ou tecla de pesquisa). Vazio = todos os locais. */
    fun onSearchSubmit() {
        val query = _uiState.value.queryInput.trim()
        if (query.length in 1 until MIN_QUERY_LENGTH) {
            _uiState.update { it.copy(showQueryTooShort = true) }
            return
        }
        val activeQuery = query.ifEmpty { null }
        savedState[KEY_QUERY] = activeQuery
        _uiState.update { it.copy(activeQuery = activeQuery, showQueryTooShort = false) }
        startSearch()
    }

    /** null = todas as modalidades. */
    fun onSportSelected(sportId: String?) {
        if (sportId == _uiState.value.selectedSportId) return
        savedState[KEY_SPORT] = sportId
        _uiState.update { it.copy(selectedSportId = sportId) }
        startSearch()
    }

    fun retrySearch() = startSearch()

    fun retrySports() = loadSports()

    fun loadMore() {
        val current = _uiState.value.results as? ResultsState.Loaded ?: return
        if (!current.hasMore || current.isLoadingMore) return

        val generation = searchGeneration
        val state = _uiState.value
        _uiState.update { it.copy(results = current.copy(isLoadingMore = true, loadMoreError = null)) }

        searchJob = viewModelScope.launch {
            val result = repository.searchPlaces(state.activeQuery, state.selectedSportId, current.page + 1, PAGE_SIZE)
            if (generation != searchGeneration) return@launch
            _uiState.update { latest ->
                val loaded = latest.results as? ResultsState.Loaded ?: return@update latest
                val updated = when (result) {
                    is DataResult.Success -> loaded.copy(
                        places = (loaded.places + result.value.items).distinctBy { it.id },
                        page = result.value.page,
                        totalItems = result.value.totalItems,
                        hasMore = result.value.hasMore,
                        isLoadingMore = false,
                    )
                    is DataResult.Failure -> loaded.copy(isLoadingMore = false, loadMoreError = result.error)
                }
                latest.copy(results = updated)
            }
        }
    }

    /** Recomeça na página 0 com a pesquisa e a modalidade atuais, cancelando o pedido anterior. */
    private fun startSearch() {
        searchJob?.cancel()
        val generation = ++searchGeneration
        val state = _uiState.value
        _uiState.update { it.copy(results = ResultsState.Loading) }

        searchJob = viewModelScope.launch {
            val result = repository.searchPlaces(state.activeQuery, state.selectedSportId, 0, PAGE_SIZE)
            if (generation != searchGeneration) return@launch
            _uiState.update {
                it.copy(
                    results = when (result) {
                        is DataResult.Success -> ResultsState.Loaded(
                            places = result.value.items,
                            page = result.value.page,
                            totalItems = result.value.totalItems,
                            hasMore = result.value.hasMore,
                        )
                        is DataResult.Failure -> ResultsState.Error(result.error)
                    },
                )
            }
        }
    }

    private fun loadSports() {
        sportsJob?.cancel()
        _uiState.update { it.copy(sports = SportsState.Loading) }
        sportsJob = viewModelScope.launch {
            val result = repository.getSports()
            _uiState.update {
                it.copy(
                    sports = when (result) {
                        is DataResult.Success -> SportsState.Loaded(result.value)
                        is DataResult.Failure -> SportsState.Error
                    },
                )
            }
        }
    }

    companion object {
        /** Tamanho de página pedido ao servidor (o contrato aceita até 50). */
        const val PAGE_SIZE = 20
        const val MIN_QUERY_LENGTH = 2
        const val MAX_QUERY_LENGTH = 100

        private const val KEY_INPUT = "search.input"
        private const val KEY_QUERY = "search.query"
        private const val KEY_SPORT = "search.sport"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PortoFitApplication
                SearchViewModel(app.container.catalogRepository, createSavedStateHandle())
            }
        }
    }
}
