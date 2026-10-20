package com.example.portofit.ui.feature.collections

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.portofit.R
import com.example.portofit.data.model.CollectionRules
import com.example.portofit.data.model.SavedAvailability
import com.example.portofit.data.model.SavedItem
import com.example.portofit.data.model.UserCollection
import com.example.portofit.data.repository.CollectionError
import com.example.portofit.data.repository.DataError
import com.example.portofit.ui.components.LoadingIndicator
import com.example.portofit.ui.feature.experience.ExperienceCard

// ------------------------------------------------------------------ As minhas listas

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionsScreen(
    onBack: () -> Unit,
    onOpenCollection: (UserCollection) -> Unit,
    onLogin: () -> Unit,
    viewModel: CollectionsViewModel = viewModel(factory = CollectionsViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lists by viewModel.lists.collectAsStateWithLifecycle()
    val creator by viewModel.creator.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    CreateDialog(creator, viewModel.creator)
    state.rename?.let { rename -> RenameDialog(rename, viewModel) }
    state.confirmDelete?.let { target ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = { Text(stringResource(R.string.collection_delete_title, target.name)) },
            text = { Text(stringResource(R.string.collection_delete_message)) },
            confirmButton = { TextButton(onClick = viewModel::confirmDelete) { Text(stringResource(R.string.collection_delete_confirm)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissDelete) { Text(stringResource(R.string.collection_delete_keep)) } },
        )
    }

    Scaffold(topBar = { BackTopBar(stringResource(R.string.collections_title), onBack) }) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state.signedIn) {
                null -> item(key = "loading") { LoadingIndicator() }
                false -> item(key = "login") { LoginInvite(R.string.collections_login_hint, onLogin) }
                true -> {
                    item(key = "new") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Button(onClick = viewModel.creator::open) { Text(stringResource(R.string.collection_new)) }
                            if (creator.pendingAttempt && !creator.open) Note(stringResource(R.string.collection_create_unconfirmed))
                            state.deleteError?.let { Error(stringResource(collectionErrorMessage(it))) }
                        }
                    }
                    collectionItems(lists, viewModel::retry, viewModel::loadMore, onLogin) { collection ->
                        CollectionCard(
                            collection,
                            deleting = state.deletingId == collection.id,
                            onOpen = { onOpenCollection(collection) },
                            actions = {
                                TextButton(onClick = { viewModel.startRename(collection) }) { Text(stringResource(R.string.collection_rename)) }
                                TextButton(onClick = { viewModel.requestDelete(collection) }, enabled = state.deletingId == null) {
                                    Text(stringResource(R.string.collection_delete))
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RenameDialog(rename: RenameState, viewModel: CollectionsViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::cancelRename,
        title = { Text(stringResource(R.string.collection_rename_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NameField(rename.name, viewModel::onRenameText, readOnly = rename.saving, showProblem = rename.showValidation && !rename.nameValid)
                if (rename.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                rename.error?.let { Error(stringResource(collectionErrorMessage(it))) }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::saveRename, enabled = !rename.saving) { Text(stringResource(R.string.collection_save_name)) } },
        dismissButton = { TextButton(onClick = viewModel::cancelRename, enabled = !rename.saving) { Text(stringResource(R.string.collection_cancel)) } },
    )
}

// ------------------------------------------------------------------ Detalhe de uma lista

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionDetailScreen(
    initialName: String,
    onBack: () -> Unit,
    onOpenExperience: (String) -> Unit,
    onOpenAuthor: (String) -> Unit,
    onLogin: () -> Unit,
    viewModel: CollectionDetailViewModel = viewModel(factory = CollectionDetailViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Guardada com o ecrã: ao voltar de uma experiência, a posição mantém-se.
    val listState = rememberLazyListState()
    val title = (state.header as? CollectionHeaderState.Loaded)?.collection?.name ?: initialName

    Scaffold(topBar = { BackTopBar(title, onBack) }) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                state.signedIn == null -> item(key = "loading") { LoadingIndicator() }
                state.signedIn == false -> item(key = "login") { LoginInvite(R.string.collections_login_hint, onLogin) }
                state.header == CollectionHeaderState.Gone -> item(key = "gone") { Error(stringResource(R.string.collection_gone)) }
                else -> {
                    (state.header as? CollectionHeaderState.Loaded)?.let { header ->
                        item(key = "count") {
                            val count = header.collection.itemCount.toInt()
                            Text(pluralStringResource(R.plurals.experiences_count, count, count), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    savedItems(state, viewModel, onOpenExperience, onOpenAuthor, onLogin)
                }
            }
        }
    }
}

private fun LazyListScope.savedItems(
    state: CollectionDetailUiState,
    viewModel: CollectionDetailViewModel,
    onOpenExperience: (String) -> Unit,
    onOpenAuthor: (String) -> Unit,
    onLogin: () -> Unit,
) {
    when (val items = state.items) {
        SavedItemsState.Loading -> item(key = "items-loading") { LoadingIndicator() }
        is SavedItemsState.Error -> item(key = "items-error") { Problem(stringResource(dataErrorMessage(items.error)), viewModel::refresh) }
        SavedItemsState.SessionEnded -> item(key = "items-session") { Problem(stringResource(R.string.collections_session_ended), onLogin, R.string.account_login) }
        SavedItemsState.SessionUnverified -> item(key = "items-unverified") { Problem(stringResource(R.string.collections_session_unverified), viewModel::refresh) }
        is SavedItemsState.Loaded -> {
            if (!items.experiencesAvailable) {
                item(key = "unavailable") {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.saved_experiences_unavailable), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = viewModel::refresh) { Text(stringResource(R.string.retry)) }
                        }
                    }
                }
            }
            if (items.items.isEmpty()) item(key = "empty") { Note(stringResource(R.string.saved_empty)) }
            items(items.items, key = { it.experienceId }) { item ->
                SavedItemEntry(
                    item,
                    removing = item.experienceId in state.removing,
                    removeError = state.removeErrors[item.experienceId],
                    onOpenExperience = onOpenExperience,
                    onOpenAuthor = onOpenAuthor,
                    onRemove = { viewModel.remove(item.experienceId) },
                    onRetry = viewModel::refresh,
                )
            }
            item(key = "footer") {
                when {
                    items.isLoadingMore -> LoadingIndicator()
                    items.loadMoreError != null -> Problem(stringResource(dataErrorMessage(items.loadMoreError)), viewModel::loadMore)
                    items.hasMore -> OutlinedButton(onClick = viewModel::loadMore, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.load_more)) }
                }
            }
        }
    }
}

/** Disponível: o cartão existente. Eliminada ou desconhecida: só o aviso, sem conteúdo antigo. Todas removíveis. */
@Composable
private fun SavedItemEntry(
    item: SavedItem,
    removing: Boolean,
    removeError: CollectionError?,
    onOpenExperience: (String) -> Unit,
    onOpenAuthor: (String) -> Unit,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val experience = item.experience
        if (item.availability == SavedAvailability.AVAILABLE && experience != null) {
            ExperienceCard(experience, showPlace = true, onClick = { onOpenExperience(experience.id) }, onAuthorClick = { onOpenAuthor(experience.authorId) })
        } else {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (item.availability == SavedAvailability.DELETED) {
                        Text(stringResource(R.string.saved_deleted), style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(stringResource(R.string.saved_unknown), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                    }
                }
            }
        }
        TextButton(onClick = onRemove, enabled = !removing) {
            Text(stringResource(if (removing) R.string.saved_removing else R.string.saved_remove))
        }
        removeError?.let { Error(stringResource(collectionErrorMessage(it))) }
    }
}

