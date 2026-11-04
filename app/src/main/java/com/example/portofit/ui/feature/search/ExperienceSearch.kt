package com.example.portofit.ui.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.portofit.PortoFitApplication
import com.example.portofit.R
import com.example.portofit.data.model.Experience
import com.example.portofit.data.model.ExperienceSort
import com.example.portofit.data.repository.ExperienceChanges
import com.example.portofit.data.repository.ExperiencesRepository
import com.example.portofit.ui.components.EmptyMessage
import com.example.portofit.ui.components.ErrorMessage
import com.example.portofit.ui.components.LoadingIndicator
import com.example.portofit.ui.feature.experience.ExperienceCard
import com.example.portofit.ui.feature.experience.ExperienceListLoader
import com.example.portofit.ui.feature.experience.ExperienceListState
import com.example.portofit.ui.feature.experience.ExperienceSortSelector
import com.example.portofit.ui.feature.experience.experiencesErrorMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ExperienceSearchUiState(
    /** Texto no campo (ainda não submetido). */
    val queryInput: String = "",
    /** Pesquisa aplicada aos resultados; null = ainda não houve pesquisa. */
    val activeQuery: String? = null,
    val showQueryInvalid: Boolean = false,
)

/**
 * Pesquisa pública de experiências por texto (docs/experiences-api.md §4.2): submissão explícita, 2 a 100 caracteres,
 * ordenação e "Carregar mais". Sem texto não há pedido: não mostra "todas as experiências". Estado próprio, separado das
 * pesquisas de locais e de pessoas, que sobrevive à recriação e à navegação para o detalhe.
 *
 * As respostas de pesquisas anteriores são ignoradas pelo [ExperienceListLoader]. As contagens confirmadas no detalhe
 * atualizam o item sem recarregar; uma publicação, edição ou eliminação recarrega a pesquisa, porque o texto ou a
 * própria experiência podem já não corresponder.
 */
class ExperienceSearchViewModel(
    repository: ExperiencesRepository,
    changes: ExperienceChanges,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ExperienceSearchUiState(queryInput = savedState[KEY_INPUT] ?: "", activeQuery = savedState[KEY_QUERY]),
    )
    val uiState: StateFlow<ExperienceSearchUiState> = _uiState.asStateFlow()

    private val list = ExperienceListLoader(
        viewModelScope,
        repository,
        initialSort = savedState.get<String>(KEY_SORT)?.let { name -> ExperienceSort.entries.firstOrNull { it.name == name } } ?: ExperienceSort.RECENT,
        counts = changes.counts,
    )
    val results: StateFlow<ExperienceListState> = list.state
    val sort: StateFlow<ExperienceSort> = list.sort

    init {
        _uiState.value.activeQuery?.let(list::setQuery)
        viewModelScope.launch {
            changes.events.collect { list.load() }
        }
    }

    fun onQueryInputChange(text: String) {
        val limited = text.takeCodePoints(MAX_QUERY)
        savedState[KEY_INPUT] = limited
        _uiState.update { it.copy(queryInput = limited, showQueryInvalid = false) }
    }

    fun onSubmit() {
        val query = _uiState.value.queryInput.trim()
        if (query.codePointCount(0, query.length) !in MIN_QUERY..MAX_QUERY) {
            _uiState.update { it.copy(showQueryInvalid = true) }
            return
        }
        savedState[KEY_QUERY] = query
        _uiState.update { it.copy(activeQuery = query, showQueryInvalid = false) }
        list.setQuery(query)
    }

    fun setSort(sort: ExperienceSort) {
        savedState[KEY_SORT] = sort.name
        list.setSort(sort)
    }

    fun retry() = list.load()

    fun loadMore() = list.loadMore()

    private fun String.takeCodePoints(max: Int): String =
        if (codePointCount(0, length) <= max) this else substring(0, offsetByCodePoints(0, max))

    companion object {
        const val MIN_QUERY = 2
        const val MAX_QUERY = 100
        const val KEY_INPUT = "experienceSearch.input"
        const val KEY_QUERY = "experienceSearch.query"
        const val KEY_SORT = "experienceSearch.sort"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PortoFitApplication
                ExperienceSearchViewModel(app.container.experiencesRepository, app.container.experienceChanges, createSavedStateHandle())
            }
        }
    }
}

