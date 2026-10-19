package com.example.portofit.ui.feature.discover

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.portofit.PortoFitApplication
import com.example.portofit.data.auth.SessionState
import com.example.portofit.data.model.FeedFallbackReason
import com.example.portofit.data.model.FeedItem
import com.example.portofit.data.model.FeedMode
import com.example.portofit.data.model.FeedPage
import com.example.portofit.data.model.Sport
import com.example.portofit.data.repository.CatalogRepository
import com.example.portofit.data.repository.DataError
import com.example.portofit.data.repository.DataResult
import com.example.portofit.data.repository.DiscoverRepository
import com.example.portofit.data.repository.ExperienceChanges
import com.example.portofit.data.repository.ExperienceCounts
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
import java.time.Instant

enum class DiscoverTab { FOR_YOU, POPULAR }

sealed interface SportsState {
    data object Loading : SportsState
    data class Loaded(val sports: List<Sport>) : SportsState

    /** Sem modalidades: o feed continua disponível sem filtro. */
    data object Failed : SportsState
}

sealed interface FeedListState {
    data object Loading : FeedListState
    data class Error(val error: DataError) : FeedListState

    /** É preciso entrar de novo (sessão terminada). */
    data object SessionEnded : FeedListState

    /** A sessão existe mas não foi possível confirmá-la agora: falha temporária, "Tentar novamente". */
    data object SessionUnverified : FeedListState

    data class Loaded(
        val items: List<FeedItem>,
        val page: Int,
        val totalItems: Long,
        val hasMore: Boolean,
        val mode: FeedMode,
        val fallbackReason: FeedFallbackReason?,
        val recentSince: Instant?,
        val generatedAt: Instant,
        val isLoadingMore: Boolean = false,
        val loadMoreError: DataError? = null,
        /** A página seguinte veio noutro modo: não foi juntada; a lista tem de recomeçar. */
        val modeChanged: Boolean = false,
    ) : FeedListState
}

data class DiscoverUiState(
    /** null enquanto a sessão guardada é lida. */
    val signedIn: Boolean? = null,
    val tab: DiscoverTab = DiscoverTab.POPULAR,
    val sportId: String? = null,
    val sports: SportsState = SportsState.Loading,
    val feed: FeedListState = FeedListState.Loading,
)

/**
 * Descobrir (docs/experiences-api.md §14, §14.7). Visitantes: só Populares. Com sessão: Para ti (feed pessoal, que o
 * servidor pode degradar para popular, indicado pelo `mode`) e Populares.
 *
 * - Cada carregamento tem uma geração: respostas antigas (outro separador, filtro ou conta) são ignoradas.
 * - Uma página seguinte com outro `mode`/`fallbackReason` não é juntada: a lista fica marcada para recomeçar.
 * - Separador e filtro ficam no SavedStateHandle.
 */
