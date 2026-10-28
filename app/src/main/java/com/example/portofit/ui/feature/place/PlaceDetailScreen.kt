package com.example.portofit.ui.feature.place

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.portofit.R
import com.example.portofit.data.model.ExperienceSort
import com.example.portofit.data.model.Offering
import com.example.portofit.data.model.PlaceDetail
import com.example.portofit.ui.components.ErrorMessage
import com.example.portofit.ui.components.ExternalLinks
import com.example.portofit.ui.components.InfoLabel
import com.example.portofit.ui.components.LoadingIndicator
import com.example.portofit.ui.components.RatingText
import com.example.portofit.ui.components.SectionHeader
import com.example.portofit.ui.components.errorMessageRes
import com.example.portofit.ui.components.levelLabelRes
import com.example.portofit.ui.feature.experience.ExperienceListSection
import com.example.portofit.ui.feature.experience.ExperienceListState
import com.example.portofit.ui.feature.experience.ExperienceSortSelector

/** Ações do ecrã do local, para a navegação decidir o destino. */
class PlaceDetailActions(
    val onBack: () -> Unit,
    /** Com sessão abre o formulário; sem sessão abre o login (e regressa aqui, sem publicar). */
    val onPublish: (place: PlaceDetail, offering: Offering, signedIn: Boolean) -> Unit,
    val onOpenExperience: (experienceId: String) -> Unit,
    val onOpenAuthor: (userId: String) -> Unit,
)

/** Secção de experiências do local (pode falhar sem afetar os dados do catálogo). */
data class ExperiencesSectionState(
    val list: ExperienceListState,
    val sort: ExperienceSort,
    val signedIn: Boolean,
    val ratingRefreshFailed: Boolean,
)

