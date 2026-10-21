package com.example.portofit.ui.feature.messaging

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.portofit.R
import com.example.portofit.data.model.ChatMessage
import com.example.portofit.data.model.Conversation
import com.example.portofit.data.model.MessageKind
import com.example.portofit.data.model.MessageRules
import com.example.portofit.data.model.ShareAvailability
import com.example.portofit.data.model.SharedExperience
import com.example.portofit.data.repository.DataError
import com.example.portofit.data.repository.MessagingError
import com.example.portofit.ui.components.LoadingIndicator
import com.example.portofit.ui.feature.experience.StarsText
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ Caixa de conversas

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    onBack: () -> Unit,
    onOpenConversation: (conversationId: String, username: String) -> Unit,
    onLogin: () -> Unit,
    viewModel: InboxViewModel = viewModel(factory = InboxViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Consulta periódica só com este ecrã RESUMED (nunca em segundo plano).
    LifecycleResumeEffect(viewModel) {
        viewModel.onResumed()
        onPauseOrDispose { viewModel.onPaused() }
    }
    Scaffold(topBar = { BackTopBar(stringResource(R.string.messages_title), onBack) }) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state.signedIn) {
                null -> item(key = "loading") { LoadingIndicator() }
                false -> item(key = "login") { LoginInvite(R.string.messages_login_hint, onLogin) }
                true -> inboxItems(state, viewModel, onOpenConversation, onLogin)
            }
        }
    }
}

private fun LazyListScope.inboxItems(
    state: InboxUiState,
    viewModel: InboxViewModel,
    onOpenConversation: (String, String) -> Unit,
    onLogin: () -> Unit,
) {
    when (val list = state.list) {
        InboxListState.Loading -> item(key = "inbox-loading") { LoadingIndicator() }
        is InboxListState.Error -> item(key = "inbox-error") { Problem(stringResource(dataErrorMessage(list.error)), viewModel::load) }
        InboxListState.SessionEnded -> item(key = "inbox-session") { Problem(stringResource(R.string.messages_session_ended), onLogin, R.string.account_login) }
        InboxListState.SessionUnverified -> item(key = "inbox-unverified") { Problem(stringResource(R.string.messages_session_unverified), viewModel::load) }
        is InboxListState.Loaded -> {
            if (state.refreshFailed) item(key = "inbox-refresh") { Note(stringResource(R.string.messages_refresh_failed)) }
            if (list.items.isEmpty()) item(key = "inbox-empty") { Note(stringResource(R.string.messages_empty)) }
            items(list.items, key = { it.id }) { conversation ->
                ConversationRow(conversation, state.myUserId) { onOpenConversation(conversation.id, conversation.other.username) }
            }
            item(key = "inbox-footer") {
                when {
                    list.isLoadingMore -> LoadingIndicator()
                    list.loadMoreError != null -> Problem(stringResource(dataErrorMessage(list.loadMoreError)), viewModel::loadMore)
                    list.nextCursor != null -> OutlinedButton(onClick = viewModel::loadMore, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.load_more)) }
                }
            }
        }
    }
}

