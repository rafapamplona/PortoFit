package com.example.portofit.ui.feature.search

import androidx.lifecycle.SavedStateHandle
import com.example.portofit.data.model.Experience
import com.example.portofit.data.model.ExperienceSort
import com.example.portofit.data.model.Page
import com.example.portofit.data.repository.DataError
import com.example.portofit.data.repository.DataResult
import com.example.portofit.data.repository.ExperienceChange
import com.example.portofit.data.repository.ExperienceChanges
import com.example.portofit.data.repository.ExperienceCounts
import com.example.portofit.testing.FakeExperiencesRepository
import com.example.portofit.testing.MainDispatcherRule
import com.example.portofit.testing.ExperienceSearchCall
import com.example.portofit.ui.feature.experience.ExperienceListState
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ExperienceSearchViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val repository = FakeExperiencesRepository()
    private val changes = ExperienceChanges()

    private fun viewModel(saved: Map<String, Any?> = emptyMap(), state: SavedStateHandle = SavedStateHandle(saved)) =
        ExperienceSearchViewModel(repository, changes, state)

    private fun ExperienceSearchViewModel.submit(text: String) {
        onQueryInputChange(text)
        onSubmit()
    }

    private val ExperienceSearchViewModel.loaded get() = results.value as ExperienceListState.Loaded

    private fun pages(pageSize: Int = 2, totalPages: Int = 2): suspend (ExperienceSearchCall) -> DataResult<Page<Experience>> = { call ->
        DataResult.Success(FakeExperiencesRepository.pageOf(call.page, totalPages, "p1", pageSize))
    }

    @Test
    fun `sem submissao nao ha pedidos`() {
        val vm = viewModel()
        vm.onQueryInputChange("boxe")
        assertTrue(repository.searchCalls.isEmpty())
        assertTrue(repository.listCalls.isEmpty())
        assertNull(vm.uiState.value.activeQuery)
    }

    @Test
    fun `submissao usa o texto aparado e o contrato de 2 a 100 caracteres`() {
        val vm = viewModel()

        vm.submit("  ")
        assertTrue(vm.uiState.value.showQueryInvalid)
        vm.submit(" a ")
        assertTrue(vm.uiState.value.showQueryInvalid)
        assertTrue(repository.searchCalls.isEmpty())

        vm.submit("  boxe  ")
        assertFalse(vm.uiState.value.showQueryInvalid)
        assertEquals("boxe", vm.uiState.value.activeQuery)
        assertEquals(ExperienceSearchCall("boxe", ExperienceSort.RECENT, 0, 10), repository.searchCalls.single())
    }

    @Test
    fun `um pedido invalido nao apaga os resultados da pesquisa anterior`() {
        val vm = viewModel()
        vm.submit("boxe")
        val before = vm.results.value

        vm.submit("a")

        assertEquals("boxe", vm.uiState.value.activeQuery)
        assertEquals(before, vm.results.value)
        assertEquals(1, repository.searchCalls.size)
    }

    @Test
    fun `o campo limita a 100 caracteres sem partir um emoji`() {
        val vm = viewModel()
        vm.onQueryInputChange("a".repeat(150))
        assertEquals(100, vm.uiState.value.queryInput.length)

        val emoji = "🥊"
        vm.onQueryInputChange(emoji.repeat(120))
        val input = vm.uiState.value.queryInput
        assertEquals(100, input.codePointCount(0, input.length))
        assertEquals(emoji.repeat(100), input)
    }

    @Test
    fun `dois emojis contam como dois caracteres, como no servidor`() {
        val vm = viewModel()
        val emoji = "🥊"
        vm.submit(emoji)
        assertTrue(vm.uiState.value.showQueryInvalid)
        vm.submit(emoji + emoji)
        assertEquals(emoji + emoji, repository.searchCalls.single().query)
    }

    @Test
    fun `paginacao junta as paginas sem repetir e mantem a pesquisa`() {
        repository.onSearch = pages()
        val vm = viewModel()
        vm.submit("boxe")
        assertTrue(vm.loaded.hasMore)

        vm.loadMore()

        assertEquals(listOf(0, 1), repository.searchCalls.map { it.page })
        assertEquals(listOf("boxe", "boxe"), repository.searchCalls.map { it.query })
        assertEquals(listOf("e0-1", "e0-2", "e1-1", "e1-2"), vm.loaded.items.map { it.id })
        assertFalse(vm.loaded.hasMore)
    }

    @Test
    fun `as quatro ordenacoes recomecam na primeira pagina com a mesma pesquisa e ficam guardadas`() {
        repository.onSearch = pages()
        val state = SavedStateHandle()
        val vm = viewModel(state = state)
        vm.submit("boxe")
        vm.loadMore()

        vm.setSort(ExperienceSort.STARS)
        vm.setSort(ExperienceSort.LIKES)
        vm.setSort(ExperienceSort.INTERACTIONS)

        assertEquals(
            listOf(
                ExperienceSearchCall("boxe", ExperienceSort.RECENT, 0, 10),
                ExperienceSearchCall("boxe", ExperienceSort.RECENT, 1, 10),
                ExperienceSearchCall("boxe", ExperienceSort.STARS, 0, 10),
                ExperienceSearchCall("boxe", ExperienceSort.LIKES, 0, 10),
                ExperienceSearchCall("boxe", ExperienceSort.INTERACTIONS, 0, 10),
            ),
            repository.searchCalls,
        )
        assertEquals(ExperienceSort.INTERACTIONS.name, state[ExperienceSearchViewModel.KEY_SORT])
    }

    @Test
    fun `ordenar antes de pesquisar nao faz pedidos e vale para a pesquisa seguinte`() {
        val vm = viewModel()
        vm.setSort(ExperienceSort.STARS)
        assertTrue(repository.searchCalls.isEmpty())

        vm.submit("boxe")

        assertEquals(ExperienceSort.STARS, repository.searchCalls.single().sort)
    }

    @Test
    fun `resposta de uma pesquisa antiga e ignorada`() {
        val first = CompletableDeferred<DataResult<Page<Experience>>>()
        repository.onSearch = { call ->
            if (call.query == "antiga") first.await() else DataResult.Success(FakeExperiencesRepository.pageOf(0, 1, "nova", 1))
        }
        val vm = viewModel()
        vm.submit("antiga")
        assertEquals(ExperienceListState.Loading, vm.results.value)

        vm.submit("nova")
        val current = vm.loaded.items.map { it.id }
        first.complete(DataResult.Success(FakeExperiencesRepository.pageOf(7, 8, "antiga", 1)))

        assertEquals(current, vm.loaded.items.map { it.id })
        assertEquals("nova", vm.uiState.value.activeQuery)
    }

    @Test
    fun `pagina em curso de uma pesquisa antiga e ignorada`() {
        val pending = CompletableDeferred<DataResult<Page<Experience>>>()
        repository.onSearch = { call ->
            when {
                call.query == "antiga" && call.page == 1 -> pending.await()
                else -> DataResult.Success(FakeExperiencesRepository.pageOf(call.page, 2, "p", 1))
            }
        }
        val vm = viewModel()
        vm.submit("antiga")
        vm.loadMore()
        assertTrue(vm.loaded.isLoadingMore)

        vm.submit("nova")
        val items = vm.loaded.items.map { it.id }
        pending.complete(DataResult.Success(FakeExperiencesRepository.pageOf(1, 2, "velha", 1)))

        assertEquals(items, vm.loaded.items.map { it.id })
        assertFalse(vm.loaded.isLoadingMore)
    }

    @Test
    fun `vazio e diferente de erro, e nova tentativa repete a mesma pesquisa`() {
        repository.onSearch = { DataResult.Failure(DataError.NETWORK) }
        val vm = viewModel()
        vm.submit("boxe")
        assertEquals(ExperienceListState.Error(DataError.NETWORK), vm.results.value)

        repository.onSearch = { DataResult.Success(Page(emptyList(), 0, 0, 0)) }
        vm.retry()

        assertEquals(listOf("boxe", "boxe"), repository.searchCalls.map { it.query })
        assertTrue(vm.loaded.items.isEmpty())
        assertEquals(0L, vm.loaded.totalItems)
    }

    @Test
    fun `erro ao carregar mais mantem os resultados e pode repetir`() {
        repository.onSearch = pages()
        val vm = viewModel()
        vm.submit("boxe")
        repository.onSearch = { DataResult.Failure(DataError.SERVER) }

        vm.loadMore()

        assertEquals(DataError.SERVER, vm.loaded.loadMoreError)
        assertEquals(2, vm.loaded.items.size)

        repository.onSearch = pages()
        vm.loadMore()
        assertNull(vm.loaded.loadMoreError)
        assertEquals(4, vm.loaded.items.size)
    }

    @Test
    fun `recriacao repoe o texto, a pesquisa e a ordenacao, e repete o pedido`() {
        val state = SavedStateHandle()
        val first = viewModel(state = state)
        first.submit("boxe")
        first.setSort(ExperienceSort.LIKES)
        first.onQueryInputChange("boxe e mais")
        repository.searchCalls.clear()

        val second = viewModel(state = state)

        assertEquals("boxe e mais", second.uiState.value.queryInput)
        assertEquals("boxe", second.uiState.value.activeQuery)
        assertEquals(ExperienceSort.LIKES, second.sort.value)
        assertEquals(ExperienceSearchCall("boxe", ExperienceSort.LIKES, 0, 10), repository.searchCalls.single())
    }

    @Test
    fun `sem pesquisa guardada a recriacao nao faz pedidos`() {
        viewModel(mapOf(ExperienceSearchViewModel.KEY_INPUT to "boxe"))
        assertTrue(repository.searchCalls.isEmpty())
    }

    @Test
    fun `contagens do detalhe atualizam o item sem recarregar nem perder paginas`() {
        repository.onSearch = pages()
        val vm = viewModel()
        vm.submit("boxe")
        vm.loadMore()
        val calls = repository.searchCalls.size

        changes.notifyCounts(ExperienceCounts("e1-1", likesCount = 4, commentsCount = 1))

        assertEquals(calls, repository.searchCalls.size)
        assertEquals(4, vm.loaded.items.size)
        assertEquals(4L, vm.loaded.items.single { it.id == "e1-1" }.likesCount)
    }

    @Test
    fun `uma alteracao de uma experiencia recarrega a pesquisa, e so depois de haver pesquisa`() {
        val vm = viewModel()
        changes.notify(ExperienceChange(experienceId = "e1", placeId = "p1", authorId = "a1"))
        assertTrue(repository.searchCalls.isEmpty())

        vm.submit("boxe")
        changes.notify(ExperienceChange(experienceId = "e1", placeId = "p1", authorId = "a1"))

        assertEquals(listOf("boxe", "boxe"), repository.searchCalls.map { it.query })
    }
}
