package com.example.portofit.ui.feature.experience

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.portofit.R
import com.example.portofit.data.model.Experience
import com.example.portofit.data.model.ExperienceSort
import com.example.portofit.data.repository.DataError
import com.example.portofit.data.repository.DataResult
import com.example.portofit.data.repository.ExperienceCounts
import com.example.portofit.data.repository.ExperiencesRepository
import com.example.portofit.ui.components.LoadingIndicator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

sealed interface ExperienceListState {
    data object Loading : ExperienceListState
    data class Error(val error: DataError) : ExperienceListState
    data class Loaded(
        val items: List<Experience>,
        val page: Int,
        val totalItems: Long,
        val hasMore: Boolean,
        val isLoadingMore: Boolean = false,
        val loadMoreError: DataError? = null,
    ) : ExperienceListState
}

/**
 * Lista paginada de experiências de um local, de um autor ou de uma pesquisa por texto ([setQuery]), usada dentro de um
 * ViewModel (com o [scope] dele). Cada carregamento tem uma geração: respostas de pedidos antigos são ignoradas.
 * Mudar a ordenação recomeça na primeira página. As contagens confirmadas no detalhe ([counts]) atualizam o
 * item já carregado, sem recarregar nem perder as páginas (a ordem só muda no próximo carregamento).
 */
class ExperienceListLoader(
    private val scope: CoroutineScope,
    private val repository: ExperiencesRepository,
    private val placeId: String? = null,
    authorId: String? = null,
    initialSort: ExperienceSort = ExperienceSort.RECENT,
    private val pageSize: Int = PAGE_SIZE,
    counts: Flow<ExperienceCounts>? = null,
) {
    private val _state = MutableStateFlow<ExperienceListState>(ExperienceListState.Loading)
    val state: StateFlow<ExperienceListState> = _state.asStateFlow()

    private val _sort = MutableStateFlow(initialSort)
    val sort: StateFlow<ExperienceSort> = _sort.asStateFlow()

    private var authorId: String? = authorId
    private var query: String? = null
    private var generation = 0
    private var job: Job? = null

    init {
        counts?.let { flow -> scope.launch { flow.collect(::applyCounts) } }
    }

    fun applyCounts(counts: ExperienceCounts) {
        _state.update { state ->
            if (state !is ExperienceListState.Loaded || state.items.none { it.id == counts.experienceId }) return@update state
            state.copy(
                items = state.items.map {
                    if (it.id == counts.experienceId) it.copy(likesCount = counts.likesCount, commentsCount = counts.commentsCount) else it
                },
            )
        }
    }

    /** Primeira página (também depois de uma alteração ou de "Tentar novamente"). */
    fun load() {
        if (placeId == null && authorId == null && query == null) return
        job?.cancel()
        val myGeneration = ++generation
        _state.value = ExperienceListState.Loading
        job = scope.launch {
            val result = fetch(0)
            if (myGeneration != generation) return@launch
            _state.value = when (result) {
                is DataResult.Success -> ExperienceListState.Loaded(result.value.items, result.value.page, result.value.totalItems, result.value.hasMore)
                is DataResult.Failure -> ExperienceListState.Error(result.error)
            }
        }
    }

    fun loadMore() {
        val current = _state.value as? ExperienceListState.Loaded ?: return
        if (!current.hasMore || current.isLoadingMore) return
        val myGeneration = generation
        _state.value = current.copy(isLoadingMore = true, loadMoreError = null)
        job = scope.launch {
            val result = fetch(current.page + 1)
            if (myGeneration != generation) return@launch
            _state.update { state ->
                val loaded = state as? ExperienceListState.Loaded ?: return@update state
                when (result) {
                    // Sem repetições pelo id: uma publicação nova pode deslocar as páginas (paginação por offset).
                    is DataResult.Success -> loaded.copy(
                        items = (loaded.items + result.value.items).distinctBy { it.id },
                        page = result.value.page,
                        totalItems = result.value.totalItems,
                        hasMore = result.value.hasMore,
                        isLoadingMore = false,
                    )
                    is DataResult.Failure -> loaded.copy(isLoadingMore = false, loadMoreError = result.error)
                }
            }
        }
    }

    fun setSort(sort: ExperienceSort) {
        if (sort == _sort.value) return
        _sort.value = sort
        load()
    }

    /** Para o próprio perfil, cujo autor só é conhecido depois de a sessão estar disponível. */
    fun setAuthor(id: String?) {
        if (id == authorId) return
        authorId = id
        if (id == null) {
            job?.cancel()
            generation++
            _state.value = ExperienceListState.Loading
        } else {
            load()
        }
    }

    /** Para a pesquisa por texto: recomeça com o novo texto, e as respostas da pesquisa anterior são ignoradas. */
    fun setQuery(text: String) {
        query = text
        load()
    }

    private suspend fun fetch(page: Int) =
        query?.let { repository.search(it, _sort.value, page, pageSize) } ?: repository.list(placeId, authorId, _sort.value, page, pageSize)

    companion object {
        const val PAGE_SIZE = 10
    }
}

