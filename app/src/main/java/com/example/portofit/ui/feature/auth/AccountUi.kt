package com.example.portofit.ui.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.portofit.R
import com.example.portofit.data.auth.SessionNotice
import com.example.portofit.data.auth.SessionState
import com.example.portofit.data.auth.SessionStatus

/**
 * Ação na barra superior: "Entrar" sem sessão; com sessão, o username com "O meu perfil", "As minhas listas",
 * "Mensagens" e "Terminar sessão".
 */
@Composable
fun AccountAction(
    session: SessionState,
    onLoginClick: () -> Unit,
    onProfileClick: () -> Unit,
    onLogout: () -> Unit,
    onCollectionsClick: () -> Unit = {},
    onMessagesClick: () -> Unit = {},
) {
    when (session) {
        SessionState.Loading -> Unit
        is SessionState.SignedOut -> TextButton(onClick = onLoginClick) { Text(stringResource(R.string.account_login)) }
        is SessionState.SignedIn -> {
            var menuOpen by rememberSaveable { mutableStateOf(false) }
            Box {
                TextButton(onClick = { menuOpen = true }) {
                    Text("@${session.username}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.account_menu_signed_in_as, session.username)) },
                        onClick = {},
                        enabled = false,
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.account_profile)) },
                        onClick = {
                            menuOpen = false
                            onProfileClick()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.account_collections)) },
                        onClick = {
                            menuOpen = false
                            onCollectionsClick()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.account_messages)) },
                        onClick = {
                            menuOpen = false
                            onMessagesClick()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.account_logout)) },
                        onClick = {
                            menuOpen = false
                            onLogout()
                        },
                    )
                }
            }
        }
    }
}

/**
 * Aviso sobre a sessão no topo da pesquisa. Nunca bloqueia o catálogo: é só informativo, com
 * "Tentar novamente" ou "Entrar" quando faz sentido.
 */
@Composable
fun SessionBanner(session: SessionState, onLoginClick: () -> Unit, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val (message, actions) = when {
        session is SessionState.SignedIn && session.status == SessionStatus.TEMPORARILY_UNVERIFIED ->
            R.string.session_unverified to listOf(R.string.retry to onRetry)
        session is SessionState.SignedIn && session.status == SessionStatus.UNCONFIRMED ->
            R.string.session_unconfirmed to listOf(R.string.retry to onRetry)
        session is SessionState.SignedOut && session.notice == SessionNotice.SESSION_ENDED ->
            R.string.session_ended to listOf(R.string.account_login to { onDismiss(); onLoginClick() }, R.string.dismiss to onDismiss)
        session is SessionState.SignedOut && session.notice == SessionNotice.LOGGED_OUT_SERVER_UNREACHABLE ->
            R.string.session_logged_out_offline to listOf(R.string.dismiss to onDismiss)
        else -> return
    }
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(message), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.forEach { (label, action) -> TextButton(onClick = action) { Text(stringResource(label)) } }
            }
        }
    }
}
