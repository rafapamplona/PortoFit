package com.example.portofit.ui.feature.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.portofit.PortoFitApplication
import com.example.portofit.data.location.LocationProvider
import com.example.portofit.data.location.LocationResult
import com.example.portofit.data.model.NearbyFilter
import com.example.portofit.data.repository.CatalogRepository
import com.example.portofit.data.repository.DataResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Pesquisa de locais com filtro de modalidade, proximidade opcional e paginação "Carregar mais".
 *
 * O texto, a pesquisa aplicada, a modalidade e o raio ficam no [SavedStateHandle], por isso sobrevivem
 * à recriação da Activity e à morte do processo. Cada nova pesquisa recebe um número de geração:
 * respostas que chegam de pedidos anteriores são ignoradas.
 *
 * Proximidade (docs/catalog-api.md §3.2): a posição só é pedida por [onUseLocation] (depois do toque e da permissão),
 * uma vez e com prazo; nunca em segundo plano nem continuamente. As coordenadas ficam só em memória (nunca no
 * SavedStateHandle nem em registos): depois da morte do processo a proximidade volta desligada e não é pedida sozinha.
 */
class SearchViewModel(
    private val repository: CatalogRepository,
    private val savedState: SavedStateHandle,
    private val location: LocationProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SearchUiState(
            mode = savedState.get<String>(KEY_MODE)?.let { runCatching { SearchMode.valueOf(it) }.getOrNull() } ?: SearchMode.PLACES,
            queryInput = savedState[KEY_INPUT] ?: "",
            activeQuery = savedState[KEY_QUERY],
            selectedSportId = savedState[KEY_SPORT],
            radiusKm = savedState.get<Int>(KEY_RADIUS)?.takeIf { it in RADIUS_OPTIONS } ?: DEFAULT_RADIUS_KM,
        ),
    )
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var searchGeneration = 0
    private var sportsJob: Job? = null
    private var locateJob: Job? = null

    /** A proximidade da pesquisa que produziu os resultados atuais ("Carregar mais" continua essa, e não outra). */
    private var resultsNear: NearbyFilter? = null

    init {
        loadSports()
        startSearch()
    }

    /** Alternar entre modos não altera a pesquisa de locais (texto, filtro, resultados). */
    fun onModeChange(mode: SearchMode) {
        savedState[KEY_MODE] = mode.name
        _uiState.update { it.copy(mode = mode) }
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

    /** Depois do toque em "Usar a minha localização" e com a permissão concedida: uma posição, com prazo. */
    fun onUseLocation() {
        if (_uiState.value.nearby == NearbyState.Locating) return
        val wasActive = _uiState.value.nearby is NearbyState.Active
        _uiState.update { it.copy(nearby = NearbyState.Locating) }
        locateJob = viewModelScope.launch {
            val nearby = when (val result = location.current()) {
                is LocationResult.Found -> {
                    val fix = result.fix
                    if (fix.ageMillis > MAX_FIX_AGE_MILLIS) {
                        NearbyState.Failed(LocationFailure.TOO_OLD)
                    } else {
                        NearbyState.Active(
                            NearbyFilter(fix.latitude, fix.longitude, _uiState.value.radiusKm),
                            approximate = fix.approximate,
                            ageMinutes = if (fix.ageMillis > FRESH_FIX_MILLIS) (fix.ageMillis / 60_000).toInt() else null,
                        )
                    }
                }
                LocationResult.PermissionMissing -> NearbyState.Failed(LocationFailure.PERMISSION_DENIED)
                LocationResult.Disabled -> NearbyState.Failed(LocationFailure.DISABLED)
                LocationResult.Timeout -> NearbyState.Failed(LocationFailure.TIMEOUT)
                LocationResult.Unavailable -> NearbyState.Failed(LocationFailure.UNAVAILABLE)
            }
            _uiState.update { it.copy(nearby = nearby) }
            // Uma falha volta à pesquisa normal (se os resultados eram por proximidade, deixam de o ser).
            if (nearby is NearbyState.Active || wasActive) startSearch()
        }
    }

    /** A permissão foi recusada no pedido do sistema ([blocked]: já não é possível voltar a perguntar). */
    fun onLocationPermissionDenied(blocked: Boolean) {
        val wasActive = _uiState.value.nearby is NearbyState.Active
        locateJob?.cancel()
        _uiState.update {
            it.copy(nearby = NearbyState.Failed(if (blocked) LocationFailure.PERMISSION_BLOCKED else LocationFailure.PERMISSION_DENIED))
        }
        if (wasActive) startSearch()
    }

    /** Mudar o raio não pede outra posição: repete a pesquisa com a mesma. */
    fun onRadiusSelected(radiusKm: Int) {
        if (radiusKm !in RADIUS_OPTIONS || radiusKm == _uiState.value.radiusKm) return
        savedState[KEY_RADIUS] = radiusKm
        val nearby = _uiState.value.nearby
        _uiState.update {
            it.copy(
                radiusKm = radiusKm,
                nearby = if (nearby is NearbyState.Active) nearby.copy(filter = nearby.filter.copy(radiusKm = radiusKm)) else nearby,
            )
        }
        if (nearby is NearbyState.Active) startSearch()
    }

    /** Volta à pesquisa normal (também cancela uma localização em curso e fecha um aviso de falha). */
    fun onDisableNearby() {
        val wasActive = _uiState.value.nearby is NearbyState.Active
        locateJob?.cancel()
        _uiState.update { it.copy(nearby = NearbyState.Off) }
        if (wasActive) startSearch()
    }

    fun retrySports() = loadSports()

    fun loadMore() {
        val current = _uiState.value.results as? ResultsState.Loaded ?: return
        if (!current.hasMore || current.isLoadingMore) return

        val generation = searchGeneration
        val state = _uiState.value
        _uiState.update { it.copy(results = current.copy(isLoadingMore = true, loadMoreError = null)) }

        searchJob = viewModelScope.launch {
            val result = repository.searchPlaces(state.activeQuery, state.selectedSportId, current.page + 1, PAGE_SIZE, resultsNear)
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
        resultsNear = state.nearFilter()
        _uiState.update { it.copy(results = ResultsState.Loading) }

        searchJob = viewModelScope.launch {
            val result = repository.searchPlaces(state.activeQuery, state.selectedSportId, 0, PAGE_SIZE, resultsNear)
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

    private fun SearchUiState.nearFilter() = (nearby as? NearbyState.Active)?.filter

    companion object {
        /** Tamanho de página pedido ao servidor (o contrato aceita até 50). */
        const val PAGE_SIZE = 20

        /** Raios oferecidos (km), dentro do intervalo do contrato (0,1 a 50); 5 é o valor por omissão do servidor. */
        val RADIUS_OPTIONS = listOf(1, 3, 5, 10)
        const val DEFAULT_RADIUS_KM = 5

        /** Uma posição anterior só é usada até esta idade; acima de [FRESH_FIX_MILLIS] o ecrã diz a idade. */
        const val MAX_FIX_AGE_MILLIS = 10 * 60_000L
        const val FRESH_FIX_MILLIS = 2 * 60_000L
        const val MIN_QUERY_LENGTH = 2
        const val MAX_QUERY_LENGTH = 100

        private const val KEY_INPUT = "search.input"
        private const val KEY_QUERY = "search.query"
        private const val KEY_SPORT = "search.sport"
        private const val KEY_MODE = "search.mode"
        private const val KEY_RADIUS = "search.radiusKm"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PortoFitApplication
                SearchViewModel(app.container.catalogRepository, createSavedStateHandle(), app.container.locationProvider)
            }
        }
    }
}
