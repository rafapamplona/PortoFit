package com.example.portofit.ui.feature.place

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.portofit.R
import com.example.portofit.data.model.Offering
import com.example.portofit.data.model.PlaceDetail
import com.example.portofit.ui.components.ErrorMessage
import com.example.portofit.ui.components.LoadingIndicator
import com.example.portofit.ui.components.errorMessageRes
import com.example.portofit.ui.components.levelLabelRes
import com.example.portofit.ui.components.ratingText

@Composable
fun PlaceDetailScreen(
    onBack: () -> Unit,
    viewModel: PlaceDetailViewModel = viewModel(factory = PlaceDetailViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PlaceDetailContent(state, onBack, onRetry = viewModel::retry)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceDetailContent(state: PlaceDetailUiState, onBack: () -> Unit, onRetry: () -> Unit) {
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
                    IconButton(onClick = onBack) {
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
            is PlaceDetailUiState.Loaded -> PlaceDetailBody(state.place, padding)
        }
    }
}

@Composable
private fun PlaceDetailBody(place: PlaceDetail, padding: PaddingValues) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(place.name, style = MaterialTheme.typography.headlineSmall)
                if (place.isDemo) {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                        Text(
                            stringResource(R.string.place_demo_label),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                Text(ratingText(place.rating), style = MaterialTheme.typography.titleSmall)
                place.description?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            }
        }
        item(key = "address") {
            Section(stringResource(R.string.section_address)) {
                Text(place.address.street)
                Text(stringResource(R.string.postal_code_city, place.address.postalCode, place.address.city))
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
                    Text(place.sports.joinToString(" · ") { it.name }, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item(key = "offerings-title") {
            Text(stringResource(R.string.section_offerings), style = MaterialTheme.typography.titleMedium)
        }
        if (place.offerings.isEmpty()) {
            item(key = "offerings-empty") { Text(stringResource(R.string.offerings_empty)) }
        }
        items(place.offerings, key = Offering::id) { OfferingCard(it) }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun OfferingCard(offering: Offering) {
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
            Text(ratingText(offering.rating), style = MaterialTheme.typography.bodySmall)
        }
    }
}
