package com.example.portofit.ui.feature.place

import androidx.lifecycle.SavedStateHandle
import com.example.portofit.data.model.Address
import com.example.portofit.data.model.PlaceDetail
import com.example.portofit.data.model.Rating
import com.example.portofit.data.repository.DataError
import com.example.portofit.data.repository.DataResult
import com.example.portofit.testing.FakeCatalogRepository
import com.example.portofit.testing.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PlaceDetailViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val repository = FakeCatalogRepository()

    private val place = PlaceDetail(
        id = "p1",
        name = "Local",
        description = null,
        address = Address("Rua Exemplo, 10", "4050-001", null, "Porto"),
        phone = null,
        website = null,
        rating = Rating.Unavailable,
        sports = emptyList(),
        offerings = emptyList(),
        isDemo = true,
    )

    private fun createViewModel() =
        PlaceDetailViewModel(repository, SavedStateHandle(mapOf(PlaceDetailViewModel.PLACE_ID_ARG to "p1")))

    @Test
    fun `carrega o local do argumento de navegacao`() {
        var requestedId: String? = null
        repository.placeResult = { id -> requestedId = id; DataResult.Success(place) }

        val viewModel = createViewModel()

        assertEquals("p1", requestedId)
        assertEquals(PlaceDetailUiState.Loaded(place), viewModel.uiState.value)
    }

    @Test
    fun `local inexistente`() {
        repository.placeResult = { DataResult.Failure(DataError.NOT_FOUND) }

        assertEquals(PlaceDetailUiState.Error(DataError.NOT_FOUND), createViewModel().uiState.value)
    }

    @Test
    fun `falha de ligacao e recuperacao com nova tentativa`() {
        repository.placeResult = { DataResult.Failure(DataError.NETWORK) }
        val viewModel = createViewModel()
        assertEquals(PlaceDetailUiState.Error(DataError.NETWORK), viewModel.uiState.value)

        repository.placeResult = { DataResult.Success(place) }
        viewModel.retry()

        assertEquals(PlaceDetailUiState.Loaded(place), viewModel.uiState.value)
    }
}
