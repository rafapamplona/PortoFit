package com.example.portofit.ui.feature.experience

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.example.portofit.R
import com.example.portofit.data.model.Comment
import com.example.portofit.data.model.ExperienceRules
import com.example.portofit.data.model.ExperienceTextProblem
import com.example.portofit.data.repository.CommentRejection
import com.example.portofit.data.repository.LikeError
import com.example.portofit.data.repository.MutationError
import com.example.portofit.ui.components.LoadingIndicator

// ------------------------------------------------------------------ Gosto

/**
 * Contagens e botão de gosto. O estado mostrado é sempre o último confirmado pelo servidor; durante o pedido o
 * botão fica desativado. Sem sessão, o botão leva ao login (ao regressar é preciso um novo toque).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LikeSection(state: ExperienceDetailUiState, onToggle: () -> Unit, onRefresh: () -> Unit, onLogin: () -> Unit) {
    val experience = state.experience ?: return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        InteractionCounts(experience.likesCount, experience.commentsCount)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state.likeButton) {
                LikeButton.LOGIN_REQUIRED -> OutlinedButton(onClick = onLogin) { Text(stringResource(R.string.like_login)) }
                LikeButton.OWN -> Unit
                LikeButton.UNKNOWN -> OutlinedButton(onClick = onRefresh, enabled = !state.refreshing) { Text(stringResource(R.string.like_refresh)) }
                LikeButton.LIKE -> FilledTonalButton(onClick = onToggle, enabled = !state.likePending) { Text(stringResource(R.string.like_action)) }
                LikeButton.UNLIKE -> OutlinedButton(onClick = onToggle, enabled = !state.likePending) { Text(stringResource(R.string.unlike_action)) }
            }
            if (state.likeError == LikeError.UNCONFIRMED && state.likeButton != LikeButton.UNKNOWN) {
                TextButton(onClick = onRefresh, enabled = !state.refreshing) { Text(stringResource(R.string.like_refresh)) }
            }
        }
        when (state.likeButton) {
            LikeButton.OWN -> SmallNote(stringResource(R.string.like_own))
            LikeButton.UNKNOWN -> SmallNote(stringResource(R.string.like_unknown))
            LikeButton.UNLIKE -> SmallNote(stringResource(R.string.like_liked))
            else -> Unit
        }
        if (state.likePending || state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.likeError?.let { error ->
            Text(likeErrorText(error, state.likeRetryAfterSeconds), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (error == LikeError.SESSION_ENDED) OutlinedButton(onClick = onLogin) { Text(stringResource(R.string.publish_login)) }
        }
        if (state.refreshFailed) SmallNote(stringResource(R.string.like_refresh_failed))
    }
}

@Composable
private fun likeErrorText(error: LikeError, retryAfterSeconds: Long?): String = when (error) {
    LikeError.UNCONFIRMED -> stringResource(R.string.like_error_unconfirmed)
    LikeError.CANNOT_LIKE_OWN -> stringResource(R.string.like_error_own)
    LikeError.EXPERIENCE_GONE -> stringResource(R.string.experience_not_found)
    LikeError.TOO_MANY -> retryAfterSeconds?.let { minutesText(R.plurals.like_error_too_many_minutes, it) } ?: stringResource(R.string.like_error_too_many)
    LikeError.SESSION_ENDED -> stringResource(R.string.like_error_session_ended)
    LikeError.SESSION_UNVERIFIED -> stringResource(R.string.profile_error_session_unverified)
    LikeError.NOT_CONFIGURED -> stringResource(R.string.experiences_not_configured)
    LikeError.UNEXPECTED -> stringResource(R.string.error_unexpected)
}

// ------------------------------------------------------------------ Comentários

/**
 * Comentários com estados próprios (carregar, vazio, erro com nova tentativa). Só o autor de cada comentário vê
 * "Editar" e "Eliminar"; eliminar pede confirmação.
 */