class DiscoverViewModel(
    private val repository: DiscoverRepository,
    private val catalog: CatalogRepository,
    changes: ExperienceChanges,
    private val session: StateFlow<SessionState>,
    private val savedState: SavedStateHandle,
    private val pageSize: Int = PAGE_SIZE,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        DiscoverUiState(
            tab = savedState.get<String>(KEY_TAB)?.let { name -> DiscoverTab.entries.firstOrNull { it.name == name } } ?: DiscoverTab.FOR_YOU,
            sportId = savedState[KEY_SPORT],
        ),
    )
    val uiState: StateFlow<DiscoverUiState> = _uiState.asStateFlow()

    private var generation = 0
    private var job: Job? = null

    /** Separador escolhido (com sessão); sem sessão vale sempre Populares. */
    private var chosenTab: DiscoverTab = _uiState.value.tab

    init {
        loadSports()
        // Primeira carga e nova carga sempre que muda quem tem sessão (visitante ↔ conta, ou outra conta).
        viewModelScope.launch {
            session.map { sessionKey(it) }.filterNotNull().distinctUntilChanged().collect { key ->
                val signedIn = key != VISITOR
                _uiState.update { it.copy(signedIn = signedIn, tab = if (signedIn) chosenTab else DiscoverTab.POPULAR) }
                load()
            }
        }
        viewModelScope.launch { changes.counts.collect(::applyCounts) }
    }

    fun selectTab(tab: DiscoverTab) {
        if (_uiState.value.signedIn != true || tab == _uiState.value.tab) return
        chosenTab = tab
        savedState[KEY_TAB] = tab.name
        _uiState.update { it.copy(tab = tab) }
        load()
    }

    fun selectSport(sportId: String?) {
        if (sportId == _uiState.value.sportId) return
        savedState[KEY_SPORT] = sportId
        _uiState.update { it.copy(sportId = sportId) }
        load()
    }

    /** "Atualizar", "Tentar novamente" e "Recomeçar": volta à primeira página. */
    fun refresh() = load()

    fun retrySports() = loadSports()

    fun loadMore() {
        val current = _uiState.value.feed as? FeedListState.Loaded ?: return
        if (!current.hasMore || current.isLoadingMore || current.modeChanged) return
        val myGeneration = generation
        val state = _uiState.value
        setFeed(current.copy(isLoadingMore = true, loadMoreError = null))
        job = viewModelScope.launch {
            val result = fetch(state.tab, state.sportId, current.page + 1)
            if (myGeneration != generation) return@launch
            _uiState.update { ui ->
                val loaded = ui.feed as? FeedListState.Loaded ?: return@update ui
                val next = when (result) {
                    is Fetched.Page -> {
                        val page = result.page
                        if (page.mode != loaded.mode || page.fallbackReason != loaded.fallbackReason) {
                            // Páginas de modos diferentes nunca se juntam (ex.: o users-service falhou a meio).
                            loaded.copy(isLoadingMore = false, modeChanged = true)
                        } else {
                            loaded.copy(
                                items = (loaded.items + page.page.items).distinctBy { it.experience.id },
                                page = page.page.page,
                                totalItems = page.page.totalItems,
                                hasMore = page.page.hasMore,
                                isLoadingMore = false,
                            )
                        }
                    }
                    is Fetched.Problem -> when (val problem = result.state) {
                        is FeedListState.Error -> loaded.copy(isLoadingMore = false, loadMoreError = problem.error)
                        else -> problem
                    }
                }
                ui.copy(feed = next)
            }
        }
    }

    private fun load() {
        val state = _uiState.value
        val signedIn = state.signedIn ?: return
        job?.cancel()
        val myGeneration = ++generation
        setFeed(FeedListState.Loading)
        val tab = if (signedIn) state.tab else DiscoverTab.POPULAR
        job = viewModelScope.launch {
            val result = fetch(tab, state.sportId, 0)
            if (myGeneration != generation) return@launch
            setFeed(
                when (result) {
                    is Fetched.Page -> result.page.let {
                        FeedListState.Loaded(
                            it.page.items, it.page.page, it.page.totalItems, it.page.hasMore, it.mode, it.fallbackReason, it.recentSince, it.generatedAt,
                        )
                    }
                    is Fetched.Problem -> result.state
                },
            )
        }
    }

    private sealed interface Fetched {
        data class Page(val page: FeedPage) : Fetched
        data class Problem(val state: FeedListState) : Fetched
    }

    private suspend fun fetch(tab: DiscoverTab, sportId: String?, page: Int): Fetched = when (tab) {
        DiscoverTab.POPULAR -> when (val result = repository.popularFeed(sportId, page, pageSize)) {
            is DataResult.Success -> Fetched.Page(result.value)
            is DataResult.Failure -> Fetched.Problem(FeedListState.Error(result.error))
        }
        DiscoverTab.FOR_YOU -> when (val result = repository.personalFeed(sportId, page, pageSize)) {
            is SessionResult.Success -> Fetched.Page(result.value)
            is SessionResult.Failure -> Fetched.Problem(FeedListState.Error(result.error))
            SessionResult.SessionEnded -> Fetched.Problem(FeedListState.SessionEnded)
            SessionResult.SessionUnverified -> Fetched.Problem(FeedListState.SessionUnverified)
        }
    }

    private fun loadSports() {
        _uiState.update { it.copy(sports = SportsState.Loading) }
        viewModelScope.launch {
            val result = catalog.getSports()
            _uiState.update { it.copy(sports = if (result is DataResult.Success) SportsState.Loaded(result.value) else SportsState.Failed) }
        }
    }

    /** Contagens confirmadas no detalhe: atualizam o item sem recarregar (a ordem só muda na próxima carga). */
    private fun applyCounts(counts: ExperienceCounts) = _uiState.update { ui ->
        val loaded = ui.feed as? FeedListState.Loaded ?: return@update ui
        if (loaded.items.none { it.experience.id == counts.experienceId }) return@update ui
        ui.copy(
            feed = loaded.copy(
                items = loaded.items.map {
                    if (it.experience.id == counts.experienceId) {
                        it.copy(experience = it.experience.copy(likesCount = counts.likesCount, commentsCount = counts.commentsCount))
                    } else {
                        it
                    }
                },
            ),
        )
    }

    private fun setFeed(feed: FeedListState) = _uiState.update { it.copy(feed = feed) }

    private fun sessionKey(state: SessionState): String? = when (state) {
        SessionState.Loading -> null
        is SessionState.SignedOut -> VISITOR
        is SessionState.SignedIn -> state.userId
    }

    companion object {
        const val PAGE_SIZE = 10
        const val KEY_TAB = "discover.tab"
        const val KEY_SPORT = "discover.sport"
        private const val VISITOR = "\u0000visitante"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PortoFitApplication
                DiscoverViewModel(
                    app.container.discoverRepository,
                    app.container.catalogRepository,
                    app.container.experienceChanges,
                    app.container.sessionManager.state,
                    createSavedStateHandle(),
                )
            }
        }
    }
}