/** Participante, início da última mensagem, data e por ler. Só dados de apresentação (nenhum email nem id). */
@Composable
private fun ConversationRow(conversation: Conversation, myUserId: String?, onOpen: () -> Unit) {
    val last = conversation.lastMessage
    val mine = last?.senderId == myUserId
    val preview = when {
        last == null -> ""
        last.kind == MessageKind.SHARE && last.textPreview == null ->
            stringResource(if (mine) R.string.messages_you_shared_preview else R.string.messages_shared_preview)
        mine -> stringResource(R.string.messages_you_prefix, last.textPreview.orEmpty())
        else -> last.textPreview.orEmpty()
    }
    val description = stringResource(R.string.messages_conversation_description, conversation.other.username)
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth().semantics { contentDescription = description }) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("@${conversation.other.username}", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (preview.isNotEmpty()) Text(preview, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                last?.let { Text(formatMessageTime(it.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (conversation.unreadCount > 0) {
                val unread = pluralStringResource(R.plurals.messages_unread_description, conversation.unreadCount, conversation.unreadCount)
                Badge(modifier = Modifier.semantics { contentDescription = unread }) {
                    Text(unreadLabel(conversation.unreadCount, stringResource(R.string.messages_unread_many)))
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Conversa

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    initialName: String,
    onBack: () -> Unit,
    onOpenExperience: (experienceId: String) -> Unit,
    onLogin: () -> Unit,
    viewModel: ConversationViewModel = viewModel(factory = ConversationViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val lastVisibleSeq by remember { derivedStateOf { lastShownSeq(listState) } }

    // Consulta periódica e leitura só com este ecrã RESUMED.
    LifecycleResumeEffect(viewModel) {
        viewModel.onResumed()
        lastShownSeq(listState)?.let(viewModel::onMessagesShown)
        onPauseOrDispose { viewModel.onPaused() }
    }
    // Marca como lidas só as mensagens recebidas que o ecrã mostrou (o ViewModel ignora com o ecrã inativo).
    LaunchedEffect(lastVisibleSeq) { lastVisibleSeq?.let(viewModel::onMessagesShown) }

    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.conversation_discard_title)) },
            text = { Text(stringResource(R.string.conversation_discard_message)) },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; viewModel.discardAttempt() }) { Text(stringResource(R.string.conversation_discard_confirm)) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.conversation_discard_keep)) } },
        )
    }

    val title = state.other?.username?.takeIf { it.isNotBlank() }?.let { "@$it" } ?: initialName.takeIf { it.isNotBlank() }?.let { "@$it" }
        ?: stringResource(R.string.conversation_title)
    Scaffold(
        topBar = { BackTopBar(title, onBack) },
        bottomBar = {
            if (state.signedIn == true && state.canSend) Composer(state, viewModel, onDiscard = { confirmDiscard = true })
        },
    ) { innerPadding ->
        val modifier = Modifier.fillMaxSize().padding(innerPadding)
        when {
            state.signedIn == null -> LoadingIndicator(modifier)
            state.signedIn == false -> Column(modifier.padding(16.dp)) { LoginInvite(R.string.conversation_login_hint, onLogin) }
            state.conversationId == null -> OpeningContent(state, viewModel, modifier.padding(16.dp))
            else -> when (val load = state.load) {
                ConversationLoad.Loading -> LoadingIndicator(modifier)
                is ConversationLoad.Error -> Column(modifier.padding(16.dp)) { Problem(stringResource(dataErrorMessage(load.error)), viewModel::retry) }
                ConversationLoad.SessionEnded -> Column(modifier.padding(16.dp)) { Problem(stringResource(R.string.messages_session_ended), onLogin, R.string.account_login) }
                ConversationLoad.SessionUnverified -> Column(modifier.padding(16.dp)) { Problem(stringResource(R.string.messages_session_unverified), viewModel::retry) }
                ConversationLoad.Gone -> Column(modifier.padding(16.dp)) { ErrorText(stringResource(R.string.conversation_gone)) }
                ConversationLoad.Loaded -> MessageList(state, listState, lastVisibleSeq, viewModel, onOpenExperience, modifier)
            }
        }
    }
}

/** Seq da última mensagem mostrada (pelo menos metade dentro do ecrã); as chaves das mensagens são o `seq`. */
private fun lastShownSeq(listState: LazyListState): Long? {
    val info = listState.layoutInfo
    return info.visibleItemsInfo
        .filter { it.offset + it.size / 2 <= info.viewportEndOffset }
        .mapNotNull { it.key as? Long }
        .maxOrNull()
}

