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
import com.example.portofit.data.model.ExperienceSort
import com.example.portofit.data.model.Sport
import com.example.portofit.data.repository.CatalogRepository
import com.example.portofit.data.repository.DataError
import com.example.portofit.data.repository.DataResult
import com.example.portofit.data.repository.ExperienceChanges
import com.example.portofit.data.repository.ExperiencesRepository
import com.example.portofit.ui.feature.experience.ExperienceListLoader
import com.example.portofit.ui.feature.experience.ExperienceListState
import kotlinx.coroutines.flow.filter
import com.example.portofit.data.repository.FollowError
import com.example.portofit.data.repository.FollowResult
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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

/** Estado do botão Seguir / Deixar de seguir. */
sealed interface FollowAction {
    data object Idle : FollowAction
    data object InProgress : FollowAction
    data class Failed(val error: FollowError) : FollowAction

    /** O servidor aceitou a ação, mas não foi possível confirmar o novo estado: mostra-se o último confirmado. */
    data object ResultUnconfirmed : FollowAction
}

/** O que o botão de seguir deve mostrar para quem está a ver. */
enum class FollowButton {
    /** Próprio perfil, ou perfil ainda não carregado: sem botão. */
    HIDDEN,

    /** Visitante: "Seguir" leva ao login (a ação não é feita sozinha depois de entrar). */
    LOGIN_REQUIRED,

    /** Sessão, mas o servidor não confirmou a relação (ex.: sessão por confirmar): sem botão, com aviso. */
    UNCONFIRMED,
    FOLLOW,
    UNFOLLOW,
}

data class PublicProfileUiState(
    val load: PublicProfileLoad = PublicProfileLoad.Loading,
    /** null enquanto as modalidades carregam ou se o catálogo falhar (usam-se nomes alternativos). */
    val sports: List<Sport>? = null,
    /** "Editar o meu perfil" só aparece com sessão ativa do próprio utilizador. */
    val isOwnProfile: Boolean = false,
    val signedIn: Boolean = false,
    val followAction: FollowAction = FollowAction.Idle,
) {
    val profile: PublicProfile? get() = (load as? PublicProfileLoad.Loaded)?.profile

    val interestLabels: List<SportLabel>
        get() = profile?.interests?.let { SportNames.labels(it, sports) }.orEmpty()

    val followButton: FollowButton
        get() {
            val p = profile ?: return FollowButton.HIDDEN
            return when {
                isOwnProfile -> FollowButton.HIDDEN
                !signedIn -> FollowButton.LOGIN_REQUIRED
                p.followedByMe == null -> FollowButton.UNCONFIRMED
                p.followedByMe -> FollowButton.UNFOLLOW
                else -> FollowButton.FOLLOW
            }
        }
}

/**
 * Perfil público de uma pessoa (docs/users-api.md §3.8). Sem sessão não envia token nem mostra a relação;
 * com sessão, a relação (`followedByMe`) e as contagens vêm sempre do servidor:
 * - recarrega quando a sessão muda (ex.: depois de entrar) e ao voltar a ficar visível;
 * - depois de seguir/deixar de seguir com sucesso, recarrega para confirmar a relação e as contagens;
 * - numa falha mantém o último estado confirmado; respostas de carregamentos antigos são ignoradas.
 */
