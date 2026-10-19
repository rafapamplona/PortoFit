package com.example.portofit.ui.feature.place

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.portofit.PortoFitApplication
import com.example.portofit.data.auth.SessionState
import com.example.portofit.data.model.ExperienceSort
import com.example.portofit.data.model.PlaceDetail
import com.example.portofit.data.repository.CatalogRepository
import com.example.portofit.data.repository.DataError
import com.example.portofit.data.repository.DataResult
import com.example.portofit.data.repository.ExperienceChanges
import com.example.portofit.data.repository.ExperiencesRepository
import com.example.portofit.ui.feature.experience.ExperienceListLoader
import com.example.portofit.ui.feature.experience.ExperienceListState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface PlaceDetailUiState {
    data object Loading : PlaceDetailUiState
    data class Error(val error: DataError) : PlaceDetailUiState
    data class Loaded(val place: PlaceDetail) : PlaceDetailUiState
}

/**
 * Detalhe de um local (catálogo) com as experiências publicadas (experiences-service). As duas partes
 * carregam e falham de forma independente. O id chega pelo argumento de navegação [PLACE_ID_ARG].
 *
 * Depois de uma publicação, edição ou eliminação neste local, a lista e as avaliações são pedidas de
 * novo. Se o catálogo falhar nessa atualização, mantém-se o local já mostrado (com um aviso): a
 * publicação, já confirmada, não é posta em causa.
 */
class PlaceDetailViewModel(
    private val repository: CatalogRepository,
    experiences: ExperiencesRepository,
    changes: ExperienceChanges,
    session: StateFlow<SessionState>,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val placeId: String = checkNotNull(savedState[PLACE_ID_ARG]) { "Falta o argumento $PLACE_ID_ARG" }

    private val _uiState = MutableStateFlow<PlaceDetailUiState>(PlaceDetailUiState.Loading)
    val uiState: StateFlow<PlaceDetailUiState> = _uiState.asStateFlow()

    private val experienceList = ExperienceListLoader(
        viewModelScope,
        experiences,
        placeId = placeId,
        initialSort = savedState.get<String>(KEY_SORT)?.let { name -> ExperienceSort.entries.firstOrNull { it.name == name } } ?: ExperienceSort.RECENT,
        counts = changes.counts,
    )
    val experiences: StateFlow<ExperienceListState> = experienceList.state
    val sort: StateFlow<ExperienceSort> = experienceList.sort

    val signedIn: StateFlow<Boolean> = session.map { it is SessionState.SignedIn }
        .stateIn(viewModelScope, SharingStarted.Eagerly, session.value is SessionState.SignedIn)

    private val _ratingRefreshFailed = MutableStateFlow(false)
    /** A última atualização das avaliações falhou; o local mostrado é o anterior. */
    val ratingRefreshFailed: StateFlow<Boolean> = _ratingRefreshFailed.asStateFlow()

    private var loadJob: Job? = null

    init {
        load(silent = false)
        experienceList.load()
        viewModelScope.launch {
            changes.events.filter { it.placeId == placeId }.collect {
                experienceList.load()
                load(silent = true)
            }
        }
    }

    fun retry() = load(silent = false)

    fun retryExperiences() = experienceList.load()

    fun loadMoreExperiences() = experienceList.loadMore()

    fun setSort(sort: ExperienceSort) {
        savedState[KEY_SORT] = sort.name
        experienceList.setSort(sort)
    }

    private fun load(silent: Boolean) {
        loadJob?.cancel()
        val previous = (_uiState.value as? PlaceDetailUiState.Loaded)
        if (!silent || previous == null) _uiState.value = PlaceDetailUiState.Loading
        loadJob = viewModelScope.launch {
            when (val result = repository.getPlace(placeId)) {
                is DataResult.Success -> {
                    _ratingRefreshFailed.value = false
                    _uiState.value = PlaceDetailUiState.Loaded(result.value)
                }
                is DataResult.Failure ->
                    if (silent && previous != null) {
                        _ratingRefreshFailed.value = true
                        _uiState.value = previous
                    } else {
                        _uiState.value = PlaceDetailUiState.Error(result.error)
                    }
            }
        }
    }

    companion object {
        const val PLACE_ID_ARG = "placeId"
        const val KEY_SORT = "place.experienceSort"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PortoFitApplication
                PlaceDetailViewModel(
                    app.container.catalogRepository,
                    app.container.experiencesRepository,
                    app.container.experienceChanges,
                    app.container.sessionManager.state,
                    createSavedStateHandle(),
                )
            }
        }
    }
}
