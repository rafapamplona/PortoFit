package com.example.portofit.ui.feature.notifications

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.portofit.PortoFitApplication
import com.example.portofit.R
import com.example.portofit.data.model.AppNotification
import com.example.portofit.data.model.NotificationTarget
import com.example.portofit.data.repository.DataError
import com.example.portofit.notifications.notificationText
import com.example.portofit.ui.components.LoadingIndicator
import com.example.portofit.ui.feature.messaging.formatMessageTime
import com.example.portofit.ui.feature.messaging.unreadLabel

/**
 * Caixa de notificações (docs/notifications-api.md §15.1–15.2): lista, "Carregar mais", "Marcar todas como lidas",
 * o cartão para ativar as notificações do sistema e os estados de erro e de sessão. Abrir uma notificação marca-a como
 * lida e abre o destino por cima da caixa (voltar regressa aqui).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    onBack: () -> Unit,
    onOpen: (NotificationTarget) -> Unit,
    onLogin: () -> Unit,
    viewModel: NotificationsViewModel = viewModel(factory = NotificationsViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Consulta periódica só com este ecrã RESUMED (nunca em segundo plano).
    LifecycleResumeEffect(viewModel) {
        viewModel.onResumed()
        onPauseOrDispose { viewModel.onPaused() }
    }
    val unread = state.unreadCount ?: 0
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.notifications_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.navigate_back)) }
                },
                actions = {
                    if (state.signedIn == true && state.list is NotificationListState.Loaded && unread > 0) {
                        TextButton(onClick = viewModel::markAllRead, enabled = !state.markingAll) { Text(stringResource(R.string.notifications_mark_all)) }
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state.signedIn) {
                null -> item(key = "loading") { LoadingIndicator() }
                false -> item(key = "login") { LoginInvite(onLogin) }
                true -> {
                    item(key = "permission") { NotificationPermissionCard() }
                    notificationItems(state, viewModel, onOpen, onLogin)
                }
            }
        }
    }
}

private fun LazyListScope.notificationItems(
    state: NotificationsUiState,
    viewModel: NotificationsViewModel,
    onOpen: (NotificationTarget) -> Unit,
    onLogin: () -> Unit,
) {
    when (val list = state.list) {
        NotificationListState.Loading -> item(key = "list-loading") { LoadingIndicator() }
        is NotificationListState.Error -> item(key = "list-error") { Problem(stringResource(dataErrorMessage(list.error)), viewModel::load) }
        NotificationListState.SessionEnded -> item(key = "list-session") {
            Problem(stringResource(R.string.notifications_session_ended), onLogin, R.string.account_login)
        }
        NotificationListState.SessionUnverified -> item(key = "list-unverified") { Problem(stringResource(R.string.notifications_session_unverified), viewModel::load) }
        is NotificationListState.Loaded -> {
            state.unreadCount?.takeIf { it > 0 }?.let { count ->
                item(key = "list-unread") { Note(pluralStringResource(R.plurals.notifications_unread, count, unreadLabel(count, stringResource(R.string.messages_unread_many)))) }
            }
            if (state.refreshFailed) item(key = "list-refresh") { Note(stringResource(R.string.notifications_refresh_failed)) }
            if (state.markFailed) item(key = "list-mark") { ErrorText(stringResource(R.string.notifications_mark_failed)) }
            if (list.items.isEmpty()) item(key = "list-empty") { Note(stringResource(R.string.notifications_empty)) }
            items(list.items, key = { it.id }) { notification ->
                NotificationRow(notification) {
                    viewModel.onOpened(notification)
                    onOpen(notification.target)
                }
            }
            item(key = "list-footer") {
                when {
                    list.isLoadingMore -> LoadingIndicator()
                    list.loadMoreError != null -> Problem(stringResource(dataErrorMessage(list.loadMoreError)), viewModel::loadMore)
                    list.hasMoreBefore -> OutlinedButton(onClick = viewModel::loadMore, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.load_more)) }
                }
            }
        }
    }
}

/** Só o texto do tipo (§9), a data e se está por ler; nunca conteúdo de mensagens ou comentários. */
@Composable
private fun NotificationRow(notification: AppNotification, onOpen: () -> Unit) {
    val text = stringResource(notificationText(notification), notification.actorUsername)
    val description = if (notification.read) text else stringResource(R.string.notifications_unread_description, text)
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = description },
        colors = if (notification.read) CardDefaults.cardColors() else CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (notification.read) FontWeight.Normal else FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(formatMessageTime(notification.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!notification.read) Surface(color = MaterialTheme.colorScheme.primary, shape = CircleShape, modifier = Modifier.size(10.dp)) { Box(Modifier) }
        }
    }
}

/**
 * Cartão para ativar as notificações do sistema (§15.2). O pedido do Android só acontece com o toque em "Ativar"; depois
 * de recusado (ou em Android < 13 com as notificações desligadas) o cartão explica como ativar nas definições. A caixa
 * funciona sempre; o trabalho periódico só é agendado com as notificações permitidas.
 */
@Composable
private fun NotificationPermissionCard() {
    val context = LocalContext.current
    val container = (context.applicationContext as PortoFitApplication).container
    var allowed by remember { mutableStateOf(container.systemNotifier.canPost()) }
    var denied by rememberSaveable { mutableStateOf(false) }
    // Ao voltar (ex.: das definições), a permissão pode ter mudado.
    LifecycleResumeEffect(Unit) {
        allowed = container.systemNotifier.canPost()
        onPauseOrDispose { }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = container.systemNotifier.canPost()
        denied = !granted
        container.syncNotifications()
    }
    if (allowed) return
    val canAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !denied
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.notifications_permission_explain), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            if (canAsk) {
                Button(onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text(stringResource(R.string.notifications_permission_enable)) }
            } else {
                Text(stringResource(R.string.notifications_permission_settings_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                        )
                    },
                ) { Text(stringResource(R.string.notifications_permission_open_settings)) }
            }
        }
    }
}

@Composable
private fun LoginInvite(onLogin: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.notifications_login_hint), style = MaterialTheme.typography.bodyLarge)
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
    if (error == DataError.NOT_CONFIGURED) R.string.notifications_not_configured else R.string.notifications_unavailable