class PublicProfileViewModel(
    private val people: PeopleRepository,
    private val catalog: CatalogRepository,
    private val session: StateFlow<SessionState>,
    experiences: ExperiencesRepository,
    changes: ExperienceChanges,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val userId: String = checkNotNull(savedState[USER_ID_ARG]) { "Falta o argumento $USER_ID_ARG" }

    /** Publicações da pessoa (experiences-service): independentes do perfil (users-service). */
    private val publicationList = ExperienceListLoader(
        viewModelScope,
        experiences,
        authorId = userId,
        initialSort = savedState.get<String>(KEY_PUBLICATION_SORT)?.let { name -> ExperienceSort.entries.firstOrNull { it.name == name } } ?: ExperienceSort.RECENT,
        counts = changes.counts,
    )
    val publications: StateFlow<ExperienceListState> = publicationList.state
    val publicationSort: StateFlow<ExperienceSort> = publicationList.sort

    private val content = MutableStateFlow(PublicProfileUiState())

    val uiState: StateFlow<PublicProfileUiState> = combine(content, session) { state, current ->
        val signedIn = current is SessionState.SignedIn
        val own = current is SessionState.SignedIn && current.userId == userId && current.status == SessionStatus.ACTIVE
        state.copy(isOwnProfile = own && state.load is PublicProfileLoad.Loaded, signedIn = signedIn)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PublicProfileUiState())

    private var loadGeneration = 0
    private var resumedOnce = false

    init {
        viewModelScope.launch {
            val result = catalog.getSports()
            content.update { it.copy(sports = (result as? DataResult.Success)?.value) }
        }
        // Primeira carga e nova carga sempre que muda quem tem sessão (visitante ↔ utilizador).
        viewModelScope.launch {
            session.map { (it as? SessionState.SignedIn)?.userId }.distinctUntilChanged().collect { load(silent = content.value.profile != null) }
        }
        publicationList.load()
        viewModelScope.launch { changes.events.filter { it.authorId == userId }.collect { publicationList.load() } }
    }

    fun retry() = load(silent = false)

    /** "Atualizar" depois de uma ação de resultado por confirmar. */
    fun refreshRelation() {
        content.update { it.copy(followAction = FollowAction.Idle) }
        load(silent = true)
    }

    /** Ao voltar a este ecrã (ex.: de outro perfil ou de uma lista): atualiza sem esconder o conteúdo. */
    fun onResumed() {
        if (!resumedOnce) {
            resumedOnce = true
            return
        }
        if (content.value.followAction != FollowAction.InProgress) load(silent = true)
    }

    fun toggleFollow() {
        val state = uiState.value
        val target = when (state.followButton) {
            FollowButton.FOLLOW -> true
            FollowButton.UNFOLLOW -> false
            else -> return
        }
        if (state.followAction == FollowAction.InProgress) return
        content.update { it.copy(followAction = FollowAction.InProgress) }
        viewModelScope.launch {
            when (val result = people.setFollowing(userId, target)) {
                FollowResult.Success -> {
                    // Confirma no servidor a nova relação e as contagens.
                    val generation = ++loadGeneration
                    val reloaded = people.profile(userId)
                    if (generation == loadGeneration) {
                        content.update {
                            when (reloaded) {
                                is DataResult.Success -> it.copy(load = PublicProfileLoad.Loaded(reloaded.value), followAction = FollowAction.Idle)
                                // A ação foi feita, mas não foi possível confirmar: não se inventa o novo estado.
                                is DataResult.Failure -> it.copy(followAction = FollowAction.ResultUnconfirmed)
                            }
                        }
                    } else {
                        content.update { it.copy(followAction = FollowAction.Idle) }
                    }
                }
                is FollowResult.Failure -> content.update { it.copy(followAction = FollowAction.Failed(result.error)) }
            }
        }
    }

    private fun load(silent: Boolean) {
        val generation = ++loadGeneration
        if (!silent) content.update { it.copy(load = PublicProfileLoad.Loading) }
        viewModelScope.launch {
            val result = people.profile(userId)
            if (generation != loadGeneration) return@launch
            content.update {
                when (result) {
                    is DataResult.Success -> it.copy(load = PublicProfileLoad.Loaded(result.value))
                    // Numa atualização silenciosa falhada, mantém-se o último perfil confirmado.
                    is DataResult.Failure -> if (silent && it.profile != null) it else it.copy(load = PublicProfileLoad.Error(result.error))
                }
            }
        }
    }

    companion object {
        const val USER_ID_ARG = "userId"
        const val KEY_PUBLICATION_SORT = "person.publicationSort"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PortoFitApplication
                PublicProfileViewModel(
                    app.container.peopleRepository,
                    app.container.catalogRepository,
                    app.container.sessionManager.state,
                    app.container.experiencesRepository,
                    app.container.experienceChanges,
                    createSavedStateHandle(),
                )
            }
        }
    }

    fun retryPublications() = publicationList.load()

    /** Recomeça na primeira página com a nova ordenação (guardada para a rotação). */
    fun setPublicationSort(sort: ExperienceSort) {
        savedState[KEY_PUBLICATION_SORT] = sort.name
        publicationList.setSort(sort)
    }

    fun loadMorePublications() = publicationList.loadMore()
}

