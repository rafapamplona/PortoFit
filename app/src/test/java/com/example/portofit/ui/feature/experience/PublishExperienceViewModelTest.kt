package com.example.portofit.ui.feature.experience

import androidx.lifecycle.SavedStateHandle
import com.example.portofit.data.auth.SessionState
import com.example.portofit.data.model.ExperienceTextProblem
import com.example.portofit.data.repository.ExperienceChange
import com.example.portofit.data.repository.ExperienceChanges
import com.example.portofit.data.repository.ExperienceDraft
import com.example.portofit.data.repository.PublishOutcome
import com.example.portofit.data.repository.PublishRejection
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

class PublishExperienceViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val repository = FakeExperiencesRepository()
    private val changes = ExperienceChanges()
    private val session = MutableStateFlow<SessionState>(SessionState.SignedIn("author-1", "ana"))
    private var now = 1_000_000L
    private var keys = 0

    private fun savedState() = SavedStateHandle(
        mapOf(
            PublishExperienceViewModel.PLACE_ID_ARG to "p1",
            PublishExperienceViewModel.OFFERING_ID_ARG to "o1",
            PublishExperienceViewModel.PLACE_NAME_ARG to "Clube",
            PublishExperienceViewModel.OFFERING_NAME_ARG to "Boxe iniciação",
        ),
    )

    private fun viewModel(state: SavedStateHandle = savedState()) =
        PublishExperienceViewModel(repository, changes, session, state, clock = { now }, newKey = { "key-${++keys}" })

    private fun PublishExperienceViewModel.fill(text: String = "  Boa aula.\r\n", stars: Int = 4) {
        onTextChange(text)
        onStarsChange(stars)
    }

    @Test
    fun `validacoes do contrato antes de enviar`() {
        val vm = viewModel()
        vm.submit()
        assertTrue(vm.uiState.value.showValidation)
        assertEquals(ExperienceTextProblem.EMPTY, vm.uiState.value.textProblem)
        assertTrue(vm.uiState.value.starsMissing)

        vm.onTextChange("   \n  ")
        assertEquals(ExperienceTextProblem.EMPTY, vm.uiState.value.textProblem)
        vm.onTextChange("😀".repeat(2000))
        assertNull(vm.uiState.value.textProblem)
        assertEquals(2000, vm.uiState.value.textLength)
        vm.onTextChange("a".repeat(2001))
        assertEquals(ExperienceTextProblem.TOO_LONG, vm.uiState.value.textProblem)
        vm.onTextChange("com campainha \u0007")
        assertEquals(ExperienceTextProblem.INVALID_CHARACTERS, vm.uiState.value.textProblem)
        vm.onStarsChange(6)
        assertTrue(vm.uiState.value.starsMissing)
        vm.submit()
        assertTrue(repository.publishCalls.isEmpty())
    }

    @Test
    fun `publica com texto normalizado, avisa as listas e termina`() = runTest(UnconfinedTestDispatcher()) {
        val received = mutableListOf<ExperienceChange>()
        val job = launch { changes.events.collect { received += it } }
        val vm = viewModel()
        vm.fill()
        vm.submit()

        assertEquals(listOf("key-1" to ExperienceDraft("p1", "o1", "Boa aula.", 4)), repository.publishCalls)
        assertEquals("e-new", vm.uiState.value.publishedId)
        assertEquals(listOf(ExperienceChange("e-new", "p1", "author-1")), received)
        job.cancel()
    }

    @Test
    fun `envios repetidos durante o pedido sao ignorados`() {
        val pending = CompletableDeferred<PublishOutcome>()
        repository.onPublish = { _, _ -> pending.await() }
        val vm = viewModel()
        vm.fill()
        vm.submit()
        vm.submit()
        vm.submit()
        assertTrue(vm.uiState.value.sending)
        assertFalse(vm.uiState.value.canSubmit)
        assertEquals(1, repository.publishCalls.size)
        pending.complete(PublishOutcome.Unconfirmed)
        assertFalse(vm.uiState.value.sending)
    }

    @Test
    fun `resultado desconhecido bloqueia o conteudo e repete com a mesma chave`() {
        repository.onPublish = { _, _ -> PublishOutcome.Unconfirmed }
        val vm = viewModel()
        vm.fill()
        vm.submit()
        val state = vm.uiState.value
        assertTrue(state.pendingAttempt)
        assertTrue(state.contentLocked)
        assertNull(state.publishedId)

        // Não se pode alterar o conteúdo de uma tentativa por confirmar.
        vm.onTextChange("Outro texto")
        vm.onStarsChange(1)
        assertEquals("  Boa aula.\r\n", vm.uiState.value.text)
        assertEquals(4, vm.uiState.value.stars)

        // A repetição (só com novo toque) usa a mesma chave e o mesmo conteúdo; o servidor responde 200.
        repository.onPublish = { _, draft ->
            PublishOutcome.Published(FakeExperiencesRepository.experience("e-1", placeId = draft.placeId), replayed = true)
        }
        vm.submit()
        assertEquals(2, repository.publishCalls.size)
        assertEquals(repository.publishCalls[0], repository.publishCalls[1])
        assertEquals("e-1", vm.uiState.value.publishedId)
        assertFalse(vm.uiState.value.pendingAttempt)
    }

    @Test
    fun `tentativa por confirmar sobrevive a recriacao do processo`() {
        val saved = savedState()
        repository.onPublish = { _, _ -> PublishOutcome.Unconfirmed }
        viewModel(saved).apply { fill(); submit() }

        val restored = viewModel(saved)
        assertTrue(restored.uiState.value.pendingAttempt)
        assertEquals("  Boa aula.\r\n", restored.uiState.value.text)
        assertEquals(4, restored.uiState.value.stars)
        assertEquals(1, repository.publishCalls.size) // nada é enviado sozinho

        restored.submit()
        assertEquals("key-1", repository.publishCalls.last().first)
        assertEquals("Boa aula.", repository.publishCalls.last().second.text)
    }

    @Test
    fun `rascunho e chave guardados antes de enviar, para uma morte a meio do pedido`() {
        val saved = savedState()
        repository.onPublish = { _, _ -> CompletableDeferred<PublishOutcome>().await() }
        viewModel(saved).apply { fill(); submit() }
        assertEquals("key-1", saved.get<String>(PublishExperienceViewModel.KEY_PENDING_KEY))

        repository.onPublish = { _, _ -> PublishOutcome.Unconfirmed }
        val restored = viewModel(saved)
        assertTrue(restored.uiState.value.pendingAttempt)
        restored.submit()
        assertEquals("key-1", repository.publishCalls.last().first)
    }

    @Test
    fun `descartar a tentativa permite alterar o conteudo e usa uma chave nova`() {
        repository.onPublish = { _, _ -> PublishOutcome.Unconfirmed }
        val vm = viewModel()
        vm.fill()
        vm.submit()
        vm.discardAttempt()
        assertFalse(vm.uiState.value.pendingAttempt)

        vm.onTextChange("Texto revisto")
        assertEquals("Texto revisto", vm.uiState.value.text)
        vm.submit()
        assertEquals("key-2", repository.publishCalls.last().first)
        assertEquals("Texto revisto", repository.publishCalls.last().second.text)
    }

    @Test
    fun `recusa definitiva descarta a chave e mantem o texto`() {
        repository.onPublish = { _, _ -> PublishOutcome.Rejected(PublishRejection.CATALOG_UNAVAILABLE) }
        val vm = viewModel()
        vm.fill()
        vm.submit()
        assertEquals(PublishMessage.Rejected(PublishRejection.CATALOG_UNAVAILABLE), vm.uiState.value.message)
        assertFalse(vm.uiState.value.pendingAttempt)
        assertEquals("  Boa aula.\r\n", vm.uiState.value.text)

        repository.onPublish = { _, _ -> PublishOutcome.Rejected(PublishRejection.TOO_MANY, retryAfterSeconds = 120) }
        vm.submit()
        assertEquals("key-2", repository.publishCalls.last().first)
        assertEquals(PublishMessage.Rejected(PublishRejection.TOO_MANY, 120), vm.uiState.value.message)
    }

    @Test
    fun `chave com mais de 6 dias nao e reutilizada`() {
        repository.onPublish = { _, _ -> PublishOutcome.Unconfirmed }
        val vm = viewModel()
        vm.fill()
        vm.submit()
        now += PublishExperienceViewModel.MAX_KEY_AGE_MILLIS + 1

        vm.submit()
        assertEquals(PublishMessage.AttemptTooOld, vm.uiState.value.message)
        assertEquals(1, repository.publishCalls.size)

        vm.discardAttempt()
        repository.onPublish = { _, draft -> PublishOutcome.Published(FakeExperiencesRepository.experience("e-2", placeId = draft.placeId), false) }
        vm.submit()
        assertEquals("key-2", repository.publishCalls.last().first)
        assertEquals("e-2", vm.uiState.value.publishedId)
    }

    @Test
    fun `conflitos 409 do contrato`() {
        repository.onPublish = { _, _ -> PublishOutcome.AlreadyPublished("e-old") }
        val vm = viewModel()
        vm.fill()
        vm.submit()
        assertEquals(PublishMessage.AlreadyPublished("e-old"), vm.uiState.value.message)
        assertFalse(vm.uiState.value.pendingAttempt)

        repository.onPublish = { _, _ -> PublishOutcome.PublishedThenDeleted }
        vm.submit()
        assertEquals(PublishMessage.PublishedThenDeleted, vm.uiState.value.message)
        assertEquals("key-2", repository.publishCalls.last().first)
    }

    @Test
    fun `sem sessao nao envia e o texto fica guardado`() {
        session.value = SessionState.SignedOut()
        val saved = savedState()
        val vm = viewModel(saved)
        vm.fill()
        assertFalse(vm.uiState.value.canSubmit)
        vm.submit()
        assertTrue(repository.publishCalls.isEmpty())
        assertEquals("  Boa aula.\r\n", viewModel(saved).uiState.value.text)

        // Depois de entrar, publicar exige um novo toque.
        session.value = SessionState.SignedIn("author-1", "ana")
        assertTrue(repository.publishCalls.isEmpty())
        vm.submit()
        assertEquals(1, repository.publishCalls.size)
    }

    @Test
    fun `sessao terminada ao publicar mantem o formulario`() {
        repository.onPublish = { _, _ -> PublishOutcome.Rejected(PublishRejection.SESSION_ENDED) }
        val vm = viewModel()
        vm.fill()
        vm.submit()
        assertEquals(PublishMessage.Rejected(PublishRejection.SESSION_ENDED), vm.uiState.value.message)
        assertEquals(4, vm.uiState.value.stars)
    }
}
