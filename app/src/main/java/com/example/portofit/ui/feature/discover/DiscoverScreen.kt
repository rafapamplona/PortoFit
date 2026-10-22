package com.example.portofit.ui.feature.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.portofit.R
import com.example.portofit.data.model.FeedFallbackReason
import com.example.portofit.data.model.FeedItem
import com.example.portofit.data.model.FeedMatch
import com.example.portofit.data.model.FeedMode
import com.example.portofit.ui.components.LoadingIndicator
import com.example.portofit.ui.feature.auth.AccountAction
import com.example.portofit.ui.feature.auth.AccountViewModel
import com.example.portofit.ui.feature.experience.ExperienceCard
import com.example.portofit.ui.feature.experience.experiencesErrorMessage

/** Ações de navegação de Descobrir (detalhes empilhados por cima do destino). */
class DiscoverActions(
    val onOpenExperience: (String) -> Unit,
    val onOpenAuthor: (String) -> Unit,
    val onLogin: () -> Unit,
    val onProfile: () -> Unit,
    /** "Escolher interesses": o meu perfil. */
    val onEditInterests: () -> Unit,
    /** "Procurar pessoas": o destino Pesquisar. */
    val onFindPeople: () -> Unit,
    val onCollections: () -> Unit = {},
    val onMessages: () -> Unit = {},
    val onNotifications: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    actions: DiscoverActions,
    viewModel: DiscoverViewModel = viewModel(factory = DiscoverViewModel.Factory),
    accountViewModel: AccountViewModel = viewModel(factory = AccountViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val session by accountViewModel.sessionState.collectAsStateWithLifecycle()
    // Guardada com o destino: ao voltar a Descobrir, a posição mantém-se.
    val listState = rememberLazyListState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.discover_title)) },
                actions = {
                    TextButton(onClick = viewModel::refresh) { Text(stringResource(R.string.refresh)) }
                    AccountAction(session, actions.onLogin, actions.onProfile, accountViewModel::logout, actions.onCollections, actions.onMessages, actions.onNotifications)
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "header") { Header(state, viewModel, actions) }
            feedItems(state, viewModel, actions)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun Header(state: DiscoverUiState, viewModel: DiscoverViewModel, actions: DiscoverActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state.signedIn) {
            true -> {
                val tabs = listOf(DiscoverTab.FOR_YOU to R.string.discover_tab_for_you, DiscoverTab.POPULAR to R.string.discover_tab_popular)
                PrimaryTabRow(selectedTabIndex = tabs.indexOfFirst { it.first == state.tab }) {
                    tabs.forEach { (tab, label) ->
                        Tab(selected = state.tab == tab, onClick = { viewModel.selectTab(tab) }, text = { Text(stringResource(label)) })
                    }
                }
            }
            false -> {
                Text(stringResource(R.string.discover_login_hint), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = actions.onLogin) { Text(stringResource(R.string.account_login)) }
            }
            null -> Unit
        }
        when (val sports = state.sports) {
            SportsState.Loading -> Unit
            SportsState.Failed -> {
                Text(stringResource(R.string.discover_sports_failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = viewModel::retrySports) { Text(stringResource(R.string.retry)) }
            }
            is SportsState.Loaded -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = state.sportId == null, onClick = { viewModel.selectSport(null) }, label = { Text(stringResource(R.string.discover_filter_all)) })
                sports.sports.forEach { sport ->
                    FilterChip(selected = state.sportId == sport.id, onClick = { viewModel.selectSport(sport.id) }, label = { Text(sport.name) })
                }
            }
        }
        (state.feed as? FeedListState.Loaded)?.let { ModeBanner(it, state, viewModel, actions) }
    }
}