// ------------------------------------------------------------------ Guardar numa lista

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveToCollectionScreen(
    onBack: () -> Unit,
    onLogin: () -> Unit,
    viewModel: SaveToCollectionViewModel = viewModel(factory = SaveToCollectionViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lists by viewModel.lists.collectAsStateWithLifecycle()
    val creator by viewModel.creator.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    CreateDialog(creator, viewModel.creator)

    Scaffold(topBar = { BackTopBar(stringResource(R.string.save_title), onBack) }) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state.signedIn) {
                null -> item(key = "loading") { LoadingIndicator() }
                // Depois de entrar, regressa aqui; guardar exige um novo toque.
                false -> item(key = "login") { LoginInvite(R.string.save_login_hint, onLogin) }
                true -> {
                    item(key = "new") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (state.experienceGone) Error(stringResource(R.string.collection_error_experience_gone))
                            OutlinedButton(onClick = viewModel.creator::open) { Text(stringResource(R.string.collection_new)) }
                            if (creator.pendingAttempt && !creator.open) Note(stringResource(R.string.collection_create_unconfirmed))
                        }
                    }
                    collectionItems(lists, viewModel::retry, viewModel::loadMore, onLogin) { collection ->
                        val save = state.saves[collection.id]
                        val savedDescription = stringResource(R.string.save_saved_description, collection.name)
                        CollectionCard(
                            collection,
                            deleting = false,
                            onOpen = null,
                            actions = {
                                when (save) {
                                    SaveState.Saved -> Text(
                                        "✓ " + stringResource(R.string.save_saved),
                                        modifier = Modifier.semantics { contentDescription = savedDescription },
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    SaveState.Saving -> Button(onClick = {}, enabled = false) { Text(stringResource(R.string.save_saving)) }
                                    else -> Button(onClick = { viewModel.save(collection.id) }, enabled = !state.experienceGone) {
                                        Text(stringResource(if (save is SaveState.Failed) R.string.retry else R.string.save_action))
                                    }
                                }
                            },
                            footer = (save as? SaveState.Failed)?.let { { Error(stringResource(collectionErrorMessage(it.error))) } },
                        )
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Partilhado

/** Lista de listas com os estados próprios (carregar, vazio, erro, sessão) e "Carregar mais". */
private fun LazyListScope.collectionItems(
    lists: CollectionsListState,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onLogin: () -> Unit,
    row: @Composable (UserCollection) -> Unit,
) {
    when (lists) {
        CollectionsListState.Loading -> item(key = "lists-loading") { LoadingIndicator() }
        is CollectionsListState.Error -> item(key = "lists-error") { Problem(stringResource(dataErrorMessage(lists.error)), onRetry) }
        CollectionsListState.SessionEnded -> item(key = "lists-session") { Problem(stringResource(R.string.collections_session_ended), onLogin, R.string.account_login) }
        CollectionsListState.SessionUnverified -> item(key = "lists-unverified") { Problem(stringResource(R.string.collections_session_unverified), onRetry) }
        is CollectionsListState.Loaded -> {
            if (lists.items.isEmpty()) item(key = "lists-empty") { Note(stringResource(R.string.collections_empty)) }
            items(lists.items, key = { it.id }) { row(it) }
            item(key = "lists-footer") {
                when {
                    lists.isLoadingMore -> LoadingIndicator()
                    lists.loadMoreError != null -> Problem(stringResource(dataErrorMessage(lists.loadMoreError)), onLoadMore)
                    lists.hasMore -> OutlinedButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.load_more)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CollectionCard(
    collection: UserCollection,
    deleting: Boolean,
    onOpen: (() -> Unit)?,
    actions: @Composable () -> Unit,
    footer: (@Composable () -> Unit)? = null,
) {
    val content: @Composable () -> Unit = {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(collection.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val count = collection.itemCount.toInt()
            Text(pluralStringResource(R.plurals.experiences_count, count, count), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (deleting) LinearProgressIndicator(Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
            footer?.invoke()
        }
    }
    if (onOpen != null) Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) { content() } else Card(Modifier.fillMaxWidth()) { content() }
}

/** Diálogo de criação, partilhado pelas listas e pelo seletor (chave preservada por tentativa). */
@Composable
private fun CreateDialog(state: CreatorState, creator: CollectionCreator) {
    if (!state.open) return
    AlertDialog(
        onDismissRequest = creator::close,
        title = { Text(stringResource(R.string.collection_new_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NameField(state.name, creator::onNameChange, readOnly = state.locked, showProblem = state.showValidation && !state.nameValid)
                if (state.sending) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.pendingAttempt && !state.sending) {
                    Error(stringResource(R.string.collection_create_unconfirmed))
                    TextButton(onClick = creator::discardAttempt) { Text(stringResource(R.string.collection_create_discard)) }
                }
                state.message?.let { message ->
                    Error(
                        when (message) {
                            is CreatorMessage.Rejected -> stringResource(collectionErrorMessage(message.error))
                            CreatorMessage.AlreadyCreated -> stringResource(R.string.collection_create_already)
                            CreatorMessage.CreatedThenDeleted -> stringResource(R.string.collection_create_deleted)
                            CreatorMessage.AttemptTooOld -> stringResource(R.string.collection_create_too_old)
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = creator::submit, enabled = !state.sending) {
                Text(
                    stringResource(
                        when {
                            state.sending -> R.string.collection_creating
                            state.pendingAttempt -> R.string.retry
                            else -> R.string.collection_create
                        },
                    ),
                )
            }
        },
        dismissButton = { TextButton(onClick = creator::close, enabled = !state.sending) { Text(stringResource(R.string.collection_cancel)) } },
    )
}

@Composable
private fun NameField(value: String, onChange: (String) -> Unit, readOnly: Boolean, showProblem: Boolean) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.collection_name_label)) },
        singleLine = true,
        readOnly = readOnly,
        isError = showProblem,
        supportingText = {
            Text(
                if (showProblem) stringResource(R.string.collection_name_invalid)
                else stringResource(R.string.collection_name_counter, CollectionRules.length(value), CollectionRules.MAX_NAME),
            )
        },
    )
}

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
private fun Error(text: String) {
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
    if (error == DataError.NOT_CONFIGURED) R.string.collections_not_configured else R.string.collections_unavailable

@StringRes
fun collectionErrorMessage(error: CollectionError): Int = when (error) {
    CollectionError.UNCONFIRMED -> R.string.collection_error_unconfirmed
    CollectionError.COLLECTION_GONE -> R.string.collection_gone
    CollectionError.NAME_TAKEN -> R.string.collection_error_name_taken
    CollectionError.VALIDATION -> R.string.collection_error_validation
    CollectionError.COLLECTION_LIMIT -> R.string.collection_error_limit
    CollectionError.ITEM_LIMIT -> R.string.collection_error_item_limit
    CollectionError.EXPERIENCE_GONE -> R.string.collection_error_experience_gone
    CollectionError.EXPERIENCES_UNAVAILABLE -> R.string.collection_error_experiences_unavailable
    CollectionError.SESSION_ENDED -> R.string.collection_error_session_ended
    CollectionError.SESSION_UNVERIFIED -> R.string.profile_error_session_unverified
    CollectionError.NOT_CONFIGURED -> R.string.collections_not_configured
    CollectionError.UNEXPECTED -> R.string.error_unexpected
}
