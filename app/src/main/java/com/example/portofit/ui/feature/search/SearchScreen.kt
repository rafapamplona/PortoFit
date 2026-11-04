package com.example.portofit.ui.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.portofit.R
import com.example.portofit.data.model.PlaceSummary
import com.example.portofit.data.model.Sport
import com.example.portofit.ui.components.EmptyMessage
import com.example.portofit.ui.components.ErrorMessage
import com.example.portofit.ui.components.InfoLabel
import com.example.portofit.ui.components.LoadingIndicator
import com.example.portofit.ui.components.RatingText
import com.example.portofit.ui.components.errorMessageRes
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
    onExperienceClick: (experienceId: String) -> Unit = {},
    onCollectionsClick: () -> Unit = {},
    onMessagesClick: () -> Unit = {},
    onNotificationsClick: () -> Unit = {},
    viewModel: SearchViewModel = viewModel(factory = SearchViewModel.Factory),
    peopleViewModel: PeopleSearchViewModel = viewModel(factory = PeopleSearchViewModel.Factory),
    experienceViewModel: ExperienceSearchViewModel = viewModel(factory = ExperienceSearchViewModel.Factory),
    accountViewModel: AccountViewModel = viewModel(factory = AccountViewModel.Factory),
) {
    // Só recolhe o estado enquanto o ecrã está visível (STARTED).
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val people by peopleViewModel.uiState.collectAsStateWithLifecycle()
    val experienceSearch by experienceViewModel.uiState.collectAsStateWithLifecycle()
    val experienceResults by experienceViewModel.results.collectAsStateWithLifecycle()
    val experienceSort by experienceViewModel.sort.collectAsStateWithLifecycle()
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
        nearbyActions = NearbyActions(
            onUseLocation = viewModel::onUseLocation,
            onPermissionDenied = viewModel::onLocationPermissionDenied,
            onRadiusSelected = viewModel::onRadiusSelected,
            onDisable = viewModel::onDisableNearby,
        ),
        accountActions = { AccountAction(session, onLoginClick, onProfileClick, accountViewModel::logout, onCollectionsClick, onMessagesClick, onNotificationsClick) },
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
        experienceItems = {
            experienceSearchItems(
                state = experienceSearch,
                results = experienceResults,
                sort = experienceSort,
                actions = ExperienceSearchActions(
                    onQueryChange = experienceViewModel::onQueryInputChange,
                    onSubmit = experienceViewModel::onSubmit,
                    onSortChange = experienceViewModel::setSort,
                    onRetry = experienceViewModel::retry,
                    onLoadMore = experienceViewModel::loadMore,
                    onOpen = onExperienceClick,
                    onAuthorClick = onPersonClick,
                ),
            )
        },
    )
}

/** Ações da proximidade (o painel trata o pedido de permissão). */
data class NearbyActions(
    val onUseLocation: () -> Unit = {},
    val onPermissionDenied: (blocked: Boolean) -> Unit = {},
    val onRadiusSelected: (Int) -> Unit = {},
    val onDisable: () -> Unit = {},
)

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
    nearbyActions: NearbyActions = NearbyActions(),
    accountActions: @Composable RowScope.() -> Unit = {},
    sessionBanner: @Composable () -> Unit = {},
    onModeChange: (SearchMode) -> Unit = {},
    peopleItems: LazyListScope.() -> Unit = {},
    experienceItems: LazyListScope.() -> Unit = {},
) {
    // Cada modo guarda a sua posição: alternar entre eles não desloca a lista dos outros.
    val placesList = rememberLazyListState()
    val experiencesList = rememberLazyListState()
    val peopleList = rememberLazyListState()
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
            state = when (state.mode) {
                SearchMode.PLACES -> placesList
                SearchMode.EXPERIENCES -> experiencesList
                SearchMode.PEOPLE -> peopleList
            },
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
            if (state.mode == SearchMode.EXPERIENCES) {
                experienceItems()
                return@LazyColumn
            }
            item(key = "search") {
                SearchField(state.queryInput, state.showQueryTooShort, onQueryChange, onSubmit)
            }
            item(key = "sports") {
                SportFilter(state.sports, state.selectedSportId, onSportSelected, onRetrySports)
            }
            item(key = "nearby") {
                NearbyPanel(
                    nearby = state.nearby,
                    radiusKm = state.radiusKm,
                    onUseLocation = nearbyActions.onUseLocation,
                    onPermissionDenied = nearbyActions.onPermissionDenied,
                    onRadiusSelected = nearbyActions.onRadiusSelected,
                    onDisable = nearbyActions.onDisable,
                )
            }
            when (val results = state.results) {
                ResultsState.Loading -> item(key = "loading") { LoadingIndicator() }
                is ResultsState.Error -> item(key = "error") {
                    ErrorMessage(stringResource(errorMessageRes(results.error)), onRetrySearch)
                }
                is ResultsState.Loaded -> loadedResults(
                    results = results,
                    nearbyRadiusKm = (state.nearby as? NearbyState.Active)?.filter?.radiusKm,
                    emptyContext = EmptyContext(state.activeQuery, state.selectedSportId?.let { id -> (state.sports as? SportsState.Loaded)?.sports?.find { it.id == id }?.name ?: "" }),
                    onPlaceClick = onPlaceClick,
                    onLoadMore = onLoadMore,
                )
            }
        }
    }
}

