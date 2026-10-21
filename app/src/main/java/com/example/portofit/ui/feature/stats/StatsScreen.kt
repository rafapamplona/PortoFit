package com.example.portofit.ui.feature.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.portofit.R
import com.example.portofit.data.model.ExperienceStats
import com.example.portofit.data.model.StatsPeriod
import com.example.portofit.data.model.StatsSort
import com.example.portofit.data.model.StatsTotals
import com.example.portofit.data.model.StatsWindow
import com.example.portofit.data.repository.DataError
import com.example.portofit.ui.components.LoadingIndicator
import com.example.portofit.ui.feature.auth.AccountAction
import com.example.portofit.ui.feature.auth.AccountViewModel
import com.example.portofit.ui.feature.experience.StarsText
import com.example.portofit.ui.feature.experience.experiencesErrorMessage
import com.example.portofit.ui.feature.experience.formatExperienceDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class StatsActions(
    val onOpenExperience: (String) -> Unit,
    val onLogin: () -> Unit,
    val onProfile: () -> Unit,
    val onCollections: () -> Unit = {},
    val onMessages: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    actions: StatsActions,
    viewModel: StatsViewModel = viewModel(factory = StatsViewModel.Factory),
    accountViewModel: AccountViewModel = viewModel(factory = AccountViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val session by accountViewModel.sessionState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stats_title)) },
                actions = {
                    if (state.signedIn == true) TextButton(onClick = viewModel::refresh) { Text(stringResource(R.string.refresh)) }
                    AccountAction(session, actions.onLogin, actions.onProfile, accountViewModel::logout, actions.onCollections, actions.onMessages)
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
            when (state.signedIn) {
                null -> item(key = "loading") { LoadingIndicator() }
                false -> item(key = "login") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.stats_login_hint), style = MaterialTheme.typography.bodyLarge)
                        Button(onClick = actions.onLogin) { Text(stringResource(R.string.account_login)) }
                    }
                }
                true -> {
                    item(key = "period") { PeriodSelector(state.period, viewModel::selectPeriod) }
                    item(key = "totals") { TotalsSection(state.totals, viewModel::retryTotals, actions.onLogin) }
                    item(key = "list-header") { ListHeader(state.sort, viewModel::selectSort) }
                    listItems(state.list, viewModel, actions)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeriodSelector(period: StatsPeriod, onSelect: (StatsPeriod) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(StatsPeriod.ALL to R.string.stats_period_all, StatsPeriod.LAST_7_DAYS to R.string.stats_period_7, StatsPeriod.LAST_30_DAYS to R.string.stats_period_30)
                .forEach { (value, label) -> FilterChip(selected = period == value, onClick = { onSelect(value) }, label = { Text(stringResource(label)) }) }
        }
        Note(stringResource(R.string.stats_utc_note))
    }
}

@Composable
private fun TotalsSection(totals: SectionState<StatsTotals>, onRetry: () -> Unit, onLogin: () -> Unit) {
    when (totals) {
        SectionState.Loading -> LoadingIndicator()
        // Uma falha nunca aparece como zeros.
        is SectionState.Error -> Problem(stringResource(errorMessage(totals.error)), stringResource(R.string.retry), onRetry)
        SectionState.SessionEnded -> Problem(stringResource(R.string.stats_session_ended), stringResource(R.string.account_login), onLogin)
        SectionState.SessionUnverified -> Problem(stringResource(R.string.stats_session_unverified), stringResource(R.string.retry), onRetry)
        is SectionState.Loaded -> Totals(totals.value)
    }
}

@Composable
private fun Totals(totals: StatsTotals) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Note(windowText(totals.window))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.stats_received_title), style = MaterialTheme.typography.titleMedium)
                Metric(stringResource(R.string.stats_likes), totals.likes)
                Metric(stringResource(R.string.stats_comments), totals.comments)
                Metric(stringResource(R.string.stats_interactions), totals.interactions)
                Metric(stringResource(R.string.stats_views), totals.views)
                Note(stringResource(R.string.stats_views_note))
                HorizontalDivider()
                Metric(stringResource(R.string.stats_publications), totals.publications)
                if (totals.publications == 0L && totals.interactions == 0L && totals.views == 0L) Note(stringResource(R.string.stats_no_activity))
            }
        }
        // Separado do que foi recebido: são opiniões do autor sobre locais/atividades.
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.stats_stars_title), style = MaterialTheme.typography.titleMedium)
                Note(stringResource(R.string.stats_stars_note))
                StarsHistogram(totals.starsGiven)
            }
        }
    }
}

