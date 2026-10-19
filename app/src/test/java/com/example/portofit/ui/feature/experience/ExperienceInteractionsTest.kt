package com.example.portofit.ui.feature.experience

import androidx.lifecycle.SavedStateHandle
import com.example.portofit.data.auth.SessionState
import com.example.portofit.data.model.Comment
import com.example.portofit.data.model.Page
import com.example.portofit.data.repository.CommentOutcome
import com.example.portofit.data.repository.CommentRejection
import com.example.portofit.data.repository.DataError
import com.example.portofit.data.repository.DataResult
import com.example.portofit.data.repository.ExperienceChange
import com.example.portofit.data.repository.ExperienceChanges
import com.example.portofit.data.repository.ExperienceCounts
import com.example.portofit.data.repository.LikeError
import com.example.portofit.data.repository.LikeResult
import com.example.portofit.data.repository.MutationError
import com.example.portofit.data.repository.MutationResult
import com.example.portofit.testing.FakeCatalogRepository
import com.example.portofit.testing.FakeExperiencesRepository
import com.example.portofit.testing.FakeExperiencesRepository.Companion.comment
import com.example.portofit.testing.FakeExperiencesRepository.Companion.experience
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

/** E3 no detalhe: gostos, visualizações e comentários (docs/experiences-api.md §13). */
class ExperienceInteractionsTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val repository = FakeExperiencesRepository()
    private val catalog = FakeCatalogRepository()
    private val changes = ExperienceChanges()
    private val session = MutableStateFlow<SessionState>(SessionState.SignedIn("rui-id", "rui"))
    private val drafts = FakePhotoDrafts()
    private var keys = 0

    private fun saved() = SavedStateHandle(mapOf(ExperienceDetailViewModel.EXPERIENCE_ID_ARG to "e1"))

    private fun viewModel(state: SavedStateHandle = saved(), now: Long = 1_000L) =
        ExperienceDetailViewModel(repository, catalog, changes, session, state, drafts, clock = { now }, newKey = { "key-${++keys}" })

    /** Experiência de "author-1", vista por "rui-id" por omissão. */
    private fun serves(likes: Long = 2, comments: Long = 0, likedByMe: Boolean? = false) {
        repository.onGet = { id -> DataResult.Success(experience(id).copy(likesCount = likes, commentsCount = comments, likedByMe = likedByMe)) }
    }

    // --- Gostos ---

    @Test
    fun `visitante ve as contagens e o gosto leva ao login sem pedido`() {
        session.value = SessionState.SignedOut()
        serves(likes = 3, comments = 1, likedByMe = null)
        val vm = viewModel()
        assertEquals(LikeButton.LOGIN_REQUIRED, vm.uiState.value.likeButton)
        assertEquals(3L, vm.uiState.value.experience?.likesCount)
        vm.toggleLike()
        assertTrue(repository.likeCalls.isEmpty())
    }

    @Test
    fun `likedByMe ausente com sessao fica por confirmar e nunca e tratado como false`() {
        serves(likedByMe = null)
        val vm = viewModel()
        assertEquals(LikeButton.UNKNOWN, vm.uiState.value.likeButton)
        vm.toggleLike()
        assertTrue("sem estado confirmado não se envia nada", repository.likeCalls.isEmpty())
        serves(likedByMe = false)
        vm.refreshLike()
        assertEquals(LikeButton.LIKE, vm.uiState.value.likeButton)
    }

    @Test
    fun `gostar sem envios duplicados confirma e atualiza as contagens com o servidor`() = runTest(UnconfinedTestDispatcher()) {
        serves(likes = 2, likedByMe = false)
        val counts = mutableListOf<ExperienceCounts>()
        val collector = launch { changes.counts.collect { counts += it } }
        val pending = CompletableDeferred<LikeResult>()
        repository.onSetLiked = { _, _ -> pending.await() }
        val vm = viewModel()

        vm.toggleLike()
        vm.toggleLike()
        assertEquals(listOf("e1" to true), repository.likeCalls)
        assertTrue(vm.uiState.value.likePending)
        // Enquanto não há resposta, mostra-se o último estado confirmado.
        assertEquals(LikeButton.LIKE, vm.uiState.value.likeButton)

        serves(likes = 3, likedByMe = true)
        pending.complete(LikeResult.Confirmed)
        assertFalse(vm.uiState.value.likePending)
        assertEquals(LikeButton.UNLIKE, vm.uiState.value.likeButton)
        assertEquals(3L, vm.uiState.value.experience?.likesCount)
        assertEquals(ExperienceCounts("e1", 3, 0), counts.last())
        collector.cancel()
    }

    @Test
    fun `falha ambigua mantem o ultimo estado confirmado e atualizar reconcilia`() {
        serves(likes = 2, likedByMe = false)
        repository.onSetLiked = { _, _ -> LikeResult.Failed(LikeError.UNCONFIRMED) }
        val vm = viewModel()
        vm.toggleLike()
        assertEquals(LikeError.UNCONFIRMED, vm.uiState.value.likeError)
        assertEquals(LikeButton.LIKE, vm.uiState.value.likeButton)
        assertEquals(2L, vm.uiState.value.experience?.likesCount)
        assertEquals("nunca há uma nova tentativa automática", 1, repository.likeCalls.size)

        // O pedido tinha chegado ao servidor: a leitura mostra o estado real.
        serves(likes = 3, likedByMe = true)
        vm.refreshLike()
        assertNull(vm.uiState.value.likeError)
        assertEquals(LikeButton.UNLIKE, vm.uiState.value.likeButton)
        assertEquals(1, repository.likeCalls.size)
    }

    @Test
    fun `falha definitiva mantem o estado e retirar o gosto tambem e confirmado`() {
        serves(likes = 1, likedByMe = true)
        repository.onSetLiked = { _, _ -> LikeResult.Failed(LikeError.TOO_MANY, 120) }
        val vm = viewModel()
        vm.toggleLike()
        assertEquals(LikeError.TOO_MANY, vm.uiState.value.likeError)
        assertEquals(120L, vm.uiState.value.likeRetryAfterSeconds)
        assertEquals(LikeButton.UNLIKE, vm.uiState.value.likeButton)

        repository.onSetLiked = { _, _ -> LikeResult.Confirmed }
        serves(likes = 0, likedByMe = false)
        vm.toggleLike()
        assertEquals("e1" to false, repository.likeCalls.last())
        assertEquals(LikeButton.LIKE, vm.uiState.value.likeButton)
        assertEquals(0L, vm.uiState.value.experience?.likesCount)
    }

    @Test
    fun `o autor nao pode gostar da propria experiencia`() {
        session.value = SessionState.SignedIn("author-1", "ana")
        serves(likedByMe = null)
        val vm = viewModel()
        assertEquals(LikeButton.OWN, vm.uiState.value.likeButton)
        vm.toggleLike()
        assertTrue(repository.likeCalls.isEmpty())
    }

    @Test
    fun `regressar do login atualiza o estado sem gostar nem comentar automaticamente`() {
        session.value = SessionState.SignedOut()
        serves(likedByMe = null)
        val vm = viewModel()
        vm.comments.onTextChange("Escrito antes de entrar")
        vm.comments.submit()
        assertTrue(repository.postCommentCalls.isEmpty())

        serves(likedByMe = false)
        session.value = SessionState.SignedIn("rui-id", "rui")
        assertEquals(2, repository.getCalls.size)
        assertEquals(LikeButton.LIKE, vm.uiState.value.likeButton)
        assertTrue(repository.likeCalls.isEmpty())
        assertTrue(repository.postCommentCalls.isEmpty())
        assertEquals("Escrito antes de entrar", vm.comments.state.value.composer.text)
    }

    @Test
    fun `experiencia eliminada durante a visita remove as acoes e avisa as listas`() = runTest(UnconfinedTestDispatcher()) {
        serves(likedByMe = false)
        val events = mutableListOf<ExperienceChange>()
        val collector = launch { changes.events.collect { events += it } }
        repository.onSetLiked = { _, _ -> LikeResult.Failed(LikeError.EXPERIENCE_GONE) }
        val vm = viewModel()
        vm.toggleLike()
        assertEquals(ExperienceLoad.Error(DataError.NOT_FOUND), vm.uiState.value.load)
        assertNull(vm.uiState.value.experience)
        assertEquals("e1", events.single().experienceId)
        collector.cancel()
    }

    // --- Visualizações ---

    @Test
    fun `visualizacao registada uma vez por abertura, nao por recomposicao, rotacao ou nova leitura`() {
        serves()
        val state = saved()
        val vm = viewModel(state)
        assertEquals(listOf("e1"), repository.viewCalls)
        // Estados novos (recomposições), atualizações e "Tentar novamente" não registam de novo.
        vm.uiState.value
        vm.refreshLike()
        vm.retry()
        repository.onSetLiked = { _, _ -> LikeResult.Confirmed }
        vm.toggleLike()
        // Recriação do ViewModel com o mesmo estado guardado (rotação/processo): também não.
        viewModel(state)
        assertEquals(1, repository.viewCalls.size)
        // Uma nova abertura do detalhe regista (o servidor elimina as repetições do período).
        viewModel(saved())
        assertEquals(2, repository.viewCalls.size)
    }

    @Test
    fun `o autor nao contribui e uma falha da leitura nao regista`() {
        session.value = SessionState.SignedIn("author-1", "ana")
        serves()
        viewModel()
        assertTrue(repository.viewCalls.isEmpty())

        session.value = SessionState.SignedOut()
        repository.onGet = { DataResult.Failure(DataError.NETWORK) }
        val vm = viewModel()
        assertTrue(repository.viewCalls.isEmpty())
        serves(likedByMe = null)
        vm.retry()
        assertEquals(listOf("e1"), repository.viewCalls)
    }

    @Test
    fun `com a sessao a carregar espera para saber se e o autor`() {
        session.value = SessionState.Loading
        serves()
        viewModel()
        assertTrue(repository.viewCalls.isEmpty())
        session.value = SessionState.SignedIn("author-1", "ana")
        assertTrue(repository.viewCalls.isEmpty())

        session.value = SessionState.Loading
        viewModel()
        session.value = SessionState.SignedOut()
        assertEquals(listOf("e1"), repository.viewCalls)
    }

    // --- Comentários ---

    private fun commentsPages(vararg pages: List<Comment>) {
        val total = pages.sumOf { it.size }.toLong()
        repository.onComments = { page -> DataResult.Success(Page(pages.getOrElse(page) { emptyList() }, page, total, pages.size)) }
    }

    @Test
    fun `comentarios paginados e com falha independente do conteudo`() {
        repository.onComments = { DataResult.Failure(DataError.NETWORK) }
        val vm = viewModel()
        assertEquals(CommentListState.Error(DataError.NETWORK), vm.comments.state.value.list)
        assertTrue("o texto e a fotografia continuam visíveis", vm.uiState.value.load is ExperienceLoad.Loaded)

        commentsPages(listOf(comment("c1"), comment("c2")), listOf(comment("c2"), comment("c3")))
        vm.comments.load()
        val first = vm.comments.state.value.list as CommentListState.Loaded
        assertEquals(listOf("c1", "c2"), first.items.map { it.id })
        assertTrue(first.hasMore)
        vm.comments.loadMore()
        val all = vm.comments.state.value.list as CommentListState.Loaded
        assertEquals("sem repetições pelo id", listOf("c1", "c2", "c3"), all.items.map { it.id })
        assertFalse(all.hasMore)

        commentsPages(emptyList())
        vm.comments.load()
        assertEquals(emptyList<Comment>(), (vm.comments.state.value.list as CommentListState.Loaded).items)
    }

    @Test
    fun `publicar exige sessao e texto valido`() {
        session.value = SessionState.SignedOut()
        val vm = viewModel()
        vm.comments.onTextChange("Olá")
        vm.comments.submit()
        assertTrue(repository.postCommentCalls.isEmpty())

        session.value = SessionState.SignedIn("rui-id", "rui")
        for (text in listOf("   \n ", "a".repeat(1001))) {
            vm.comments.onTextChange(text)
            vm.comments.submit()
            assertTrue(vm.comments.state.value.composer.showValidation)
        }
        assertTrue(repository.postCommentCalls.isEmpty())
        vm.comments.onTextChange("💪".repeat(1000))
        vm.comments.submit()
        assertEquals(1, repository.postCommentCalls.size)
    }

    @Test
    fun `falha ambigua preserva texto e chave, tambem ao recriar, e a repeticao usa a mesma chave`() {
        serves(comments = 0)
        repository.onPostComment = { _, _, _ -> CommentOutcome.Unconfirmed }
        val state = saved()
        val vm = viewModel(state)
        vm.comments.onTextChange("  Também gostei!  ")
        vm.comments.submit()
        assertEquals(Triple("key-1", "e1", "Também gostei!"), repository.postCommentCalls.single())
        assertTrue(vm.comments.state.value.composer.pendingAttempt)
        // O texto fica bloqueado: a repetição tem de ser exatamente a mesma tentativa.
        vm.comments.onTextChange("Outro texto")
        assertEquals("  Também gostei!  ", vm.comments.state.value.composer.text)

        // Recriação (processo terminado): a tentativa continua por confirmar, com a mesma chave.
        val recreated = viewModel(state)
        assertTrue(recreated.comments.state.value.composer.pendingAttempt)
        serves(comments = 1)
        commentsPages(listOf(comment("c-new", authorId = "rui-id", text = "Também gostei!")))
        repository.onPostComment = { _, id, text -> CommentOutcome.Posted(comment("c-new", authorId = "rui-id", experienceId = id, text = text), replayed = true) }
        recreated.comments.submit()
        assertEquals(Triple("key-1", "e1", "Também gostei!"), repository.postCommentCalls.last())
        assertEquals(2, repository.postCommentCalls.size)

        val composer = recreated.comments.state.value.composer
        assertEquals("", composer.text)
        assertFalse(composer.pendingAttempt)
        assertEquals(CommentMessage.Posted, composer.message)
        assertEquals(listOf("c-new"), (recreated.comments.state.value.list as CommentListState.Loaded).items.map { it.id })
        assertEquals(1L, recreated.uiState.value.experience?.commentsCount)
    }

    @Test
    fun `recusa definitiva descarta a chave e mantem o texto e descartar a tentativa permite editar`() {
        repository.onPostComment = { _, _, _ -> CommentOutcome.Rejected(CommentRejection.TOO_MANY, 60) }
        val vm = viewModel()
        vm.comments.onTextChange("Texto")
        vm.comments.submit()
        assertEquals(CommentMessage.Rejected(CommentRejection.TOO_MANY, 60), vm.comments.state.value.composer.message)
        assertEquals("Texto", vm.comments.state.value.composer.text)
        repository.onPostComment = { _, _, _ -> CommentOutcome.Unconfirmed }
        vm.comments.submit()
        assertEquals(listOf("key-1", "key-2"), repository.postCommentCalls.map { it.first })

        vm.comments.discardAttempt()
        assertFalse(vm.comments.state.value.composer.pendingAttempt)
        vm.comments.onTextChange("Texto corrigido")
        assertEquals("Texto corrigido", vm.comments.state.value.composer.text)
    }

    @Test
    fun `tentativa por confirmar com mais de 6 dias nao e repetida`() {
        repository.onPostComment = { _, _, _ -> CommentOutcome.Unconfirmed }
        val state = saved()
        viewModel(state, now = 0L).comments.apply { onTextChange("Texto"); submit() }
        val later = viewModel(state, now = CommentsController.MAX_KEY_AGE_MILLIS + 1)
        later.comments.submit()
        assertEquals(1, repository.postCommentCalls.size)
        assertEquals(CommentMessage.AttemptTooOld, later.comments.state.value.composer.message)
    }

    @Test
    fun `so o autor do comentario edita e elimina, e eliminar pede confirmacao`() {
        commentsPages(listOf(comment("mine", authorId = "rui-id", text = "Meu"), comment("other", authorId = "eva-id")))
        serves(comments = 2)
        val vm = viewModel()
        val (mine, other) = (vm.comments.state.value.list as CommentListState.Loaded).items
        assertTrue(vm.comments.canModify(mine))
        assertFalse(vm.comments.canModify(other))
        vm.comments.startEdit(other)
        vm.comments.requestDelete(other)
        assertNull(vm.comments.state.value.edit)
        assertNull(vm.comments.state.value.confirmDeleteId)

        vm.comments.requestDelete(mine)
        assertEquals("mine", vm.comments.state.value.confirmDeleteId)
        assertTrue("nada é apagado antes de confirmar", repository.deleteCommentCalls.isEmpty())
        vm.comments.dismissDelete()
        assertTrue(repository.deleteCommentCalls.isEmpty())

        commentsPages(listOf(comment("other", authorId = "eva-id")))
        serves(comments = 1)
        vm.comments.requestDelete(mine)
        vm.comments.confirmDelete()
        assertEquals(listOf("mine"), repository.deleteCommentCalls)
        assertEquals(listOf("other"), (vm.comments.state.value.list as CommentListState.Loaded).items.map { it.id })
        assertEquals(1L, vm.uiState.value.experience?.commentsCount)

        // Visitante: ninguém pode alterar.
        session.value = SessionState.SignedOut()
        assertFalse(vm.comments.canModify(mine))
    }

    @Test
    fun `editar comentario - falha de rede mantem o texto e sucesso atualiza o item`() {
        commentsPages(listOf(comment("mine", authorId = "rui-id", text = "Meu")))
        val vm = viewModel()
        val mine = (vm.comments.state.value.list as CommentListState.Loaded).items.single()
        vm.comments.startEdit(mine)
        vm.comments.onEditText("Meu, revisto")
        repository.onEditComment = { _, _ -> MutationResult.Failure(MutationError.NETWORK) }
        vm.comments.saveEdit()
        assertEquals(MutationError.NETWORK, vm.comments.state.value.edit?.error)
        assertEquals("Meu, revisto", vm.comments.state.value.edit?.text)

        repository.onEditComment = { id, text -> MutationResult.Success(comment(id, authorId = "rui-id", text = text)) }
        vm.comments.saveEdit()
        assertNull(vm.comments.state.value.edit)
        assertEquals("Meu, revisto", (vm.comments.state.value.list as CommentListState.Loaded).items.single().text)
        assertEquals(listOf("mine" to "Meu, revisto", "mine" to "Meu, revisto"), repository.editCommentCalls)
    }

    @Test
    fun `experiencia eliminada ao comentar`() {
        repository.onPostComment = { _, _, _ -> CommentOutcome.Rejected(CommentRejection.EXPERIENCE_GONE) }
        val vm = viewModel()
        vm.comments.onTextChange("Olá")
        vm.comments.submit()
        assertEquals(ExperienceLoad.Error(DataError.NOT_FOUND), vm.uiState.value.load)
        assertEquals("o texto não se perde", "Olá", vm.comments.state.value.composer.text)
    }
}
