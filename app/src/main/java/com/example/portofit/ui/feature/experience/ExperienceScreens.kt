package com.example.portofit.ui.feature.experience

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.portofit.R
import com.example.portofit.data.model.CatalogStatus
import com.example.portofit.data.model.ExperienceRules
import com.example.portofit.data.model.ExperienceTextProblem
import com.example.portofit.data.repository.MutationError
import com.example.portofit.data.repository.PublishRejection
import com.example.portofit.ui.components.ErrorMessage
import com.example.portofit.ui.components.LoadingIndicator

// ------------------------------------------------------------------ Publicar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PublishExperienceScreen(
    onBack: () -> Unit,
    onLoginRequired: () -> Unit,
    onPublished: (experienceId: String) -> Unit,
    onOpenExperience: (experienceId: String) -> Unit,
    viewModel: PublishExperienceViewModel = viewModel(factory = PublishExperienceViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.publishedId) { state.publishedId?.let(onPublished) }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.publish_discard_title)) },
            text = { Text(stringResource(R.string.publish_discard_message)) },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; viewModel.discardAttempt() }) { Text(stringResource(R.string.publish_discard_confirm)) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.publish_discard_keep)) } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.publish_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.navigate_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            Modifier.fillMaxSize().padding(innerPadding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.experience_place_offering, state.placeName, state.offeringName), style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = state.text,
                onValueChange = viewModel::onTextChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.publish_text_label)) },
                minLines = 4,
                readOnly = state.contentLocked,
                isError = state.showValidation && state.textProblem != null,
                supportingText = {
                    val problem = state.textProblem.takeIf { state.showValidation }
                    Text(
                        if (problem != null) stringResource(textProblemMessage(problem))
                        else stringResource(R.string.publish_text_counter, state.textLength, ExperienceRules.MAX_TEXT_LENGTH),
                    )
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )

            Text(stringResource(R.string.publish_stars_label), style = MaterialTheme.typography.titleSmall)
            StarsPicker(state.stars, enabled = !state.contentLocked, onSelect = viewModel::onStarsChange)
            if (state.showValidation && state.starsMissing) {
                Text(stringResource(R.string.publish_stars_missing), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            // Fotografia opcional: bloqueada durante o envio e numa tentativa por confirmar.
            PhotoDraftSection(
                photo = state.photo,
                error = state.photoError,
                previewFile = viewModel.photos.previewFile(state.photo),
                enabled = !state.contentLocked,
                actions = PhotoActions(
                    onPicked = viewModel::onPicked,
                    onStartCapture = viewModel::startCapture,
                    onCaptureResult = viewModel::onCaptureResult,
                    onCaptureUnavailable = viewModel::onCaptureUnavailable,
                    onRemove = viewModel::removePhoto,
                    onDismissError = viewModel::dismissPhotoError,
                ),
            )
            if (state.pendingAttempt && state.pendingHasPhoto) {
                Text(stringResource(R.string.publish_photo_already_sent), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (state.sending) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    stringResource(if (state.sendPhase == SendPhase.UPLOADING_PHOTO) R.string.publish_uploading_photo else R.string.publish_sending),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (state.pendingAttempt && !state.sending) {
                Notice(stringResource(R.string.publish_unconfirmed), error = true)
            }
            state.message?.let { message ->
                when (message) {
                    is PublishMessage.Rejected -> Notice(rejectionText(message), error = true)
                    is PublishMessage.UploadFailed -> {
                        // A experiência não foi criada: só o envio da fotografia falhou.
                        Notice(stringResource(R.string.publish_upload_failed) + " " + stringResource(uploadErrorMessage(message.error)), error = true)
                        TextButton(onClick = viewModel::removePhoto) { Text(stringResource(R.string.publish_without_photo)) }
                    }
                    is PublishMessage.AlreadyPublished -> {
                        Notice(stringResource(R.string.publish_already), error = false)
                        message.experienceId?.let { id -> OutlinedButton(onClick = { onOpenExperience(id) }) { Text(stringResource(R.string.publish_open_existing)) } }
                    }
                    PublishMessage.PublishedThenDeleted -> Notice(stringResource(R.string.publish_deleted), error = false)
                    PublishMessage.AttemptTooOld -> Notice(stringResource(R.string.publish_attempt_too_old), error = true)
                }
            }

            if (!state.signedIn) {
                Text(stringResource(R.string.publish_login_required), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onLoginRequired) { Text(stringResource(R.string.publish_login)) }
            } else {
                val sessionEnded = (state.message as? PublishMessage.Rejected)?.reason == PublishRejection.SESSION_ENDED
                if (sessionEnded) OutlinedButton(onClick = onLoginRequired) { Text(stringResource(R.string.publish_login)) }
                Button(onClick = viewModel::submit, enabled = state.canSubmit, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(
                            when {
                                state.sending -> R.string.publish_sending
                                state.pendingAttempt -> R.string.publish_retry
                                else -> R.string.publish_action
                            },
                        ),
                    )
                }
                if (state.pendingAttempt && !state.sending) {
                    TextButton(onClick = { confirmDiscard = true }) { Text(stringResource(R.string.publish_discard)) }
                }
            }
        }
    }
}