@Composable
fun CommentsSection(state: CommentsUiState, signedIn: Boolean, controller: CommentsController, onLogin: () -> Unit) {
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.comment_discard_title)) },
            text = { Text(stringResource(R.string.comment_discard_message)) },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; controller.discardAttempt() }) { Text(stringResource(R.string.publish_discard_confirm)) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.publish_discard_keep)) } },
        )
    }
    if (state.confirmDeleteId != null) {
        AlertDialog(
            onDismissRequest = controller::dismissDelete,
            title = { Text(stringResource(R.string.comment_delete_title)) },
            text = { Text(stringResource(R.string.comment_delete_message)) },
            confirmButton = { TextButton(onClick = controller::confirmDelete) { Text(stringResource(R.string.experience_delete_confirm)) } },
            dismissButton = { TextButton(onClick = controller::dismissDelete) { Text(stringResource(R.string.experience_delete_keep)) } },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.comments_title), style = MaterialTheme.typography.titleMedium)
        when (val list = state.list) {
            CommentListState.Loading -> LoadingIndicator()
            is CommentListState.Error -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.comments_unavailable), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { controller.load() }) { Text(stringResource(R.string.retry)) }
            }
            is CommentListState.Loaded -> {
                if (list.items.isEmpty()) {
                    SmallNote(stringResource(R.string.comments_empty))
                } else {
                    val count = list.totalItems.toInt()
                    Text(pluralStringResource(R.plurals.comments_count, count, count), style = MaterialTheme.typography.labelLarge)
                }
                list.items.forEach { comment ->
                    CommentItem(comment, state, controller)
                    HorizontalDivider()
                }
                when {
                    list.isLoadingMore -> LoadingIndicator()
                    list.loadMoreError != null -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.comments_unavailable), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = controller::loadMore) { Text(stringResource(R.string.retry)) }
                    }
                    list.hasMore -> OutlinedButton(onClick = controller::loadMore, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.load_more)) }
                }
            }
        }
        state.deleteError?.let { error ->
            Text(stringResource(commentErrorMessage(error)), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Composer(state.composer, signedIn, controller, onLogin, onDiscard = { confirmDiscard = true })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommentItem(comment: Comment, state: CommentsUiState, controller: CommentsController) {
    val edit = state.edit?.takeIf { it.commentId == comment.id }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        val date = formatExperienceDate(comment.createdAt) + (comment.editedAt?.let { " · " + stringResource(R.string.comment_edited) } ?: "")
        Text(stringResource(R.string.comment_by, comment.authorUsername, date), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (edit == null) {
            Text(comment.text, style = MaterialTheme.typography.bodyMedium)
            if (controller.canModify(comment)) {
                val deleting = state.deletingId == comment.id
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { controller.startEdit(comment) }, enabled = !deleting && state.edit == null) { Text(stringResource(R.string.comment_edit)) }
                    TextButton(onClick = { controller.requestDelete(comment) }, enabled = !deleting) { Text(stringResource(R.string.comment_delete)) }
                }
                if (deleting) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        } else {
            OutlinedTextField(
                value = edit.text,
                onValueChange = controller::onEditText,
                modifier = Modifier.fillMaxWidth(),
                readOnly = edit.saving,
                isError = edit.showValidation && edit.textProblem != null,
                supportingText = { CounterOrProblem(edit.textProblem.takeIf { edit.showValidation }, edit.textLength) },
            )
            if (edit.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            edit.error?.let { Text(stringResource(commentErrorMessage(it)), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = controller::saveEdit, enabled = !edit.saving) { Text(stringResource(R.string.comment_save)) }
                TextButton(onClick = controller::cancelEdit, enabled = !edit.saving) { Text(stringResource(R.string.comment_cancel)) }
            }
        }
    }
}

@Composable
private fun Composer(composer: CommentComposer, signedIn: Boolean, controller: CommentsController, onLogin: () -> Unit, onDiscard: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = composer.text,
            onValueChange = controller::onTextChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.comment_label)) },
            minLines = 2,
            readOnly = composer.locked,
            isError = composer.showValidation && composer.textProblem != null,
            supportingText = { CounterOrProblem(composer.textProblem.takeIf { composer.showValidation }, composer.textLength) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
        if (composer.sending) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (composer.pendingAttempt && !composer.sending) {
            Text(stringResource(R.string.comment_unconfirmed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        composer.message?.let { message ->
            val error = message is CommentMessage.Rejected || message == CommentMessage.AttemptTooOld
            Text(
                commentMessageText(message),
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (!signedIn) {
            SmallNote(stringResource(R.string.comment_login_required))
            OutlinedButton(onClick = onLogin) { Text(stringResource(R.string.publish_login)) }
        } else {
            val sessionEnded = (composer.message as? CommentMessage.Rejected)?.reason == CommentRejection.SESSION_ENDED
            if (sessionEnded) OutlinedButton(onClick = onLogin) { Text(stringResource(R.string.publish_login)) }
            Button(onClick = controller::submit, enabled = !composer.sending) {
                Text(
                    stringResource(
                        when {
                            composer.sending -> R.string.comment_sending
                            composer.pendingAttempt -> R.string.publish_retry
                            else -> R.string.comment_post
                        },
                    ),
                )
            }
            if (composer.pendingAttempt && !composer.sending) {
                TextButton(onClick = onDiscard) { Text(stringResource(R.string.comment_discard)) }
            }
        }
    }
}

@Composable
private fun CounterOrProblem(problem: ExperienceTextProblem?, length: Int) {
    Text(
        when (problem) {
            ExperienceTextProblem.EMPTY -> stringResource(R.string.comment_text_empty)
            ExperienceTextProblem.TOO_LONG -> stringResource(R.string.comment_text_too_long)
            ExperienceTextProblem.INVALID_CHARACTERS -> stringResource(R.string.publish_text_invalid)
            null -> stringResource(R.string.comment_counter, length, ExperienceRules.MAX_COMMENT_LENGTH)
        },
    )
}

@Composable
private fun commentMessageText(message: CommentMessage): String = when (message) {
    CommentMessage.Posted -> stringResource(R.string.comment_posted)
    CommentMessage.AlreadyPosted -> stringResource(R.string.comment_already)
    CommentMessage.PostedThenDeleted -> stringResource(R.string.comment_posted_then_deleted)
    CommentMessage.AttemptTooOld -> stringResource(R.string.comment_attempt_too_old)
    is CommentMessage.Rejected -> when (message.reason) {
        CommentRejection.VALIDATION -> stringResource(R.string.comment_error_validation)
        CommentRejection.EXPERIENCE_GONE -> stringResource(R.string.experience_not_found)
        CommentRejection.TOO_MANY -> message.retryAfterSeconds?.let { minutesText(R.plurals.comment_error_too_many_minutes, it) }
            ?: stringResource(R.string.comment_error_too_many)
        CommentRejection.SESSION_ENDED -> stringResource(R.string.comment_error_session_ended)
        CommentRejection.SESSION_UNVERIFIED -> stringResource(R.string.profile_error_session_unverified)
        CommentRejection.NOT_CONFIGURED -> stringResource(R.string.experiences_not_configured)
        CommentRejection.UNEXPECTED -> stringResource(R.string.error_unexpected)
    }
}

@StringRes
private fun commentErrorMessage(error: MutationError): Int = when (error) {
    MutationError.NETWORK -> R.string.comment_error_network
    MutationError.SERVER -> R.string.experience_error_server
    MutationError.SESSION_ENDED -> R.string.comment_error_session_ended
    MutationError.SESSION_UNVERIFIED -> R.string.profile_error_session_unverified
    MutationError.FORBIDDEN -> R.string.comment_error_forbidden
    MutationError.NOT_FOUND -> R.string.comment_gone
    MutationError.VALIDATION -> R.string.comment_error_validation
    MutationError.NOT_CONFIGURED -> R.string.experiences_not_configured
    MutationError.MEDIA_NOT_USABLE, MutationError.UNEXPECTED -> R.string.error_unexpected
}

@Composable
private fun minutesText(plural: Int, seconds: Long): String {
    val minutes = ((seconds + 59) / 60).toInt().coerceAtLeast(1)
    return pluralStringResource(plural, minutes, minutes)
}

@Composable
private fun SmallNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
