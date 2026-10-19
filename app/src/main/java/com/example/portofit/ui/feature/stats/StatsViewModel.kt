package com.example.portofit.ui.feature.stats

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.portofit.PortoFitApplication
import com.example.portofit.data.auth.SessionState
import com.example.portofit.data.model.ExperienceStats
import com.example.portofit.data.model.StatsPeriod
import com.example.portofit.data.model.StatsSort
import com.example.portofit.data.model.StatsTotals
import com.example.portofit.data.model.StatsWindow
import com.example.portofit.data.repository.DataError
import com.example.portofit.data.repository.DiscoverRepository
import com.example.portofit.data.repository.SessionResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Estado de uma secção do dashboard: nunca zeros quando há falha. */
sealed interface SectionState<out T> {
    data object Loading : SectionState<Nothing>
    data class Error(val error: DataError) : SectionState<Nothing>
    data object SessionEnded : SectionState<Nothing>
    data object SessionUnverified : SectionState<Nothing>
    data class Loaded<T>(val value: T) : SectionState<T>
}

/** Lista por publicação já carregada; [window] é o intervalo do servidor da primeira página. */
data class StatsList(
    val items: List<ExperienceStats>,
    val page: Int,
    val totalItems: Long,
    val hasMore: Boolean,
    val window: StatsWindow,
    val isLoadingMore: Boolean = false,
    val loadMoreError: DataError? = null,
    /** Uma página seguinte veio com outro intervalo (ex.: mudou o dia UTC): não foi juntada; recomeçar. */
    val windowChanged: Boolean = false,
)

data class StatsUiState(
    /** null enquanto a sessão guardada é lida; false = visitante (convite para entrar, sem dados). */
    val signedIn: Boolean? = null,
    val period: StatsPeriod = StatsPeriod.ALL,
    val sort: StatsSort = StatsSort.RECENT,
    val totals: SectionState<StatsTotals> = SectionState.Loading,
    val list: SectionState<StatsList> = SectionState.Loading,
)

/**
 * Estatísticas do próprio utilizador (docs/experiences-api.md §14.4).
 *
 * - Totais e lista carregam e falham de forma independente; cada uma mostra o intervalo devolvido pelo servidor.
 * - Ao mudar de conta ou terminar sessão, os dados privados são apagados **logo** e uma resposta tardia da conta
 *   anterior é ignorada (geração + conta do pedido).
 * - Período e ordenação ficam no SavedStateHandle; mudar recomeça e ignora respostas antigas.
 */