@Composable
private fun OpeningContent(state: ConversationUiState, viewModel: ConversationViewModel, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (val open = state.open) {
            OpenState.Opening, OpenState.None -> {
                Text(stringResource(R.string.conversation_opening), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            // Depois de entrar: nada é criado sem um novo toque.
            OpenState.NeedsTap -> {
                Text(stringResource(R.string.conversation_start_hint, state.other?.username.orEmpty()), style = MaterialTheme.typography.bodyLarge)
                Button(onClick = viewModel::openConversation) { Text(stringResource(R.string.conversation_start)) }
            }
            is OpenState.Failed -> Problem(stringResource(messagingErrorMessage(open.error)), viewModel::openConversation)
        }
    }
}

@Composable
private fun MessageList(
    state: ConversationUiState,
    listState: LazyListState,
    lastVisibleSeq: Long?,
    viewModel: ConversationViewModel,
    onOpenExperience: (String) -> Unit,
    modifier: Modifier,
) {
    val messages = state.messages
    val lastSeq = messages.lastOrNull()?.seq
    val scope = rememberCoroutineScope()
    // Última mensagem quando quem lê deixou de estar no fim: só as recebidas depois dela contam como "novas".
    var awaySinceSeq by rememberSaveable { mutableStateOf<Long?>(null) }
    var previousLastSeq by rememberSaveable { mutableStateOf<Long?>(null) }

    LaunchedEffect(lastSeq) {
        if (lastSeq == null) return@LaunchedEffect
        val previous = previousLastSeq
        previousLastSeq = lastSeq
        val lastIsMine = messages.last().senderId == state.myUserId
        // Abrir (sem anterior), estar no fim ou acabar de enviar: segue para o fim. A ler mais acima: fica onde está.
        if (previous == null || lastIsMine || (lastVisibleSeq ?: 0) >= previous) {
            listState.scrollToItem(messages.size)
            awaySinceSeq = null
        } else if (awaySinceSeq == null) {
            awaySinceSeq = previous
        }
    }
    val atEnd = lastVisibleSeq != null && lastVisibleSeq == lastSeq
    LaunchedEffect(atEnd) { if (atEnd) awaySinceSeq = null }
    val since = awaySinceSeq
    val newCount = if (since == null) 0 else messages.count { it.senderId != state.myUserId && it.seq > maxOf(since, lastVisibleSeq ?: 0) }

    Box(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Índice 0: cabeçalho fixo (o histórico mais antigo e os avisos); as mensagens começam no índice 1.
            item(key = "header") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (state.refreshFailed) Note(stringResource(R.string.messages_refresh_failed))
                    when {
                        state.loadingOlder -> LoadingIndicator()
                        state.olderError != null -> Problem(stringResource(dataErrorMessage(state.olderError)), viewModel::loadOlder)
                        state.hasMoreBefore -> OutlinedButton(onClick = viewModel::loadOlder, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.conversation_load_older))
                        }
                    }
                    if (messages.isEmpty()) Note(stringResource(R.string.conversation_empty))
                }
            }
            items(messages, key = { it.seq }) { message ->
                MessageBubble(
                    message = message,
                    mine = message.senderId == state.myUserId,
                    otherName = state.other?.username.orEmpty(),
                    refreshingShares = state.refreshingShares,
                    onOpenExperience = onOpenExperience,
                    onRefreshShares = viewModel::refreshShares,
                )
            }
        }
        if (newCount > 0) {
            val label = pluralStringResource(R.plurals.conversation_new_messages_count, newCount, newCount)
            SmallFloatingActionButton(
                onClick = { scope.launch { listState.animateScrollToItem(messages.size) } },
                modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp).semantics { contentDescription = label },
            ) {
                Text(label, modifier = Modifier.padding(horizontal = 12.dp))
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    mine: Boolean,
    otherName: String,
    refreshingShares: Boolean,
    onOpenExperience: (String) -> Unit,
    onRefreshShares: () -> Unit,
) {
    val sender = if (mine) stringResource(R.string.conversation_me) else "@$otherName"
    val time = formatMessageTime(message.createdAt)
    val description = stringResource(
        R.string.conversation_message_description,
        sender,
        time,
        message.text ?: stringResource(R.string.messages_shared_preview),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(Modifier.semantics(mergeDescendants = true) { contentDescription = description }) {
                    Text("$sender · $time", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    message.text?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                }
                message.shared?.let { SharedBlock(it, refreshingShares, onOpenExperience, onRefreshShares) }
            }
        }
    }
}