/** Explica o que a lista é; um resultado popular nunca é apresentado como "Para ti". */
@Composable
private fun ModeBanner(feed: FeedListState.Loaded, state: DiscoverUiState, viewModel: DiscoverViewModel, actions: DiscoverActions) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
            feed.mode == FeedMode.PERSONAL -> Note(stringResource(R.string.discover_personal_banner))
            feed.fallbackReason == FeedFallbackReason.NO_PREFERENCES -> Notice {
                Text(stringResource(R.string.discover_no_preferences), style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = actions.onEditInterests) { Text(stringResource(R.string.discover_edit_interests)) }
                    OutlinedButton(onClick = actions.onFindPeople) { Text(stringResource(R.string.discover_find_people)) }
                }
            }
            feed.fallbackReason == FeedFallbackReason.NO_MATCHES -> Notice {
                Text(
                    stringResource(if (state.sportId != null) R.string.discover_no_matches_filter else R.string.discover_no_matches),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            feed.mode == FeedMode.POPULAR_FALLBACK -> Notice {
                Text(stringResource(R.string.discover_fallback), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = viewModel::refresh) { Text(stringResource(R.string.retry)) }
            }
            else -> Unit
        }
        if (feed.mode != FeedMode.PERSONAL) Note(stringResource(R.string.discover_popular_banner))
        // Modo de demonstração: as experiências sobre locais fictícios continuam assinaladas.
        if (feed.items.any { it.experience.placeDemo }) Note(stringResource(R.string.discover_demo_notice))
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.feedItems(state: DiscoverUiState, viewModel: DiscoverViewModel, actions: DiscoverActions) {
    when (val feed = state.feed) {
        FeedListState.Loading -> item(key = "loading") { LoadingIndicator() }
        is FeedListState.Error -> item(key = "error") {
            Problem(stringResource(experiencesErrorMessage(feed.error)), stringResource(R.string.retry), viewModel::refresh)
        }
        FeedListState.SessionEnded -> item(key = "session-ended") {
            Problem(stringResource(R.string.discover_session_ended), stringResource(R.string.account_login), actions.onLogin)
        }
        FeedListState.SessionUnverified -> item(key = "session-unverified") {
            Problem(stringResource(R.string.discover_session_unverified), stringResource(R.string.retry), viewModel::refresh)
        }
        is FeedListState.Loaded -> {
            if (feed.items.isEmpty()) {
                item(key = "empty") { Note(stringResource(if (state.sportId != null) R.string.discover_empty_filter else R.string.discover_empty)) }
            }
            items(feed.items, key = { it.experience.id }) { item -> FeedEntry(item, actions) }
            item(key = "footer") {
                when {
                    feed.modeChanged -> Notice {
                        Text(stringResource(R.string.discover_mode_changed), style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = viewModel::refresh) { Text(stringResource(R.string.discover_restart)) }
                    }
                    feed.isLoadingMore -> LoadingIndicator()
                    feed.loadMoreError != null -> Problem(stringResource(experiencesErrorMessage(feed.loadMoreError)), stringResource(R.string.retry), viewModel::loadMore)
                    feed.hasMore -> OutlinedButton(onClick = viewModel::loadMore, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.load_more)) }
                }
            }
        }
    }
}

@Composable
private fun FeedEntry(item: FeedItem, actions: DiscoverActions) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val reasons = item.matchedBy.sortedBy { it.ordinal }.map { match ->
            when (match) {
                FeedMatch.FOLLOWING -> stringResource(R.string.discover_matched_following, item.experience.authorUsername)
                FeedMatch.INTEREST -> stringResource(R.string.discover_matched_interest, item.experience.sportName)
            }
        }
        if (reasons.isNotEmpty()) Note(reasons.joinToString(" · "))
        ExperienceCard(
            item.experience,
            showPlace = true,
            onClick = { actions.onOpenExperience(item.experience.id) },
            onAuthorClick = { actions.onOpenAuthor(item.experience.authorId) },
        )
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Notice(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    }
}

@Composable
private fun Problem(message: String, action: String, onAction: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = onAction) { Text(action) }
    }
}