class StatsViewModel(
    private val repository: DiscoverRepository,
    private val session: StateFlow<SessionState>,
    private val savedState: SavedStateHandle,
    private val pageSize: Int = PAGE_SIZE,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        StatsUiState(
            period = savedState.get<String>(KEY_PERIOD)?.let { name -> StatsPeriod.entries.firstOrNull { it.name == name } } ?: StatsPeriod.ALL,
            sort = savedState.get<String>(KEY_SORT)?.let { name -> StatsSort.entries.firstOrNull { it.name == name } } ?: StatsSort.RECENT,
        ),
    )
    val uiState: StateFlow<StatsUiState> = _uiState.asStateFlow()

    /** Conta a que os dados mostrados pertencem (null = nenhuma). */
    private var account: String? = null
    private var totalsGeneration = 0
    private var listGeneration = 0
    private var totalsJob: Job? = null
    private var listJob: Job? = null

    init {
        viewModelScope.launch {
            session.map { key(it) }.filterNotNull().distinctUntilChanged().collect { key ->
                // Apagar já os dados da conta anterior e invalidar qualquer pedido em curso.
                account = key.takeUnless { it == VISITOR }
                totalsGeneration++
                listGeneration++
                totalsJob?.cancel()
                listJob?.cancel()
                _uiState.update { it.copy(signedIn = account != null, totals = SectionState.Loading, list = SectionState.Loading) }
                if (account != null) {
                    loadTotals()
                    loadList()
                }
            }
        }
    }

    fun selectPeriod(period: StatsPeriod) {
        if (period == _uiState.value.period) return
        savedState[KEY_PERIOD] = period.name
        _uiState.update { it.copy(period = period) }
        loadTotals()
        loadList()
    }

    fun selectSort(sort: StatsSort) {
        if (sort == _uiState.value.sort) return
        savedState[KEY_SORT] = sort.name
        _uiState.update { it.copy(sort = sort) }
        loadList()
    }

    fun retryTotals() = loadTotals()

    /** "Tentar novamente" e "Recomeçar" da lista. */
    fun retryList() = loadList()

    fun refresh() {
        loadTotals()
        loadList()
    }

    fun loadMore() {
        val current = (_uiState.value.list as? SectionState.Loaded)?.value ?: return
        if (!current.hasMore || current.isLoadingMore || current.windowChanged) return
        val requestAccount = account ?: return
        val myGeneration = listGeneration
        val state = _uiState.value
        _uiState.update { it.copy(list = SectionState.Loaded(current.copy(isLoadingMore = true, loadMoreError = null))) }
        listJob = viewModelScope.launch {
            val result = repository.statsExperiences(state.period, state.sort, current.page + 1, pageSize)
            if (myGeneration != listGeneration || requestAccount != account) return@launch
            _uiState.update { ui ->
                val loaded = (ui.list as? SectionState.Loaded)?.value ?: return@update ui
                val next: SectionState<StatsList> = when (result) {
                    is SessionResult.Success -> {
                        val page = result.value
                        if (page.window.from != loaded.window.from || page.window.period != loaded.window.period) {
                            SectionState.Loaded(loaded.copy(isLoadingMore = false, windowChanged = true))
                        } else {
                            SectionState.Loaded(
                                loaded.copy(
                                    items = (loaded.items + page.page.items).distinctBy { it.experienceId },
                                    page = page.page.page,
                                    totalItems = page.page.totalItems,
                                    hasMore = page.page.hasMore,
                                    isLoadingMore = false,
                                ),
                            )
                        }
                    }
                    is SessionResult.Failure -> SectionState.Loaded(loaded.copy(isLoadingMore = false, loadMoreError = result.error))
                    SessionResult.SessionEnded -> SectionState.SessionEnded
                    SessionResult.SessionUnverified -> SectionState.Loaded(loaded.copy(isLoadingMore = false, loadMoreError = DataError.NETWORK))
                }
                ui.copy(list = next)
            }
        }
    }

    private fun loadTotals() {
        val requestAccount = account ?: return
        totalsJob?.cancel()
        val myGeneration = ++totalsGeneration
        val period = _uiState.value.period
        _uiState.update { it.copy(totals = SectionState.Loading) }
        totalsJob = viewModelScope.launch {
            val result = repository.stats(period)
            if (myGeneration != totalsGeneration || requestAccount != account) return@launch
            _uiState.update { it.copy(totals = result.toSection { totals -> totals }) }
        }
    }

    private fun loadList() {
        val requestAccount = account ?: return
        listJob?.cancel()
        val myGeneration = ++listGeneration
        val state = _uiState.value
        _uiState.update { it.copy(list = SectionState.Loading) }
        listJob = viewModelScope.launch {
            val result = repository.statsExperiences(state.period, state.sort, 0, pageSize)
            if (myGeneration != listGeneration || requestAccount != account) return@launch
            _uiState.update {
                it.copy(list = result.toSection { page -> StatsList(page.page.items, page.page.page, page.page.totalItems, page.page.hasMore, page.window) })
            }
        }
    }

    private fun <T, R> SessionResult<T>.toSection(convert: (T) -> R): SectionState<R> = when (this) {
        is SessionResult.Success -> SectionState.Loaded(convert(value))
        is SessionResult.Failure -> SectionState.Error(error)
        SessionResult.SessionEnded -> SectionState.SessionEnded
        SessionResult.SessionUnverified -> SectionState.SessionUnverified
    }

    private fun key(state: SessionState): String? = when (state) {
        SessionState.Loading -> null
        is SessionState.SignedOut -> VISITOR
        is SessionState.SignedIn -> state.userId
    }

    companion object {
        const val PAGE_SIZE = 10
        const val KEY_PERIOD = "stats.period"
        const val KEY_SORT = "stats.sort"
        private const val VISITOR = "\u0000visitante"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PortoFitApplication
                StatsViewModel(app.container.discoverRepository, app.container.sessionManager.state, createSavedStateHandle())
            }
        }
    }
}
