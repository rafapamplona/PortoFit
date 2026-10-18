package com.example.portofit.ui.feature.people

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.portofit.PortoFitApplication
import com.example.portofit.data.auth.SessionState
import com.example.portofit.data.auth.SessionStatus
import com.example.portofit.data.model.Sport
import com.example.portofit.data.repository.CatalogRepository
import com.example.portofit.data.repository.DataError
import com.example.portofit.data.repository.DataResult
import com.example.portofit.data.repository.PeopleRepository
import com.example.portofit.data.repository.PublicProfile
import com.example.portofit.data.repository.UserSummary
import com.example.portofit.ui.components.SportLabel
import com.example.portofit.ui.components.SportNames
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

// ---------------------------------------------------------------- Pesquisa de pessoas

sealed interface PeopleResults {
    /** Ainda não houve pesquisa (o contrato exige texto: não há lista "de todos"). */
    data object Idle : PeopleResults
    data object Loading : PeopleResults
    data class Error(val error: DataError) : PeopleResults
    data class Loaded(
        val people: List<UserSummary>,
        val page: Int,
        val totalItems: Long,
        val hasMore: Boolean,
        val isLoadingMore: Boolean = false,
        val loadMoreError: DataError? = null,
    ) : PeopleResults
}

data class PeopleSearchUiState(
    val queryInput: String = "",
    val activeQuery: String? = null,
    val showQueryInvalid: Boolean = false,
    val results: PeopleResults = PeopleResults.Idle,
)

/**
 * Pesquisa pública de pessoas por username (docs/users-api.md §3.7): submissão explícita, 2–50
 * caracteres, paginação "Carregar mais". Estado próprio, separado da pesquisa de locais; sobrevive à
 * recriação e à navegação para um perfil. Respostas de pesquisas antigas são ignoradas.
 */
class PeopleSearchViewModel(
    private val people: PeopleRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        PeopleSearchUiState(queryInput = savedState[KEY_INPUT] ?: "", activeQuery = savedState[KEY_QUERY]),
    )
    val uiState: StateFlow<PeopleSearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var generation = 0

    init {
        if (_uiState.value.activeQuery != null) startSearch()
    }

    fun onQueryInputChange(text: String) {
        val limited = text.take(MAX_QUERY)
        savedState[KEY_INPUT] = limited
        _uiState.update { it.copy(queryInput = limited, showQueryInvalid = false) }
    }

    fun onSubmit() {
        val query = _uiState.value.queryInput.trim().lowercase(Locale.ROOT)
        if (query.length !in MIN_QUERY..MAX_QUERY) {
            _uiState.update { it.copy(showQueryInvalid = true) }
            return
        }
        savedState[KEY_QUERY] = query
        _uiState.update { it.copy(activeQuery = query, showQueryInvalid = false) }
        startSearch()
    }

    fun retry() = startSearch()

    fun loadMore() {
        val current = _uiState.value.results as? PeopleResults.Loaded ?: return
        val query = _uiState.value.activeQuery ?: return
        if (!current.hasMore || current.isLoadingMore) return
        val myGeneration = generation
        _uiState.update { it.copy(results = current.copy(isLoadingMore = true, loadMoreError = null)) }
        searchJob = viewModelScope.launch {
            val result = people.search(query, current.page + 1, PAGE_SIZE)
            if (myGeneration != generation) return@launch
            _uiState.update { state ->
                val loaded = state.results as? PeopleResults.Loaded ?: return@update state
                state.copy(
                    results = when (result) {
                        is DataResult.Success -> loaded.copy(
                            people = (loaded.people + result.value.items).distinctBy { it.userId },
                            page = result.value.page,
                            totalItems = result.value.totalItems,
                            hasMore = result.value.hasMore,
                            isLoadingMore = false,
                        )
                        is DataResult.Failure -> loaded.copy(isLoadingMore = false, loadMoreError = result.error)
                    },
                )
            }
        }
    }

    private fun startSearch() {
        val query = _uiState.value.activeQuery ?: return
        searchJob?.cancel()
        val myGeneration = ++generation
        _uiState.update { it.copy(results = PeopleResults.Loading) }
        searchJob = viewModelScope.launch {
            val result = people.search(query, 0, PAGE_SIZE)
            if (myGeneration != generation) return@launch
            _uiState.update {
                it.copy(
                    results = when (result) {
                        is DataResult.Success -> PeopleResults.Loaded(
                            result.value.items, result.value.page, result.value.totalItems, result.value.hasMore,
                        )
                        is DataResult.Failure -> PeopleResults.Error(result.error)
                    },
                )
            }
        }
    }

    companion object {
        const val PAGE_SIZE = 20
        const val MIN_QUERY = 2
        const val MAX_QUERY = 50
        private const val KEY_INPUT = "people.input"
        private const val KEY_QUERY = "people.query"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PortoFitApplication
                PeopleSearchViewModel(app.container.peopleRepository, createSavedStateHandle())
            }
        }
    }
}

// ---------------------------------------------------------------- Perfil público

sealed interface PublicProfileLoad {
    data object Loading : PublicProfileLoad
    data class Error(val error: DataError) : PublicProfileLoad
    data class Loaded(val profile: PublicProfile) : PublicProfileLoad
}

data class PublicProfileUiState(
    val load: PublicProfileLoad = PublicProfileLoad.Loading,
    /** null enquanto as modalidades carregam ou se o catálogo falhar (usam-se nomes alternativos). */
    val sports: List<Sport>? = null,
    /** "Editar o meu perfil" só aparece com sessão ativa do próprio utilizador. */
    val isOwnProfile: Boolean = false,
) {
    val interestLabels: List<SportLabel>
        get() = (load as? PublicProfileLoad.Loaded)?.profile?.interests?.let { SportNames.labels(it, sports) }.orEmpty()
}

/**
 * Perfil público de uma pessoa (docs/users-api.md §3.8). Não pede sessão nem envia token; só tem os
 * campos públicos (nunca email), mesmo quando é o próprio utilizador.
 */
class PublicProfileViewModel(
    private val people: PeopleRepository,
    private val catalog: CatalogRepository,
    session: StateFlow<SessionState>,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val userId: String = checkNotNull(savedState[USER_ID_ARG]) { "Falta o argumento $USER_ID_ARG" }

    private val content = MutableStateFlow(PublicProfileUiState())

    val uiState: StateFlow<PublicProfileUiState> = combine(content, session) { state, current ->
        val own = current is SessionState.SignedIn && current.userId == userId && current.status == SessionStatus.ACTIVE
        state.copy(isOwnProfile = own && state.load is PublicProfileLoad.Loaded)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PublicProfileUiState())

    private var loadJob: Job? = null

    init {
        load()
        viewModelScope.launch {
            val result = catalog.getSports()
            content.update { it.copy(sports = (result as? DataResult.Success)?.value) }
        }
    }

    fun retry() = load()

    private fun load() {
        loadJob?.cancel()
        content.update { it.copy(load = PublicProfileLoad.Loading) }
        loadJob = viewModelScope.launch {
            val result = people.profile(userId)
            content.update {
                it.copy(
                    load = when (result) {
                        is DataResult.Success -> PublicProfileLoad.Loaded(result.value)
                        is DataResult.Failure -> PublicProfileLoad.Error(result.error)
                    },
                )
            }
        }
    }

    companion object {
        const val USER_ID_ARG = "userId"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PortoFitApplication
                PublicProfileViewModel(
                    app.container.peopleRepository,
                    app.container.catalogRepository,
                    app.container.sessionManager.state,
                    createSavedStateHandle(),
                )
            }
        }
    }
}