/** Uma linha por valor (5 a 1), com barra proporcional; cada linha é lida como "4 estrelas de 5: 3 publicações". */
@Composable
private fun StarsHistogram(counts: Map<Int, Long>) {
    val max = counts.values.maxOrNull()?.takeIf { it > 0 } ?: 1L
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (stars in 5 downTo 1) {
            val count = counts[stars] ?: 0L
            // Ex.: "1 estrela de 5: 0 publicações" (cada parte com o seu plural).
            val description = pluralStringResource(R.plurals.experience_stars_description, stars, stars) + ": " +
                pluralStringResource(R.plurals.publications_count, count.toInt(), count.toInt())
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description },
            ) {
                Text("$stars★", modifier = Modifier.width(32.dp), style = MaterialTheme.typography.bodyMedium)
                Box(Modifier.weight(1f).height(12.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    if (count > 0) {
                        Box(Modifier.fillMaxWidth(count.toFloat() / max).height(12.dp).background(MaterialTheme.colorScheme.primary))
                    }
                }
                Text(count.toString(), modifier = Modifier.width(32.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ListHeader(sort: StatsSort, onSelect: (StatsSort) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.stats_per_publication), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(StatsSort.RECENT to R.string.stats_sort_recent, StatsSort.INTERACTIONS to R.string.stats_sort_interactions, StatsSort.VIEWS to R.string.stats_sort_views)
                .forEach { (value, label) -> FilterChip(selected = sort == value, onClick = { onSelect(value) }, label = { Text(stringResource(label)) }) }
        }
    }
}

private fun LazyListScope.listItems(list: SectionState<StatsList>, viewModel: StatsViewModel, actions: StatsActions) {
    when (list) {
        SectionState.Loading -> item(key = "list-loading") { LoadingIndicator() }
        is SectionState.Error -> item(key = "list-error") { Problem(stringResource(errorMessage(list.error)), stringResource(R.string.retry), viewModel::retryList) }
        SectionState.SessionEnded -> item(key = "list-session") { Problem(stringResource(R.string.stats_session_ended), stringResource(R.string.account_login), actions.onLogin) }
        SectionState.SessionUnverified -> item(key = "list-unverified") { Problem(stringResource(R.string.stats_session_unverified), stringResource(R.string.retry), viewModel::retryList) }
        is SectionState.Loaded -> {
            val value = list.value
            // O intervalo desta lista, tal como o servidor o devolveu (pode ser pedido noutro instante dos totais).
            item(key = "list-window") { Note(windowText(value.window)) }
            if (value.items.isEmpty()) item(key = "list-empty") { Note(stringResource(R.string.stats_no_publications)) }
            items(value.items, key = { it.experienceId }) { row -> StatsRow(row) { actions.onOpenExperience(row.experienceId) } }
            item(key = "list-footer") {
                when {
                    value.windowChanged -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.stats_window_changed), style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = viewModel::retryList) { Text(stringResource(R.string.discover_restart)) }
                    }
                    value.isLoadingMore -> LoadingIndicator()
                    value.loadMoreError != null -> Problem(stringResource(errorMessage(value.loadMoreError)), stringResource(R.string.retry), viewModel::loadMore)
                    value.hasMore -> OutlinedButton(onClick = viewModel::loadMore, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.load_more)) }
                }
            }
        }
    }
}

@Composable
private fun StatsRow(row: ExperienceStats, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.experience_place_offering, row.placeName, row.offeringName), style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StarsText(row.stars)
                Note(formatExperienceDate(row.createdAt))
            }
            val metrics = listOf(
                pluralStringResource(R.plurals.likes_count, row.likes.toInt(), row.likes.toInt()),
                pluralStringResource(R.plurals.comments_count, row.comments.toInt(), row.comments.toInt()),
                pluralStringResource(R.plurals.views_count, row.views.toInt(), row.views.toInt()),
            )
            Text(metrics.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Metric(label: String, value: Long) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value.toString(), style = MaterialTheme.typography.titleMedium)
    }
}

/** Intervalo do servidor em UTC (nunca recalculado com o relógio do telemóvel). */
@Composable
private fun windowText(window: StatsWindow): String {
    val to = UTC_DATE_TIME.format(window.to)
    return window.from?.let { stringResource(R.string.stats_window, UTC_DATE.format(it), to) } ?: stringResource(R.string.stats_window_all, to)
}

private val UTC_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneOffset.UTC)
private val UTC_DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneOffset.UTC)

private fun errorMessage(error: DataError): Int =
    if (error == DataError.NOT_CONFIGURED) experiencesErrorMessage(error) else R.string.stats_unavailable

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Problem(message: String, action: String, onAction: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = onAction) { Text(action) }
    }
}
