package com.example.portofit.ui.feature.people

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.portofit.R
import com.example.portofit.data.repository.DataError
import com.example.portofit.ui.feature.experience.ExperienceListSection
import com.example.portofit.ui.feature.experience.ExperienceSortSelector
import com.example.portofit.ui.feature.profile.PublicationsSection
import com.example.portofit.data.repository.FollowError
import com.example.portofit.data.repository.UserSummary
import com.example.portofit.ui.components.ErrorMessage
import com.example.portofit.ui.components.LoadingIndicator
import com.example.portofit.ui.components.errorMessageRes

/** Itens da pesquisa de pessoas, dentro da lista do ecrã de pesquisa (modo "Pessoas"). */
fun LazyListScope.peopleSearchItems(
    state: PeopleSearchUiState,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onPersonClick: (String) -> Unit,
) {
    item(key = "people-search") { PeopleSearchField(state.queryInput, state.showQueryInvalid, onQueryChange, onSubmit) }
    when (val results = state.results) {
        PeopleResults.Idle -> item(key = "people-idle") {
            Text(
                stringResource(R.string.people_idle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PeopleResults.Loading -> item(key = "people-loading") { LoadingIndicator() }
        is PeopleResults.Error -> item(key = "people-error") {
            ErrorMessage(stringResource(peopleErrorMessage(results.error)), onRetry)
        }
        is PeopleResults.Loaded -> {
            if (results.people.isEmpty()) {
                item(key = "people-empty") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.people_empty), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.people_empty_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                item(key = "people-count") {
                    val count = results.totalItems.toInt()
                    Text(pluralStringResource(R.plurals.people_count, count, count), style = MaterialTheme.typography.labelLarge)
                }
                items(results.people, key = { "person-${it.userId}" }) { person -> PersonCard(person) { onPersonClick(person.userId) } }
                item(key = "people-footer") {
                    when {
                        results.isLoadingMore -> LoadingIndicator()
                        results.loadMoreError != null -> ErrorMessage(
                            stringResource(R.string.people_load_more_error) + "\n" + stringResource(peopleErrorMessage(results.loadMoreError)),
                            onRetry = onLoadMore,
                        )
                        results.hasMore -> OutlinedButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.load_more))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PeopleSearchField(value: String, showInvalid: Boolean, onValueChange: (String) -> Unit, onSubmit: () -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = {
        keyboard?.hide()
        onSubmit()
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            label = { Text(stringResource(R.string.people_search_label)) },
            singleLine = true,
            isError = showInvalid,
            supportingText = if (showInvalid) {
                { Text(stringResource(R.string.people_search_invalid)) }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onSearch = { submit() }),
        )
        Button(onClick = submit) { Text(stringResource(R.string.search_action)) }
    }
}

@Composable
private fun PersonCard(person: UserSummary, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("@${person.username}", style = MaterialTheme.typography.titleMedium)
            Text(
                person.description ?: stringResource(R.string.public_profile_no_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun PublicProfileScreen(
    onBack: () -> Unit,
    onEditOwnProfile: () -> Unit,
    onLoginRequired: () -> Unit,
    onOpenList: (userId: String, kind: FollowListKind) -> Unit,
    onOpenExperience: (experienceId: String) -> Unit,
    /** "Enviar mensagem" com sessão; sem sessão, [onLoginRequired] (ao regressar, é preciso um novo toque). */
    onMessage: (userId: String, username: String) -> Unit = { _, _ -> },
    viewModel: PublicProfileViewModel = viewModel(factory = PublicProfileViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val publications by viewModel.publications.collectAsStateWithLifecycle()
    val publicationSort by viewModel.publicationSort.collectAsStateWithLifecycle()
    // Ao voltar de outro perfil, de uma lista ou do login, a relação e as contagens são atualizadas.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResumed() }
    PublicProfileContent(
        state = state,
        onBack = onBack,
        onRetry = viewModel::retry,
        onEditOwnProfile = onEditOwnProfile,
        // Visitante: vai para o login; ao regressar, seguir exige um novo toque.
        onFollowClick = { if (state.followButton == FollowButton.LOGIN_REQUIRED) onLoginRequired() else viewModel.toggleFollow() },
        onRefreshRelation = viewModel::refreshRelation,
        onOpenList = { kind -> state.profile?.let { onOpenList(it.userId, kind) } },
        onMessageClick = { state.profile?.let { if (state.signedIn) onMessage(it.userId, it.username) else onLoginRequired() } },
        publications = PublicationsSection(
            state = publications,
            onOpen = { onOpenExperience(it.id) },
            onRetry = viewModel::retryPublications,
            onLoadMore = viewModel::loadMorePublications,
            sort = publicationSort,
            onSortChange = viewModel::setPublicationSort,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PublicProfileContent(
    state: PublicProfileUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onEditOwnProfile: () -> Unit,
    onFollowClick: () -> Unit,
    onRefreshRelation: () -> Unit,
    onOpenList: (FollowListKind) -> Unit,
    publications: PublicationsSection,
    onMessageClick: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        (state.load as? PublicProfileLoad.Loaded)?.profile?.username?.let { "@$it" } ?: stringResource(R.string.public_profile_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
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
            PublicProfileLoad.Loading -> LoadingIndicator(modifier)
            is PublicProfileLoad.Error -> ErrorMessage(stringResource(peopleErrorMessage(load.error, profile = true)), onRetry, modifier.padding(16.dp))
            is PublicProfileLoad.Loaded -> Column(
                modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Só campos públicos: username, descrição e interesses (nunca email, mesmo no próprio perfil).
                Text("@${load.profile.username}", style = MaterialTheme.typography.headlineSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val followers = load.profile.followersCount.toInt()
                    TextButton(onClick = { onOpenList(FollowListKind.FOLLOWERS) }) {
                        Text(pluralStringResource(R.plurals.followers_count, followers, followers))
                    }
                    TextButton(onClick = { onOpenList(FollowListKind.FOLLOWING) }) {
                        Text(stringResource(R.string.following_count, load.profile.followingCount.toInt()))
                    }
                }
                if (state.isOwnProfile) {
                    OutlinedButton(onClick = onEditOwnProfile) { Text(stringResource(R.string.public_profile_edit_own)) }
                }
                FollowSection(state, onFollowClick, onRefreshRelation)
                if (!state.isSelf) {
                    OutlinedButton(onClick = onMessageClick) { Text(stringResource(R.string.public_profile_message)) }
                }
                Text(
                    load.profile.description ?: stringResource(R.string.public_profile_no_description),
                    style = MaterialTheme.typography.bodyLarge,
                )
                HorizontalDivider()
                Text(stringResource(R.string.profile_interests), style = MaterialTheme.typography.titleMedium)
                val labels = state.interestLabels
                if (labels.isEmpty()) {
                    Text(stringResource(R.string.public_profile_no_interests), style = MaterialTheme.typography.bodyMedium)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        labels.forEach { AssistChip(onClick = {}, label = { Text(it.name) }, enabled = false) }
                    }
                    if (labels.any { !it.fromCatalog }) {
                        Text(
                            stringResource(R.string.profile_interests_fallback_names),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider()
                // Publicações (experiences-service): uma falha só afeta esta secção.
                Text(stringResource(R.string.profile_posts), style = MaterialTheme.typography.titleMedium)
                ExperienceSortSelector(publications.sort, publications.onSortChange)
                ExperienceListSection(
                    state = publications.state,
                    showPlace = true,
                    emptyText = R.string.experiences_empty_profile,
                    onOpen = publications.onOpen,
                    onAuthorClick = null,
                    onRetry = publications.onRetry,
                    onLoadMore = publications.onLoadMore,
                )
            }
        }
    }
}

/** Seguir / Deixar de seguir conforme a relação confirmada pelo servidor; nada no próprio perfil. */
@Composable
private fun FollowSection(state: PublicProfileUiState, onFollowClick: () -> Unit, onRefreshRelation: () -> Unit) {
    val inProgress = state.followAction == FollowAction.InProgress
    when (state.followButton) {
        FollowButton.HIDDEN -> return
        FollowButton.UNCONFIRMED -> {
            Column {
                Text(stringResource(R.string.follow_relation_unconfirmed), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onRefreshRelation) { Text(stringResource(R.string.retry)) }
            }
            return
        }
        FollowButton.UNFOLLOW -> OutlinedButton(onClick = onFollowClick, enabled = !inProgress) {
            Text(stringResource(R.string.follow_unfollow))
        }
        FollowButton.FOLLOW, FollowButton.LOGIN_REQUIRED -> Button(onClick = onFollowClick, enabled = !inProgress) {
            Text(stringResource(R.string.follow_follow))
        }
    }
    if (state.followButton == FollowButton.LOGIN_REQUIRED) {
        Text(
            stringResource(R.string.follow_login_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    when (val action = state.followAction) {
        FollowAction.InProgress -> LinearProgressIndicator(Modifier.fillMaxWidth())
        is FollowAction.Failed -> Text(
            stringResource(followErrorMessage(action.error)),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
        FollowAction.ResultUnconfirmed -> Column {
            Text(
                stringResource(R.string.follow_result_unconfirmed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onRefreshRelation) { Text(stringResource(R.string.follow_refresh)) }
        }
        FollowAction.Idle -> Unit
    }
}

@Composable
fun FollowListScreen(
    onBack: () -> Unit,
    onPersonClick: (String) -> Unit,
    viewModel: FollowListViewModel = viewModel(factory = FollowListViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    FollowListContent(viewModel.kind, state, onBack, viewModel::retry, viewModel::loadMore, onPersonClick)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowListContent(
    kind: FollowListKind,
    state: FollowListState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onPersonClick: (String) -> Unit,
) {
    val followers = kind == FollowListKind.FOLLOWERS
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (followers) R.string.follow_list_followers else R.string.follow_list_following)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.navigate_back))
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
            when (state) {
                FollowListState.Loading -> item(key = "loading") { LoadingIndicator() }
                is FollowListState.Error -> item(key = "error") {
                    ErrorMessage(stringResource(peopleErrorMessage(state.error, profile = true)), onRetry)
                }
                is FollowListState.Loaded -> {
                    if (state.people.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                stringResource(if (followers) R.string.follow_list_followers_empty else R.string.follow_list_following_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(state.people, key = { "follow-${it.userId}" }) { person -> PersonCard(person) { onPersonClick(person.userId) } }
                    item(key = "footer") {
                        when {
                            state.isLoadingMore -> LoadingIndicator()
                            state.loadMoreError != null -> ErrorMessage(
                                stringResource(R.string.people_load_more_error) + "\n" + stringResource(peopleErrorMessage(state.loadMoreError)),
                                onRetry = onLoadMore,
                            )
                            state.hasMore -> OutlinedButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.load_more))
                            }
                        }
                    }
                }
            }
        }
    }
}

@StringRes
private fun followErrorMessage(error: FollowError): Int = when (error) {
    FollowError.NETWORK -> R.string.follow_error_network
    FollowError.SERVER -> R.string.error_auth_server
    FollowError.SESSION_ENDED -> R.string.follow_error_session_ended
    FollowError.SESSION_UNVERIFIED -> R.string.profile_error_session_unverified
    FollowError.NOT_FOUND -> R.string.public_profile_not_found
    FollowError.CANNOT_FOLLOW_SELF -> R.string.follow_error_self
    FollowError.NOT_CONFIGURED -> R.string.error_auth_not_configured
    FollowError.UNEXPECTED -> R.string.error_unexpected
}

@StringRes
private fun peopleErrorMessage(error: DataError, profile: Boolean = false): Int = when (error) {
    DataError.NETWORK -> R.string.people_error_network
    DataError.NOT_FOUND -> if (profile) R.string.public_profile_not_found else R.string.error_unexpected
    else -> errorMessageRes(error)
}
