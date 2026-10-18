package com.example.portofit.ui.feature.search

import androidx.lifecycle.SavedStateHandle
import com.example.portofit.data.model.Page
import com.example.portofit.data.model.PlaceSummary
import com.example.portofit.data.repository.DataError
import com.example.portofit.data.repository.DataResult
import com.example.portofit.testing.FakeCatalogRepository
import com.example.portofit.testing.FakeCatalogRepository.Companion.pageOf
import com.example.portofit.testing.MainDispatcherRule
import com.example.portofit.testing.SearchCall
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SearchViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val repository = FakeCatalogRepository()

    private fun createViewModel(savedState: SavedStateHandle = SavedStateHandle()) = SearchViewModel(repository, savedState)

    private val SearchViewModel.loaded get() = uiState.value.results as ResultsState.Loaded

    @Test
    fun `ao abrir carrega modalidades e a primeira pagina de todos os locais`() {
        val viewModel = createViewModel()

        assertEquals(SportsState.Loaded(listOf(FakeCatalogRepository.BOXE, FakeCatalogRepository.NATACAO)), viewModel.uiState.value.sports)
        assertEquals(listOf(SearchCall(null, null, 0, SearchViewModel.PAGE_SIZE)), repository.searchCalls)
        assertEquals(2, viewModel.loaded.places.size)
    }

    @Test
    fun `falha nas modalidades nao impede a pesquisa por texto`() {
        repository.sportsResult = { DataResult.Failure(DataError.NETWORK) }
        val viewModel = createViewModel()

        assertEquals(SportsState.Error, viewModel.uiState.value.sports)
        viewModel.onQueryInputChange("boxe")
        viewModel.onSearchSubmit()
        assertEquals("boxe", repository.searchCalls.last().query)
        assertTrue(viewModel.uiState.value.results is ResultsState.Loaded)

        repository.sportsResult = { DataResult.Success(listOf(FakeCatalogRepository.BOXE)) }
        viewModel.retrySports()
        assertEquals(SportsState.Loaded(listOf(FakeCatalogRepository.BOXE)), viewModel.uiState.value.sports)
    }

    @Test
    fun `erro na pesquisa e nova tentativa`() {
        repository.searchResult = { DataResult.Failure(DataError.NETWORK) }
        val viewModel = createViewModel()
        assertEquals(ResultsState.Error(DataError.NETWORK), viewModel.uiState.value.results)

        repository.searchResult = { DataResult.Success(pageOf(0, 1)) }
        viewModel.retrySearch()
        assertTrue(viewModel.uiState.value.results is ResultsState.Loaded)
    }

    @Test
    fun `lista vazia`() {
        repository.searchResult = { DataResult.Success(Page(emptyList(), 0, 0, 0)) }
        val viewModel = createViewModel()

        assertTrue(viewModel.loaded.places.isEmpty())
        assertFalse(viewModel.loaded.hasMore)
    }

    @Test
    fun `nova pesquisa reinicia a paginacao e apara espacos`() {
        repository.searchResult = { call -> DataResult.Success(pageOf(call.page, 3)) }
        val viewModel = createViewModel()
        viewModel.loadMore()
        assertEquals(1, viewModel.loaded.page)

        viewModel.onQueryInputChange("  boxe ")
        viewModel.onSearchSubmit()

        assertEquals(SearchCall("boxe", null, 0, SearchViewModel.PAGE_SIZE), repository.searchCalls.last())
        assertEquals(0, viewModel.loaded.page)
        assertEquals("boxe", viewModel.uiState.value.activeQuery)
    }

    @Test
    fun `pesquisa com um caractere nao faz pedido e mostra aviso`() {
        val viewModel = createViewModel()
        val callsBefore = repository.searchCalls.size

        viewModel.onQueryInputChange("b")
        viewModel.onSearchSubmit()

        assertTrue(viewModel.uiState.value.showQueryTooShort)
        assertEquals(callsBefore, repository.searchCalls.size)

        viewModel.onQueryInputChange("bo")
        assertFalse(viewModel.uiState.value.showQueryTooShort)
    }

    @Test
    fun `mudar de modalidade reinicia a paginacao com o filtro`() {
        val viewModel = createViewModel()

        viewModel.onSportSelected("natacao")
        assertEquals(SearchCall(null, "natacao", 0, SearchViewModel.PAGE_SIZE), repository.searchCalls.last())
        assertEquals("natacao", viewModel.uiState.value.selectedSportId)

        // Voltar a escolher a mesma modalidade não repete o pedido.
        val calls = repository.searchCalls.size
        viewModel.onSportSelected("natacao")
        assertEquals(calls, repository.searchCalls.size)

        viewModel.onSportSelected(null)
        assertNull(repository.searchCalls.last().sportId)
    }

    @Test
    fun `carregar mais acrescenta a pagina seguinte ate ao fim`() {
        repository.searchResult = { call -> DataResult.Success(pageOf(call.page, 2)) }
        val viewModel = createViewModel()
        assertTrue(viewModel.loaded.hasMore)

        viewModel.loadMore()

        assertEquals(1, repository.searchCalls.last().page)
        assertEquals(listOf("p0-1", "p0-2", "p1-1", "p1-2"), viewModel.loaded.places.map { it.id })
        assertFalse(viewModel.loaded.hasMore)

        // Sem mais páginas, não faz novo pedido.
        val calls = repository.searchCalls.size
        viewModel.loadMore()
        assertEquals(calls, repository.searchCalls.size)
    }

    @Test
    fun `erro ao carregar mais mantem os resultados ja mostrados`() {
        repository.searchResult = { call ->
            if (call.page == 0) DataResult.Success(pageOf(0, 2)) else DataResult.Failure(DataError.SERVER)
        }
        val viewModel = createViewModel()

        viewModel.loadMore()

        assertEquals(2, viewModel.loaded.places.size)
        assertEquals(DataError.SERVER, viewModel.loaded.loadMoreError)
        assertFalse(viewModel.loaded.isLoadingMore)
        assertTrue(viewModel.loaded.hasMore)
    }

    @Test
    fun `resposta de uma pesquisa antiga nao substitui a mais recente`() {
        val slowBoxe = CompletableDeferred<DataResult<Page<PlaceSummary>>>()
        repository.searchResult = { call ->
            when (call.query) {
                "boxe" -> slowBoxe.await()
                "ioga" -> DataResult.Success(Page(listOf(FakeCatalogRepository.place("ioga-1")), 0, 1, 1))
                else -> DataResult.Success(pageOf(0, 1))
            }
        }
        val viewModel = createViewModel()

        viewModel.onQueryInputChange("boxe")
        viewModel.onSearchSubmit() // fica à espera
        viewModel.onQueryInputChange("ioga")
        viewModel.onSearchSubmit() // responde logo

        // A resposta atrasada de "boxe" chega depois e tem de ser ignorada.
        slowBoxe.complete(DataResult.Success(Page(listOf(FakeCatalogRepository.place("boxe-1")), 0, 1, 1)))

        assertEquals(listOf("ioga-1"), viewModel.loaded.places.map { it.id })
        assertEquals("ioga", viewModel.uiState.value.activeQuery)
    }

    @Test
    fun `pagina de carregar mais antiga e ignorada apos nova pesquisa`() {
        val slowPage1 = CompletableDeferred<DataResult<Page<PlaceSummary>>>()
        repository.searchResult = { call ->
            if (call.page == 1) slowPage1.await() else DataResult.Success(pageOf(0, 2))
        }
        val viewModel = createViewModel()

        viewModel.loadMore()                 // pede a página 1 (lenta)
        viewModel.onSportSelected("boxe")    // nova pesquisa entretanto
        slowPage1.complete(DataResult.Success(pageOf(1, 2)))

        assertEquals(listOf("p0-1", "p0-2"), viewModel.loaded.places.map { it.id })
        assertEquals(0, viewModel.loaded.page)
    }

    @Test
    fun `alternar para pessoas e voltar mantem a pesquisa de locais`() {
        val saved = SavedStateHandle()
        val viewModel = createViewModel(saved)
        viewModel.onQueryInputChange("boxe")
        viewModel.onSearchSubmit()
        viewModel.onSportSelected("boxe")
        val calls = repository.searchCalls.size
        val before = viewModel.uiState.value

        viewModel.onModeChange(SearchMode.PEOPLE)
        viewModel.onModeChange(SearchMode.PLACES)

        assertEquals(before, viewModel.uiState.value)
        assertEquals(calls, repository.searchCalls.size) // alternar não repete a pesquisa
        viewModel.onModeChange(SearchMode.PEOPLE)
        assertEquals(SearchMode.PEOPLE, createViewModel(saved).uiState.value.mode) // modo reposto ao recriar
    }

    @Test
    fun `pesquisa e filtro guardados sao repostos ao recriar`() {
        val viewModel = createViewModel()
        viewModel.onQueryInputChange("boxe")
        viewModel.onSearchSubmit()
        viewModel.onSportSelected("boxe")

        // Simula a recriação (ex.: morte do processo) com o mesmo estado guardado.
        val savedState = SavedStateHandle(
            mapOf("search.input" to "boxe", "search.query" to "boxe", "search.sport" to "boxe"),
        )
        repository.searchCalls.clear()
        val restored = createViewModel(savedState)

        assertEquals("boxe", restored.uiState.value.queryInput)
        assertEquals("boxe", restored.uiState.value.selectedSportId)
        assertEquals(SearchCall("boxe", "boxe", 0, SearchViewModel.PAGE_SIZE), repository.searchCalls.single())
    }
}
