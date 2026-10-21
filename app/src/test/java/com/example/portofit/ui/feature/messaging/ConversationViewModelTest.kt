package com.example.portofit.ui.feature.messaging

import androidx.lifecycle.SavedStateHandle
import com.example.portofit.data.auth.SessionState
import com.example.portofit.data.model.ChatMessage
import com.example.portofit.data.model.MessagesPage
import com.example.portofit.data.model.ShareAvailability
import com.example.portofit.data.model.SharesPage
import com.example.portofit.data.repository.DataError
import com.example.portofit.data.repository.MessagingError
import com.example.portofit.data.repository.OpenConversationResult
import com.example.portofit.data.repository.SendOutcome
import com.example.portofit.data.repository.SessionResult
import com.example.portofit.testing.FakeMessagingRepository
import com.example.portofit.testing.FakeMessagingRepository.Companion.ME
import com.example.portofit.testing.FakeMessagingRepository.Companion.OTHER
import com.example.portofit.testing.FakeMessagingRepository.Companion.message
import com.example.portofit.testing.FakeMessagingRepository.Companion.share
import com.example.portofit.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationViewModelTest {

    private val scheduler = TestCoroutineScheduler()

    @get:Rule
    val mainDispatcher = MainDispatcherRule(UnconfinedTestDispatcher(scheduler))

    private val repository = FakeMessagingRepository()
    private val session = MutableStateFlow<SessionState>(SessionState.SignedIn(ME, "ana"))
    private var keys = 0

    /** Mensagens no "servidor"; [serve] responde como o messaging-service (§6.3): sempre por seq crescente. */
    private val server = mutableListOf<ChatMessage>()

    private fun serve(call: FakeMessagingRepository.MessagesCall): SessionResult<MessagesPage> {
        val sorted = server.sortedBy { it.seq }
        val items = when {
            call.after != null -> sorted.filter { it.seq > call.after }.take(call.limit)
            call.before != null -> sorted.filter { it.seq < call.before }.takeLast(call.limit)
            else -> sorted.takeLast(call.limit)
        }
        val low = items.firstOrNull()?.seq ?: call.before ?: call.after?.plus(1) ?: Long.MAX_VALUE
        val high = items.lastOrNull()?.seq ?: call.after ?: call.before?.minus(1) ?: 0
        val unavailable = items.any { it.shared?.availability == ShareAvailability.UNKNOWN }
        return SessionResult.Success(MessagesPage(items, sorted.lastOrNull()?.seq ?: 0, sorted.any { it.seq < low }, sorted.any { it.seq > high }, unavailable))
    }

    init {
        repository.onMessages = { serve(it) }
    }

    private fun conversation(state: SavedStateHandle = SavedStateHandle(mapOf(ConversationViewModel.CONVERSATION_ID_ARG to "c1"))) =
        ConversationViewModel(repository, session, state, clock = { scheduler.currentTime }, newKey = { "key-${++keys}" })

    private fun withPerson(state: SavedStateHandle = SavedStateHandle(mapOf(ConversationViewModel.PARTICIPANT_ID_ARG to OTHER, ConversationViewModel.PARTICIPANT_NAME_ARG to "rui"))) =
        conversation(state)

    private fun advance(millis: Long) {
        scheduler.advanceTimeBy(millis)
        scheduler.runCurrent()
    }

    private fun seqs(vm: ConversationViewModel) = vm.uiState.value.messages.map { it.seq }

    private fun polls() = repository.messagesCalls.filter { it.after != null }

    // --- Sessão ---

    @Test
    fun `visitante ve o convite e nao ha pedidos`() {
        session.value = SessionState.SignedOut()
        val vm = conversation()
        vm.onResumed()
        advance(60_000)
        assertEquals(false, vm.uiState.value.signedIn)
        assertTrue(repository.messagesCalls.isEmpty() && repository.conversationCalls.isEmpty() && repository.openCalls.isEmpty())
    }

    // --- Histórico e consulta incremental ---

    @Test
    fun `ultimas mensagens e historico por seq crescente sem repetidos`() {
        server += (1L..45L).map { message(it) }
        val vm = conversation()
        assertEquals((16L..45L).toList(), seqs(vm))
        assertTrue(vm.uiState.value.hasMoreBefore)
        vm.loadOlder()
        assertEquals((1L..45L).toList(), seqs(vm))
        assertFalse(vm.uiState.value.hasMoreBefore)
        assertEquals(listOf(null, 16L), repository.messagesCalls.map { it.before })
    }

    @Test
    fun `consulta incremental drena as paginas pelo ultimo item e o envio nao avanca o cursor`() {
        server += (1L..3L).map { message(it) }
        val vm = conversation()
        vm.onResumed()
        server += (4L..120L).map { message(it) }
        advance(10_000)
        assertEquals((1L..120L).toList(), seqs(vm))
        // after=3 → 50 itens; after=53 → 50; after=103 → 17 (sem saltos: o cursor avança só até ao último recebido).
        assertEquals(listOf(3L, 3L, 53L, 103L), polls().map { it.after })

        // Uma mensagem da outra pessoa (121) chega antes da minha (122): o envio entra na lista, mas não mexe no cursor.
        server += message(121)
        repository.onSend = { call -> SendOutcome.Sent(message(122, ME, call.text, clientKey = call.key).also { server += it }, replayed = false) }
        vm.onDraftChange("Olá")
        vm.send()
        assertEquals(120L, polls().last().after)
        assertEquals((1L..122L).toList(), seqs(vm))
        assertEquals(seqs(vm).distinct(), seqs(vm))
    }

    @Test
    fun `mais de 10 paginas novas ficam para o ciclo seguinte, a partir do mesmo cursor`() {
        server += message(1)
        val vm = conversation()
        vm.onResumed()
        server += (2L..601L).map { message(it) }
        advance(10_000)
        assertEquals(501, vm.uiState.value.messages.size)
        advance(10_000)
        assertEquals((1L..601L).toList(), seqs(vm))
    }

    @Test
    fun `consulta so com o ecra ativo e sem pedidos sobrepostos`() {
        server += message(1)
        val vm = conversation()
        advance(60_000)
        assertEquals("sem o ecrã ativo, só o carregamento inicial", 1, repository.messagesCalls.size)
        vm.onResumed()
        assertEquals("ao ficar ativo, consulta logo", 1, polls().size)
        advance(10_000)
        assertEquals(2, polls().size)

        // Uma consulta lenta: o ciclo espera por ela; a consulta pedida depois de enviar fica para logo a seguir.
        val gate = CompletableDeferred<Unit>()
        repository.onMessages = { gate.await(); serve(it) }
        advance(10_000)
        vm.onDraftChange("Olá")
        vm.send()
        advance(30_000)
        assertEquals(3, polls().size)
        assertEquals(1, repository.maxInFlightMessages)
        repository.onMessages = { serve(it) }
        gate.complete(Unit)
        scheduler.runCurrent()
        assertEquals(4, polls().size)
        assertEquals(1, repository.maxInFlightMessages)

        vm.onPaused()
        advance(300_000)
        assertEquals("em pausa, nenhum pedido", 4, polls().size)
    }

    @Test
    fun `erros fazem o intervalo subir ate 60 s e voltar a 10 s`() {
        server += message(1)
        val vm = conversation()
        vm.onResumed()
        repository.onMessages = { SessionResult.Failure(DataError.NETWORK) }
        advance(10_000)
        assertEquals(2, polls().size)
        assertTrue(vm.uiState.value.refreshFailed)
        assertEquals("as mensagens mantêm-se", listOf(1L), seqs(vm))
        advance(19_000)
        assertEquals(2, polls().size)
        advance(1_000)
        assertEquals("20 s", 3, polls().size)
        advance(40_000)
        assertEquals("40 s", 4, polls().size)
        advance(60_000)
        assertEquals("60 s", 5, polls().size)
        advance(59_000)
        assertEquals("nunca mais de 60 s", 5, polls().size)
        repository.onMessages = { serve(it) }
        advance(1_000)
        assertEquals(6, polls().size)
        assertFalse(vm.uiState.value.refreshFailed)
        advance(10_000)
        assertEquals("de volta aos 10 s", 7, polls().size)
    }

    // --- Leitura ---

    @Test
    fun `marca como lidas so as recebidas mostradas e so com a conversa ativa`() {
        server += listOf(message(1), message(2), message(3, ME), message(4), message(5, ME))
        val vm = conversation()
        vm.onMessagesShown(5)
        assertTrue("sem a conversa ativa nada é marcado", repository.readCalls.isEmpty())
        vm.onResumed()
        // A ler mais acima: só até à que está no ecrã.
        vm.onMessagesShown(2)
        assertEquals(listOf(2L), repository.readCalls)
        vm.onMessagesShown(2)
        vm.onMessagesShown(3)
        assertEquals("as minhas não contam", listOf(2L), repository.readCalls)
        vm.onMessagesShown(5)
        assertEquals(listOf(2L, 4L), repository.readCalls)
        assertEquals(4L, vm.uiState.value.lastReadSeq)
        // O servidor ficou com um valor maior (outra instância da app): nunca recua.
        repository.onRead = { SessionResult.Success(com.example.portofit.data.model.ReadState(9, 0)) }
        server += message(6)
        advance(10_000)
        vm.onMessagesShown(6)
        assertEquals(9L, vm.uiState.value.lastReadSeq)
    }

    // --- Envio ---

    @Test
    fun `envio com chave guardada antes do pedido, sem repeticao automatica e confirmado pelo clientKey`() {
        server += message(1)
        val state = SavedStateHandle(mapOf(ConversationViewModel.CONVERSATION_ID_ARG to "c1"))
        val vm = conversation(state)
        vm.onResumed()
        var keyBeforeRequest: String? = null
        repository.onSend = { keyBeforeRequest = state.get<String>("conversation.$ME.pendingKey"); SendOutcome.Unconfirmed }
        vm.onDraftChange("  Vamos treinar?  ")
        vm.send()
        assertEquals("key-1", keyBeforeRequest)
        assertEquals(SendState.Unconfirmed, vm.uiState.value.send)
        assertTrue(vm.uiState.value.composerLocked)
        vm.onDraftChange("outro texto")
        assertEquals("  Vamos treinar?  ", vm.uiState.value.draft)

        // Sem repetição automática, mesmo com o tempo a passar.
        advance(120_000)
        assertEquals(1, repository.sendCalls.size)

        // Recriação: a tentativa e o texto mantêm-se.
        val recreated = conversation(state)
        assertEquals(SendState.Unconfirmed, recreated.uiState.value.send)
        assertEquals("  Vamos treinar?  ", recreated.uiState.value.draft)

        // "Tentar novamente": mesma chave e mesmo texto normalizado.
        vm.send()
        assertEquals(listOf("key-1", "key-1"), repository.sendCalls.map { it.key })
        assertEquals(listOf("Vamos treinar?", "Vamos treinar?"), repository.sendCalls.map { it.text })

        // A consulta devolve a mensagem com o mesmo clientKey: confirmada sem novo envio.
        server += message(2, ME, "Vamos treinar?", clientKey = "key-1")
        advance(60_000)
        assertEquals(SendState.Idle, vm.uiState.value.send)
        assertEquals("", vm.uiState.value.draft)
        assertNull(state.get<String>("conversation.$ME.pendingKey"))
        assertNull(state.get<String>("conversation.$ME.draft"))
        assertEquals(2, repository.sendCalls.size)
        assertEquals(listOf(1L, 2L), seqs(vm))
    }

    @Test
    fun `recusa mantem o texto e descarta a chave, validacao sem pedidos`() {
        server += message(1)
        val vm = conversation()
        for (bad in listOf("", "   ", "a".repeat(1001), "com\u0007controlo")) {
            vm.onDraftChange(bad)
            vm.send()
            assertTrue(vm.uiState.value.showValidation)
        }
        assertTrue(repository.sendCalls.isEmpty())
        repository.onSend = { SendOutcome.Rejected(MessagingError.TOO_MANY_REQUESTS, 120) }
        vm.onDraftChange("Olá")
        vm.send()
        assertEquals(SendState.Failed(MessagingError.TOO_MANY_REQUESTS, 120), vm.uiState.value.send)
        assertEquals("Olá", vm.uiState.value.draft)
        repository.onSend = { call -> SendOutcome.Sent(message(2, ME, call.text, clientKey = call.key), replayed = false) }
        vm.send()
        assertEquals(listOf("key-1", "key-2"), repository.sendCalls.map { it.key })
        assertEquals(SendState.Idle, vm.uiState.value.send)
        // Descartar uma tentativa por confirmar desbloqueia o texto.
        repository.onSend = { SendOutcome.Unconfirmed }
        vm.onDraftChange("Outra")
        vm.send()
        vm.discardAttempt()
        assertFalse(vm.uiState.value.composerLocked)
        vm.send()
        assertEquals("key-4", repository.sendCalls.last().key)
    }

    // --- Privacidade entre contas ---

    @Test
    fun `terminar sessao ou trocar de conta apaga tudo, para a consulta e ignora respostas antigas`() {
        server += (1L..3L).map { message(it) }
        val state = SavedStateHandle(mapOf(ConversationViewModel.CONVERSATION_ID_ARG to "c1"))
        val vm = conversation(state)
        vm.onResumed()
        vm.onDraftChange("Rascunho da Ana")
        val gate = CompletableDeferred<Unit>()
        repository.onMessages = { gate.await(); serve(it) }
        server += message(4)
        advance(10_000)

        session.value = SessionState.SignedOut()
        assertEquals(false, vm.uiState.value.signedIn)
        assertTrue(vm.uiState.value.messages.isEmpty())
        assertEquals("", vm.uiState.value.draft)
        assertNull(state.get<String>("conversation.$ME.draft"))
        gate.complete(Unit)
        scheduler.runCurrent()
        assertTrue("resposta tardia ignorada", vm.uiState.value.messages.isEmpty())
        val calls = repository.messagesCalls.size
        advance(120_000)
        assertEquals("sem consulta depois de terminar sessão", calls, repository.messagesCalls.size)

        session.value = SessionState.SignedIn("bea-id", "bea")
        assertEquals("", vm.uiState.value.draft)
        assertEquals(SendState.Idle, vm.uiState.value.send)
        assertEquals((1L..4L).toList(), seqs(vm))
    }

    // --- Abrir a partir do perfil ---

    @Test
    fun `abrir com uma pessoa so com um toque explicito e falha do users-service com nova tentativa`() {
        repository.onOpen = { OpenConversationResult.Failed(MessagingError.USERS_UNAVAILABLE) }
        val vm = withPerson()
        assertEquals(listOf(OTHER), repository.openCalls)
        assertEquals(OpenState.Failed(MessagingError.USERS_UNAVAILABLE), vm.uiState.value.open)
        assertNull(vm.uiState.value.conversationId)
        repository.onOpen = { OpenConversationResult.Opened(FakeMessagingRepository.conversationOf("c-rui")) }
        vm.openConversation()
        assertEquals("c-rui", vm.uiState.value.conversationId)
        assertEquals("c-rui", repository.messagesCalls.single().conversationId)

        // Visitante: nada é criado; depois de entrar é preciso um novo toque.
        session.value = SessionState.SignedOut()
        val visitor = withPerson()
        session.value = SessionState.SignedIn(ME, "ana")
        assertEquals(OpenState.NeedsTap, visitor.uiState.value.open)
        assertEquals(2, repository.openCalls.size)
    }

    @Test
    fun `conversa inexistente ou de outra pessoa`() {
        repository.onMessages = { SessionResult.Failure(DataError.NOT_FOUND) }
        val vm = conversation()
        assertEquals(ConversationLoad.Gone, vm.uiState.value.load)
        assertFalse(vm.uiState.value.canSend)
    }

    // --- Partilhas ---

    @Test
    fun `partilhas indisponiveis nao bloqueiam mensagens nem o cursor e atualizam-se a parte`() {
        server += listOf(message(1, shared = share("e1", ShareAvailability.UNKNOWN)), message(2, shared = share("e2", ShareAvailability.DELETED)))
        val vm = conversation()
        vm.onResumed()
        assertTrue(vm.uiState.value.hasUnknownShares)
        assertNull("eliminada sem conteúdo", vm.uiState.value.messages[1].shared?.experience)

        // Mensagens novas continuam a chegar com as partilhas indisponíveis.
        server += message(3, text = "Só texto")
        advance(10_000)
        assertEquals(listOf(1L, 2L, 3L), seqs(vm))
        assertEquals("o cursor estava na última recebida", 2L, polls().last().after)

        // "Atualizar" pede só as partilhas indisponíveis, sem mexer nas mensagens.
        repository.onShares = { SessionResult.Success(SharesPage(mapOf(1L to share("e1", ShareAvailability.AVAILABLE)), false)) }
        val pollsBefore = polls().size
        vm.refreshShares()
        assertEquals(listOf(listOf(1L)), repository.sharesCalls)
        assertEquals(ShareAvailability.AVAILABLE, vm.uiState.value.messages[0].shared?.availability)
        assertEquals(pollsBefore, polls().size)
        assertFalse(vm.uiState.value.hasUnknownShares)

        // Atualização automática: no máximo de 60 em 60 s, com a conversa visível e partilhas indisponíveis.
        server += message(4, shared = share("e4", ShareAvailability.UNKNOWN))
        repository.onShares = { SessionResult.Failure(DataError.NETWORK) }
        advance(10_000)
        assertEquals(1, repository.sharesCalls.size)
        advance(60_000)
        assertEquals(listOf(4L), repository.sharesCalls.last())
        assertEquals(ShareAvailability.UNKNOWN, vm.uiState.value.messages.last().shared?.availability)
    }
}
