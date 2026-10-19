package com.example.portofit.ui.feature.place

import androidx.lifecycle.SavedStateHandle
import com.example.portofit.data.auth.SessionState
import com.example.portofit.data.model.ActivityLevel
import com.example.portofit.data.model.Address
import com.example.portofit.data.model.CatalogStatus
import com.example.portofit.data.model.ExperienceSort
import com.example.portofit.data.model.Offering
import com.example.portofit.data.model.PlaceDetail
import com.example.portofit.data.model.Rating
import com.example.portofit.data.model.Sport
import com.example.portofit.data.repository.DataError
import com.example.portofit.data.repository.DataResult
import com.example.portofit.data.repository.ExperienceChange
import com.example.portofit.data.repository.ExperienceChanges
import com.example.portofit.testing.FakeCatalogRepository
import com.example.portofit.testing.FakeExperiencesRepository
import com.example.portofit.testing.MainDispatcherRule
import com.example.portofit.ui.feature.experience.ExperienceListState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PlaceDetailViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val repository = FakeCatalogRepository()
    private val experiences = FakeExperiencesRepository()
    private val changes = ExperienceChanges()
    private val session = MutableStateFlow<SessionState>(SessionState.SignedOut())

    private fun offering(id: String, status: CatalogStatus = CatalogStatus.ACTIVE) =
        Offering(id, "Atividade $id", Sport("boxe", "Boxe"), null, ActivityLevel.BEGINNER, null, Rating.NoRatings, status)

    private val place = PlaceDetail(
        id = "p1",
        name = "Local",
        description = null,
        address = Address("Rua Exemplo, 10", "4050-001", null, "Porto"),
        phone = null,
        website = null,
        rating = Rating.Unavailable,
        sports = emptyList(),
        offerings = listOf(offering("o1"), offering("o2", CatalogStatus.RETIRED)),
        isDemo = true,
    )

    private fun createViewModel(saved: SavedStateHandle = SavedStateHandle(mapOf(PlaceDetailViewModel.PLACE_ID_ARG to "p1"))) =
        PlaceDetailViewModel(repository, experiences, changes, session, saved)

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

    // --- Experiências do local ---

    @Test
    fun `experiencias do local paginadas, por data e por estrelas`() {
        repository.placeResult = { DataResult.Success(place) }
        experiences.onList = { call -> DataResult.Success(FakeExperiencesRepository.pageOf(call.page, 2)) }
        val viewModel = createViewModel()

        val first = viewModel.experiences.value as ExperienceListState.Loaded
        assertEquals(2, first.items.size)
        assertTrue(first.hasMore)
        assertEquals("p1", experiences.listCalls.single().placeId)
        assertEquals(ExperienceSort.RECENT, experiences.listCalls.single().sort)

        viewModel.loadMoreExperiences()
        val all = viewModel.experiences.value as ExperienceListState.Loaded
        assertEquals(4, all.items.size)
        assertFalse(all.hasMore)

        viewModel.setSort(ExperienceSort.STARS)
        assertEquals(ExperienceSort.STARS, experiences.listCalls.last().sort)
        assertEquals(0, experiences.listCalls.last().page)
        assertEquals(ExperienceSort.STARS, viewModel.sort.value)
    }

    @Test
    fun `ordenacao escolhida e reposta ao recriar`() {
        repository.placeResult = { DataResult.Success(place) }
        val saved = SavedStateHandle(mapOf(PlaceDetailViewModel.PLACE_ID_ARG to "p1"))
        createViewModel(saved).setSort(ExperienceSort.STARS)
        experiences.listCalls.clear()

        val recreated = createViewModel(saved)
        assertEquals(ExperienceSort.STARS, recreated.sort.value)
        assertEquals(ExperienceSort.STARS, experiences.listCalls.single().sort)
    }

    @Test
    fun `falha das experiencias so afeta essa seccao`() {
        repository.placeResult = { DataResult.Success(place) }
        experiences.onList = { DataResult.Failure(DataError.NETWORK) }
        val viewModel = createViewModel()

        assertEquals(PlaceDetailUiState.Loaded(place), viewModel.uiState.value)
        assertEquals(ExperienceListState.Error(DataError.NETWORK), viewModel.experiences.value)

        experiences.onList = { call -> DataResult.Success(FakeExperiencesRepository.pageOf(call.page, 1)) }
        viewModel.retryExperiences()
        assertTrue(viewModel.experiences.value is ExperienceListState.Loaded)
    }

    @Test
    fun `resposta antiga da lista e ignorada ao mudar a ordenacao`() {
        repository.placeResult = { DataResult.Success(place) }
        val slow = CompletableDeferred<DataResult<com.example.portofit.data.model.Page<com.example.portofit.data.model.Experience>>>()
        experiences.onList = { call -> if (call.sort == ExperienceSort.RECENT) slow.await() else DataResult.Success(FakeExperiencesRepository.pageOf(0, 1)) }
        val viewModel = createViewModel()
        viewModel.setSort(ExperienceSort.STARS)
        slow.complete(DataResult.Failure(DataError.NETWORK))
        assertTrue(viewModel.experiences.value is ExperienceListState.Loaded)
    }

    @Test
    fun `alteracao neste local atualiza a lista e as avaliacoes`() {
        repository.placeResult = { DataResult.Success(place) }
        val viewModel = createViewModel()
        experiences.listCalls.clear()

        val updated = place.copy(rating = Rating.Available(4.0, 1))
        repository.placeResult = { DataResult.Success(updated) }
        changes.notify(ExperienceChange("e1", "p1", "author-1"))

        assertEquals(1, experiences.listCalls.size)
        assertEquals(PlaceDetailUiState.Loaded(updated), viewModel.uiState.value)

        // Alteração noutro local: nada muda.
        changes.notify(ExperienceChange("e2", "p9", "author-1"))
        assertEquals(1, experiences.listCalls.size)
    }

    @Test
    fun `falha ao atualizar a avaliacao mantem o local e avisa`() {
        repository.placeResult = { DataResult.Success(place) }
        val viewModel = createViewModel()

        repository.placeResult = { DataResult.Failure(DataError.NETWORK) }
        changes.notify(ExperienceChange("e1", "p1", "author-1"))

        assertEquals(PlaceDetailUiState.Loaded(place), viewModel.uiState.value)
        assertTrue(viewModel.ratingRefreshFailed.value)
        assertTrue(viewModel.experiences.value is ExperienceListState.Loaded)
    }

    @Test
    fun `so atividades ativas aceitam publicacoes e a sessao e indicada`() {
        repository.placeResult = { DataResult.Success(place) }
        val viewModel = createViewModel()
        val loaded = (viewModel.uiState.value as PlaceDetailUiState.Loaded).place
        assertEquals(listOf(true, false), loaded.offerings.map { it.acceptsExperiences })
        assertFalse(loaded.isRetired)

        assertFalse(viewModel.signedIn.value)
        session.value = SessionState.SignedIn("u1", "ana")
        assertTrue(viewModel.signedIn.value)

        val retired = place.copy(status = CatalogStatus.RETIRED)
        assertTrue(retired.isRetired)
    }
}
