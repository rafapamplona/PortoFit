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
import com.example.portofit.data.model.Conversation
import com.example.portofit.data.model.Experience
import com.example.portofit.data.model.MessageRules
import com.example.portofit.data.repository.DataError
import com.example.portofit.data.repository.DataResult
import com.example.portofit.data.repository.ExperiencesRepository
import com.example.portofit.data.repository.MessagingError
import com.example.portofit.data.repository.MessagingRepository
import com.example.portofit.data.repository.OpenConversationResult
import com.example.portofit.data.repository.PeopleRepository
import com.example.portofit.data.repository.SendOutcome
import com.example.portofit.data.repository.SessionResult
import com.example.portofit.data.repository.UserSummary
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

/** Destinatário escolhido: uma conversa existente, ou uma pessoa (a conversa é aberta só ao enviar). */
sealed interface ShareTarget {
    val username: String

    data class ToConversation(val conversationId: String, override val username: String) : ShareTarget
    data class ToPerson(val userId: String, override val username: String) : ShareTarget
}

sealed interface PreviewState {
    data object Loading : PreviewState
    data class Loaded(val experience: Experience) : PreviewState

    /** A experiência foi eliminada ou não existe: não pode ser partilhada. */
    data object Gone : PreviewState

    /** Não foi possível obter a pré-visualização agora (partilhar continua possível: o servidor valida). */
    data class Error(val error: DataError) : PreviewState
}

sealed interface RecentState {
    data object Loading : RecentState
    data class Loaded(val items: List<Conversation>) : RecentState
    data class Error(val error: DataError) : RecentState
}

sealed interface PeopleSearchState {
    data object Idle : PeopleSearchState
    data object Searching : PeopleSearchState
    data class Results(val items: List<UserSummary>) : PeopleSearchState
    data class Error(val error: DataError) : PeopleSearchState
}

sealed interface ShareSendState {
    data object Idle : ShareSendState
    data object Sending : ShareSendState

    /** Resultado desconhecido: destinatário, texto e chave bloqueados; só "Tentar novamente" (mesma chave) ou descartar. */
    data object Unconfirmed : ShareSendState
    data class Failed(val error: MessagingError, val retryAfterSeconds: Long? = null) : ShareSendState
    data class Sent(val conversationId: String) : ShareSendState
}

data class ShareUiState(
    val signedIn: Boolean? = null,
    val preview: PreviewState = PreviewState.Loading,
    val recent: RecentState = RecentState.Loading,
    val query: String = "",
    val people: PeopleSearchState = PeopleSearchState.Idle,
    val target: ShareTarget? = null,
    val text: String = "",
    val send: ShareSendState = ShareSendState.Idle,
) {
    val locked: Boolean get() = send == ShareSendState.Sending || send == ShareSendState.Unconfirmed || send is ShareSendState.Sent
    val textValid: Boolean get() = MessageRules.isValidOptional(text)
    val textLength: Int get() = MessageRules.length(text)
    val experienceGone: Boolean get() = preview == PreviewState.Gone || (send as? ShareSendState.Failed)?.error == MessagingError.EXPERIENCE_GONE
}

/**
 * "Partilhar" a partir do detalhe de uma experiência (docs/messaging-api.md §7): escolher uma conversa recente ou procurar
 * uma pessoa, escrever um texto opcional e confirmar com "Enviar". Nada é enviado sozinho, nem depois de entrar.
 *
 * O envio usa uma `Idempotency-Key` por tentativa lógica, guardada com o destinatário e o texto no SavedStateHandle antes
 * do pedido (por conta). Uma pessoa sem conversa é aberta primeiro (idempotente); se o users-service falhar, o texto e o
 * destinatário mantêm-se. Ao trocar de conta ou terminar sessão, tudo é apagado e as respostas tardias ignoradas.
 */
