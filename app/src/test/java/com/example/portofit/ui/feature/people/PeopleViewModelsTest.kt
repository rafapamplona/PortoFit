package com.example.portofit.ui.feature.people

import androidx.lifecycle.SavedStateHandle
import com.example.portofit.data.auth.SessionState
import com.example.portofit.data.auth.SessionStatus
import com.example.portofit.data.model.Page
import com.example.portofit.data.model.Sport
import com.example.portofit.data.remote.PublicProfileDto
import com.example.portofit.data.repository.DataError
import com.example.portofit.data.repository.DataResult
import com.example.portofit.data.repository.PeopleRepository
import com.example.portofit.data.repository.PublicProfile
import com.example.portofit.data.repository.UserSummary
import com.example.portofit.testing.FakeCatalogRepository
import com.example.portofit.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PeopleViewModelsTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private class FakePeople : PeopleRepository {
        val searches = mutableListOf<Triple<String, Int, Int>>()
        val profiles = mutableListOf<String>()
        var onSearch: suspend (String, Int) -> DataResult<Page<UserSummary>> = { q, page -> DataResult.Success(pageOf(q, page, 1)) }
        var onProfile: suspend (String) -> DataResult<PublicProfile> = { id ->
            DataResult.Success(PublicProfile(id, "pamplona", "Corro ao sábado.", listOf("boxe", "artes-marciais")))
        }
        override suspend fun search(query: String, page: Int, pageSize: Int): DataResult<Page<UserSummary>> {
            searches += Triple(query, page, pageSize)
            return onSearch(query, page)
        }
        override suspend fun profile(userId: String): DataResult<PublicProfile> {
            profiles += userId
            return onProfile(userId)
        }

        companion object {
            fun pageOf(q: String, page: Int, totalPages: Int) = Page(
                items = (1..2).map { UserSummary("$q-$page-$it", "$q$page$it", null) },
                page = page,
                totalItems = (totalPages * 2).toLong(),
                totalPages = totalPages,
            )
        }
    }

    private val people = FakePeople()

    // --- Pesquisa de pessoas ---

    @Test
    fun `sem pesquisa submetida nao faz pedidos`() {
        val vm = PeopleSearchViewModel(people, SavedStateHandle())
        assertEquals(PeopleResults.Idle, vm.uiState.value.results)
        assertTrue(people.searches.isEmpty())
    }

    @Test
    fun `valida 2 a 50 caracteres e normaliza o texto`() {
        val vm = PeopleSearchViewModel(people, SavedStateHandle())
        vm.onQueryInputChange(" a ")
        vm.onSubmit()
        assertTrue(vm.uiState.value.showQueryInvalid)
        assertTrue(people.searches.isEmpty())

        vm.onQueryInputChange("  PAMP ")
        vm.onSubmit()
        assertEquals(listOf(Triple("pamp", 0, PeopleSearchViewModel.PAGE_SIZE)), people.searches)
        assertTrue(vm.uiState.value.results is PeopleResults.Loaded)
    }

    @Test
    fun `carregar mais acrescenta paginas ate ao fim`() {
        people.onSearch = { q, page -> DataResult.Success(FakePeople.pageOf(q, page, 2)) }
        val vm = PeopleSearchViewModel(people, SavedStateHandle())
        vm.onQueryInputChange("ana")
        vm.onSubmit()
        vm.loadMore()
        val loaded = vm.uiState.value.results as PeopleResults.Loaded
        assertEquals(4, loaded.people.size)
        assertFalse(loaded.hasMore)
        assertEquals(1, people.searches.last().second)
    }

    @Test
    fun `resposta de uma pesquisa antiga e ignorada`() {
        val slow = CompletableDeferred<DataResult<Page<UserSummary>>>()
        people.onSearch = { q, page -> if (q == "ana") slow.await() else DataResult.Success(FakePeople.pageOf(q, page, 1)) }
        val vm = PeopleSearchViewModel(people, SavedStateHandle())
        vm.onQueryInputChange("ana")
        vm.onSubmit()
        vm.onQueryInputChange("rui")
        vm.onSubmit()
        slow.complete(DataResult.Success(FakePeople.pageOf("ana", 0, 1)))

        val loaded = vm.uiState.value.results as PeopleResults.Loaded
        assertTrue(loaded.people.all { it.userId.startsWith("rui") })
    }

    @Test
    fun `erro, nova tentativa e lista vazia`() {
        people.onSearch = { _, _ -> DataResult.Failure(DataError.NETWORK) }
        val vm = PeopleSearchViewModel(people, SavedStateHandle())
        vm.onQueryInputChange("ana")
        vm.onSubmit()
        assertEquals(PeopleResults.Error(DataError.NETWORK), vm.uiState.value.results)

        people.onSearch = { _, _ -> DataResult.Success(Page(emptyList(), 0, 0, 0)) }
        vm.retry()
        assertEquals(emptyList<UserSummary>(), (vm.uiState.value.results as PeopleResults.Loaded).people)
    }

    @Test
    fun `pesquisa submetida e reposta ao recriar`() {
        val state = SavedStateHandle()
        PeopleSearchViewModel(people, state).apply {
            onQueryInputChange("pamp")
            onSubmit()
        }
        people.searches.clear()
        val recreated = PeopleSearchViewModel(people, state)
        assertEquals("pamp", recreated.uiState.value.queryInput)
        assertEquals(listOf(Triple("pamp", 0, PeopleSearchViewModel.PAGE_SIZE)), people.searches)
    }

    // --- Perfil público ---

    private val catalog = FakeCatalogRepository().apply { sportsResult = { DataResult.Success(listOf(Sport("boxe", "Boxe"))) } }

    private fun profileVm(session: SessionState = SessionState.SignedOut(), userId: String = "user-1") =
        PublicProfileViewModel(people, catalog, MutableStateFlow(session), SavedStateHandle(mapOf(PublicProfileViewModel.USER_ID_ARG to userId)))

    @Test
    fun `perfil publico sem sessao com nomes do catalogo e alternativos`() {
        val vm = profileVm()
        val s = vm.uiState.value
        assertEquals("pamplona", (s.load as PublicProfileLoad.Loaded).profile.username)
        assertEquals(listOf("Boxe", "Artes marciais"), s.interestLabels.map { it.name })
        assertEquals(listOf(true, false), s.interestLabels.map { it.fromCatalog })
        assertFalse(s.isOwnProfile)
    }

    @Test
    fun `interesses legiveis com o catalogo indisponivel`() {
        catalog.sportsResult = { DataResult.Failure(DataError.NETWORK) }
        val s = profileVm().uiState.value
        assertEquals(listOf("Boxe", "Artes marciais"), s.interestLabels.map { it.name })
        assertTrue(s.interestLabels.none { it.fromCatalog })
    }

    @Test
    fun `editar o meu perfil so com sessao ativa do proprio`() {
        assertTrue(profileVm(SessionState.SignedIn("user-1", "pamplona")).uiState.value.isOwnProfile)
        assertFalse(profileVm(SessionState.SignedIn("outro", "rui")).uiState.value.isOwnProfile)
        assertFalse(profileVm(SessionState.SignedIn("user-1", "pamplona", SessionStatus.UNCONFIRMED)).uiState.value.isOwnProfile)
        assertFalse(profileVm(SessionState.SignedOut()).uiState.value.isOwnProfile)
    }

    @Test
    fun `pessoa inexistente e nova tentativa`() {
        people.onProfile = { DataResult.Failure(DataError.NOT_FOUND) }
        val vm = profileVm()
        assertEquals(PublicProfileLoad.Error(DataError.NOT_FOUND), vm.uiState.value.load)
        people.onProfile = { DataResult.Success(PublicProfile("user-1", "pamplona", null, emptyList())) }
        vm.retry()
        assertTrue(vm.uiState.value.load is PublicProfileLoad.Loaded)
    }

    // --- Privacidade ---

    @Test
    fun `perfil publico nunca tem email, mesmo que a resposta o traga`() {
        val json = Json { ignoreUnknownKeys = true }
        val dto = json.decodeFromString<PublicProfileDto>(
            """{"userId":"u","username":"ana","description":null,"interests":[],"email":"ana@example.org","createdAt":"2026-10-18T10:00:00Z"}""",
        )
        assertFalse(dto.toString().contains("@example.org"))
        val fields = PublicProfile::class.java.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()
        assertEquals(setOf("userId", "username", "description", "interests"), fields)
    }
}