/** Ações da pesquisa de experiências. */
class ExperienceSearchActions(
    val onQueryChange: (String) -> Unit,
    val onSubmit: () -> Unit,
    val onSortChange: (ExperienceSort) -> Unit,
    val onRetry: () -> Unit,
    val onLoadMore: () -> Unit,
    val onOpen: (experienceId: String) -> Unit,
    val onAuthorClick: (userId: String) -> Unit,
)

/** Itens da pesquisa de experiências, dentro da lista do ecrã de pesquisa (modo "Experiências"). */
fun LazyListScope.experienceSearchItems(
    state: ExperienceSearchUiState,
    results: ExperienceListState,
    sort: ExperienceSort,
    actions: ExperienceSearchActions,
) {
    item(key = "experience-search") { ExperienceSearchField(state, actions) }
    val query = state.activeQuery
    if (query == null) {
        item(key = "experience-idle") {
            Text(
                stringResource(R.string.experience_search_idle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    item(key = "experience-sort") { ExperienceSortSelector(sort, actions.onSortChange) }
    when (results) {
        ExperienceListState.Loading -> item(key = "experience-loading") { LoadingIndicator() }
        is ExperienceListState.Error -> item(key = "experience-error") {
            ErrorMessage(stringResource(experiencesErrorMessage(results.error)), actions.onRetry)
        }
        is ExperienceListState.Loaded -> loadedExperiences(results, query, actions)
    }
}

private fun LazyListScope.loadedExperiences(results: ExperienceListState.Loaded, query: String, actions: ExperienceSearchActions) {
    if (results.items.isEmpty()) {
        item(key = "experience-empty") {
            EmptyMessage(stringResource(R.string.experience_search_empty, query), stringResource(R.string.experience_search_empty_hint))
        }
        return
    }
    item(key = "experience-count") {
        val count = results.totalItems.toInt()
        Text(pluralStringResource(R.plurals.experiences_count, count, count), style = MaterialTheme.typography.labelLarge)
    }
    // A pesquisa não exclui publicações antigas sobre locais fictícios: fica claro que o local não é real.
    if (results.items.any { it.placeDemo }) {
        item(key = "experience-demo") {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                Text(
                    stringResource(R.string.discover_demo_notice),
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
    items(results.items, key = { "experience-${it.id}" }) { experience: Experience ->
        ExperienceCard(
            experience,
            showPlace = true,
            onClick = { actions.onOpen(experience.id) },
            onAuthorClick = { actions.onAuthorClick(experience.authorId) },
        )
    }
    item(key = "experience-footer") {
        when {
            results.isLoadingMore -> LoadingIndicator()
            results.loadMoreError != null -> ErrorMessage(stringResource(experiencesErrorMessage(results.loadMoreError)), actions.onLoadMore)
            results.hasMore -> OutlinedButton(onClick = actions.onLoadMore, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.load_more))
            }
        }
    }
}

@Composable
private fun ExperienceSearchField(state: ExperienceSearchUiState, actions: ExperienceSearchActions) {
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = {
        keyboard?.hide()
        actions.onSubmit()
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.queryInput,
            onValueChange = actions.onQueryChange,
            modifier = Modifier.weight(1f),
            label = { Text(stringResource(R.string.experience_search_label)) },
            placeholder = { Text(stringResource(R.string.experience_search_placeholder)) },
            singleLine = true,
            isError = state.showQueryInvalid,
            supportingText = if (state.showQueryInvalid) {
                { Text(stringResource(R.string.experience_search_invalid)) }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit() }),
        )
        Button(onClick = submit) { Text(stringResource(R.string.search_action)) }
    }
}