/** Disponível: abre a experiência. Eliminada: só o aviso, sem conteúdo antigo. Indisponível: "Atualizar". */
@Composable
private fun SharedBlock(shared: SharedExperience, refreshing: Boolean, onOpenExperience: (String) -> Unit, onRefresh: () -> Unit) {
    val experience = shared.experience
    when {
        shared.availability == ShareAvailability.AVAILABLE && experience != null -> {
            val open = stringResource(R.string.share_open_description)
            Card(onClick = { onOpenExperience(experience.id) }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = open }) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(experience.offeringName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(experience.placeName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    StarsText(experience.stars)
                    Text(experience.text, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text("@${experience.authorUsername}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        shared.availability == ShareAvailability.DELETED -> Note(stringResource(R.string.share_deleted))
        else -> Column {
            Note(stringResource(R.string.share_unknown))
            TextButton(onClick = onRefresh, enabled = !refreshing) {
                Text(stringResource(if (refreshing) R.string.share_refreshing else R.string.share_refresh))
            }
        }
    }
}

@Composable
private fun Composer(state: ConversationUiState, viewModel: ConversationViewModel, onDiscard: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (val send = state.send) {
                SendState.Unconfirmed -> {
                    ErrorText(stringResource(R.string.conversation_unconfirmed))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = viewModel::send) { Text(stringResource(R.string.retry)) }
                        TextButton(onClick = onDiscard) { Text(stringResource(R.string.conversation_discard)) }
                    }
                }
                is SendState.Failed -> ErrorText(sendErrorMessage(send.error, send.retryAfterSeconds))
                SendState.AlreadySent -> Note(stringResource(R.string.conversation_already_sent))
                SendState.Sending, SendState.Idle -> Unit
            }
            val showProblem = state.showValidation && !state.draftValid
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.draft,
                    onValueChange = viewModel::onDraftChange,
                    modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.conversation_draft_label)) },
                    readOnly = state.composerLocked,
                    isError = showProblem,
                    maxLines = 5,
                    supportingText = {
                        Text(
                            if (showProblem) stringResource(R.string.conversation_draft_invalid)
                            else stringResource(R.string.conversation_draft_counter, state.draftLength, MessageRules.MAX_TEXT),
                        )
                    },
                )
                if (state.send != SendState.Unconfirmed) {
                    Button(onClick = viewModel::send, enabled = state.send != SendState.Sending) {
                        Text(stringResource(if (state.send == SendState.Sending) R.string.conversation_sending else R.string.conversation_send))
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Partilhar uma experiência

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareExperienceScreen(
    onBack: () -> Unit,
    onLogin: () -> Unit,
    onOpenConversation: (conversationId: String, username: String) -> Unit,
    viewModel: ShareExperienceViewModel = viewModel(factory = ShareExperienceViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.conversation_discard_title)) },
            text = { Text(stringResource(R.string.conversation_discard_message)) },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; viewModel.discardAttempt() }) { Text(stringResource(R.string.conversation_discard_confirm)) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.conversation_discard_keep)) } },
        )
    }
    Scaffold(topBar = { BackTopBar(stringResource(R.string.share_title), onBack) }) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding).imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state.signedIn) {
                null -> item(key = "loading") { LoadingIndicator() }
                // Depois de entrar, regressa aqui; nada é enviado sem "Enviar".
                false -> item(key = "login") { LoginInvite(R.string.share_login_hint, onLogin) }
                true -> {
                    item(key = "preview") { SharePreview(state.preview) }
                    val target = state.target
                    if (target == null) chooseTarget(state, viewModel) else item(key = "confirm") {
                        ConfirmShare(state, target, viewModel, onOpenConversation, onDiscard = { confirmDiscard = true })
                    }
                }
            }
        }
    }
}

@Composable
private fun SharePreview(preview: PreviewState) {
    when (preview) {
        PreviewState.Loading -> LoadingIndicator()
        is PreviewState.Loaded -> Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(preview.experience.offeringName, style = MaterialTheme.typography.titleMedium)
                Text(preview.experience.placeName, style = MaterialTheme.typography.bodySmall)
                StarsText(preview.experience.stars)
                Text(preview.experience.text, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
        PreviewState.Gone -> ErrorText(stringResource(R.string.messaging_error_experience_gone))
        is PreviewState.Error -> Note(stringResource(R.string.share_preview_unavailable))
    }
}

private fun LazyListScope.chooseTarget(state: ShareUiState, viewModel: ShareExperienceViewModel) {
    item(key = "recent-title") { Text(stringResource(R.string.share_recent), style = MaterialTheme.typography.titleMedium) }
    when (val recent = state.recent) {
        RecentState.Loading -> item(key = "recent-loading") { LoadingIndicator() }
        is RecentState.Error -> item(key = "recent-error") { Problem(stringResource(dataErrorMessage(recent.error)), viewModel::retryRecent) }
        is RecentState.Loaded -> {
            if (recent.items.isEmpty()) item(key = "recent-empty") { Note(stringResource(R.string.share_recent_empty)) }
            items(recent.items, key = { "c-" + it.id }) { conversation ->
                TargetRow("@${conversation.other.username}", enabled = !state.experienceGone) { viewModel.chooseConversation(conversation) }
            }
        }
    }
    item(key = "search") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HorizontalDivider()
            Text(stringResource(R.string.share_search), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.share_search)) },
                supportingText = { Text(stringResource(R.string.share_search_hint)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
            )
            OutlinedButton(onClick = viewModel::search, enabled = state.query.trim().length >= ShareExperienceViewModel.MIN_QUERY) {
                Text(stringResource(R.string.share_search_action))
            }
        }
    }
    when (val people = state.people) {
        PeopleSearchState.Idle -> Unit
        PeopleSearchState.Searching -> item(key = "people-loading") { LoadingIndicator() }
        is PeopleSearchState.Error -> item(key = "people-error") { Problem(stringResource(R.string.error_network), viewModel::search) }
        is PeopleSearchState.Results -> {
            if (people.items.isEmpty()) item(key = "people-empty") { Note(stringResource(R.string.share_search_empty)) }
            items(people.items, key = { "p-" + it.userId }) { person ->
                TargetRow("@${person.username}", enabled = !state.experienceGone) { viewModel.choosePerson(person) }
            }
        }
    }
}