class ShareExperienceViewModel(
    private val messaging: MessagingRepository,
    private val people: PeopleRepository,
    private val experiences: ExperiencesRepository,
    session: StateFlow<SessionState>,
    private val savedState: SavedStateHandle,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {

    private val experienceId: String = checkNotNull(savedState[EXPERIENCE_ID_ARG])

    private val _uiState = MutableStateFlow(ShareUiState())
    val uiState: StateFlow<ShareUiState> = _uiState.asStateFlow()

    private var account: String? = null
    private var generation = 0
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            session.map { sessionKey(it) }.filterNotNull().distinctUntilChanged().collect { key ->
                val previous = account
                account = key.takeUnless { it == VISITOR }
                generation++
                searchJob?.cancel()
                if (previous != null && previous != account) clearSaved(previous)
                val requestAccount = account
                _uiState.value = ShareUiState(signedIn = requestAccount != null).let { base ->
                    if (requestAccount == null) base
                    else base.copy(
                        target = restoreTarget(requestAccount),
                        text = savedState.get<String>(key(requestAccount, KEY_TEXT)).orEmpty(),
                        send = if (savedState.get<String>(key(requestAccount, KEY_PENDING)) != null) ShareSendState.Unconfirmed else ShareSendState.Idle,
                    )
                }
                if (requestAccount != null) {
                    loadPreview(requestAccount, generation)
                    loadRecent(requestAccount, generation)
                }
            }
        }
    }

    fun retryRecent() {
        account?.let { loadRecent(it, generation) }
    }

    fun onQueryChange(query: String) = _uiState.update { it.copy(query = query) }

    /** Procurar uma pessoa (pesquisa pública do users-service), com 2 ou mais caracteres. */
    fun search() {
        val requestAccount = account ?: return
        val query = _uiState.value.query.trim()
        if (query.length < MIN_QUERY) return
        searchJob?.cancel()
        val myGeneration = generation
        _uiState.update { it.copy(people = PeopleSearchState.Searching) }
        searchJob = viewModelScope.launch {
            val result = people.search(query, 0, SEARCH_SIZE)
            if (!current(myGeneration, requestAccount)) return@launch
            _uiState.update {
                it.copy(
                    people = when (result) {
                        // Partilhar consigo próprio não é possível: o próprio não aparece.
                        is DataResult.Success -> PeopleSearchState.Results(result.value.items.filter { person -> person.userId != requestAccount })
                        is DataResult.Failure -> PeopleSearchState.Error(result.error)
                    },
                )
            }
        }
    }

    fun chooseConversation(conversation: Conversation) = choose(ShareTarget.ToConversation(conversation.id, conversation.other.username))

    fun choosePerson(person: UserSummary) = choose(ShareTarget.ToPerson(person.userId, person.username))

    /** Voltar à escolha do destinatário (o texto mantém-se). */
    fun clearTarget() {
        val requestAccount = account ?: return
        if (_uiState.value.locked) return
        savedState.remove<String>(key(requestAccount, KEY_TARGET))
        _uiState.update { it.copy(target = null, send = ShareSendState.Idle) }
    }

    fun onTextChange(text: String) {
        val requestAccount = account ?: return
        if (_uiState.value.locked) return
        savedState[key(requestAccount, KEY_TEXT)] = text
        _uiState.update { it.copy(text = text, send = if (it.send is ShareSendState.Failed) ShareSendState.Idle else it.send) }
    }

    /** "Enviar" (confirmação explícita) ou "Tentar novamente" de uma tentativa por confirmar (mesma chave). */
    fun send() {
        val state = _uiState.value
        val requestAccount = account ?: return
        val target = state.target ?: return
        if (state.send == ShareSendState.Sending || state.send is ShareSendState.Sent || state.experienceGone || !state.textValid) return
        val attempt = savedState.get<String>(key(requestAccount, KEY_PENDING)) ?: newKey().also { savedState[key(requestAccount, KEY_PENDING)] = it }
        val text = MessageRules.cleanText(state.text)
        val myGeneration = generation
        _uiState.update { it.copy(send = ShareSendState.Sending) }
        viewModelScope.launch {
            val conversationId = when (target) {
                is ShareTarget.ToConversation -> target.conversationId
                is ShareTarget.ToPerson -> when (val opened = messaging.open(target.userId)) {
                    is OpenConversationResult.Opened -> opened.conversation.id.also {
                        if (current(myGeneration, requestAccount)) saveTarget(requestAccount, ShareTarget.ToConversation(it, target.username))
                    }
                    is OpenConversationResult.Failed -> {
                        if (!current(myGeneration, requestAccount)) return@launch
                        // Nada foi enviado: a chave não chegou a ser usada; o destinatário e o texto mantêm-se.
                        clearPending(requestAccount)
                        _uiState.update { it.copy(send = ShareSendState.Failed(opened.error)) }
                        return@launch
                    }
                }
            }
            val outcome = messaging.send(conversationId, attempt, text, experienceId)
            if (!current(myGeneration, requestAccount)) return@launch
            when (outcome) {
                is SendOutcome.Sent, is SendOutcome.AlreadySent -> {
                    clearPending(requestAccount)
                    savedState.remove<String>(key(requestAccount, KEY_TEXT))
                    _uiState.update { it.copy(send = ShareSendState.Sent(conversationId)) }
                }
                SendOutcome.Unconfirmed -> _uiState.update { it.copy(send = ShareSendState.Unconfirmed) }
                is SendOutcome.Rejected -> {
                    clearPending(requestAccount)
                    _uiState.update { it.copy(send = ShareSendState.Failed(outcome.error, outcome.retryAfterSeconds)) }
                }
            }
        }
    }

    /** Descartar a tentativa por confirmar (pode já ter sido enviada; o ecrã avisa). */
    fun discardAttempt() {
        val requestAccount = account ?: return
        if (_uiState.value.send == ShareSendState.Sending) return
        clearPending(requestAccount)
        _uiState.update { it.copy(send = ShareSendState.Idle) }
    }

    private fun choose(target: ShareTarget) {
        val requestAccount = account ?: return
        if (_uiState.value.locked) return
        saveTarget(requestAccount, target)
        _uiState.update { it.copy(target = target, send = ShareSendState.Idle) }
    }

    private fun saveTarget(requestAccount: String, target: ShareTarget) {
        savedState[key(requestAccount, KEY_TARGET)] = when (target) {
            is ShareTarget.ToConversation -> "c|${target.conversationId}|${target.username}"
            is ShareTarget.ToPerson -> "p|${target.userId}|${target.username}"
        }
        if (_uiState.value.target != null) _uiState.update { it.copy(target = target) }
    }

    private fun restoreTarget(requestAccount: String): ShareTarget? {
        val parts = savedState.get<String>(key(requestAccount, KEY_TARGET))?.split('|', limit = 3) ?: return null
        if (parts.size != 3) return null
        return if (parts[0] == "c") ShareTarget.ToConversation(parts[1], parts[2]) else ShareTarget.ToPerson(parts[1], parts[2])
    }

    private fun loadPreview(requestAccount: String, myGeneration: Int) {
        viewModelScope.launch {
            val result = experiences.get(experienceId)
            if (!current(myGeneration, requestAccount)) return@launch
            _uiState.update {
                it.copy(
                    preview = when (result) {
                        is DataResult.Success -> PreviewState.Loaded(result.value)
                        is DataResult.Failure -> if (result.error == DataError.NOT_FOUND) PreviewState.Gone else PreviewState.Error(result.error)
                    },
                )
            }
        }
    }

    private fun loadRecent(requestAccount: String, myGeneration: Int) {
        _uiState.update { it.copy(recent = RecentState.Loading) }
        viewModelScope.launch {
            val result = messaging.inbox(null, RECENT_SIZE)
            if (!current(myGeneration, requestAccount)) return@launch
            _uiState.update {
                it.copy(
                    recent = when (result) {
                        is SessionResult.Success -> RecentState.Loaded(result.value.items)
                        is SessionResult.Failure -> RecentState.Error(result.error)
                        else -> RecentState.Error(DataError.NETWORK)
                    },
                )
            }
        }
    }

    private fun clearPending(requestAccount: String) = savedState.remove<String>(key(requestAccount, KEY_PENDING))

    private fun clearSaved(previous: String) {
        clearPending(previous)
        savedState.remove<String>(key(previous, KEY_TEXT))
        savedState.remove<String>(key(previous, KEY_TARGET))
    }

    private fun current(myGeneration: Int, requestAccount: String) = myGeneration == generation && requestAccount == account

    private fun key(account: String, name: String) = "share.$account.$name"

    companion object {
        const val EXPERIENCE_ID_ARG = "experienceId"
        const val MIN_QUERY = 2
        const val SEARCH_SIZE = 20
        const val RECENT_SIZE = 20

        private const val KEY_TEXT = "text"
        private const val KEY_TARGET = "target"
        private const val KEY_PENDING = "pendingKey"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PortoFitApplication
                ShareExperienceViewModel(
                    app.container.messagingRepository,
                    app.container.peopleRepository,
                    app.container.experiencesRepository,
                    app.container.sessionManager.state,
                    createSavedStateHandle(),
                )
            }
        }
    }
}