@Composable
private fun rejectionText(message: PublishMessage.Rejected): String = when (message.reason) {
    PublishRejection.TOO_MANY -> message.retryAfterSeconds?.let { seconds ->
        val minutes = ((seconds + 59) / 60).toInt().coerceAtLeast(1)
        pluralStringResource(R.plurals.publish_error_too_many_minutes, minutes, minutes)
    } ?: stringResource(R.string.publish_error_too_many)
    else -> stringResource(rejectionMessage(message.reason))
}

@StringRes
private fun rejectionMessage(reason: PublishRejection): Int = when (reason) {
    PublishRejection.VALIDATION -> R.string.publish_error_validation
    PublishRejection.UNKNOWN_OFFERING -> R.string.publish_error_unknown_offering
    PublishRejection.OFFERING_MISMATCH -> R.string.publish_error_mismatch
    PublishRejection.OFFERING_RETIRED -> R.string.publish_error_retired
    PublishRejection.CATALOG_UNAVAILABLE -> R.string.publish_error_catalog
    PublishRejection.MEDIA_NOT_USABLE -> R.string.publish_error_media_not_usable
    PublishRejection.TOO_MANY -> R.string.publish_error_too_many
    PublishRejection.SESSION_ENDED -> R.string.publish_error_session_ended
    PublishRejection.SESSION_UNVERIFIED -> R.string.profile_error_session_unverified
    PublishRejection.NOT_CONFIGURED -> R.string.experiences_not_configured
    PublishRejection.UNEXPECTED -> R.string.error_unexpected
}

@StringRes
private fun textProblemMessage(problem: ExperienceTextProblem): Int = when (problem) {
    ExperienceTextProblem.EMPTY -> R.string.publish_text_empty
    ExperienceTextProblem.TOO_LONG -> R.string.publish_text_too_long
    ExperienceTextProblem.INVALID_CHARACTERS -> R.string.publish_text_invalid
}

/** 1 a 5 estrelas, como escolhas exclusivas (com descrição para leitores de ecrã). */
@Composable
private fun StarsPicker(selected: Int?, enabled: Boolean, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (value in 1..5) {
            val description = pluralStringResource(R.plurals.experience_stars_description, value, value)
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                enabled = enabled,
                label = { Text("$value★") },
                modifier = Modifier.semantics { contentDescription = description },
            )
        }
    }
}

@Composable
private fun Notice(text: String, error: Boolean) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
}

