package com.example.portofit.ui.feature.stats

import androidx.lifecycle.SavedStateHandle
import com.example.portofit.data.auth.SessionState
import com.example.portofit.data.model.StatsPage
import com.example.portofit.data.model.StatsPeriod
import com.example.portofit.data.model.StatsSort
import com.example.portofit.data.model.StatsTotals
import com.example.portofit.data.repository.DataError
import com.example.portofit.data.repository.SessionResult
import com.example.portofit.testing.FakeDiscoverRepository
import com.example.portofit.testing.FakeDiscoverRepository.Companion.statsPage
import com.example.portofit.testing.FakeDiscoverRepository.Companion.totals
import com.example.portofit.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class StatsViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val repository = FakeDiscoverRepository()
    private val session = MutableStateFlow<SessionState>(SessionState.SignedIn("ana-id", "ana"))

    private fun viewModel(state: SavedStateHandle = SavedStateHandle()) = StatsViewModel(repository, session, state)

    private fun totalsOf(vm: StatsViewModel) = (vm.uiState.value.totals as SectionState.Loaded).value
    private fun listValue(vm: StatsViewModel) = (vm.uiState.value.list as SectionState.Loaded).value

    @Test
    fun `visitante ve o convite e nao ha pedidos`() {
        session.value = SessionState.SignedOut()
        val vm = viewModel()
        assertEquals(false, vm.uiState.value.signedIn)
        assertTrue(repository.statsCalls.isEmpty())
    }

    @Test
    fun `com sessao carrega totais e lista do mesmo periodo, com o intervalo do servidor`() {
        val vm = viewModel()
        assertEquals(true, vm.uiState.value.signedIn)
        assertEquals(StatsPeriod.ALL, totalsOf(vm).window.period)
        assertEquals(StatsPeriod.ALL, listValue(vm).window.period)

        val state = SavedStateHandle()
        val week = viewModel(state)
        week.selectPeriod(StatsPeriod.LAST_7_DAYS)
        assertEquals(Instant.parse("2026-10-13T00:00:00Z"), totalsOf(week).window.from)
        assertEquals(Instant.parse("2026-10-13T00:00:00Z"), listValue(week).window.from)
        // Período e ordenação ficam guardados.
        week.selectSort(StatsSort.VIEWS)
        val restored = viewModel(state)
        assertEquals(StatsPeriod.LAST_7_DAYS, restored.uiState.value.period)
        assertEquals(StatsSort.VIEWS, restored.uiState.value.sort)
    }

    @Test
    fun `resposta antiga e ignorada ao mudar de periodo`() {
        val slowTotals = CompletableDeferred<SessionResult<StatsTotals>>()
        val slowPage = CompletableDeferred<SessionResult<StatsPage>>()
        repository.onStats = { if (it.period == StatsPeriod.ALL) slowTotals.await() else SessionResult.Success(totals(it.period, likes = 1)) }
        repository.onStatsPage = { if (it.period == StatsPeriod.ALL) slowPage.await() else SessionResult.Success(statsPage(it.period, 0, 1, prefix = "semana")) }
        val vm = viewModel()
        vm.selectPeriod(StatsPeriod.LAST_7_DAYS)
        slowTotals.complete(SessionResult.Success(totals(StatsPeriod.ALL, likes = 99)))
        slowPage.complete(SessionResult.Success(statsPage(StatsPeriod.ALL, 0, 1, prefix = "sempre")))
        assertEquals(1L, totalsOf(vm).likes)
        assertEquals(StatsPeriod.LAST_7_DAYS, totalsOf(vm).window.period)
        assertEquals(listOf("semana0-1", "semana0-2"), listValue(vm).items.map { it.experienceId })
    }

    @Test
    fun `falhas independentes e nunca zeros`() {
        repository.onStats = { SessionResult.Failure(DataError.SERVER) }
        val vm = viewModel()
        assertEquals(SectionState.Error(DataError.SERVER), vm.uiState.value.totals)
        assertTrue("a lista não depende dos totais", vm.uiState.value.list is SectionState.Loaded)

        repository.onStats = { SessionResult.Success(totals(it.period)) }
        repository.onStatsPage = { SessionResult.Failure(DataError.NETWORK) }
        vm.retryTotals()
        vm.retryList()
        assertTrue(vm.uiState.value.totals is SectionState.Loaded)
        assertEquals(SectionState.Error(DataError.NETWORK), vm.uiState.value.list)

        repository.onStats = { SessionResult.SessionEnded }
        vm.retryTotals()
        assertEquals(SectionState.SessionEnded, vm.uiState.value.totals)
        repository.onStats = { SessionResult.SessionUnverified }
        vm.retryTotals()
        assertEquals(SectionState.SessionUnverified, vm.uiState.value.totals)
    }

    @Test
    fun `terminar sessao apaga logo os dados e uma resposta tardia nao os repoe`() {
        val vm = viewModel()
        val slow = CompletableDeferred<SessionResult<StatsTotals>>()
        repository.onStats = { slow.await() }
        vm.refresh()
        session.value = SessionState.SignedOut()
        assertEquals(false, vm.uiState.value.signedIn)
        assertEquals(SectionState.Loading, vm.uiState.value.totals)
        assertEquals(SectionState.Loading, vm.uiState.value.list)
        slow.complete(SessionResult.Success(totals(StatsPeriod.ALL, likes = 42)))
        assertEquals(SectionState.Loading, vm.uiState.value.totals)
        vm.refresh()
        assertEquals(SectionState.Loading, vm.uiState.value.totals)
    }

    @Test
    fun `trocar de conta nao mostra dados da anterior`() {
        val slowTotals = CompletableDeferred<SessionResult<StatsTotals>>()
        val slowPage = CompletableDeferred<SessionResult<StatsPage>>()
        repository.onStats = { slowTotals.await() }
        repository.onStatsPage = { slowPage.await() }
        val vm = viewModel()
        repository.onStats = { SessionResult.Success(totals(it.period, likes = 5)) }
        repository.onStatsPage = { SessionResult.Success(statsPage(it.period, 0, 1, prefix = "rui")) }
        session.value = SessionState.SignedIn("rui-id", "rui")
        slowTotals.complete(SessionResult.Success(totals(StatsPeriod.ALL, likes = 99)))
        slowPage.complete(SessionResult.Success(statsPage(StatsPeriod.ALL, 0, 1, prefix = "ana")))
        assertEquals(5L, totalsOf(vm).likes)
        assertEquals(listOf("rui0-1", "rui0-2"), listValue(vm).items.map { it.experienceId })
    }

    @Test
    fun `paginacao da lista e intervalo diferente numa pagina seguinte pede para recomecar`() {
        repository.onStatsPage = { SessionResult.Success(statsPage(it.period, it.page ?: 0, 3)) }
        val vm = viewModel()
        vm.selectPeriod(StatsPeriod.LAST_7_DAYS)
        vm.loadMore()
        assertEquals(4, listValue(vm).items.size)

        // Mudou o dia UTC entre páginas: o servidor devolve outro "from".
        repository.onStatsPage = { SessionResult.Success(statsPage(it.period, it.page ?: 0, 3, prefix = "x", from = Instant.parse("2026-10-14T00:00:00Z"))) }
        vm.loadMore()
        assertTrue(listValue(vm).windowChanged)
        assertEquals(4, listValue(vm).items.size)
        vm.retryList()
        assertEquals(Instant.parse("2026-10-14T00:00:00Z"), listValue(vm).window.from)
        assertEquals(2, listValue(vm).items.size)
    }
}
