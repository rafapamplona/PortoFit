package com.example.portofit.ui.feature.discover

import androidx.lifecycle.SavedStateHandle
import com.example.portofit.data.auth.SessionState
import com.example.portofit.data.model.FeedFallbackReason
import com.example.portofit.data.model.FeedMode
import com.example.portofit.data.model.FeedPage
import com.example.portofit.data.repository.DataError
import com.example.portofit.data.repository.DataResult
import com.example.portofit.data.repository.ExperienceChanges
import com.example.portofit.data.repository.ExperienceCounts
import com.example.portofit.data.repository.SessionResult
import com.example.portofit.testing.FakeCatalogRepository
import com.example.portofit.testing.FakeDiscoverRepository
import com.example.portofit.testing.FakeDiscoverRepository.Companion.feedPage
import com.example.portofit.testing.FeedCall
import com.example.portofit.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DiscoverViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val repository = FakeDiscoverRepository()
    private val catalog = FakeCatalogRepository()
    private val changes = ExperienceChanges()
    private val session = MutableStateFlow<SessionState>(SessionState.SignedIn("ana-id", "ana"))

    private fun viewModel(state: SavedStateHandle = SavedStateHandle()) = DiscoverViewModel(repository, catalog, changes, session, state)

    private fun loaded(vm: DiscoverViewModel) = vm.uiState.value.feed as FeedListState.Loaded

    @Test
    fun `visitante so ve populares e nao pode escolher Para ti`() {
        session.value = SessionState.SignedOut()
        val vm = viewModel()
        assertEquals(false, vm.uiState.value.signedIn)
        assertEquals(DiscoverTab.POPULAR, vm.uiState.value.tab)
        vm.selectTab(DiscoverTab.FOR_YOU)
        assertEquals(DiscoverTab.POPULAR, vm.uiState.value.tab)
        assertEquals(listOf(FeedCall(false, null, 0)), repository.feedCalls)
    }

    @Test
    fun `sessao a carregar nao pede nada ate se saber quem e`() {
        session.value = SessionState.Loading
        val vm = viewModel()
        assertNull(vm.uiState.value.signedIn)
        assertTrue(repository.feedCalls.isEmpty())
        session.value = SessionState.SignedIn("ana-id", "ana")
        assertEquals(listOf(FeedCall(true, null, 0)), repository.feedCalls)
    }

    @Test
    fun `com sessao abre Para ti e o separador fica guardado`() {
        val state = SavedStateHandle()
        val vm = viewModel(state)
        assertEquals(DiscoverTab.FOR_YOU, vm.uiState.value.tab)
        assertEquals(FeedMode.PERSONAL, loaded(vm).mode)
        vm.selectTab(DiscoverTab.POPULAR)
        assertEquals(FeedCall(false, null, 0), repository.feedCalls.last())
        assertEquals(DiscoverTab.POPULAR, viewModel(state).uiState.value.tab)
    }

    @Test
    fun `fallback e motivos chegam ao estado e nunca sao modo pessoal`() {
        for ((mode, reason) in listOf(
            FeedMode.POPULAR_FALLBACK to FeedFallbackReason.USERS_UNAVAILABLE,
            FeedMode.POPULAR to FeedFallbackReason.NO_PREFERENCES,
            FeedMode.POPULAR to FeedFallbackReason.NO_MATCHES,
        )) {
            repository.onPersonal = { SessionResult.Success(feedPage(it.page, 1, mode, reason)) }
            val feed = loaded(viewModel())
            assertEquals(mode, feed.mode)
            assertEquals(reason, feed.fallbackReason)
            assertTrue("um resultado popular não tem motivos de personalização", feed.items.all { it.matchedBy.isEmpty() })
        }
    }

    @Test
    fun `paginacao junta paginas do mesmo modo e uma mudanca de modo pede para recomecar`() {
        repository.onPersonal = { SessionResult.Success(feedPage(it.page, 3, FeedMode.PERSONAL)) }
        val vm = viewModel()
        vm.loadMore()
        assertEquals(listOf("f0-1", "f0-2", "f1-1", "f1-2"), loaded(vm).items.map { it.experience.id })

        // A página seguinte vem degradada (o users-service falhou a meio): não é juntada.
        repository.onPersonal = { SessionResult.Success(feedPage(it.page, 3, FeedMode.POPULAR_FALLBACK, FeedFallbackReason.USERS_UNAVAILABLE, prefix = "p")) }
        vm.loadMore()
        val feed = loaded(vm)
        assertTrue(feed.modeChanged)
        assertEquals(FeedMode.PERSONAL, feed.mode)
        assertEquals(4, feed.items.size)
        val calls = repository.feedCalls.size
        vm.loadMore()
        assertEquals("sem mais páginas até recomeçar", calls, repository.feedCalls.size)

        vm.refresh()
        assertEquals(FeedCall(true, null, 0), repository.feedCalls.last())
        assertEquals(FeedMode.POPULAR_FALLBACK, loaded(vm).mode)
        assertFalse(loaded(vm).modeChanged)
    }

    @Test
    fun `resposta antiga e ignorada ao mudar de filtro`() {
        val slow = CompletableDeferred<SessionResult<FeedPage>>()
        repository.onPersonal = { call -> if (call.sportId == null) slow.await() else SessionResult.Success(feedPage(0, 1, FeedMode.PERSONAL, prefix = "boxe")) }
        val vm = viewModel()
        vm.selectSport("boxe")
        assertEquals("boxe", vm.uiState.value.sportId)
        slow.complete(SessionResult.Success(feedPage(0, 1, FeedMode.PERSONAL, prefix = "antiga")))
        assertEquals(listOf("boxe0-1", "boxe0-2"), loaded(vm).items.map { it.experience.id })
    }

    @Test
    fun `falha das modalidades nao impede o feed sem filtro`() {
        catalog.sportsResult = { DataResult.Failure(DataError.NETWORK) }
        val vm = viewModel()
        assertEquals(SportsState.Failed, vm.uiState.value.sports)
        assertEquals(FeedMode.PERSONAL, loaded(vm).mode)
        catalog.sportsResult = { DataResult.Success(listOf(FakeCatalogRepository.BOXE)) }
        vm.retrySports()
        assertTrue(vm.uiState.value.sports is SportsState.Loaded)
    }

    @Test
    fun `sessao terminada e por confirmar sao estados distintos e falha de rede tem nova tentativa`() {
        repository.onPersonal = { SessionResult.SessionEnded }
        assertEquals(FeedListState.SessionEnded, viewModel().uiState.value.feed)
        repository.onPersonal = { SessionResult.SessionUnverified }
        assertEquals(FeedListState.SessionUnverified, viewModel().uiState.value.feed)
        repository.onPersonal = { SessionResult.Failure(DataError.NETWORK) }
        val vm = viewModel()
        assertEquals(FeedListState.Error(DataError.NETWORK), vm.uiState.value.feed)
        repository.onPersonal = { SessionResult.Success(feedPage(0, 1, FeedMode.PERSONAL)) }
        vm.refresh()
        assertEquals(FeedMode.PERSONAL, loaded(vm).mode)
    }

    @Test
    fun `mudar de conta recarrega e ignora a resposta da conta anterior`() {
        val slow = CompletableDeferred<SessionResult<FeedPage>>()
        repository.onPersonal = { slow.await() }
        val vm = viewModel()
        repository.onPersonal = { SessionResult.Success(feedPage(0, 1, FeedMode.PERSONAL, prefix = "rui")) }
        session.value = SessionState.SignedIn("rui-id", "rui")
        slow.complete(SessionResult.Success(feedPage(0, 1, FeedMode.PERSONAL, prefix = "ana")))
        assertEquals(listOf("rui0-1", "rui0-2"), loaded(vm).items.map { it.experience.id })

        // Terminar sessão: volta a Populares.
        session.value = SessionState.SignedOut()
        assertEquals(DiscoverTab.POPULAR, vm.uiState.value.tab)
        assertEquals(FeedCall(false, null, 0), repository.feedCalls.last())
        assertEquals(FeedMode.POPULAR, loaded(vm).mode)
    }

    @Test
    fun `contagens confirmadas no detalhe atualizam o item sem recarregar`() {
        val vm = viewModel()
        val calls = repository.feedCalls.size
        changes.notifyCounts(ExperienceCounts("f0-2", likesCount = 7, commentsCount = 3))
        val item = loaded(vm).items.single { it.experience.id == "f0-2" }
        assertEquals(7L to 3L, item.experience.likesCount to item.experience.commentsCount)
        assertEquals(calls, repository.feedCalls.size)
    }
}
