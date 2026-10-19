package com.example.portofit.ui.feature.experience

import androidx.lifecycle.SavedStateHandle
import com.example.portofit.data.auth.SessionState
import com.example.portofit.data.auth.SessionStatus
import com.example.portofit.data.model.CatalogStatus
import com.example.portofit.data.model.OfferingSummary
import com.example.portofit.data.repository.DataError
import com.example.portofit.data.repository.DataResult
import com.example.portofit.data.repository.ExperienceChange
import com.example.portofit.data.repository.ExperienceChanges
import com.example.portofit.data.repository.MutationError
import com.example.portofit.data.repository.MutationResult
import com.example.portofit.testing.FakeCatalogRepository
import com.example.portofit.testing.FakeExperiencesRepository
import com.example.portofit.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ExperienceDetailViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val repository = FakeExperiencesRepository()
    private val catalog = FakeCatalogRepository()
    private val changes = ExperienceChanges()
    private val session = MutableStateFlow<SessionState>(SessionState.SignedIn("author-1", "ana"))

    private fun saved() = SavedStateHandle(mapOf(ExperienceDetailViewModel.EXPERIENCE_ID_ARG to "e1"))

    private fun viewModel(state: SavedStateHandle = saved()) = ExperienceDetailViewModel(repository, catalog, changes, session, state)

    @Test
    fun `detalhe publico e estado da atividade`() {
        catalog.offeringResult = { DataResult.Success(OfferingSummary("o1", "Boxe iniciação", CatalogStatus.RETIRED, "p1", "Clube")) }
        session.value = SessionState.SignedOut()
        val vm = viewModel()
        assertEquals("e1", vm.uiState.value.experience?.id)
        assertEquals(CatalogStatus.RETIRED, vm.uiState.value.offeringStatus)
        assertFalse(vm.uiState.value.isAuthor)
    }

    @Test
    fun `falha do catalogo nao impede o detalhe`() {
        catalog.offeringResult = { DataResult.Failure(DataError.NETWORK) }
        val vm = viewModel()
        assertTrue(vm.uiState.value.load is ExperienceLoad.Loaded)
        assertNull(vm.uiState.value.offeringStatus)
    }

    @Test
    fun `experiencia inexistente`() {
        repository.onGet = { DataResult.Failure(DataError.NOT_FOUND) }
        assertEquals(ExperienceLoad.Error(DataError.NOT_FOUND), viewModel().uiState.value.load)
    }

    @Test
    fun `so o autor pode editar e eliminar`() {
        session.value = SessionState.SignedIn("outra-pessoa", "rui")
        val vm = viewModel()
        assertFalse(vm.uiState.value.isAuthor)
        vm.startEdit()
        assertNull(vm.uiState.value.edit)
        vm.requestDelete()
        assertFalse(vm.uiState.value.showDeleteConfirmation)

        session.value = SessionState.SignedIn("author-1", "ana", SessionStatus.TEMPORARILY_UNVERIFIED)
        assertTrue(vm.uiState.value.isAuthor)
        session.value = SessionState.SignedOut()
        assertFalse(vm.uiState.value.isAuthor)
    }

    @Test
    fun `editar envia so o que mudou e avisa as listas`() = runTest(UnconfinedTestDispatcher()) {
        val received = mutableListOf<ExperienceChange>()
        val job = launch { changes.events.collect { received += it } }
        val vm = viewModel()
        vm.startEdit()
        vm.onEditStars(5)
        vm.saveEdit()

        assertEquals(listOf(Triple("e1", null, 5)), repository.editCalls)
        assertEquals(5, vm.uiState.value.experience?.stars)
        assertNull(vm.uiState.value.edit)
        assertEquals(listOf(ExperienceChange("e1", "p1", "author-1")), received)
        job.cancel()
    }

    @Test
    fun `editar sem alteracoes nao faz pedido e texto invalido nao e enviado`() {
        val vm = viewModel()
        vm.startEdit()
        vm.saveEdit()
        assertTrue(repository.editCalls.isEmpty())
        assertNull(vm.uiState.value.edit)

        vm.startEdit()
        vm.onEditText("   ")
        vm.saveEdit()
        assertTrue(repository.editCalls.isEmpty())
        assertTrue(vm.uiState.value.edit!!.showValidation)
    }

    @Test
    fun `edicao em curso sobrevive a recriacao`() {
        val state = saved()
        viewModel(state).apply { startEdit(); onEditText("Texto novo"); onEditStars(2) }
        val restored = viewModel(state)
        assertEquals("Texto novo", restored.uiState.value.edit?.text)
        assertEquals(2, restored.uiState.value.edit?.stars)
    }

    @Test
    fun `falha ao editar mantem a edicao e impede pedidos duplicados`() {
        val pending = CompletableDeferred<MutationResult<com.example.portofit.data.model.Experience>>()
        repository.onEdit = { _, _, _ -> pending.await() }
        val vm = viewModel()
        vm.startEdit()
        vm.onEditText("Revisto")
        vm.saveEdit()
        vm.saveEdit()
        assertEquals(1, repository.editCalls.size)
        pending.complete(MutationResult.Failure(MutationError.NETWORK))
        assertEquals(MutationError.NETWORK, vm.uiState.value.actionError)
        assertEquals("Revisto", vm.uiState.value.edit?.text)
        assertEquals("Boa aula.", vm.uiState.value.experience?.text)
    }

    @Test
    fun `eliminar pede confirmacao, avisa as listas e termina`() = runTest(UnconfinedTestDispatcher()) {
        val received = mutableListOf<ExperienceChange>()
        val job = launch { changes.events.collect { received += it } }
        val vm = viewModel()
        vm.requestDelete()
        assertTrue(vm.uiState.value.showDeleteConfirmation)
        assertTrue(repository.deleteCalls.isEmpty())

        vm.confirmDelete()
        assertEquals(listOf("e1"), repository.deleteCalls)
        assertTrue(vm.uiState.value.deleted)
        assertEquals(listOf(ExperienceChange("e1", "p1", "author-1")), received)
        job.cancel()
    }

    @Test
    fun `eliminar recusado pelo servidor nao termina`() {
        repository.onDelete = { MutationResult.Failure(MutationError.FORBIDDEN) }
        val vm = viewModel()
        vm.requestDelete()
        vm.confirmDelete()
        assertFalse(vm.uiState.value.deleted)
        assertEquals(MutationError.FORBIDDEN, vm.uiState.value.actionError)
    }
}
