package com.example.portofit.ui.feature.place

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.portofit.PortoFitApplication
import com.example.portofit.data.model.PlaceDetail
import com.example.portofit.data.repository.CatalogRepository
import com.example.portofit.data.repository.DataError
import com.example.portofit.data.repository.DataResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PlaceDetailUiState {
    data object Loading : PlaceDetailUiState
    data class Error(val error: DataError) : PlaceDetailUiState
    data class Loaded(val place: PlaceDetail) : PlaceDetailUiState
}

/** Detalhe de um local. O id chega pelo argumento de navegação [PLACE_ID_ARG]. */
class PlaceDetailViewModel(
    private val repository: CatalogRepository,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val placeId: String = checkNotNull(savedState[PLACE_ID_ARG]) { "Falta o argumento $PLACE_ID_ARG" }

    private val _uiState = MutableStateFlow<PlaceDetailUiState>(PlaceDetailUiState.Loading)
    val uiState: StateFlow<PlaceDetailUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    fun retry() = load()

    private fun load() {
        loadJob?.cancel()
        _uiState.value = PlaceDetailUiState.Loading
        loadJob = viewModelScope.launch {
            _uiState.value = when (val result = repository.getPlace(placeId)) {
                is DataResult.Success -> PlaceDetailUiState.Loaded(result.value)
                is DataResult.Failure -> PlaceDetailUiState.Error(result.error)
            }
        }
    }

    companion object {
        const val PLACE_ID_ARG = "placeId"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PortoFitApplication
                PlaceDetailViewModel(app.container.catalogRepository, createSavedStateHandle())
            }
        }
    }
}
