package com.example.portofit.ui.feature.experience

import com.example.portofit.data.model.ExperienceSort
import com.example.portofit.data.repository.ExperienceChanges
import com.example.portofit.data.repository.ExperienceCounts
import com.example.portofit.testing.FakeExperiencesRepository
import com.example.portofit.testing.ListCall
import com.example.portofit.testing.MainDispatcherRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ExperienceListLoaderTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val repository = FakeExperiencesRepository()
    private val changes = ExperienceChanges()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @After
    fun tearDown() = scope.cancel()

    private fun loaded(loader: ExperienceListLoader) = loader.state.value as ExperienceListState.Loaded

    @Test
    fun `as quatro ordenacoes vao para o servidor e mudar recomeca na primeira pagina`() {
        repository.onList = { call -> com.example.portofit.data.repository.DataResult.Success(FakeExperiencesRepository.pageOf(call.page, 3)) }
        val loader = ExperienceListLoader(scope, repository, placeId = "p1", pageSize = 2)
        loader.load()
        loader.loadMore()
        assertEquals(1, loaded(loader).page)

        loader.setSort(ExperienceSort.LIKES)
        assertEquals(0, loaded(loader).page)
        assertEquals(listOf("e0-1", "e0-2"), loaded(loader).items.map { it.id })
        loader.setSort(ExperienceSort.INTERACTIONS)
        loader.setSort(ExperienceSort.STARS)
        loader.setSort(ExperienceSort.RECENT)
        assertEquals(
            listOf(
                ListCall("p1", null, ExperienceSort.RECENT, 0, 2),
                ListCall("p1", null, ExperienceSort.RECENT, 1, 2),
                ListCall("p1", null, ExperienceSort.LIKES, 0, 2),
                ListCall("p1", null, ExperienceSort.INTERACTIONS, 0, 2),
                ListCall("p1", null, ExperienceSort.STARS, 0, 2),
                ListCall("p1", null, ExperienceSort.RECENT, 0, 2),
            ),
            repository.listCalls,
        )
        assertEquals(listOf("recent", "stars", "likes", "interactions"), ExperienceSort.entries.map { it.apiValue })
    }

    @Test
    fun `contagens confirmadas no detalhe atualizam o item sem recarregar nem perder paginas`() {
        repository.onList = { call -> com.example.portofit.data.repository.DataResult.Success(FakeExperiencesRepository.pageOf(call.page, 2)) }
        val loader = ExperienceListLoader(scope, repository, authorId = "author-1", pageSize = 2, counts = changes.counts)
        loader.load()
        loader.loadMore()
        val calls = repository.listCalls.size

        changes.notifyCounts(ExperienceCounts("e1-2", likesCount = 5, commentsCount = 2))
        changes.notifyCounts(ExperienceCounts("desconhecida", likesCount = 9, commentsCount = 9))

        val items = loaded(loader).items
        assertEquals(4, items.size)
        assertEquals(5L to 2L, items.single { it.id == "e1-2" }.let { it.likesCount to it.commentsCount })
        assertEquals(listOf(0L), items.filter { it.id != "e1-2" }.map { it.likesCount }.distinct())
        assertEquals("sem novos pedidos", calls, repository.listCalls.size)
    }
}
