package com.example.portofit.ui.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.portofit.R
import com.example.portofit.data.model.PlaceSummary
import com.example.portofit.data.model.Sport
import com.example.portofit.ui.components.ErrorMessage
import com.example.portofit.ui.components.LoadingIndicator
import com.example.portofit.ui.components.errorMessageRes
import com.example.portofit.ui.components.ratingText
import com.example.portofit.ui.feature.auth.AccountAction
import com.example.portofit.ui.feature.auth.AccountViewModel
import com.example.portofit.ui.feature.auth.SessionBanner
import com.example.portofit.ui.feature.people.PeopleSearchViewModel
import com.example.portofit.ui.feature.people.peopleSearchItems

@Composable
fun SearchScreen(
    onPlaceClick: (placeId: String) -> Unit,
    onLoginClick: () -> Unit,
    onProfileClick: () -> Unit,
    onPersonClick: (userId: String) -> Unit,
    onCollectionsClick: () -> Unit = {},
    onMessagesClick: () -> Unit = {},
    viewModel: SearchViewModel = viewModel(factory = SearchViewModel.Factory),
    peopleViewModel: PeopleSearchViewModel = viewModel(factory = PeopleSearchViewModel.Factory),
    accountViewModel: AccountViewModel = viewModel(factory = AccountViewModel.Factory),
) {
    // Só recolhe o estado enquanto o ecrã está visível (STARTED).
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val people by peopleViewModel.uiState.collectAsStateWithLifecycle()
    val session by accountViewModel.sessionState.collectAsStateWithLifecycle()
    SearchContent(
        state = state,
        onQueryChange = viewModel::onQueryInputChange,
        onSubmit = viewModel::onSearchSubmit,
        onSportSelected = viewModel::onSportSelected,
        onRetrySports = viewModel::retrySports,
        onRetrySearch = viewModel::retrySearch,
        onLoadMore = viewModel::loadMore,
        onPlaceClick = onPlaceClick,
        accountActions = { AccountAction(session, onLoginClick, onProfileClick, accountViewModel::logout, onCollectionsClick, onMessagesClick) },
        sessionBanner = { SessionBanner(session, onLoginClick, accountViewModel::retry, accountViewModel::dismissNotice) },
        onModeChange = viewModel::onModeChange,
        peopleItems = {
            peopleSearchItems(
                state = people,
                onQueryChange = peopleViewModel::onQueryInputChange,
                onSubmit = peopleViewModel::onSubmit,
                onRetry = peopleViewModel::retry,
                onLoadMore = peopleViewModel::loadMore,
                onPersonClick = onPersonClick,
            )
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchContent(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onSportSelected: (String?) -> Unit,
    onRetrySports: () -> Unit,
    onRetrySearch: () -> Unit,
    onLoadMore: () -> Unit,
    onPlaceClick: (String) -> Unit,
    accountActions: @Composable RowScope.() -> Unit = {},
    sessionBanner: @Composable () -> Unit = {},
    onModeChange: (SearchMode) -> Unit = {},
    peopleItems: LazyListScope.() -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.app_name))
                        Text(
                            stringResource(R.string.app_tagline),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = accountActions,
            )
        },
    ) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = innerPadding.calculateStartPadding(layoutDirection) + 16.dp,
                end = innerPadding.calculateEndPadding(layoutDirection) + 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "session") { sessionBanner() }
            item(key = "mode") { ModeSelector(state.mode, onModeChange) }
            if (state.mode == SearchMode.PEOPLE) {
                peopleItems()
                return@LazyColumn
            }
            item(key = "search") {
                SearchField(state.queryInput, state.showQueryTooShort, onQueryChange, onSubmit)
            }
            item(key = "sports") {
                SportFilter(state.sports, state.selectedSportId, onSportSelected, onRetrySports)
            }
            when (val results = state.results) {
                ResultsState.Loading -> item(key = "loading") { LoadingIndicator() }
                is ResultsState.Error -> item(key = "error") {
                    ErrorMessage(stringResource(errorMessageRes(results.error)), onRetrySearch)
                }
                is ResultsState.Loaded -> loadedResults(results, onPlaceClick, onLoadMore)
            }
        }
    }
}

/** Escolha entre pesquisar locais e pessoas (cada modo mantém o seu estado). */
@Composable
private fun ModeSelector(mode: SearchMode, onModeChange: (SearchMode) -> Unit) {
    val options = listOf(SearchMode.PLACES to R.string.search_mode_places, SearchMode.PEOPLE to R.string.search_mode_people)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (option, label) ->
            SegmentedButton(
                selected = mode == option,
                onClick = { onModeChange(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) { Text(stringResource(label)) }
        }
    }
}

@Composable
private fun SearchField(
    value: String,
    showTooShort: Boolean,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
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
            label = { Text(stringResource(R.string.search_label)) },
            placeholder = { Text(stringResource(R.string.search_placeholder)) },
            singleLine = true,
            isError = showTooShort,
            supportingText = if (showTooShort) {
                { Text(stringResource(R.string.search_too_short)) }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit() }),
        )
        Button(onClick = submit) {
            Text(stringResource(R.string.search_action))
        }
    }
}

@Composable
private fun SportFilter(
    sports: SportsState,
    selectedSportId: String?,
    onSportSelected: (String?) -> Unit,
    onRetry: () -> Unit,
) {
    when (sports) {
        SportsState.Loading -> Text(
            stringResource(R.string.sports_loading),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SportsState.Error -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.sports_error),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
        is SportsState.Loaded -> LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "all") {
                FilterChip(
                    selected = selectedSportId == null,
                    onClick = { onSportSelected(null) },
                    label = { Text(stringResource(R.string.sports_all)) },
                )
            }
            items(sports.sports, key = Sport::id) { sport ->
                FilterChip(
                    selected = sport.id == selectedSportId,
                    onClick = { onSportSelected(sport.id) },
                    label = { Text(sport.name) },
                )
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.loadedResults(
    results: ResultsState.Loaded,
    onPlaceClick: (String) -> Unit,
    onLoadMore: () -> Unit,
) {
    if (results.places.isEmpty()) {
        item(key = "empty") {
            Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.results_empty), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.results_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    item(key = "count") {
        val count = results.totalItems.toInt()
        Text(
            pluralStringResource(R.plurals.results_count, count, count),
            style = MaterialTheme.typography.labelLarge,
        )
    }
    // Um único aviso para a lista; os nomes dos locais fictícios já trazem "(fictício)".
    if (results.places.any { it.isDemo }) {
        item(key = "demo") {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                Text(
                    stringResource(R.string.demo_notice),
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
    items(results.places, key = PlaceSummary::id) { place ->
        PlaceCard(place, onClick = { onPlaceClick(place.id) })
    }
    item(key = "footer") {
        when {
            results.isLoadingMore -> LoadingIndicator()
            results.loadMoreError != null -> ErrorMessage(
                stringResource(R.string.load_more_error) + "\n" + stringResource(errorMessageRes(results.loadMoreError)),
                onRetry = onLoadMore,
            )
            results.hasMore -> OutlinedButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.load_more))
            }
        }
    }
}

@Composable
private fun PlaceCard(place: PlaceSummary, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(place.name, style = MaterialTheme.typography.titleMedium)
            Text(
                place.parish ?: place.city,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (place.sports.isNotEmpty()) {
                Text(
                    place.sports.joinToString(" · ") { it.name },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(ratingText(place.rating), style = MaterialTheme.typography.bodySmall)
        }
    }
}