@Composable
private fun TargetRow(label: String, enabled: Boolean, onChoose: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = onChoose, enabled = enabled) { Text(stringResource(R.string.share_choose)) }
        }
    }
}

@Composable
private fun ConfirmShare(
    state: ShareUiState,
    target: ShareTarget,
    viewModel: ShareExperienceViewModel,
    onOpenConversation: (String, String) -> Unit,
    onDiscard: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.share_to, target.username), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (!state.locked) TextButton(onClick = viewModel::clearTarget) { Text(stringResource(R.string.share_change_target)) }
        }
        val showProblem = !state.textValid
        OutlinedTextField(
            value = state.text,
            onValueChange = viewModel::onTextChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.share_text_label)) },
            readOnly = state.locked,
            isError = showProblem,
            minLines = 2,
            maxLines = 5,
            supportingText = {
                Text(
                    if (showProblem) stringResource(R.string.share_text_invalid)
                    else stringResource(R.string.conversation_draft_counter, state.textLength, MessageRules.MAX_TEXT),
                )
            },
        )
        when (val send = state.send) {
            is ShareSendState.Sent -> {
                Text(stringResource(R.string.share_sent, target.username), color = MaterialTheme.colorScheme.primary)
                Button(onClick = { onOpenConversation(send.conversationId, target.username) }) { Text(stringResource(R.string.share_open_conversation)) }
            }
            ShareSendState.Unconfirmed -> {
                ErrorText(stringResource(R.string.conversation_unconfirmed))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::send) { Text(stringResource(R.string.retry)) }
                    TextButton(onClick = onDiscard) { Text(stringResource(R.string.conversation_discard)) }
                }
            }
            else -> {
                if (send is ShareSendState.Failed) ErrorText(sendErrorMessage(send.error, send.retryAfterSeconds))
                // Confirmação explícita: só este botão envia.
                Button(onClick = viewModel::send, enabled = send != ShareSendState.Sending && !state.experienceGone && state.textValid) {
                    Text(stringResource(if (send == ShareSendState.Sending) R.string.conversation_sending else R.string.share_send))
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Partilhado

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.navigate_back)) }
        },
    )
}

@Composable
private fun LoginInvite(@StringRes message: Int, onLogin: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(message), style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onLogin) { Text(stringResource(R.string.account_login)) }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ErrorText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun Problem(message: String, onAction: () -> Unit, @StringRes action: Int = R.string.retry) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = onAction) { Text(stringResource(action)) }
    }
}

@StringRes
private fun dataErrorMessage(error: DataError): Int =
    if (error == DataError.NOT_CONFIGURED) R.string.messages_not_configured else R.string.messages_unavailable

@Composable
private fun sendErrorMessage(error: MessagingError, retryAfterSeconds: Long?): String =
    if (error == MessagingError.TOO_MANY_REQUESTS && retryAfterSeconds != null) {
        val minutes = ((retryAfterSeconds + 59) / 60).toInt().coerceAtLeast(1)
        pluralStringResource(R.plurals.messaging_error_too_many_minutes, minutes, minutes)
    } else {
        stringResource(messagingErrorMessage(error))
    }

@StringRes
fun messagingErrorMessage(error: MessagingError): Int = when (error) {
    MessagingError.USERS_UNAVAILABLE -> R.string.messaging_error_users_unavailable
    MessagingError.USER_NOT_FOUND -> R.string.messaging_error_user_not_found
    MessagingError.CANNOT_MESSAGE_SELF -> R.string.messaging_error_self
    MessagingError.CONVERSATION_GONE -> R.string.messaging_error_conversation_gone
    MessagingError.EXPERIENCE_GONE -> R.string.messaging_error_experience_gone
    MessagingError.EXPERIENCES_UNAVAILABLE -> R.string.messaging_error_experiences_unavailable
    MessagingError.CONVERSATION_FULL -> R.string.messaging_error_full
    MessagingError.TOO_MANY_REQUESTS -> R.string.messaging_error_too_many
    MessagingError.VALIDATION -> R.string.messaging_error_validation
    MessagingError.UNAVAILABLE -> R.string.messaging_error_unavailable
    MessagingError.SESSION_ENDED -> R.string.messaging_error_session_ended
    MessagingError.SESSION_UNVERIFIED -> R.string.profile_error_session_unverified
    MessagingError.NOT_CONFIGURED -> R.string.messages_not_configured
    MessagingError.UNEXPECTED -> R.string.error_unexpected
}
