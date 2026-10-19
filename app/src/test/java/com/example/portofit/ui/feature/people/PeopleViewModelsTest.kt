package com.example.portofit.ui.feature.people

import androidx.lifecycle.SavedStateHandle
import com.example.portofit.data.auth.SessionState
import com.example.portofit.data.auth.SessionStatus
import com.example.portofit.data.model.Page
import com.example.portofit.data.model.Sport
import com.example.portofit.data.remote.PublicProfileDto
import com.example.portofit.data.repository.DataError
import com.example.portofit.data.repository.DataResult
import com.example.portofit.data.repository.FollowError
import com.example.portofit.data.repository.FollowResult
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

        val listCalls = mutableListOf<Triple<String, String, Int>>()
        var onList: suspend (String, Int) -> DataResult<Page<UserSummary>> = { kind, page -> DataResult.Success(pageOf(kind, page, 2)) }
        override suspend fun followers(userId: String, page: Int, pageSize: Int): DataResult<Page<UserSummary>> {
            listCalls += Triple("followers", userId, page)
            return onList("followers", page)
        }
        override suspend fun following(userId: String, page: Int, pageSize: Int): DataResult<Page<UserSummary>> {
            listCalls += Triple("following", userId, page)
            return onList("following", page)
        }

        val followCalls = mutableListOf<Pair<String, Boolean>>()
        var onSetFollowing: suspend (Boolean) -> FollowResult = { FollowResult.Success }
        override suspend fun setFollowing(userId: String, follow: Boolean): FollowResult {
            followCalls += userId to follow
            return onSetFollowing(follow)
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

    // --- Seguir ---

    /** Servidor falso coerente: seguir altera a relação e a contagem devolvidas no perfil seguinte. */
    private var serverFollows = false

    private fun serverProfile(signedIn: Boolean) = PublicProfile(
        userId = "user-1",
        username = "pamplona",
        description = null,
        interests = emptyList(),
        followersCount = if (serverFollows) 4 else 3,
        followingCount = 2,
        followedByMe = if (signedIn) serverFollows else null,
    )

    private fun followVm(session: MutableStateFlow<SessionState>): PublicProfileViewModel {
        people.onProfile = { DataResult.Success(serverProfile(session.value is SessionState.SignedIn)) }
        people.onSetFollowing = { follow ->
            serverFollows = follow
            FollowResult.Success
        }
        return PublicProfileViewModel(people, catalog, session, SavedStateHandle(mapOf(PublicProfileViewModel.USER_ID_ARG to "user-1")))
    }

    private val visitor get() = MutableStateFlow<SessionState>(SessionState.SignedOut())
    private val signedInAsRui get() = MutableStateFlow<SessionState>(SessionState.SignedIn("user-2", "rui"))

    @Test
    fun `visitante ve contagens e Seguir leva ao login sem pedidos`() {
        val vm = followVm(visitor)
        val s = vm.uiState.value
        assertEquals(FollowButton.LOGIN_REQUIRED, s.followButton)
        assertEquals(3L, s.profile!!.followersCount)
        vm.toggleFollow()
        assertTrue(people.followCalls.isEmpty())
    }

    @Test
    fun `seguir e deixar de seguir conforme a relacao confirmada pelo servidor`() {
        val vm = followVm(signedInAsRui)
        assertEquals(FollowButton.FOLLOW, vm.uiState.value.followButton)

        vm.toggleFollow()
        assertEquals(listOf("user-1" to true), people.followCalls)
        assertEquals(FollowButton.UNFOLLOW, vm.uiState.value.followButton)
        assertEquals(4L, vm.uiState.value.profile!!.followersCount)
        assertEquals(FollowAction.Idle, vm.uiState.value.followAction)

        vm.toggleFollow()
        assertEquals("user-1" to false, people.followCalls.last())
        assertEquals(FollowButton.FOLLOW, vm.uiState.value.followButton)
        assertEquals(3L, vm.uiState.value.profile!!.followersCount)
    }

    @Test
    fun `toques repetidos durante o pedido nao duplicam a acao`() {
        val vm = followVm(signedInAsRui)
        val pending = CompletableDeferred<FollowResult>()
        people.onSetFollowing = { pending.await() }

        vm.toggleFollow()
        vm.toggleFollow()
        vm.toggleFollow()
        assertEquals(FollowAction.InProgress, vm.uiState.value.followAction)
        assertEquals(1, people.followCalls.size)

        serverFollows = true
        pending.complete(FollowResult.Success)
        assertEquals(FollowButton.UNFOLLOW, vm.uiState.value.followButton)
    }

    @Test
    fun `falha mantem o ultimo estado confirmado e permite repetir`() {
        val vm = followVm(signedInAsRui)
        people.onSetFollowing = { FollowResult.Failure(FollowError.NETWORK) }

        vm.toggleFollow()
        assertEquals(FollowAction.Failed(FollowError.NETWORK), vm.uiState.value.followAction)
        assertEquals(FollowButton.FOLLOW, vm.uiState.value.followButton)
        assertEquals(3L, vm.uiState.value.profile!!.followersCount)

        people.onSetFollowing = { follow ->
            serverFollows = follow
            FollowResult.Success
        }
        vm.toggleFollow()
        assertEquals(FollowButton.UNFOLLOW, vm.uiState.value.followButton)
        assertEquals(FollowAction.Idle, vm.uiState.value.followAction)
    }

    @Test
    fun `acao aceite mas sem confirmacao nao inventa o novo estado`() {
        val vm = followVm(signedInAsRui)
        val confirmedProfile = vm.uiState.value.profile
        people.onSetFollowing = {
            serverFollows = true
            people.onProfile = { DataResult.Failure(DataError.NETWORK) }
            FollowResult.Success
        }

        vm.toggleFollow()
        assertEquals(FollowAction.ResultUnconfirmed, vm.uiState.value.followAction)
        assertEquals(confirmedProfile, vm.uiState.value.profile)

        people.onProfile = { DataResult.Success(serverProfile(signedIn = true)) }
        vm.refreshRelation()
        assertEquals(FollowAction.Idle, vm.uiState.value.followAction)
        assertEquals(FollowButton.UNFOLLOW, vm.uiState.value.followButton)
    }

    @Test
    fun `proprio perfil sem botao e relacao nao confirmada sem botao`() {
        assertEquals(FollowButton.HIDDEN, followVm(MutableStateFlow(SessionState.SignedIn("user-1", "pamplona"))).uiState.value.followButton)

        // Com sessão, mas o servidor não indicou a relação (ex.: pedido feito sem token válido).
        val vm = followVm(signedInAsRui)
        people.onProfile = { DataResult.Success(serverProfile(signedIn = false)) }
        vm.retry()
        assertEquals(FollowButton.UNCONFIRMED, vm.uiState.value.followButton)
        vm.toggleFollow()
        assertTrue(people.followCalls.isEmpty())
    }

    @Test
    fun `depois de entrar recarrega a relacao sem seguir automaticamente`() {
        val session = visitor
        val vm = followVm(session)
        assertEquals(FollowButton.LOGIN_REQUIRED, vm.uiState.value.followButton)

        session.value = SessionState.SignedIn("user-2", "rui")
        assertEquals(FollowButton.FOLLOW, vm.uiState.value.followButton)
        assertEquals(2, people.profiles.size)
        assertTrue(people.followCalls.isEmpty())

        // Terminar sessão volta ao estado de visitante.
        session.value = SessionState.SignedOut()
        assertEquals(FollowButton.LOGIN_REQUIRED, vm.uiState.value.followButton)
        assertEquals(null, vm.uiState.value.profile!!.followedByMe)
    }

    @Test
    fun `ao voltar ao ecra atualiza a relacao e uma falha mantem o perfil`() {
        val vm = followVm(signedInAsRui)
        vm.onResumed() // primeira apresentação: já carregado no init
        assertEquals(1, people.profiles.size)

        // Entretanto, noutro ecrã (ex.: numa lista), passou a seguir esta pessoa.
        serverFollows = true
        vm.onResumed()
        assertEquals(FollowButton.UNFOLLOW, vm.uiState.value.followButton)
        assertEquals(4L, vm.uiState.value.profile!!.followersCount)

        people.onProfile = { DataResult.Failure(DataError.NETWORK) }
        vm.onResumed()
        assertTrue(vm.uiState.value.load is PublicProfileLoad.Loaded)
        assertEquals(FollowButton.UNFOLLOW, vm.uiState.value.followButton)
    }

    @Test
    fun `resposta de um carregamento antigo do perfil e ignorada`() {
        val vm = followVm(signedInAsRui)
        val slow = CompletableDeferred<DataResult<PublicProfile>>()
        people.onProfile = { slow.await() }
        vm.onResumed()
        vm.onResumed() // pedido lento em curso
        serverFollows = true
        people.onProfile = { DataResult.Success(serverProfile(signedIn = true)) }
        vm.retry()
        slow.complete(DataResult.Success(serverProfile(signedIn = true).copy(followedByMe = false, followersCount = 99)))
        assertEquals(FollowButton.UNFOLLOW, vm.uiState.value.followButton)
        assertEquals(4L, vm.uiState.value.profile!!.followersCount)
    }

    // --- Listas de seguidores e seguidos ---

    private fun listVm(kind: FollowListKind) = FollowListViewModel(
        people,
        SavedStateHandle(mapOf(FollowListViewModel.USER_ID_ARG to "user-1", FollowListViewModel.KIND_ARG to kind.name)),
    )

    @Test
    fun `lista de seguidores pagina ate ao fim`() {
        val vm = listVm(FollowListKind.FOLLOWERS)
        assertEquals(Triple("followers", "user-1", 0), people.listCalls.single())
        vm.loadMore()
        val loaded = vm.uiState.value as FollowListState.Loaded
        assertEquals(4, loaded.people.size)
        assertFalse(loaded.hasMore)
        vm.loadMore()
        assertEquals(2, people.listCalls.size)
    }

    @Test
    fun `lista de seguidos usa o pedido certo e trata erro e nova tentativa`() {
        people.onList = { _, _ -> DataResult.Failure(DataError.NETWORK) }
        val vm = listVm(FollowListKind.FOLLOWING)
        assertEquals(FollowListState.Error(DataError.NETWORK), vm.uiState.value)
        assertEquals("following", people.listCalls.single().first)

        people.onList = { _, _ -> DataResult.Success(Page(emptyList(), 0, 0, 0)) }
        vm.retry()
        assertEquals(emptyList<UserSummary>(), (vm.uiState.value as FollowListState.Loaded).people)
    }

    @Test
    fun `falha ao carregar mais mantem a lista`() {
        val vm = listVm(FollowListKind.FOLLOWERS)
        people.onList = { _, _ -> DataResult.Failure(DataError.SERVER) }
        vm.loadMore()
        val loaded = vm.uiState.value as FollowListState.Loaded
        assertEquals(2, loaded.people.size)
        assertEquals(DataError.SERVER, loaded.loadMoreError)
        assertTrue(loaded.hasMore)
    }

    @Test
    fun `resposta antiga da lista e ignorada depois de nova tentativa`() {
        val slow = CompletableDeferred<DataResult<Page<UserSummary>>>()
        people.onList = { _, _ -> slow.await() }
        val vm = listVm(FollowListKind.FOLLOWERS)
        people.onList = { kind, page -> DataResult.Success(FakePeople.pageOf(kind, page, 1)) }
        vm.retry()
        slow.complete(DataResult.Failure(DataError.NETWORK))
        assertTrue(vm.uiState.value is FollowListState.Loaded)
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
        assertEquals(setOf("userId", "username", "description", "interests", "followersCount", "followingCount", "followedByMe"), fields)
    }
}
