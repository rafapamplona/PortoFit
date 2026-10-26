package com.example.portofit.ui.feature.search

import androidx.lifecycle.SavedStateHandle
import com.example.portofit.data.location.LocationFix
import com.example.portofit.data.location.LocationResult
import com.example.portofit.data.model.NearbyFilter
import com.example.portofit.data.model.Page
import com.example.portofit.data.remote.AddressDto
import com.example.portofit.data.remote.CatalogApi
import com.example.portofit.data.remote.LocationDto
import com.example.portofit.data.remote.OfferingDetailDto
import com.example.portofit.data.remote.PageDto
import com.example.portofit.data.remote.PlaceDetailDto
import com.example.portofit.data.remote.PlaceSummaryDto
import com.example.portofit.data.remote.RatingDto
import com.example.portofit.data.remote.SportsResponseDto
import com.example.portofit.data.repository.DataError
import com.example.portofit.data.repository.DataResult
import com.example.portofit.data.repository.NetworkCatalogRepository
import com.example.portofit.testing.FakeCatalogRepository
import com.example.portofit.testing.FakeCatalogRepository.Companion.pageOf
import com.example.portofit.testing.FakeCatalogRepository.Companion.place
import com.example.portofit.testing.FakeLocationProvider
import com.example.portofit.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Pesquisa por proximidade (docs/catalog-api.md §3.2) com localização e catálogo simulados. */
class NearbySearchTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val repository = FakeCatalogRepository()
    private val location = FakeLocationProvider()

    private fun found(ageMillis: Long = 0, approximate: Boolean = false) =
        LocationResult.Found(LocationFix(PORTO_LAT, PORTO_LNG, ageMillis, approximate))

    private fun createViewModel(savedState: SavedStateHandle = SavedStateHandle()) = SearchViewModel(repository, savedState, location)

    @Test
    fun `ao abrir nao pede localizacao e a pesquisa e a normal`() {
        val vm = createViewModel()
        assertEquals(NearbyState.Off, vm.uiState.value.nearby)
        assertEquals(0, location.calls)
        assertNull(repository.searchCalls.single().near)
    }

    @Test
    fun `usar a localizacao pesquisa por raio com texto e modalidade e mostra a distancia`() {
        repository.searchResult = { call ->
            DataResult.Success(Page(listOf(place("perto").copy(distanceKm = 1.02)), call.page, 1, 1))
        }
        location.result = { found() }
        val vm = createViewModel()
        vm.onQueryInputChange("boxe")
        vm.onSearchSubmit()
        vm.onSportSelected(FakeCatalogRepository.BOXE.id)
        vm.onUseLocation()

        val nearby = vm.uiState.value.nearby as NearbyState.Active
        assertEquals(NearbyFilter(PORTO_LAT, PORTO_LNG, 5), nearby.filter)
        assertEquals(false, nearby.approximate)
        assertNull(nearby.ageMinutes)
        val call = repository.searchCalls.last()
        assertEquals(Triple("boxe", "boxe", NearbyFilter(PORTO_LAT, PORTO_LNG, 5)), Triple(call.query, call.sportId, call.near))
        assertEquals(1.02, (vm.uiState.value.results as ResultsState.Loaded).places.single().distanceKm)
        assertEquals(1, location.calls)
    }

    @Test
    fun `localizacao aproximada e anterior recente ficam assinaladas, demasiado antiga nao e usada`() {
        location.result = { found(ageMillis = 5 * 60_000, approximate = true) }
        val vm = createViewModel()
        vm.onUseLocation()
        val active = vm.uiState.value.nearby as NearbyState.Active
        assertEquals(true, active.approximate)
        assertEquals(5, active.ageMinutes)

        location.result = { found(ageMillis = SearchViewModel.MAX_FIX_AGE_MILLIS + 1) }
        vm.onUseLocation()
        assertEquals(NearbyState.Failed(LocationFailure.TOO_OLD), vm.uiState.value.nearby)
        assertNull("volta à pesquisa normal", repository.searchCalls.last().near)
    }

    @Test
    fun `falhas de localizacao nunca impedem a pesquisa por texto`() {
        val failures = mapOf(
            LocationResult.PermissionMissing to LocationFailure.PERMISSION_DENIED,
            LocationResult.Disabled to LocationFailure.DISABLED,
            LocationResult.Timeout to LocationFailure.TIMEOUT,
            LocationResult.Unavailable to LocationFailure.UNAVAILABLE,
        )
        for ((result, failure) in failures) {
            location.result = { result }
            val vm = createViewModel()
            vm.onUseLocation()
            assertEquals(NearbyState.Failed(failure), vm.uiState.value.nearby)
            vm.onQueryInputChange("natação")
            vm.onSearchSubmit()
            assertEquals(SearchCallSummary("natação", null), repository.searchCalls.last().let { SearchCallSummary(it.query, it.near) })
            assertTrue(vm.uiState.value.results is ResultsState.Loaded)
        }
    }

    @Test
    fun `permissao recusada, ou bloqueada, nao pede a localizacao`() {
        val vm = createViewModel()
        vm.onLocationPermissionDenied(blocked = false)
        assertEquals(NearbyState.Failed(LocationFailure.PERMISSION_DENIED), vm.uiState.value.nearby)
        vm.onLocationPermissionDenied(blocked = true)
        assertEquals(NearbyState.Failed(LocationFailure.PERMISSION_BLOCKED), vm.uiState.value.nearby)
        assertEquals(0, location.calls)
        vm.onDisableNearby()
        assertEquals(NearbyState.Off, vm.uiState.value.nearby)
        assertEquals(1, repository.searchCalls.size) // fechar o aviso não repete a pesquisa normal
    }

    @Test
    fun `mudar o raio repete a pesquisa com a mesma posicao, sem pedir outra`() {
        location.result = { found() }
        val vm = createViewModel()
        vm.onRadiusSelected(10) // ainda sem proximidade: só guarda a escolha
        assertEquals(1, repository.searchCalls.size)
        vm.onUseLocation()
        assertEquals(10, repository.searchCalls.last().near?.radiusKm)
        vm.onRadiusSelected(1)
        assertEquals(NearbyFilter(PORTO_LAT, PORTO_LNG, 1), repository.searchCalls.last().near)
        vm.onRadiusSelected(50) // fora das opções
        assertEquals(1, vm.uiState.value.radiusKm)
        assertEquals(1, location.calls)
    }

    @Test
    fun `desativar volta a pesquisa normal e cancela uma localizacao em curso`() {
        location.result = { found() }
        val vm = createViewModel()
        vm.onUseLocation()
        vm.onDisableNearby()
        assertEquals(NearbyState.Off, vm.uiState.value.nearby)
        assertNull(repository.searchCalls.last().near)

        val gate = CompletableDeferred<LocationResult>()
        location.result = { gate.await() }
        vm.onUseLocation()
        assertEquals(NearbyState.Locating, vm.uiState.value.nearby)
        val calls = repository.searchCalls.size
        vm.onDisableNearby()
        gate.complete(found())
        assertEquals(NearbyState.Off, vm.uiState.value.nearby)
        assertEquals(calls, repository.searchCalls.size)
    }

    @Test
    fun `carregar mais continua a pesquisa por proximidade, mesmo durante uma nova localizacao`() {
        repository.searchResult = { call -> DataResult.Success(pageOf(call.page, 3)) }
        location.result = { found() }
        val vm = createViewModel()
        vm.onUseLocation()
        val gate = CompletableDeferred<LocationResult>()
        location.result = { gate.await() }
        vm.onUseLocation() // "Atualizar localização"
        vm.loadMore()
        assertEquals(1, repository.searchCalls.last().page)
        assertEquals(NearbyFilter(PORTO_LAT, PORTO_LNG, 5), repository.searchCalls.last().near)
        // A atualização falha: volta à pesquisa normal.
        gate.complete(LocationResult.Timeout)
        assertEquals(NearbyState.Failed(LocationFailure.TIMEOUT), vm.uiState.value.nearby)
        assertNull(repository.searchCalls.last().near)
    }

    @Test
    fun `um erro na pesquisa por proximidade tem nova tentativa com o mesmo filtro`() {
        location.result = { found() }
        val vm = createViewModel()
        repository.searchResult = { DataResult.Failure(DataError.NETWORK) }
        vm.onUseLocation()
        assertEquals(ResultsState.Error(DataError.NETWORK), vm.uiState.value.results)
        repository.searchResult = { call -> DataResult.Success(pageOf(call.page, 1)) }
        vm.retrySearch()
        assertEquals(NearbyFilter(PORTO_LAT, PORTO_LNG, 5), repository.searchCalls.last().near)
        assertTrue(vm.uiState.value.results is ResultsState.Loaded)
    }

    @Test
    fun `depois da morte do processo guarda o raio mas nao as coordenadas nem volta a pedir`() {
        location.result = { found() }
        val saved = SavedStateHandle()
        createViewModel(saved).apply {
            onQueryInputChange("boxe")
            onSearchSubmit()
            onRadiusSelected(3)
            onUseLocation()
        }
        assertTrue(saved.keys().none { saved.get<Any>(it).toString().contains(PORTO_LAT.toString()) })
        val restored = createViewModel(saved)
        assertEquals(NearbyState.Off, restored.uiState.value.nearby)
        assertEquals(3, restored.uiState.value.radiusKm)
        assertEquals("boxe", restored.uiState.value.activeQuery)
        assertEquals(1, location.calls)
        assertNull(repository.searchCalls.last().near)
    }

    @Test
    fun `distancia como o utilizador a le`() {
        assertEquals("850 m", formatDistance(0.85))
        assertEquals("1,0 km", formatDistance(0.996))
        assertEquals("1,0 km", formatDistance(1.02))
        assertEquals("4,4 km", formatDistance(4.37))
        assertEquals("12 km", formatDistance(12.4))
    }

    @Test
    fun `o repositorio envia as coordenadas, o raio e a ordenacao do contrato`() = runTest {
        val api = RecordingCatalogApi()
        val result = NetworkCatalogRepository(api).searchPlaces("boxe", null, 0, 20, NearbyFilter(41.1495843, -8.6108, 3))
        assertEquals(listOf("41.149584", "-8.610800", "3", "distance"), api.nearParams)
        assertEquals(1.02, (result as DataResult.Success).value.items.single().distanceKm)
        NetworkCatalogRepository(api).searchPlaces("boxe", null, 0, 20)
        assertEquals(listOf(null, null, null, null), api.nearParams)
    }

    private data class SearchCallSummary(val query: String?, val near: NearbyFilter?)

    private class RecordingCatalogApi : CatalogApi {
        var nearParams: List<String?> = emptyList()

        override suspend fun getSports() = SportsResponseDto(emptyList())

        override suspend fun searchPlaces(
            query: String?, sportId: String?, latitude: String?, longitude: String?, radiusKm: String?, sort: String?, page: Int, size: Int,
        ): PageDto<PlaceSummaryDto> {
            nearParams = listOf(latitude, longitude, radiusKm, sort)
            return PageDto(
                items = listOf(
                    PlaceSummaryDto(
                        id = "p1", name = "Local", address = AddressDto("Rua Exemplo", "4000-000", "Bonfim", "Porto"),
                        location = LocationDto(41.14, -8.61), sports = emptyList(), rating = RatingDto("UNAVAILABLE"), demo = true,
                        distanceKm = if (latitude != null) 1.02 else null,
                    ),
                ),
                page = 0, size = size, totalItems = 1, totalPages = 1,
            )
        }

        override suspend fun getPlace(placeId: String): PlaceDetailDto = error("não usado")

        override suspend fun getOffering(offeringId: String): OfferingDetailDto = error("não usado")
    }

    private companion object {
        const val PORTO_LAT = 41.1496
        const val PORTO_LNG = -8.6109
    }
}
