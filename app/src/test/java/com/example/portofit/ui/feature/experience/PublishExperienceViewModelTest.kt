package com.example.portofit.ui.feature.experience

import androidx.lifecycle.SavedStateHandle
import com.example.portofit.data.auth.SessionState
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.portofit.data.model.ExperienceTextProblem
import com.example.portofit.data.photo.PhotoError
import com.example.portofit.data.photo.PrepareResult
import com.example.portofit.data.repository.UploadError
import com.example.portofit.data.repository.UploadOutcome
import com.example.portofit.data.repository.ExperienceChange
import com.example.portofit.data.repository.ExperienceChanges
import com.example.portofit.data.repository.ExperienceDraft
import com.example.portofit.data.repository.PublishOutcome
import com.example.portofit.data.repository.PublishRejection
import com.example.portofit.testing.FakeExperiencesRepository
import com.example.portofit.testing.FakePhotoDrafts
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

    private val drafts = FakePhotoDrafts()

    private fun viewModel(state: SavedStateHandle = savedState()) =
        PublishExperienceViewModel(repository, changes, session, state, drafts, clock = { now }, newKey = { "key-${++keys}" })

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

    // --- Fotografia (E2) ---

    private fun PublishExperienceViewModel.pick() = onPicked("content://media/picker/0/1")

    @Test
    fun `fotografia enviada antes de publicar e mediaId usado so depois do sucesso`() {
        val vm = viewModel()
        vm.fill()
        vm.pick()
        assertTrue(vm.uiState.value.photo is PhotoDraftState.Ready)
        vm.submit()

        assertEquals(1, repository.uploadCalls.size)
        assertEquals("media-1", repository.publishCalls.single().second.mediaId)
        assertEquals("e-new", vm.uiState.value.publishedId)
        assertTrue("ficheiros temporários apagados depois de publicar", drafts.files().isEmpty())
    }

    @Test
    fun `falha do envio da fotografia nao publica nem perde o rascunho`() {
        repository.onUpload = { UploadOutcome.Failed(UploadError.NETWORK) }
        val vm = viewModel()
        vm.fill()
        vm.pick()
        vm.submit()

        assertEquals(PublishMessage.UploadFailed(UploadError.NETWORK), vm.uiState.value.message)
        assertTrue(repository.publishCalls.isEmpty())
        assertFalse(vm.uiState.value.pendingAttempt)
        assertEquals("  Boa aula.\r\n", vm.uiState.value.text)
        assertTrue(vm.uiState.value.photo is PhotoDraftState.Ready)

        // "Publicar sem fotografia".
        vm.removePhoto()
        assertNull(vm.uiState.value.message)
        vm.submit()
        assertNull(repository.publishCalls.single().second.mediaId)
    }

    @Test
    fun `envios repetidos durante o envio da fotografia sao ignorados`() {
        val pending = CompletableDeferred<UploadOutcome>()
        repository.onUpload = { pending.await() }
        val vm = viewModel()
        vm.fill()
        vm.pick()
        vm.submit()
        vm.submit()
        assertEquals(SendPhase.UPLOADING_PHOTO, vm.uiState.value.sendPhase)
        assertFalse(vm.uiState.value.canSubmit)
        vm.removePhoto()
        assertTrue("fotografia bloqueada durante o envio", vm.uiState.value.photo is PhotoDraftState.Ready)
        pending.complete(UploadOutcome.Uploaded("m1", 1600, 1200))
        assertEquals(1, repository.uploadCalls.size)
        assertEquals(1, repository.publishCalls.size)
    }

    @Test
    fun `resultado ambiguo com fotografia repete a mesma chave, conteudo e mediaId sem novo envio`() {
        repository.onPublish = { _, _ -> PublishOutcome.Unconfirmed }
        val saved = savedState()
        val vm = viewModel(saved)
        vm.fill()
        vm.pick()
        vm.submit()
        assertTrue(vm.uiState.value.pendingAttempt)
        assertTrue(vm.uiState.value.pendingHasPhoto)

        // A fotografia desta tentativa não pode ser alterada.
        val photoBefore = vm.uiState.value.photo
        vm.pick()
        vm.removePhoto()
        assertEquals(photoBefore, vm.uiState.value.photo)
        assertNull(vm.startCapture())

        vm.submit()
        assertEquals(1, repository.uploadCalls.size)
        assertEquals(repository.publishCalls[0], repository.publishCalls[1])

        // Depois da recriação do processo: a mesma tentativa, sem novo envio.
        val restored = viewModel(saved)
        assertTrue(restored.uiState.value.pendingHasPhoto)
        restored.submit()
        assertEquals(1, repository.uploadCalls.size)
        assertEquals(repository.publishCalls[0], repository.publishCalls[2])
    }

    @Test
    fun `recusa definitiva reaproveita a fotografia enviada, mas nao se ja nao servir ou depois de descartar`() {
        repository.onPublish = { _, _ -> PublishOutcome.Rejected(PublishRejection.CATALOG_UNAVAILABLE) }
        val vm = viewModel()
        vm.fill()
        vm.pick()
        vm.submit()
        vm.submit()
        assertEquals(1, repository.uploadCalls.size)
        assertEquals(listOf("key-1", "key-2"), repository.publishCalls.map { it.first })
        assertEquals(listOf("media-1", "media-1"), repository.publishCalls.map { it.second.mediaId })

        repository.onPublish = { _, _ -> PublishOutcome.Rejected(PublishRejection.MEDIA_NOT_USABLE) }
        vm.submit()
        vm.submit()
        assertEquals(2, repository.uploadCalls.size)

        repository.onPublish = { _, _ -> PublishOutcome.Unconfirmed }
        vm.submit()
        vm.discardAttempt()
        repository.onPublish = { _, d -> PublishOutcome.Published(FakeExperiencesRepository.experience("e-9", placeId = d.placeId), false) }
        vm.submit()
        // Envio 3: a tentativa que ficou por confirmar; envio 4: depois de a descartar.
        assertEquals(4, repository.uploadCalls.size)
        assertEquals("media-4", repository.publishCalls.last().second.mediaId)
    }

    @Test
    fun `fotografia em falta depois da recriacao - aviso, texto mantido e nova escolha`() {
        val saved = savedState()
        viewModel(saved).apply { fill(); pick() }
        drafts.files().forEach { java.io.File(drafts.dir, it).delete() }

        val restored = viewModel(saved)
        assertEquals(PhotoDraftState.Missing, restored.uiState.value.photo)
        assertEquals(PhotoError.MISSING, restored.uiState.value.photoError)
        assertEquals("  Boa aula.\r\n", restored.uiState.value.text)
        restored.submit()
        assertTrue(repository.uploadCalls.isEmpty())
        assertTrue(repository.publishCalls.isEmpty())

        restored.pick()
        assertTrue(restored.uiState.value.photo is PhotoDraftState.Ready)
        assertNull(restored.uiState.value.photoError)
    }

    @Test
    fun `fotografia apagada antes de enviar - aviso sem publicar`() {
        val vm = viewModel()
        vm.fill()
        vm.pick()
        drafts.files().forEach { java.io.File(drafts.dir, it).delete() }
        vm.submit()
        assertEquals(PhotoDraftState.Missing, vm.uiState.value.photo)
        assertTrue(repository.uploadCalls.isEmpty())
        assertTrue(repository.publishCalls.isEmpty())
    }

    @Test
    fun `captura - sobrevive a recriacao, cancelamento e camara indisponivel sem perder o rascunho`() {
        val saved = savedState()
        val vm = viewModel(saved)
        vm.fill()
        val uri = vm.startCapture()
        assertTrue(uri!!.startsWith("content://com.example.portofit.photos/"))

        // A app é terminada com a câmara aberta; ao voltar, o resultado chega ao novo ViewModel.
        val restored = viewModel(saved)
        restored.onCaptureResult(true)
        assertTrue(restored.uiState.value.photo is PhotoDraftState.Ready)
        assertTrue("captura apagada depois de preparada", drafts.files().none { it.startsWith("capture-") })

        // Cancelada: fica a fotografia anterior e o texto.
        restored.startCapture()
        restored.onCaptureResult(false)
        assertEquals(PhotoError.CAPTURE_CANCELLED, restored.uiState.value.photoError)
        assertTrue(restored.uiState.value.photo is PhotoDraftState.Ready)
        assertTrue(drafts.files().none { it.startsWith("capture-") })
        assertEquals("  Boa aula.\r\n", restored.uiState.value.text)

        restored.startCapture()
        restored.onCaptureUnavailable()
        assertEquals(PhotoError.CAMERA_UNAVAILABLE, restored.uiState.value.photoError)
        assertEquals(4, restored.uiState.value.stars)
    }

    @Test
    fun `falha a preparar mantem a fotografia anterior`() {
        val vm = viewModel()
        vm.pick()
        val first = vm.uiState.value.photo
        drafts.onPrepare = { PrepareResult.Failure(PhotoError.UNREADABLE) }
        vm.pick()
        assertEquals(first, vm.uiState.value.photo)
        assertEquals(PhotoError.UNREADABLE, vm.uiState.value.photoError)
        assertTrue(drafts.exists((first as PhotoDraftState.Ready).name))
    }

    @Test
    fun `escolher outra fotografia apaga a anterior da app e obriga a novo envio`() {
        repository.onPublish = { _, _ -> PublishOutcome.Rejected(PublishRejection.CATALOG_UNAVAILABLE) }
        val vm = viewModel()
        vm.fill()
        vm.pick()
        val first = (vm.uiState.value.photo as PhotoDraftState.Ready).name
        vm.submit()
        vm.pick()
        assertFalse(drafts.exists(first))
        vm.submit()
        assertEquals(2, repository.uploadCalls.size)
    }

    @Test
    fun `sair do ecra apaga so os ficheiros temporarios do rascunho`() {
        val store = ViewModelStore()
        val vm = ViewModelProvider.create(store, viewModelFactory { initializer { viewModel() } })[PublishExperienceViewModel::class.java]
        vm.pick()
        vm.startCapture()
        assertEquals(2, drafts.files().size)
        store.clear()
        assertTrue(drafts.files().isEmpty())
    }
}