// ------------------------------------------------------------------ Detalhe

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExperienceDetailScreen(
    onBack: () -> Unit,
    onOpenAuthor: (userId: String) -> Unit,
    onOpenPlace: (placeId: String) -> Unit,
    onLoginRequired: () -> Unit,
    viewModel: ExperienceDetailViewModel = viewModel(factory = ExperienceDetailViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val comments by viewModel.comments.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }

    if (state.showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = { Text(stringResource(R.string.experience_delete_title)) },
            text = { Text(stringResource(R.string.experience_delete_message)) },
            confirmButton = { TextButton(onClick = viewModel::confirmDelete) { Text(stringResource(R.string.experience_delete_confirm)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissDelete) { Text(stringResource(R.string.experience_delete_keep)) } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.experience_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.navigate_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        val modifier = Modifier.fillMaxSize().padding(innerPadding)
        when (val load = state.load) {
            ExperienceLoad.Loading -> LoadingIndicator(modifier)
            is ExperienceLoad.Error -> ErrorMessage(stringResource(experiencesErrorMessage(load.error)), viewModel::retry, modifier.padding(16.dp))
            is ExperienceLoad.Loaded -> {
                val experience = load.experience
                Column(
                    modifier.imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(experience.placeName, style = MaterialTheme.typography.headlineSmall)
                    if (experience.placeDemo) {
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                            Text(
                                stringResource(R.string.experience_demo_place),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                    Text(stringResource(R.string.experience_place_offering, experience.offeringName, experience.sportName), color = MaterialTheme.colorScheme.primary)
                    if (state.offeringStatus == CatalogStatus.RETIRED) {
                        Text(stringResource(R.string.experience_offering_retired), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { onOpenPlace(experience.placeId) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                        Text(stringResource(R.string.experience_open_place))
                    }
                    HorizontalDivider()
                    TextButton(onClick = { onOpenAuthor(experience.authorId) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                        Text(stringResource(R.string.experience_author, experience.authorUsername), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        stringResource(R.string.experience_published_on, formatExperienceDate(experience.createdAt)) +
                            (experience.editedAt?.let { " · " + stringResource(R.string.experience_edited, formatExperienceDate(it)) } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    val edit = state.edit
                    if (edit != null && state.isAuthor) {
                        EditForm(state, edit, viewModel)
                    } else {
                        experience.photo?.let { ExperiencePhotoView(it) }
                        StarsText(experience.stars)
                        Text(experience.text, style = MaterialTheme.typography.bodyLarge)
                    }

                    if (state.saving || state.deleting) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.actionError?.let { error ->
                        Text(stringResource(mutationErrorMessage(error)), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        if (error == MutationError.SESSION_ENDED) OutlinedButton(onClick = onLoginRequired) { Text(stringResource(R.string.publish_login)) }
                    }

                    if (state.isAuthor && edit == null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = viewModel::startEdit, enabled = !state.deleting) { Text(stringResource(R.string.experience_edit)) }
                            OutlinedButton(onClick = viewModel::requestDelete, enabled = !state.deleting) { Text(stringResource(R.string.experience_delete)) }
                        }
                    }

                    // Gostos e comentários (E3): estados próprios; uma falha aqui não esconde o texto nem a fotografia.
                    HorizontalDivider()
                    LikeSection(state, onToggle = viewModel::toggleLike, onRefresh = viewModel::refreshLike, onLogin = onLoginRequired)
                    HorizontalDivider()
                    CommentsSection(comments, state.signedIn, viewModel.comments, onLogin = onLoginRequired)
                }
            }
        }
    }
}

@Composable
private fun EditForm(state: ExperienceDetailUiState, edit: ExperienceEdit, viewModel: ExperienceDetailViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.experience_offering_not_editable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = edit.text,
            onValueChange = viewModel::onEditText,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.publish_text_label)) },
            minLines = 4,
            readOnly = state.saving,
            isError = edit.showValidation && edit.textProblem != null,
            supportingText = {
                val problem = edit.textProblem.takeIf { edit.showValidation }
                Text(
                    if (problem != null) stringResource(textProblemMessage(problem))
                    else stringResource(R.string.publish_text_counter, edit.textLength, ExperienceRules.MAX_TEXT_LENGTH),
                )
            },
        )
        StarsPicker(edit.stars, enabled = !state.saving && !state.uploadingPhoto, onSelect = viewModel::onEditStars)
        val locked = state.saving || state.uploadingPhoto
        PhotoDraftSection(
            photo = state.editPhoto,
            error = state.editPhotoError,
            previewFile = viewModel.photos.previewFile(state.editPhoto),
            enabled = !locked,
            actions = PhotoActions(
                onPicked = viewModel::onPicked,
                onStartCapture = viewModel::startCapture,
                onCaptureResult = viewModel::onCaptureResult,
                onCaptureUnavailable = viewModel::onCaptureUnavailable,
                onRemove = viewModel::removePhoto,
                onDismissError = viewModel::dismissPhotoError,
            ),
            current = state.experience?.photo,
            removingCurrent = edit.removePhoto,
            onKeepCurrent = viewModel::keepCurrentPhoto,
        )
        if (state.uploadingPhoto) Text(stringResource(R.string.publish_uploading_photo), style = MaterialTheme.typography.bodySmall)
        state.uploadError?.let {
            Text(stringResource(R.string.edit_upload_failed) + " " + stringResource(uploadErrorMessage(it)), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::saveEdit, enabled = !state.saving) { Text(stringResource(R.string.experience_save)) }
            TextButton(onClick = viewModel::cancelEdit, enabled = !state.saving) { Text(stringResource(R.string.experience_cancel)) }
        }
    }
}

@StringRes
private fun mutationErrorMessage(error: MutationError): Int = when (error) {
    MutationError.NETWORK -> R.string.experience_error_network
    MutationError.SERVER -> R.string.experience_error_server
    MutationError.SESSION_ENDED -> R.string.experience_error_session_ended
    MutationError.SESSION_UNVERIFIED -> R.string.profile_error_session_unverified
    MutationError.FORBIDDEN -> R.string.experience_error_forbidden
    MutationError.NOT_FOUND -> R.string.experience_not_found
    MutationError.VALIDATION -> R.string.publish_error_validation
    MutationError.MEDIA_NOT_USABLE -> R.string.edit_error_media_not_usable
    MutationError.NOT_CONFIGURED -> R.string.experiences_not_configured
    MutationError.UNEXPECTED -> R.string.error_unexpected
}