private val PORTUGUESE = Locale.forLanguageTag("pt-PT")
private val DATE_FORMAT = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(PORTUGUESE)

/** Data no fuso do aparelho, em português (ex.: 19/10/2026). */
fun formatExperienceDate(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String = DATE_FORMAT.format(instant.atZone(zone))

/** Estrelas de 1 a 5 como texto (★★★★☆), com descrição para leitores de ecrã. */
@Composable
fun StarsText(stars: Int, modifier: Modifier = Modifier) {
    val description = pluralStringResource(R.plurals.experience_stars_description, stars, stars)
    Text(
        "★".repeat(stars) + "☆".repeat(5 - stars),
        modifier = modifier.semantics { contentDescription = description },
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * Ordenação (as quatro do contrato, §9): os rótulos deixam claro que as estrelas avaliam o local/atividade, não a
 * publicação, e que as interações são gostos e comentários.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExperienceSortSelector(sort: ExperienceSort, onSortChange: (ExperienceSort) -> Unit) {
    val options = listOf(
        ExperienceSort.RECENT to R.string.experience_sort_recent,
        ExperienceSort.STARS to R.string.experience_sort_stars,
        ExperienceSort.LIKES to R.string.experience_sort_likes,
        ExperienceSort.INTERACTIONS to R.string.experience_sort_interactions,
    )
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(selected = sort == value, onClick = { onSortChange(value) }, label = { Text(stringResource(label)) })
        }
    }
}

/** "3 gostos · 2 comentários": contagens da publicação, separadas das estrelas (que avaliam o local). */
@Composable
fun InteractionCounts(likes: Long, comments: Long, modifier: Modifier = Modifier) {
    val likesText = pluralStringResource(R.plurals.likes_count, likes.toInt(), likes.toInt())
    val commentsText = pluralStringResource(R.plurals.comments_count, comments.toInt(), comments.toInt())
    Text(
        "$likesText · $commentsText",
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun ExperienceCard(experience: Experience, showPlace: Boolean, onClick: () -> Unit, onAuthorClick: (() -> Unit)?) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StarsText(experience.stars)
                Text(
                    formatExperienceDate(experience.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Nos perfis o autor é sempre o mesmo: o botão só aparece nas listas de um local.
            if (onAuthorClick != null) {
                TextButton(onClick = onAuthorClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text("@${experience.authorUsername}", style = MaterialTheme.typography.labelLarge)
                }
            }
            Text(
                if (showPlace) stringResource(R.string.experience_place_offering, experience.placeName, experience.offeringName) else experience.offeringName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            // Fotografia (se houver) com erro isolado: o texto aparece sempre.
            experience.photo?.let { ExperiencePhotoView(it, crop = true) }
            Text(experience.text, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
            InteractionCounts(experience.likesCount, experience.commentsCount)
        }
    }
}

/**
 * Secção de experiências (local ou perfil). Uma falha só afeta esta secção: o resto do ecrã continua
 * visível. Cada cartão mostra as estrelas (do local) e, à parte, os gostos e comentários da publicação.
 */
@Composable
fun ExperienceListSection(
    state: ExperienceListState,
    showPlace: Boolean,
    @StringRes emptyText: Int,
    onOpen: (Experience) -> Unit,
    /** null = não mostrar o autor (perfis). */
    onAuthorClick: ((Experience) -> Unit)?,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (state) {
            ExperienceListState.Loading -> LoadingIndicator()
            is ExperienceListState.Error -> SectionError(stringResource(experiencesErrorMessage(state.error)), onRetry)
            is ExperienceListState.Loaded -> {
                if (state.items.isEmpty()) {
                    Text(stringResource(emptyText), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val count = state.totalItems.toInt()
                    Text(pluralStringResource(R.plurals.experiences_count, count, count), style = MaterialTheme.typography.labelLarge)
                }
                state.items.forEach { experience ->
                    ExperienceCard(experience, showPlace, onClick = { onOpen(experience) }, onAuthorClick = onAuthorClick?.let { { it(experience) } })
                }
                when {
                    state.isLoadingMore -> LoadingIndicator()
                    state.loadMoreError != null -> SectionError(stringResource(experiencesErrorMessage(state.loadMoreError)), onLoadMore)
                    state.hasMore -> OutlinedButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.load_more))
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionError(message: String, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    }
}

@StringRes
fun experiencesErrorMessage(error: DataError): Int = when (error) {
    DataError.NOT_CONFIGURED -> R.string.experiences_not_configured
    DataError.NOT_FOUND -> R.string.experience_not_found
    else -> R.string.experiences_unavailable
}
