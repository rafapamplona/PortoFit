package com.example.portofit.ui.feature.profile

import androidx.lifecycle.SavedStateHandle
import com.example.portofit.data.model.Sport
import com.example.portofit.data.repository.DataError
import com.example.portofit.data.repository.DataResult
import com.example.portofit.data.repository.Profile
import com.example.portofit.data.repository.ProfileError
import com.example.portofit.data.repository.ProfileRepository
import com.example.portofit.data.repository.ProfileResult
import com.example.portofit.data.auth.SessionState
import com.example.portofit.data.repository.ExperienceChanges
import com.example.portofit.testing.FakeCatalogRepository
import com.example.portofit.testing.FakeExperiencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import com.example.portofit.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProfileViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val saved = Profile("user-1", "pamplona", "p@example.org", "Corro ao sábado.", listOf("boxe", "artes-marciais"))

    private class FakeProfiles(var profile: Profile) : ProfileRepository {
        val descriptionCalls = mutableListOf<String?>()
        val interestsCalls = mutableListOf<List<String>>()
        var loadCalls = 0
        var onLoad: suspend () -> ProfileResult = { ProfileResult.Success(profile) }
        var onDescription: suspend (String?) -> ProfileResult = { d -> profile = profile.copy(description = d); ProfileResult.Success(profile) }
        var onInterests: suspend (List<String>) -> ProfileResult = { i -> profile = profile.copy(interests = i); ProfileResult.Success(profile) }

        override suspend fun load(): ProfileResult { loadCalls++; return onLoad() }
        override suspend fun saveDescription(description: String?) = descriptionCalls.add(description).let { onDescription(description) }
        override suspend fun saveInterests(interests: List<String>) = interestsCalls.add(interests).let { onInterests(interests) }
    }

    private val profiles = FakeProfiles(saved)
    private val catalog = FakeCatalogRepository().apply {
        sportsResult = { DataResult.Success(listOf(Sport("boxe", "Boxe"), Sport("ioga", "Ioga"), Sport("natacao", "Natação"))) }
    }

    private val experiences = FakeExperiencesRepository()
    private val changes = ExperienceChanges()
    private val session = MutableStateFlow<SessionState>(SessionState.SignedIn("user-1", "pamplona"))

    private fun viewModel(state: SavedStateHandle = SavedStateHandle()) = ProfileViewModel(profiles, catalog, experiences, changes, session, state)

    // --- Publicações ---

    @Test
    fun `as minhas publicacoes vem do userId da sessao e falham a parte`() {
        experiences.onList = { com.example.portofit.data.repository.DataResult.Failure(DataError.NETWORK) }
        val vm = viewModel()
        assertEquals("user-1", experiences.listCalls.single().authorId)
        assertEquals(com.example.portofit.ui.feature.experience.ExperienceListState.Error(DataError.NETWORK), vm.publications.value)
        // O perfil continua carregado.
        assertEquals(ProfileLoad.Loaded, vm.uiState.value.load)

        experiences.onList = { call -> DataResult.Success(FakeExperiencesRepository.pageOf(call.page, 1)) }
        vm.retryPublications()
        assertTrue(vm.publications.value is com.example.portofit.ui.feature.experience.ExperienceListState.Loaded)
    }

    @Test
    fun `publicacao propria noutro ecra atualiza a lista`() {
        viewModel()
        experiences.listCalls.clear()
        changes.notify(com.example.portofit.data.repository.ExperienceChange("e1", "p1", "user-1"))
        assertEquals(1, experiences.listCalls.size)
        changes.notify(com.example.portofit.data.repository.ExperienceChange("e2", "p1", "outra-pessoa"))
        assertEquals(1, experiences.listCalls.size)
    }

    // --- Carregamento ---

    @Test
    fun `carrega o perfil e as modalidades`() {
        val vm = viewModel()
        val s = vm.uiState.value
        assertEquals(ProfileLoad.Loaded, s.load)
        assertEquals("Corro ao sábado.", s.description)
        assertEquals(listOf("boxe", "artes-marciais"), s.interests)
        assertFalse(s.hasUnsavedChanges)
        // Modalidade guardada que o catálogo não indica: nome legível a partir do id.
        val unknown = s.interestOptions.single { it.id == "artes-marciais" }
        assertEquals("Artes marciais", unknown.name)
        assertFalse(unknown.nameFromCatalog)
        assertTrue(unknown.selected)
    }

    @Test
    fun `erro ao carregar e nova tentativa`() {
        profiles.onLoad = { ProfileResult.Failure(ProfileError.NETWORK) }
        val vm = viewModel()
        assertEquals(ProfileLoad.Failed(ProfileError.NETWORK), vm.uiState.value.load)
        assertNull(vm.uiState.value.profile)

        profiles.onLoad = { ProfileResult.Success(saved) }
        vm.retryLoad()
        assertEquals(ProfileLoad.Loaded, vm.uiState.value.load)
    }

    @Test
    fun `resposta de carregamento nao substitui edicoes locais`() {
        val vm = viewModel()
        vm.onDescriptionChange("Texto que estou a escrever")
        vm.onInterestToggle("ioga")

        val slow = CompletableDeferred<ProfileResult>()
        profiles.onLoad = { slow.await() }
        vm.retryLoad()
        slow.complete(ProfileResult.Success(saved.copy(description = "Mudou noutro aparelho", interests = listOf("natacao"))))

        val s = vm.uiState.value
        assertEquals("Texto que estou a escrever", s.description)
        assertEquals(listOf("boxe", "artes-marciais", "ioga"), s.interests)
        assertEquals("Mudou noutro aparelho", s.profile?.description) // o servidor foi atualizado
        assertTrue(s.hasUnsavedChanges)
    }

    @Test
    fun `resposta antiga de carregamento e ignorada`() {
        val first = CompletableDeferred<ProfileResult>()
        val second = CompletableDeferred<ProfileResult>()
        val queue = ArrayDeque(listOf(first, second))
        profiles.onLoad = { ProfileResult.Success(saved) }
        val vm = viewModel()
        profiles.onLoad = { queue.removeFirst().await() }
        vm.retryLoad()
        vm.retryLoad()
        second.complete(ProfileResult.Success(saved.copy(description = "recente")))
        first.complete(ProfileResult.Success(saved.copy(description = "antiga")))
        assertEquals("recente", vm.uiState.value.profile?.description)
    }

    // --- Descrição ---

    @Test
    fun `guardar descricao apara espacos, confirma e impede envios repetidos`() {
        val slow = CompletableDeferred<ProfileResult>()
        profiles.onDescription = { slow.await() }
        val vm = viewModel()
        vm.onDescriptionChange("  Nova descrição  ")
        vm.saveDescription()
        vm.saveDescription()
        assertEquals(SaveState.Saving, vm.uiState.value.descriptionSave)
        assertEquals(listOf<String?>("Nova descrição"), profiles.descriptionCalls)

        slow.complete(ProfileResult.Success(saved.copy(description = "Nova descrição")))
        val s = vm.uiState.value
        assertEquals(SaveState.Saved, s.descriptionSave)
        assertNull(s.editedDescription)
        assertFalse(s.hasUnsavedChanges)
    }

    @Test
    fun `descricao vazia e enviada como null e texto demasiado longo nao e enviado`() {
        val vm = viewModel()
        vm.onDescriptionChange("a".repeat(301))
        assertTrue(vm.uiState.value.descriptionTooLong)
        vm.saveDescription()
        assertTrue(profiles.descriptionCalls.isEmpty())

        vm.onDescriptionChange("   ")
        vm.saveDescription()
        assertEquals(listOf<String?>(null), profiles.descriptionCalls)
    }

    @Test
    fun `edicao feita durante a gravacao nao se perde`() {
        val slow = CompletableDeferred<ProfileResult>()
        profiles.onDescription = { slow.await() }
        val vm = viewModel()
        vm.onDescriptionChange("Primeira")
        vm.saveDescription()
        vm.onDescriptionChange("Segunda")
        slow.complete(ProfileResult.Success(saved.copy(description = "Primeira")))

        val s = vm.uiState.value
        assertEquals("Segunda", s.description)
        assertTrue(s.descriptionDirty)
    }

    @Test
    fun `falha ao guardar descricao mantem a edicao e nao afeta os interesses`() {
        profiles.onDescription = { ProfileResult.Failure(ProfileError.NETWORK) }
        val vm = viewModel()
        vm.onDescriptionChange("Não chega ao servidor")
        vm.onInterestToggle("ioga")
        vm.saveDescription()
        assertEquals(SaveState.Failed(ProfileError.NETWORK), vm.uiState.value.descriptionSave)
        assertEquals("Não chega ao servidor", vm.uiState.value.description)

        vm.saveInterests()
        assertEquals(SaveState.Saved, vm.uiState.value.interestsSave)
        assertEquals(listOf("boxe", "artes-marciais", "ioga"), vm.uiState.value.profile?.interests)
        assertTrue(vm.uiState.value.descriptionDirty)
    }

    // --- Interesses ---

    @Test
    fun `selecionar, remover e guardar interesses pela ordem escolhida`() {
        val vm = viewModel()
        vm.onInterestToggle("boxe")
        vm.onInterestToggle("natacao")
        vm.onInterestToggle("ioga")
        vm.saveInterests()
        assertEquals(listOf(listOf("artes-marciais", "natacao", "ioga")), profiles.interestsCalls)
        assertEquals(SaveState.Saved, vm.uiState.value.interestsSave)
        assertFalse(vm.uiState.value.hasUnsavedChanges)
    }

    @Test
    fun `limite de 10 interesses`() {
        catalog.sportsResult = { DataResult.Success((1..12).map { Sport("m$it", "M$it") }) }
        profiles.profile = saved.copy(interests = emptyList())
        val vm = viewModel()
        (1..11).forEach { vm.onInterestToggle("m$it") }
        assertEquals(10, vm.uiState.value.interests.size)
        assertFalse(vm.uiState.value.interestOptions.single { it.id == "m11" }.enabled)
    }

    @Test
    fun `catalogo indisponivel mostra os interesses guardados e so permite remover`() {
        catalog.sportsResult = { DataResult.Failure(DataError.NETWORK) }
        val vm = viewModel()
        val s = vm.uiState.value
        assertEquals(SportsLoad.Failed, s.sports)
        assertEquals(listOf("boxe", "artes-marciais"), s.interests) // nada apagado
        assertEquals(listOf("Boxe", "Artes marciais"), s.interestOptions.map { it.name })

        vm.onInterestToggle("boxe") // remover
        vm.onInterestToggle("ioga") // não existe nas opções: ignorado
        assertEquals(listOf("artes-marciais"), vm.uiState.value.interests)
        vm.onInterestToggle("boxe") // um interesse que já estava guardado pode voltar
        vm.saveInterests()
        assertEquals(listOf(listOf("artes-marciais", "boxe")), profiles.interestsCalls)
    }

    @Test
    fun `catalogo indisponivel no servidor mantem a edicao dos interesses`() {
        profiles.onInterests = { ProfileResult.Failure(ProfileError.CATALOG_UNAVAILABLE) }
        val vm = viewModel()
        vm.onInterestToggle("ioga")
        vm.saveInterests()
        assertEquals(SaveState.Failed(ProfileError.CATALOG_UNAVAILABLE), vm.uiState.value.interestsSave)
        assertEquals(listOf("boxe", "artes-marciais", "ioga"), vm.uiState.value.interests)
        assertEquals(listOf("boxe", "artes-marciais"), vm.uiState.value.profile?.interests)
    }

    // --- Sair e recriar ---

    @Test
    fun `sair com alteracoes pede confirmacao e descartar repoe o perfil`() {
        val vm = viewModel()
        assertTrue(vm.requestExit())
        vm.onDescriptionChange("rascunho")
        assertFalse(vm.requestExit())
        assertTrue(vm.uiState.value.showDiscardConfirmation)
        vm.discardChanges()
        assertFalse(vm.uiState.value.hasUnsavedChanges)
        assertEquals("Corro ao sábado.", vm.uiState.value.description)
        assertTrue(vm.requestExit())
    }

    @Test
    fun `edicoes por guardar sobrevivem a recriacao`() {
        val state = SavedStateHandle()
        viewModel(state).apply {
            onDescriptionChange("Rascunho")
            onInterestToggle("ioga")
        }
        val recreated = viewModel(state)
        assertEquals("Rascunho", recreated.uiState.value.description)
        assertEquals(listOf("boxe", "artes-marciais", "ioga"), recreated.uiState.value.interests)
        assertTrue(recreated.uiState.value.hasUnsavedChanges)
    }
}