// ---------------------------------------------------------------- Seguidores e seguidos

enum class FollowListKind { FOLLOWERS, FOLLOWING }

sealed interface FollowListState {
    data object Loading : FollowListState
    data class Error(val error: DataError) : FollowListState
    data class Loaded(
        val people: List<UserSummary>,
        val page: Int,
        val totalItems: Long,
        val hasMore: Boolean,
        val isLoadingMore: Boolean = false,
        val loadMoreError: DataError? = null,
    ) : FollowListState
}

/**
 * Lista pública de seguidores ou de pessoas seguidas, paginada (docs/users-api.md §3.10). O estado fica
 * no ViewModel: abrir um perfil e voltar mantém a lista e a posição carregada.
 */
class FollowListViewModel(
    private val people: PeopleRepository,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val userId: String = checkNotNull(savedState[USER_ID_ARG])
    val kind: FollowListKind = FollowListKind.valueOf(checkNotNull(savedState[KIND_ARG]))

    private val _uiState = MutableStateFlow<FollowListState>(FollowListState.Loading)
    val uiState: StateFlow<FollowListState> = _uiState.asStateFlow()

    private var generation = 0
    private var job: Job? = null

    init {
        load()
    }

    fun retry() = load()

    fun loadMore() {
        val current = _uiState.value as? FollowListState.Loaded ?: return
        if (!current.hasMore || current.isLoadingMore) return
        val myGeneration = generation
        _uiState.value = current.copy(isLoadingMore = true, loadMoreError = null)
        job = viewModelScope.launch {
            val result = fetch(current.page + 1)
            if (myGeneration != generation) return@launch
            _uiState.update { state ->
                val loaded = state as? FollowListState.Loaded ?: return@update state
                when (result) {
                    is DataResult.Success -> loaded.copy(
                        people = (loaded.people + result.value.items).distinctBy { it.userId },
                        page = result.value.page,
                        totalItems = result.value.totalItems,
                        hasMore = result.value.hasMore,
                        isLoadingMore = false,
                    )
                    is DataResult.Failure -> loaded.copy(isLoadingMore = false, loadMoreError = result.error)
                }
            }
        }
    }

    private fun load() {
        job?.cancel()
        val myGeneration = ++generation
        _uiState.value = FollowListState.Loading
        job = viewModelScope.launch {
            val result = fetch(0)
            if (myGeneration != generation) return@launch
            _uiState.value = when (result) {
                is DataResult.Success -> FollowListState.Loaded(result.value.items, result.value.page, result.value.totalItems, result.value.hasMore)
                is DataResult.Failure -> FollowListState.Error(result.error)
            }
        }
    }

    private suspend fun fetch(page: Int) = when (kind) {
        FollowListKind.FOLLOWERS -> people.followers(userId, page, PAGE_SIZE)
        FollowListKind.FOLLOWING -> people.following(userId, page, PAGE_SIZE)
    }

    companion object {
        const val USER_ID_ARG = "userId"
        const val KIND_ARG = "kind"
        const val PAGE_SIZE = 20

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PortoFitApplication
                FollowListViewModel(app.container.peopleRepository, createSavedStateHandle())
            }
        }
    }
}