@Composable
fun PlaceDetailScreen(
    actions: PlaceDetailActions,
    viewModel: PlaceDetailViewModel = viewModel(factory = PlaceDetailViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val experiences by viewModel.experiences.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val signedIn by viewModel.signedIn.collectAsStateWithLifecycle()
    val ratingRefreshFailed by viewModel.ratingRefreshFailed.collectAsStateWithLifecycle()
    PlaceDetailContent(
        state = state,
        experiences = ExperiencesSectionState(experiences, sort, signedIn, ratingRefreshFailed),
        actions = actions,
        onRetry = viewModel::retry,
        onSortChange = viewModel::setSort,
        onRetryExperiences = viewModel::retryExperiences,
        onLoadMoreExperiences = viewModel::loadMoreExperiences,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceDetailContent(
    state: PlaceDetailUiState,
    experiences: ExperiencesSectionState,
    actions: PlaceDetailActions,
    onRetry: () -> Unit,
    onSortChange: (ExperienceSort) -> Unit,
    onRetryExperiences: () -> Unit,
    onLoadMoreExperiences: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        (state as? PlaceDetailUiState.Loaded)?.place?.name ?: stringResource(R.string.place_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.navigate_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        val padding = PaddingValues(
            start = innerPadding.calculateStartPadding(layoutDirection) + 16.dp,
            end = innerPadding.calculateEndPadding(layoutDirection) + 16.dp,
            top = innerPadding.calculateTopPadding() + 8.dp,
            bottom = innerPadding.calculateBottomPadding() + 16.dp,
        )
        when (state) {
            PlaceDetailUiState.Loading -> LoadingIndicator(Modifier.padding(padding))
            is PlaceDetailUiState.Error -> ErrorMessage(
                stringResource(errorMessageRes(state.error)),
                onRetry,
                Modifier.padding(padding),
            )
            is PlaceDetailUiState.Loaded ->
                PlaceDetailBody(state.place, padding, experiences, actions, onSortChange, onRetryExperiences, onLoadMoreExperiences)
        }
    }
}

@Composable
private fun PlaceDetailBody(
    place: PlaceDetail,
    padding: PaddingValues,
    experiences: ExperiencesSectionState,
    actions: PlaceDetailActions,
    onSortChange: (ExperienceSort) -> Unit,
    onRetryExperiences: () -> Unit,
    onLoadMoreExperiences: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(place.name, style = MaterialTheme.typography.headlineSmall)
                if (place.isDemo) Badge(stringResource(R.string.place_demo_label), error = false)
                if (place.isRetired) {
                    Text(stringResource(R.string.place_retired_notice), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                RatingText(place.rating, MaterialTheme.typography.titleSmall)
                if (experiences.ratingRefreshFailed) {
                    Text(
                        stringResource(R.string.place_rating_refresh_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                place.description?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            }
        }
        item(key = "actions") { ExternalActions(place) }
        item(key = "address") {
            Section(stringResource(R.string.section_address)) {
                Text(place.address.street)
                // Sem código postal (ponto de encontro de treino), só a cidade.
                Text(place.address.postalCode?.let { stringResource(R.string.postal_code_city, it, place.address.city) } ?: place.address.city)
                place.address.parish?.let { Text(stringResource(R.string.parish, it)) }
            }
        }
        if (place.phone != null || place.website != null) {
            item(key = "contacts") {
                Section(stringResource(R.string.section_contacts)) {
                    place.phone?.let { Text(stringResource(R.string.phone, it)) }
                    place.website?.let { Text(stringResource(R.string.website, it)) }
                }
            }
        }
        if (place.sports.isNotEmpty()) {
            item(key = "sports") {
                Section(stringResource(R.string.section_sports)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        place.sports.forEach { InfoLabel(it.name) }
                    }
                }
            }
        }
        item(key = "offerings-title") {
            SectionHeader(stringResource(R.string.section_offerings), Modifier.padding(top = 8.dp))
        }
        if (place.offerings.isEmpty()) {
            item(key = "offerings-empty") { Text(stringResource(R.string.offerings_empty)) }
        }
        if (!experiences.signedIn && place.offerings.any { it.acceptsExperiences }) {
            item(key = "publish-hint") {
                Text(
                    stringResource(R.string.offering_publish_login),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(place.offerings, key = Offering::id) { offering ->
            OfferingCard(offering, onPublish = { actions.onPublish(place, offering, experiences.signedIn) })
        }
        item(key = "experiences") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                SectionHeader(stringResource(R.string.section_experiences))
                ExperienceSortSelector(experiences.sort, onSortChange)
                ExperienceListSection(
                    state = experiences.list,
                    showPlace = false,
                    emptyText = R.string.experiences_empty_place,
                    onOpen = { actions.onOpenExperience(it.id) },
                    onAuthorClick = { actions.onOpenAuthor(it.authorId) },
                    onRetry = onRetryExperiences,
                    onLoadMore = onLoadMoreExperiences,
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionHeader(title)
        content()
    }
}

/**
 * Abrir o site, o marcador (sem ligar) ou uma app de mapas. Só aparecem as ações com dados válidos; sem nenhuma app
 * compatível, fica uma mensagem por baixo, sem sair do ecrã.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExternalActions(place: PlaceDetail) {
    val context = LocalContext.current
    val website = ExternalLinks.website(place.website)
    val phone = ExternalLinks.dialNumber(place.phone)
    val geo = ExternalLinks.geoUri(place.location, place.name)
    if (website == null && phone == null && geo == null) return
    var missingApp by rememberSaveable(place.id) { mutableStateOf<Int?>(null) }
    fun open(intent: Intent, missing: Int) {
        missingApp = try {
            context.startActivity(intent)
            null
        } catch (_: ActivityNotFoundException) {
            missing
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            geo?.let {
                FilledTonalButton(onClick = { open(Intent(Intent.ACTION_VIEW, it.toUri()), R.string.place_action_no_maps) }) {
                    Text(stringResource(R.string.place_action_map))
                }
            }
            phone?.let {
                OutlinedButton(onClick = { open(Intent(Intent.ACTION_DIAL, ExternalLinks.telUri(it).toUri()), R.string.place_action_no_dialer) }) {
                    Text(stringResource(R.string.place_action_call))
                }
            }
            website?.let {
                OutlinedButton(onClick = { open(Intent(Intent.ACTION_VIEW, it.toUri()), R.string.place_action_no_browser) }) {
                    Text(stringResource(R.string.place_action_website))
                }
            }
        }
        missingApp?.let {
            Text(
                stringResource(it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun Badge(text: String, error: Boolean) {
    Surface(
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun OfferingCard(offering: Offering, onPublish: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(offering.name, style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.offering_sport_level, offering.sport.name, stringResource(levelLabelRes(offering.level))),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            offering.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            offering.scheduleNotes?.let {
                Text(
                    stringResource(R.string.schedule_notes, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RatingText(offering.rating, MaterialTheme.typography.bodySmall)
            if (offering.acceptsExperiences) {
                OutlinedButton(onClick = onPublish) { Text(stringResource(R.string.offering_publish)) }
            } else {
                // Atividade (ou local) retirada: continua legível, mas não está disponível.
                Badge(stringResource(R.string.offering_retired), error = true)
            }
        }
    }
}
