package com.example.portofit.ui.feature.messaging

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.portofit.PortoFitApplication
import com.example.portofit.data.auth.SessionState
import com.example.portofit.data.model.ChatMessage
import com.example.portofit.data.model.MessageRules
import com.example.portofit.data.model.Participant
import com.example.portofit.data.model.ShareAvailability
import com.example.portofit.data.repository.DataError
import com.example.portofit.data.repository.MessagingError
import com.example.portofit.data.repository.MessagingRepository
import com.example.portofit.data.repository.OpenConversationResult
import com.example.portofit.data.repository.SendOutcome
import com.example.portofit.data.repository.SessionResult
import com.example.portofit.ui.feature.collections.VISITOR
import com.example.portofit.ui.feature.collections.sessionKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

sealed interface ConversationLoad {
    data object Loading : ConversationLoad
    data object Loaded : ConversationLoad
    data class Error(val error: DataError) : ConversationLoad
    data object SessionEnded : ConversationLoad
    data object SessionUnverified : ConversationLoad

    /** A conversa não existe ou não é desta conta (o servidor responde igual nos dois casos). */
    data object Gone : ConversationLoad
}

/** Abrir a conversa com uma pessoa (a partir do perfil): só com um toque explícito, nunca depois de entrar sozinho. */
sealed interface OpenState {
    data object None : OpenState
    data object NeedsTap : OpenState
    data object Opening : OpenState
    data class Failed(val error: MessagingError) : OpenState
}

sealed interface SendState {
    data object Idle : SendState
    data object Sending : SendState

    /** Resultado desconhecido: texto e chave bloqueados; só "Tentar novamente" (mesma chave) ou descartar. */
    data object Unconfirmed : SendState
    data class Failed(val error: MessagingError, val retryAfterSeconds: Long? = null) : SendState

    /** A chave já tinha enviado outra mensagem (só com outra versão da app). */
    data object AlreadySent : SendState
}

data class ConversationUiState(
    /** null enquanto a sessão é lida; false = visitante (convite para entrar). */
    val signedIn: Boolean? = null,
    val myUserId: String? = null,
    val other: Participant? = null,
    val conversationId: String? = null,
    val open: OpenState = OpenState.None,
    val load: ConversationLoad = ConversationLoad.Loading,
    /** Sempre por `seq` crescente, sem repetidos. */
    val messages: List<ChatMessage> = emptyList(),
    val hasMoreBefore: Boolean = false,
    val loadingOlder: Boolean = false,
    val olderError: DataError? = null,
    /** A última consulta periódica falhou (aviso discreto; as mensagens mantêm-se). */
    val refreshFailed: Boolean = false,
    val refreshingShares: Boolean = false,
    val lastReadSeq: Long = 0,
    val draft: String = "",
    val showValidation: Boolean = false,
    val send: SendState = SendState.Idle,
) {
    val composerLocked: Boolean get() = send == SendState.Sending || send == SendState.Unconfirmed
    val canSend: Boolean get() = conversationId != null && load == ConversationLoad.Loaded
    val draftValid: Boolean get() = MessageRules.cleanText(draft) != null
    val draftLength: Int get() = MessageRules.length(draft)
    val hasUnknownShares: Boolean get() = messages.any { it.shared?.availability == ShareAvailability.UNKNOWN }
}

/**
 * Uma conversa (docs/messaging-api.md §6–§7):
 * - **histórico** paginado para trás (`before`) e **mensagens novas** pela consulta incremental (`after`). O cursor avança
 *   só até à última mensagem recebida nessa consulta, e todas as páginas novas são drenadas (até 10 por ciclo). Uma
 *   mensagem enviada entra na lista, mas não mexe no cursor;
 * - **consulta periódica** de 10 s, só enquanto o ecrã estiver RESUMED ([onResumed]/[onPaused]), até 60 s com erros;
 * - **leitura:** [onMessagesShown] marca como lidas só as recebidas que o ecrã mostrou, com a conversa ativa;
 * - **envio:** uma `Idempotency-Key` por tentativa lógica, guardada com o texto no SavedStateHandle **antes** do pedido.
 *   Resultado desconhecido → texto bloqueado, sem repetição automática. A tentativa fica confirmada quando uma consulta
 *   devolve uma mensagem com o mesmo `clientKey`, ou com "Tentar novamente" (mesma chave);
 * - **partilhas** indisponíveis atualizadas à parte (`GET …/shares`), sem afetar as mensagens nem o cursor.
 *
 * O rascunho e a tentativa ficam no SavedStateHandle por conta (só o necessário para a recriação). Ao terminar sessão ou
 * trocar de conta, a consulta para, as mensagens e o rascunho da conta anterior são apagados e as respostas tardias
 * ignoradas.
 */
