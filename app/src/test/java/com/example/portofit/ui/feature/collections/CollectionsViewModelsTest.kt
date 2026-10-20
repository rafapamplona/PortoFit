package com.example.portofit.ui.feature.collections

import androidx.lifecycle.SavedStateHandle
import com.example.portofit.data.auth.SessionState
import com.example.portofit.data.model.Page
import com.example.portofit.data.model.SavedAvailability
import com.example.portofit.data.model.SavedItemsPage
import com.example.portofit.data.model.UserCollection
import com.example.portofit.data.repository.CollectionChanges
import com.example.portofit.data.repository.CollectionError
import com.example.portofit.data.repository.CollectionResult
import com.example.portofit.data.repository.CreateCollectionOutcome
import com.example.portofit.data.repository.DataError
import com.example.portofit.data.repository.ExperienceChanges
import com.example.portofit.data.repository.SessionResult
import com.example.portofit.testing.FakeCollectionsRepository
import com.example.portofit.testing.FakeCollectionsRepository.Companion.collection
import com.example.portofit.testing.FakeCollectionsRepository.Companion.item
import com.example.portofit.testing.FakeCollectionsRepository.Companion.itemsPage
import com.example.portofit.testing.FakeCollectionsRepository.Companion.listPage
import com.example.portofit.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CollectionsViewModelsTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val repository = FakeCollectionsRepository()
    private val changes = CollectionChanges()
    private val session = MutableStateFlow<SessionState>(SessionState.SignedIn("ana-id", "ana"))
    private var keys = 0

    private fun lists(state: SavedStateHandle = SavedStateHandle(), now: Long = 1_000L) =
        CollectionsViewModel(repository, changes, session, state, clock = { now }, newKey = { "key-${++keys}" })

    private fun detail(state: SavedStateHandle = SavedStateHandle(mapOf(CollectionDetailViewModel.COLLECTION_ID_ARG to "c1"))) =
        CollectionDetailViewModel(repository, changes, ExperienceChanges(), session, state)

    private fun picker(state: SavedStateHandle = SavedStateHandle(mapOf(SaveToCollectionViewModel.EXPERIENCE_ID_ARG to "e1"))) =
        SaveToCollectionViewModel(repository, changes, session, state, clock = { 1_000L }, newKey = { "key-${++keys}" })

    private fun loaded(vm: CollectionsViewModel) = vm.lists.value as CollectionsListState.Loaded
    private fun loadedItems(vm: CollectionDetailViewModel) = vm.uiState.value.items as SavedItemsState.Loaded

    // --- As minhas listas ---

    @Test
    fun `visitante ve o convite e nao ha pedidos`() {
        session.value = SessionState.SignedOut()
        val vm = lists()
        assertEquals(false, vm.uiState.value.signedIn)
        assertTrue(repository.listCalls.isEmpty())
    }

    @Test
    fun `listas paginadas sem repeticoes`() {
        repository.onList = { page -> SessionResult.Success(listPage(page, 2)) }
        val vm = lists()
        vm.loadMore()
        assertEquals(listOf("c0-1", "c0-2", "c1-1", "c1-2"), loaded(vm).items.map { it.id })
        assertFalse(loaded(vm).hasMore)
        repository.onList = { SessionResult.Failure(DataError.NETWORK) }
        vm.retry()
        assertEquals(CollectionsListState.Error(DataError.NETWORK), vm.lists.value)
    }

    @Test
    fun `criar valida o nome e uma recusa mantem o nome e descarta a chave`() {
        val vm = lists()
        vm.creator.open()
        for (bad in listOf("", "   ", "a".repeat(61), "linha\nnova")) {
            vm.creator.onNameChange(bad)
            vm.creator.submit()
            assertTrue(vm.creator.state.value.showValidation)
        }
        assertTrue(repository.createCalls.isEmpty())

        repository.onCreate = { _, _ -> CreateCollectionOutcome.Rejected(CollectionError.NAME_TAKEN) }
        vm.creator.onNameChange("  Treinos  ")
        vm.creator.submit()
        assertEquals(listOf("key-1" to "Treinos"), repository.createCalls)
        assertEquals(CreatorMessage.Rejected(CollectionError.NAME_TAKEN), vm.creator.state.value.message)
        assertEquals("  Treinos  ", vm.creator.state.value.name)

        repository.onCreate = { _, name -> CreateCollectionOutcome.Created(collection("c-new", name), replayed = false) }
        vm.creator.onNameChange("Corridas")
        vm.creator.submit()
        assertEquals("key-2" to "Corridas", repository.createCalls.last())
        assertEquals("c-new", loaded(vm).items.first().id)
        assertFalse(vm.creator.state.value.open)
    }

    @Test
    fun `criacao ambigua preserva nome e chave, tambem ao recriar, sem repetir sozinha`() {
        repository.onCreate = { _, _ -> CreateCollectionOutcome.Unconfirmed }
        val state = SavedStateHandle()
        val vm = lists(state)
        vm.creator.open()
        vm.creator.onNameChange("Para experimentar")
        vm.creator.submit()
        assertTrue(vm.creator.state.value.pendingAttempt)
        assertEquals(1, repository.createCalls.size)
        // O nome fica bloqueado: a repetição tem de ser a mesma tentativa.
        vm.creator.onNameChange("Outro nome")
        assertEquals("Para experimentar", vm.creator.state.value.name)

        val recreated = lists(state)
        assertTrue(recreated.creator.state.value.pendingAttempt)
        assertTrue(recreated.creator.state.value.open)
        assertEquals("nenhuma repetição automática", 1, repository.createCalls.size)
        repository.onCreate = { _, name -> CreateCollectionOutcome.Created(collection("c-new", name), replayed = true) }
        recreated.creator.submit()
        assertEquals(listOf("key-1" to "Para experimentar", "key-1" to "Para experimentar"), repository.createCalls)
        assertFalse(recreated.creator.state.value.pendingAttempt)
        assertEquals("c-new", loaded(recreated).items.first().id)
    }

    @Test
    fun `descartar a tentativa e tentativa antiga`() {
        repository.onCreate = { _, _ -> CreateCollectionOutcome.Unconfirmed }
        val state = SavedStateHandle()
        lists(state, now = 0L).creator.apply { open(); onNameChange("Antiga"); submit() }
        val later = lists(state, now = CollectionCreator.MAX_KEY_AGE_MILLIS + 1)
        later.creator.submit()
        assertEquals(CreatorMessage.AttemptTooOld, later.creator.state.value.message)
        assertEquals(1, repository.createCalls.size)
        later.creator.discardAttempt()
        later.creator.onNameChange("Nova")
        assertEquals("Nova", later.creator.state.value.name)
    }

    @Test
    fun `renomear - sucesso, nome repetido e resultado desconhecido`() {
        val vm = lists()
        val target = loaded(vm).items.first()
        vm.startRename(target)
        vm.onRenameText("  ")
        vm.saveRename()
        assertTrue(vm.uiState.value.rename!!.showValidation)
        assertTrue(repository.renameCalls.isEmpty())

        repository.onRename = { _, _ -> CollectionResult.Failure(CollectionError.NAME_TAKEN) }
        vm.onRenameText("Treinos")
        vm.saveRename()
        assertEquals(CollectionError.NAME_TAKEN, vm.uiState.value.rename!!.error)
        repository.onRename = { _, _ -> CollectionResult.Failure(CollectionError.UNCONFIRMED) }
        vm.saveRename()
        assertEquals(CollectionError.UNCONFIRMED, vm.uiState.value.rename!!.error)
        assertEquals("Treinos", vm.uiState.value.rename!!.name)

        repository.onRename = { id, name -> CollectionResult.Success(collection(id, name)) }
        // O aviso de alteração volta a pedir a lista ao servidor, que já tem o nome novo.
        repository.onGet = { id -> SessionResult.Success(collection(id, "Treinos")) }
        vm.saveRename()
        assertNull(vm.uiState.value.rename)
        assertEquals("Treinos", loaded(vm).items.first { it.id == target.id }.name)
    }

    @Test
    fun `eliminar pede confirmacao e uma falha temporaria nao apaga a lista`() {
        val vm = lists()
        val target = loaded(vm).items.first()
        vm.requestDelete(target)
        assertTrue(repository.deleteCalls.isEmpty())
        repository.onDelete = { CollectionResult.Failure(CollectionError.UNCONFIRMED) }
        vm.confirmDelete()
        assertEquals(CollectionError.UNCONFIRMED, vm.uiState.value.deleteError)
        assertTrue(loaded(vm).items.any { it.id == target.id })

        repository.onDelete = { CollectionResult.Success(Unit) }
        vm.requestDelete(target)
        vm.confirmDelete()
        assertFalse(loaded(vm).items.any { it.id == target.id })
        assertEquals(listOf(target.id, target.id), repository.deleteCalls)
    }

    @Test
    fun `trocar de conta ou sair apaga logo as listas e ignora respostas tardias`() {
        val slow = CompletableDeferred<SessionResult<Page<UserCollection>>>()
        repository.onList = { slow.await() }
        val vm = lists()
        repository.onList = { SessionResult.Success(listPage(0, 1, prefix = "rui")) }
        session.value = SessionState.SignedIn("rui-id", "rui")
        slow.complete(SessionResult.Success(listPage(0, 1, prefix = "ana")))
        assertEquals(listOf("rui0-1", "rui0-2"), loaded(vm).items.map { it.id })

        session.value = SessionState.SignedOut()
        assertEquals(false, vm.uiState.value.signedIn)
        assertEquals(CollectionsListState.Loading, vm.lists.value)
    }

    @Test
    fun `alteracoes noutro ecra atualizam a contagem sem recarregar`() {
        val vm = lists()
        val calls = repository.listCalls.size
        repository.onGet = { id -> SessionResult.Success(collection(id, itemCount = 5)) }
        changes.notify("c0-2")
        assertEquals(5L, loaded(vm).items.first { it.id == "c0-2" }.itemCount)
        repository.onGet = { SessionResult.Failure(DataError.NOT_FOUND) }
        changes.notify("c0-1")
        assertFalse(loaded(vm).items.any { it.id == "c0-1" })
        assertEquals(calls, repository.listCalls.size)
    }

    // --- Detalhe de uma lista ---

    @Test
    fun `itens disponiveis, eliminados e desconhecidos, todos removiveis`() {
        repository.onItems = { _, page ->
            SessionResult.Success(itemsPage(page, 1, listOf(item("ok"), item("gone", SavedAvailability.DELETED), item("unk", SavedAvailability.UNKNOWN)), available = false))
        }
        val vm = detail()
        val items = loadedItems(vm)
        assertEquals(listOf(SavedAvailability.AVAILABLE, SavedAvailability.DELETED, SavedAvailability.UNKNOWN), items.items.map { it.availability })
        assertFalse("serviço indisponível: aviso, não eliminação", items.experiencesAvailable)
        assertNull(items.items[1].experience)
        assertNull(items.items[2].experience)

        for (id in listOf("gone", "unk")) vm.remove(id)
        assertEquals(listOf("ok"), loadedItems(vm).items.map { it.experienceId })
        assertEquals(listOf("c1" to "gone", "c1" to "unk"), repository.removeCalls)
    }

    @Test
    fun `remover sem duplicados e um resultado desconhecido mantem o item`() {
        val pending = CompletableDeferred<CollectionResult<Unit>>()
        repository.onRemove = { _, _ -> pending.await() }
        val vm = detail()
        vm.remove("x0-1")
        vm.remove("x0-1")
        assertEquals(1, repository.removeCalls.size)
        assertTrue("x0-1" in vm.uiState.value.removing)
        pending.complete(CollectionResult.Failure(CollectionError.UNCONFIRMED))
        assertEquals(CollectionError.UNCONFIRMED, vm.uiState.value.removeErrors["x0-1"])
        assertTrue(loadedItems(vm).items.any { it.experienceId == "x0-1" })
    }

    @Test
    fun `paginacao, lista inexistente e troca de conta no detalhe`() {
        repository.onItems = { _, page -> SessionResult.Success(itemsPage(page, 2)) }
        val vm = detail()
        vm.loadMore()
        assertEquals(4, loadedItems(vm).items.size)

        val slow = CompletableDeferred<SessionResult<SavedItemsPage>>()
        repository.onItems = { _, _ -> slow.await() }
        vm.refresh()
        session.value = SessionState.SignedOut()
        slow.complete(SessionResult.Success(itemsPage(0, 1)))
        assertEquals(SavedItemsState.Loading, vm.uiState.value.items)
        assertEquals(false, vm.uiState.value.signedIn)

        session.value = SessionState.SignedIn("rui-id", "rui")
        repository.onGet = { SessionResult.Failure(DataError.NOT_FOUND) }
        repository.onItems = { _, _ -> SessionResult.Failure(DataError.NOT_FOUND) }
        vm.refresh()
        assertEquals(CollectionHeaderState.Gone, vm.uiState.value.header)
    }

    // --- Guardar numa lista ---

    @Test
    fun `guardar sem duplicados, com confirmacao, e sem guardar depois do login`() {
        session.value = SessionState.SignedOut()
        val vm = picker()
        assertEquals(false, vm.uiState.value.signedIn)
        session.value = SessionState.SignedIn("ana-id", "ana")
        assertTrue("entrar não guarda nada", repository.addCalls.isEmpty())

        val pending = CompletableDeferred<CollectionResult<Unit>>()
        repository.onAdd = { _, _ -> pending.await() }
        vm.save("c0-1")
        vm.save("c0-1")
        assertEquals(listOf("c0-1" to "e1"), repository.addCalls)
        assertEquals(SaveState.Saving, vm.uiState.value.saves["c0-1"])
        pending.complete(CollectionResult.Success(Unit))
        assertEquals(SaveState.Saved, vm.uiState.value.saves["c0-1"])
    }

    @Test
    fun `guardar - falhas distintas e lista nova sem guardar sozinha`() {
        val vm = picker()
        repository.onAdd = { _, _ -> CollectionResult.Failure(CollectionError.UNCONFIRMED) }
        vm.save("c0-1")
        assertEquals(SaveState.Failed(CollectionError.UNCONFIRMED), vm.uiState.value.saves["c0-1"])
        repository.onAdd = { _, _ -> CollectionResult.Failure(CollectionError.EXPERIENCES_UNAVAILABLE) }
        vm.save("c0-1")
        assertEquals(SaveState.Failed(CollectionError.EXPERIENCES_UNAVAILABLE), vm.uiState.value.saves["c0-1"])
        assertFalse(vm.uiState.value.experienceGone)

        vm.creator.open()
        vm.creator.onNameChange("Nova")
        vm.creator.submit()
        assertEquals("c-new", (vm.lists.value as CollectionsListState.Loaded).items.first().id)
        assertEquals("criar não guarda", 2, repository.addCalls.size)

        repository.onAdd = { _, _ -> CollectionResult.Failure(CollectionError.EXPERIENCE_GONE) }
        vm.save("c0-2")
        assertTrue(vm.uiState.value.experienceGone)
        vm.save("c0-1")
        assertEquals(3, repository.addCalls.size)

        // Troca de conta: os estados de guardar desaparecem.
        session.value = SessionState.SignedIn("rui-id", "rui")
        assertTrue(vm.uiState.value.saves.isEmpty())
        assertFalse(vm.creator.state.value.open)
    }
}