/**
 * Escolha entre pesquisar locais, experiências e pessoas (cada modo mantém o seu estado). As opções passam para a linha
 * seguinte quando não cabem, por exemplo com texto ampliado.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModeSelector(mode: SearchMode, onModeChange: (SearchMode) -> Unit) {
    val options = listOf(
        SearchMode.PLACES to R.string.search_mode_places,
        SearchMode.EXPERIENCES to R.string.search_mode_experiences,
        SearchMode.PEOPLE to R.string.search_mode_people,
    )
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (option, label) ->
            FilterChip(selected = mode == option, onClick = { onModeChange(option) }, label = { Text(stringResource(label)) })
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

/**
 * O que estava aplicado quando não houve resultados: [query] (texto submetido) e [sportName] (modalidade escolhida;
 * vazio se o nome ainda não é conhecido), para distinguir "nada corresponde" de "ainda não há locais".
 */
private data class EmptyContext(val query: String?, val sportName: String?)

/** [nearbyRadiusKm]: raio da pesquisa por proximidade em curso (null sem proximidade). */
private fun androidx.compose.foundation.lazy.LazyListScope.loadedResults(
    results: ResultsState.Loaded,
    nearbyRadiusKm: Int?,
    emptyContext: EmptyContext,
    onPlaceClick: (String) -> Unit,
    onLoadMore: () -> Unit,
) {
    if (results.places.isEmpty()) {
        item(key = "empty") { EmptyResults(nearbyRadiusKm, emptyContext) }
        return
    }

    item(key = "count") {
        val count = results.totalItems.toInt()
        Column {
            Text(
                pluralStringResource(R.plurals.results_count, count, count),
                style = MaterialTheme.typography.labelLarge,
            )
            if (nearbyRadiusKm != null) {
                Text(
                    stringResource(R.string.nearby_sorted),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
private fun EmptyResults(nearbyRadiusKm: Int?, context: EmptyContext) {
    val sport = context.sportName?.ifBlank { stringResource(R.string.results_empty_sport_fallback) }
    when {
        // Proximidade: o raio é o que mais provavelmente explica a lista vazia.
        nearbyRadiusKm != null -> EmptyMessage(stringResource(R.string.results_empty), stringResource(R.string.nearby_empty_hint, nearbyRadiusKm))
        // Texto submetido sem correspondência (com ou sem modalidade).
        context.query != null -> EmptyMessage(
            if (sport != null) stringResource(R.string.results_empty_query_sport, context.query, sport) else stringResource(R.string.results_empty_query, context.query),
            stringResource(R.string.results_empty_query_hint),
        )
        // Só a modalidade: o catálogo ainda não tem locais dela.
        sport != null -> EmptyMessage(stringResource(R.string.results_empty_sport, sport), stringResource(R.string.results_empty_sport_hint))
        else -> EmptyMessage(stringResource(R.string.results_empty_catalog), hint = null)
    }
}

/** Cartão de um local: nome; modalidades em etiquetas; localização (e distância); avaliação. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaceCard(place: PlaceSummary, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(place.name, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (place.sports.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    place.sports.forEach { InfoLabel(it.name) }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    place.parish?.let { stringResource(R.string.place_location, it, place.city) } ?: place.city,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                place.distanceKm?.let { km ->
                    val distance = formatDistance(km)
                    val description = stringResource(R.string.nearby_distance_description, distance)
                    Text(
                        stringResource(R.string.nearby_distance, distance),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.semantics { contentDescription = description },
                    )
                }
            }
            RatingText(place.rating, MaterialTheme.typography.bodyMedium)
        }
    }
}