class ConversationViewModel(
    private val repository: MessagingRepository,
    session: StateFlow<SessionState>,
    private val savedState: SavedStateHandle,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {

    private val argConversationId: String? = savedState.get<String>(CONVERSATION_ID_ARG)?.takeIf { it.isNotBlank() }
    private val participantId: String? = savedState.get<String>(PARTICIPANT_ID_ARG)?.takeIf { it.isNotBlank() }
    private val participantName: String? = savedState.get<String>(PARTICIPANT_NAME_ARG)?.takeIf { it.isNotBlank() }

    private val _uiState = MutableStateFlow(ConversationUiState())
    val uiState: StateFlow<ConversationUiState> = _uiState.asStateFlow()

    private var account: String? = null
    private var generation = 0
    private var firstSession = true
    private var resumed = false
    private var job: Job? = null

    /** Último `seq` recebido pela consulta incremental (ou pela página inicial). */
    private var cursor = 0L
    private var lastShareRefresh = 0L

    private var readJob: Job? = null
    private var wantedRead = 0L

    private val poller = Poller(viewModelScope, Poller.CONVERSATION_MILLIS) { pollNew() }

    init {
        viewModelScope.launch {
            session.map { sessionKey(it) }.filterNotNull().distinctUntilChanged().collect(::onAccount)
        }
    }

    // --- Ciclo de vida do ecrã ---

    fun onResumed() {
        resumed = true
        if (_uiState.value.load == ConversationLoad.Loaded) poller.start()
    }

    fun onPaused() {
        resumed = false
        poller.stop()
    }

    // --- Abrir e carregar ---

    /** "Iniciar conversa" (perfil, depois de entrar) ou "Tentar novamente" depois de uma falha a abrir. */
    fun openConversation() {
        val participant = participantId ?: return
        val requestAccount = account ?: return
        if (_uiState.value.open == OpenState.Opening || _uiState.value.conversationId != null) return
        val myGeneration = generation
        _uiState.update { it.copy(open = OpenState.Opening) }
        job = viewModelScope.launch {
            val result = repository.open(participant)
            if (!current(myGeneration, requestAccount)) return@launch
            when (result) {
                is OpenConversationResult.Opened -> {
                    savedState[key(requestAccount, KEY_RESOLVED)] = result.conversation.id
                    _uiState.update {
                        it.copy(
                            open = OpenState.None,
                            conversationId = result.conversation.id,
                            other = result.conversation.other,
                            lastReadSeq = result.conversation.lastReadSeq,
                        )
                    }
                    loadMessages(result.conversation.id, requestAccount, myGeneration)
                }
                is OpenConversationResult.Failed -> _uiState.update { it.copy(open = OpenState.Failed(result.error)) }
            }
        }
    }

    /** "Tentar novamente" do carregamento inicial. */
    fun retry() {
        val id = _uiState.value.conversationId ?: return openConversation()
        val requestAccount = account ?: return
        poller.stop()
        job?.cancel()
        val myGeneration = generation
        loadHeader(id, requestAccount, myGeneration)
        loadMessages(id, requestAccount, myGeneration)
    }

    fun loadOlder() {
        val state = _uiState.value
        val id = state.conversationId ?: return
        val oldest = state.messages.firstOrNull()?.seq ?: return
        if (!state.hasMoreBefore || state.loadingOlder) return
        val requestAccount = account ?: return
        val myGeneration = generation
        _uiState.update { it.copy(loadingOlder = true, olderError = null) }
        viewModelScope.launch {
            val result = repository.messages(id, oldest, null, PAGE_SIZE)
            if (!current(myGeneration, requestAccount)) return@launch
            when (result) {
                is SessionResult.Success -> {
                    merge(result.value.items)
                    _uiState.update { it.copy(loadingOlder = false, hasMoreBefore = result.value.hasMoreBefore) }
                }
                is SessionResult.Failure -> _uiState.update { it.copy(loadingOlder = false, olderError = result.error) }
                SessionResult.SessionEnded -> _uiState.update { it.copy(loadingOlder = false, load = ConversationLoad.SessionEnded) }
                SessionResult.SessionUnverified -> _uiState.update { it.copy(loadingOlder = false, olderError = DataError.NETWORK) }
            }
        }
    }

    // --- Leitura ---

    /**
     * O ecrã mostrou as mensagens até [maxVisibleSeq] (inclusive). Só com a conversa ativa, e só se houver mensagens
     * recebidas mostradas e ainda por ler: quem está a ler o histórico antigo não marca as novas.
     */
    fun onMessagesShown(maxVisibleSeq: Long) {
        if (!resumed) return
        val state = _uiState.value
        val id = state.conversationId ?: return
        val me = account ?: return
        val target = state.messages.filter { it.senderId != me && it.seq <= maxVisibleSeq && it.seq > state.lastReadSeq }.maxOfOrNull { it.seq } ?: return
        if (target <= wantedRead && readJob?.isActive == true) return
        wantedRead = maxOf(wantedRead, target)
        if (readJob?.isActive == true) return
        sendRead(id, me, generation)
    }

    private fun sendRead(id: String, requestAccount: String, myGeneration: Int) {
        val target = wantedRead
        readJob = viewModelScope.launch {
            val result = repository.markRead(id, target)
            if (!current(myGeneration, requestAccount)) return@launch
            if (result is SessionResult.Success) {
                // O servidor fica com o maior (várias instâncias da app): nunca recua.
                _uiState.update { it.copy(lastReadSeq = maxOf(it.lastReadSeq, result.value.lastReadSeq)) }
            }
            if (wantedRead > target && result is SessionResult.Success) sendRead(id, requestAccount, myGeneration)
        }
    }

    // --- Rascunho e envio ---

    fun onDraftChange(text: String) {
        val requestAccount = account ?: return
        if (_uiState.value.composerLocked) return
        savedState[key(requestAccount, KEY_DRAFT)] = text
        _uiState.update { it.copy(draft = text, showValidation = false, send = if (it.send is SendState.Failed || it.send == SendState.AlreadySent) SendState.Idle else it.send) }
    }

    /** Enviar, ou "Tentar novamente" de uma tentativa por confirmar (mesma chave e mesmo texto). */
    fun send() {
        val state = _uiState.value
        if (state.send == SendState.Sending || !state.canSend) return
        val requestAccount = account ?: return
        val pendingKey = savedState.get<String>(key(requestAccount, KEY_PENDING))
        if (pendingKey != null) {
            dispatch(pendingKey, savedState.get<String>(key(requestAccount, KEY_PENDING_TEXT)).orEmpty(), requestAccount)
            return
        }
        val text = MessageRules.cleanText(state.draft)
        if (text == null) {
            _uiState.update { it.copy(showValidation = true) }
            return
        }
        val attempt = newKey()
        // A tentativa fica guardada antes do pedido: sobrevive à rotação e à recriação do processo.
        savedState[key(requestAccount, KEY_PENDING)] = attempt
        savedState[key(requestAccount, KEY_PENDING_TEXT)] = text
        dispatch(attempt, text, requestAccount)
    }

    /** Descartar uma tentativa por confirmar (o ecrã avisa que pode já ter sido enviada): o texto volta a poder ser editado. */
    fun discardAttempt() {
        val requestAccount = account ?: return
        if (_uiState.value.send == SendState.Sending) return
        clearPending(requestAccount)
        _uiState.update { it.copy(send = SendState.Idle) }
    }

    private fun dispatch(attempt: String, text: String, requestAccount: String) {
        val id = _uiState.value.conversationId ?: return
        val myGeneration = generation
        _uiState.update { it.copy(send = SendState.Sending, showValidation = false) }
        viewModelScope.launch {
            val outcome = repository.send(id, attempt, text, null)
            if (!current(myGeneration, requestAccount)) return@launch
            // Uma consulta pode já ter confirmado esta tentativa (mesmo clientKey) enquanto o pedido decorria.
            val stillPending = savedState.get<String>(key(requestAccount, KEY_PENDING)) == attempt
            if (!stillPending && outcome !is SendOutcome.Sent) {
                _uiState.update { it.copy(send = SendState.Idle) }
                return@launch
            }
            when (outcome) {
                is SendOutcome.Sent -> {
                    confirmSent(requestAccount)
                    _uiState.update { it.copy(send = SendState.Idle) }
                    merge(listOf(outcome.message))
                    poller.pollNow()
                }
                SendOutcome.Unconfirmed -> _uiState.update { it.copy(send = SendState.Unconfirmed) }
                is SendOutcome.Rejected -> {
                    // Nada foi enviado: a chave é descartada e o texto mantém-se para corrigir ou repetir.
                    clearPending(requestAccount)
                    _uiState.update {
                        it.copy(
                            send = SendState.Failed(outcome.error, outcome.retryAfterSeconds),
                            load = if (outcome.error == MessagingError.CONVERSATION_GONE) ConversationLoad.Gone else it.load,
                        )
                    }
                }
                is SendOutcome.AlreadySent -> {
                    clearPending(requestAccount)
                    _uiState.update { it.copy(send = SendState.AlreadySent) }
                    poller.pollNow()
                }
            }
        }
    }

    // --- Partilhas ---

    /** Atualiza as partilhas temporariamente indisponíveis, sem voltar a pedir mensagens nem mexer no cursor (§7.3). */
    fun refreshShares() {
        val requestAccount = account ?: return
        if (_uiState.value.refreshingShares) return
        viewModelScope.launch { updateShares(requestAccount, generation) }
    }

    private suspend fun updateShares(requestAccount: String, myGeneration: Int) {
        val state = _uiState.value
        val id = state.conversationId ?: return
        val seqs = state.messages.filter { it.shared?.availability == ShareAvailability.UNKNOWN }.map { it.seq }.takeLast(SHARES_LIMIT)
        if (seqs.isEmpty()) return
        lastShareRefresh = clock()
        _uiState.update { it.copy(refreshingShares = true) }
        val result = repository.shares(id, seqs)
        if (!current(myGeneration, requestAccount)) return
        _uiState.update { now ->
            val updated = (result as? SessionResult.Success)?.value?.items.orEmpty()
            now.copy(
                refreshingShares = false,
                messages = now.messages.map { message -> updated[message.seq]?.let { message.copy(shared = it) } ?: message },
            )
        }
    }

    // --- Interno ---

    private fun onAccount(key: String) {
        val previous = account
        account = key.takeUnless { it == VISITOR }
        generation++
        job?.cancel()
        readJob?.cancel()
        wantedRead = 0
        poller.stop()
        poller.reset()
        cursor = 0
        // O rascunho e a tentativa pertenciam à conta anterior: não ficam para a seguinte.
        if (previous != null && previous != account) clearSaved(previous)
        val requestAccount = account
        _uiState.value = ConversationUiState(
            signedIn = requestAccount != null,
            myUserId = requestAccount,
            other = participantId?.let { Participant(it, participantName.orEmpty()) },
            draft = requestAccount?.let { savedState.get<String>(key(it, KEY_DRAFT)) }.orEmpty(),
            send = if (requestAccount != null && savedState.get<String>(key(requestAccount, KEY_PENDING)) != null) SendState.Unconfirmed else SendState.Idle,
        )
        val first = firstSession
        firstSession = false
        if (requestAccount == null) return
        val id = argConversationId ?: savedState.get<String>(key(requestAccount, KEY_RESOLVED))
        when {
            id != null -> {
                _uiState.update { it.copy(conversationId = id) }
                val myGeneration = generation
                loadHeader(id, requestAccount, myGeneration)
                loadMessages(id, requestAccount, myGeneration)
            }
            // Aberta a partir do perfil com sessão: o toque em "Enviar mensagem" foi explícito.
            first -> openConversation()
            // Depois de entrar (ou de trocar de conta): é preciso um novo toque.
            else -> _uiState.update { it.copy(open = OpenState.NeedsTap) }
        }
    }

    private fun loadHeader(id: String, requestAccount: String, myGeneration: Int) {
        viewModelScope.launch {
            val result = repository.conversation(id)
            if (!current(myGeneration, requestAccount)) return@launch
            if (result is SessionResult.Success) {
                _uiState.update { it.copy(other = result.value.other, lastReadSeq = maxOf(it.lastReadSeq, result.value.lastReadSeq)) }
            }
        }
    }

    private fun loadMessages(id: String, requestAccount: String, myGeneration: Int) {
        _uiState.update { it.copy(load = ConversationLoad.Loading, refreshFailed = false) }
        job = viewModelScope.launch {
            val result = repository.messages(id, null, null, PAGE_SIZE)
            if (!current(myGeneration, requestAccount)) return@launch
            when (result) {
                is SessionResult.Success -> {
                    cursor = result.value.items.lastOrNull()?.seq ?: result.value.lastSeq
                    lastShareRefresh = clock()
                    _uiState.update { it.copy(load = ConversationLoad.Loaded, messages = emptyList(), hasMoreBefore = result.value.hasMoreBefore) }
                    merge(result.value.items)
                    if (resumed) poller.start(immediate = false)
                }
                is SessionResult.Failure -> _uiState.update {
                    it.copy(load = if (result.error == DataError.NOT_FOUND) ConversationLoad.Gone else ConversationLoad.Error(result.error))
                }
                SessionResult.SessionEnded -> _uiState.update { it.copy(load = ConversationLoad.SessionEnded) }
                SessionResult.SessionUnverified -> _uiState.update { it.copy(load = ConversationLoad.SessionUnverified) }
            }
        }
    }

    /**
     * Consulta incremental: `after=cursor` até não haver mais páginas (no máximo [MAX_PAGES_PER_CYCLE] seguidas; o resto
     * fica para o ciclo seguinte, a partir do mesmo cursor). Devolve false numa falha (o intervalo sobe).
     */
    private suspend fun pollNew(): Boolean {
        val requestAccount = account ?: return true
        val id = _uiState.value.conversationId ?: return true
        if (_uiState.value.load != ConversationLoad.Loaded) return true
        val myGeneration = generation
        var pages = 0
        while (true) {
            val result = repository.messages(id, null, cursor, POLL_LIMIT)
            if (!current(myGeneration, requestAccount)) return true
            when (result) {
                is SessionResult.Success -> {
                    merge(result.value.items)
                    // Só até à última recebida: nunca até lastSeq, que saltaria as do meio.
                    result.value.items.lastOrNull()?.let { cursor = maxOf(cursor, it.seq) }
                    pages++
                    if (!result.value.hasMoreAfter || result.value.items.isEmpty() || pages >= MAX_PAGES_PER_CYCLE) break
                }
                SessionResult.SessionEnded -> {
                    poller.stop()
                    _uiState.update { it.copy(load = ConversationLoad.SessionEnded) }
                    return true
                }
                else -> {
                    _uiState.update { it.copy(refreshFailed = true) }
                    return false
                }
            }
        }
        _uiState.update { it.copy(refreshFailed = false) }
        // Partilhas indisponíveis: no máximo de 60 em 60 s, enquanto a conversa estiver visível.
        if (_uiState.value.hasUnknownShares && clock() - lastShareRefresh >= SHARES_REFRESH_MILLIS) updateShares(requestAccount, myGeneration)
        return true
    }

    /** Junta por `seq` (sem repetidos, por ordem) e confirma a tentativa pendente quando aparece o seu `clientKey`. */
    private fun merge(items: List<ChatMessage>) {
        if (items.isEmpty()) return
        val requestAccount = account ?: return
        _uiState.update { state ->
            val bySeq = state.messages.associateBy { it.seq }.toMutableMap()
            items.forEach { bySeq[it.seq] = it }
            state.copy(messages = bySeq.values.sortedBy { it.seq })
        }
        val pending = savedState.get<String>(key(requestAccount, KEY_PENDING)) ?: return
        if (items.any { it.clientKey == pending && it.senderId == requestAccount }) confirmSent(requestAccount)
    }

    private fun confirmSent(requestAccount: String) {
        clearPending(requestAccount)
        savedState.remove<String>(key(requestAccount, KEY_DRAFT))
        _uiState.update { it.copy(send = if (it.send == SendState.Sending) SendState.Sending else SendState.Idle, draft = "") }
    }

    private fun clearPending(requestAccount: String) {
        savedState.remove<String>(key(requestAccount, KEY_PENDING))
        savedState.remove<String>(key(requestAccount, KEY_PENDING_TEXT))
    }

    private fun clearSaved(previous: String) {
        clearPending(previous)
        savedState.remove<String>(key(previous, KEY_DRAFT))
        savedState.remove<String>(key(previous, KEY_RESOLVED))
    }

    private fun current(myGeneration: Int, requestAccount: String) = myGeneration == generation && requestAccount == account

    private fun key(account: String, name: String) = "conversation.$account.$name"

    companion object {
        const val CONVERSATION_ID_ARG = "conversationId"
        const val PARTICIPANT_ID_ARG = "participantId"
        const val PARTICIPANT_NAME_ARG = "participantName"

        const val PAGE_SIZE = 30
        const val POLL_LIMIT = 50
        const val MAX_PAGES_PER_CYCLE = 10
        const val SHARES_LIMIT = 50
        const val SHARES_REFRESH_MILLIS = 60_000L

        private const val KEY_DRAFT = "draft"
        private const val KEY_PENDING = "pendingKey"
        private const val KEY_PENDING_TEXT = "pendingText"
        private const val KEY_RESOLVED = "conversationId"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PortoFitApplication
                ConversationViewModel(app.container.messagingRepository, app.container.sessionManager.state, createSavedStateHandle())
            }
        }
    }
}
