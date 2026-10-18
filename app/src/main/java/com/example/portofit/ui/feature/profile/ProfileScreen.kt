package com.example.portofit.ui.feature.profile

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.portofit.R
import com.example.portofit.data.repository.ProfileError
import com.example.portofit.ui.components.ErrorMessage
import com.example.portofit.ui.components.LoadingIndicator

@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    viewModel: ProfileViewModel = viewModel(factory = ProfileViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val exit = { if (viewModel.requestExit()) onBack() }
    // Com alterações por guardar, o gesto/botão "voltar" do sistema pede confirmação.
    BackHandler(enabled = state.hasUnsavedChanges) { exit() }

    if (state.showDiscardConfirmation) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDiscardConfirmation,
            title = { Text(stringResource(R.string.profile_discard_title)) },
            text = { Text(stringResource(R.string.profile_discard_message)) },
            confirmButton = {
                TextButton(onClick = { viewModel.discardChanges(); onBack() }) { Text(stringResource(R.string.profile_discard_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDiscardConfirmation) { Text(stringResource(R.string.profile_discard_keep)) }
            },
        )
    }

    ProfileContent(
        state = state,
        onBack = exit,
        onRetryLoad = viewModel::retryLoad,
        onRetrySports = viewModel::retrySports,
        onDescriptionChange = viewModel::onDescriptionChange,
        onSaveDescription = viewModel::saveDescription,
        onInterestToggle = viewModel::onInterestToggle,
        onSaveInterests = viewModel::saveInterests,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProfileContent(
    state: ProfileUiState,
    onBack: () -> Unit,
    onRetryLoad: () -> Unit,
    onRetrySports: () -> Unit,
    onDescriptionChange: (String) -> Unit,
    onSaveDescription: () -> Unit,
    onInterestToggle: (String) -> Unit,
    onSaveInterests: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profile_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.navigate_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        val profile = state.profile
        val modifier = Modifier.fillMaxSize().padding(innerPadding)
        when {
            profile == null && state.load is ProfileLoad.Failed ->
                ErrorMessage(stringResource(profileErrorMessage(state.load.error)), onRetryLoad, modifier.padding(16.dp))
            profile == null -> LoadingIndicator(modifier)
            else -> Column(
                modifier = modifier
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("@${profile.username}", style = MaterialTheme.typography.headlineSmall)
                if (state.load is ProfileLoad.Failed) {
                    Text(stringResource(profileErrorMessage(state.load.error)), color = MaterialTheme.colorScheme.error)
                }

                // --- Descrição ---
                SectionTitle(R.string.profile_description)
                OutlinedTextField(
                    value = state.description,
                    onValueChange = onDescriptionChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.profile_description_label)) },
                    minLines = 3,
                    isError = state.descriptionTooLong,
                    supportingText = {
                        Text(
                            if (state.descriptionTooLong) {
                                stringResource(R.string.profile_description_too_long)
                            } else {
                                stringResource(R.string.profile_description_counter, state.descriptionLength, ProfileUiState.MAX_DESCRIPTION)
                            },
                        )
                    },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                SaveRow(
                    label = R.string.profile_save_description,
                    enabled = state.canSaveDescription,
                    save = state.descriptionSave,
                    savedMessage = R.string.profile_description_saved,
                    onSave = onSaveDescription,
                )

                HorizontalDivider()

                // --- Interesses ---
                SectionTitle(R.string.profile_interests)
                when (state.sports) {
                    SportsLoad.Loading -> Text(stringResource(R.string.sports_loading), style = MaterialTheme.typography.bodySmall)
                    SportsLoad.Failed -> Column {
                        Text(
                            stringResource(R.string.profile_sports_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onRetrySports) { Text(stringResource(R.string.retry)) }
                    }
                    is SportsLoad.Loaded -> Unit
                }
                val options = state.interestOptions
                if (options.isEmpty()) {
                    Text(stringResource(R.string.profile_interests_empty), style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(
                        stringResource(R.string.profile_interests_counter, state.interests.size, ProfileUiState.MAX_INTERESTS),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        options.forEach { option ->
                            FilterChip(
                                selected = option.selected,
                                onClick = { onInterestToggle(option.id) },
                                enabled = option.enabled,
                                label = { Text(option.name) },
                            )
                        }
                    }
                    if (options.any { !it.nameFromCatalog }) {
                        Text(
                            stringResource(R.string.profile_interests_fallback_names),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                SaveRow(
                    label = R.string.profile_save_interests,
                    enabled = state.canSaveInterests,
                    save = state.interestsSave,
                    savedMessage = R.string.profile_interests_saved,
                    onSave = onSaveInterests,
                )

                HorizontalDivider()

                // --- Dados privados ---
                SectionTitle(R.string.profile_private)
                Text(stringResource(R.string.profile_email, profile.email))
                Text(
                    stringResource(R.string.profile_private_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider()

                // --- Publicações (ainda não integradas: não é mostrada uma lista vazia) ---
                SectionTitle(R.string.profile_posts)
                Text(
                    stringResource(R.string.profile_posts_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(@StringRes text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.titleMedium)
}

/** Botão de gravação com o estado (a guardar, guardado, erro) e sem envios duplicados. */
@Composable
private fun SaveRow(@StringRes label: Int, enabled: Boolean, save: SaveState, @StringRes savedMessage: Int, onSave: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onSave, enabled = enabled) { Text(stringResource(label)) }
            if (save == SaveState.Saving) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        when (save) {
            SaveState.Saved -> Text(stringResource(savedMessage), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            is SaveState.Failed -> Text(stringResource(profileErrorMessage(save.error)), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            else -> Unit
        }
    }
}

@StringRes
fun profileErrorMessage(error: ProfileError): Int = when (error) {
    ProfileError.NETWORK -> R.string.profile_error_network
    ProfileError.SERVER -> R.string.error_auth_server
    ProfileError.SESSION_ENDED -> R.string.profile_error_session_ended
    ProfileError.SESSION_UNVERIFIED -> R.string.profile_error_session_unverified
    ProfileError.CATALOG_UNAVAILABLE -> R.string.profile_error_catalog_unavailable
    ProfileError.UNKNOWN_INTERESTS -> R.string.profile_error_unknown_interests
    ProfileError.VALIDATION -> R.string.profile_error_validation
    ProfileError.NOT_CONFIGURED -> R.string.error_auth_not_configured
    ProfileError.UNEXPECTED -> R.string.error_unexpected
}
