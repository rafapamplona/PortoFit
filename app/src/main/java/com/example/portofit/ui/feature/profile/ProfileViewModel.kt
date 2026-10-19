package com.example.portofit.ui.feature.profile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.portofit.PortoFitApplication
import com.example.portofit.data.model.Sport
import com.example.portofit.data.auth.SessionState
import com.example.portofit.data.model.ExperienceSort
import com.example.portofit.data.repository.CatalogRepository
import com.example.portofit.data.repository.ExperienceChanges
import com.example.portofit.data.repository.ExperiencesRepository
import com.example.portofit.ui.feature.experience.ExperienceListLoader
import com.example.portofit.ui.feature.experience.ExperienceListState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.example.portofit.data.repository.DataResult
import com.example.portofit.data.repository.Profile
import com.example.portofit.data.repository.ProfileError
import com.example.portofit.data.repository.ProfileRepository
import com.example.portofit.data.repository.ProfileResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.example.portofit.ui.components.SportNames

sealed interface ProfileLoad {
    data object Loading : ProfileLoad
    data object Loaded : ProfileLoad
    data class Failed(val error: ProfileError) : ProfileLoad
}

sealed interface SportsLoad {
    data object Loading : SportsLoad
    data class Loaded(val sports: List<Sport>) : SportsLoad
    data object Failed : SportsLoad
}

sealed interface SaveState {
    data object Idle : SaveState
    data object Saving : SaveState
    data object Saved : SaveState
    data class Failed(val error: ProfileError) : SaveState
}

/** Uma modalidade que pode ser escolhida (ou removida) como interesse. */
data class InterestOption(
    val id: String,
    val name: String,
    /** false quando o nome não veio do catálogo (catálogo indisponível ou id desconhecido). */
    val nameFromCatalog: Boolean,
    val selected: Boolean,
    val enabled: Boolean,
)

/**
 * Estado do ecrã. O perfil do servidor e as edições locais ficam separados: uma resposta de
 * carregamento nunca substitui o que o utilizador está a editar.
 */
data class ProfileUiState(
    val load: ProfileLoad = ProfileLoad.Loading,
    val profile: Profile? = null,
    /** null = sem edição; caso contrário, o texto atualmente no campo. */
    val editedDescription: String? = null,
    /** null = sem edição; caso contrário, a seleção atual, pela ordem escolhida. */
    val editedInterests: List<String>? = null,
    val sports: SportsLoad = SportsLoad.Loading,
    val descriptionSave: SaveState = SaveState.Idle,
    val interestsSave: SaveState = SaveState.Idle,
    val showDiscardConfirmation: Boolean = false,
) {
    val description: String get() = editedDescription ?: profile?.description.orEmpty()
    val interests: List<String> get() = editedInterests ?: profile?.interests.orEmpty()

    val descriptionLength: Int get() = description.codePointCount(0, description.length)
    val descriptionTooLong: Boolean get() = descriptionLength > MAX_DESCRIPTION

    val descriptionDirty: Boolean
        get() = profile != null && editedDescription != null && normalizeDescription(editedDescription) != profile.description
    val interestsDirty: Boolean
        get() = profile != null && editedInterests != null && editedInterests != profile.interests
    val hasUnsavedChanges: Boolean get() = descriptionDirty || interestsDirty

    val canSaveDescription: Boolean get() = descriptionDirty && !descriptionTooLong && descriptionSave != SaveState.Saving
    val canSaveInterests: Boolean get() = interestsDirty && interestsSave != SaveState.Saving

    /**
     * Modalidades do catálogo (se carregadas) mais os ids já guardados ou selecionados que o catálogo
     * não indicou. Sem catálogo só aparecem os interesses existentes: podem ser removidos, não acrescentados.
     */
    val interestOptions: List<InterestOption>
        get() {
            val selected = interests
            val catalog = (sports as? SportsLoad.Loaded)?.sports.orEmpty()
            val known = catalog.map { it.id }.toSet()
            val savedIds = profile?.interests.orEmpty()
            val full = selected.size >= MAX_INTERESTS
            val fromCatalog = catalog.map { sport ->
                val isSelected = sport.id in selected
                InterestOption(sport.id, sport.name, true, isSelected, enabled = isSelected || !full)
            }
            val unknown = (savedIds + selected).distinct().filterNot { it in known }.map { id ->
                val isSelected = id in selected
                // Um id já guardado pode voltar a ser escolhido sem catálogo (o servidor não o valida de novo).
                InterestOption(id, fallbackSportName(id), false, isSelected, enabled = isSelected || (id in savedIds && !full))
            }
            return fromCatalog + unknown
        }

    companion object {
        const val MAX_DESCRIPTION = 300
        const val MAX_INTERESTS = 10

        fun normalizeDescription(text: String): String? = text.trim().ifEmpty { null }

        /** Nome legível a partir do id quando o catálogo não o indica (ex.: "artes-marciais" → "Artes marciais"). */
        fun fallbackSportName(id: String): String = SportNames.fallback(id)
    }
}

/**
 * O meu perfil: descrição e interesses, gravados em separado.
 *
 * - As edições ficam no SavedStateHandle (sobrevivem à rotação e à recriação do processo).
 * - Cada carregamento tem um número de geração; respostas antigas são ignoradas, e uma gravação bem
 *   sucedida invalida carregamentos ainda em curso.
 * - Uma resposta de gravação só limpa a edição se o utilizador não a tiver alterado entretanto.
 */
class ProfileViewModel(
    private val profiles: ProfileRepository,
    private val catalog: CatalogRepository,
    experiences: ExperiencesRepository,
    changes: ExperienceChanges,
    session: StateFlow<SessionState>,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    /**
     * As minhas publicações (experiences-service), pelo `userId` da sessão: carregam e falham de forma
     * independente do perfil (users-service).
     */
    private val publicationList = ExperienceListLoader(
        viewModelScope,
        experiences,
        initialSort = savedState.get<String>(KEY_PUBLICATION_SORT)?.let { name -> ExperienceSort.entries.firstOrNull { it.name == name } } ?: ExperienceSort.RECENT,
        counts = changes.counts,
    )
    val publications: StateFlow<ExperienceListState> = publicationList.state
    val publicationSort: StateFlow<ExperienceSort> = publicationList.sort

    private val sessionUserId = session.map { (it as? SessionState.SignedIn)?.userId }.distinctUntilChanged()

    private val _uiState = MutableStateFlow(
        ProfileUiState(
            editedDescription = savedState[KEY_DESCRIPTION],
            editedInterests = savedState.get<ArrayList<String>>(KEY_INTERESTS),
        ),
    )
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    private var loadGeneration = 0

    init {
        loadProfile()
        loadSports()
        viewModelScope.launch { sessionUserId.collect { publicationList.setAuthor(it) } }
        viewModelScope.launch {
            changes.events.collect { change ->
                if (change.authorId.isNotEmpty() && change.authorId == (session.value as? SessionState.SignedIn)?.userId) publicationList.load()
            }
        }
    }

    fun retryPublications() = publicationList.load()

    /** Recomeça na primeira página com a nova ordenação (guardada para a rotação). */
    fun setPublicationSort(sort: ExperienceSort) {
        savedState[KEY_PUBLICATION_SORT] = sort.name
        publicationList.setSort(sort)
    }

    fun loadMorePublications() = publicationList.loadMore()

    fun retryLoad() = loadProfile()

    fun retrySports() = loadSports()

    fun onDescriptionChange(text: String) {
        setEditedDescription(text)
        _uiState.update { it.copy(descriptionSave = if (it.descriptionSave == SaveState.Saving) SaveState.Saving else SaveState.Idle) }
    }

    fun onInterestToggle(id: String) {
        val state = _uiState.value
        val option = state.interestOptions.firstOrNull { it.id == id } ?: return
        if (!option.enabled) return
        val current = state.interests
        val next = if (id in current) current - id else current + id
        setEditedInterests(next)
        _uiState.update { it.copy(interestsSave = if (it.interestsSave == SaveState.Saving) SaveState.Saving else SaveState.Idle) }
    }

    fun saveDescription() {
        val state = _uiState.value
        if (!state.canSaveDescription) return
        val sent = ProfileUiState.normalizeDescription(state.description)
        val editAtSend = state.editedDescription
        _uiState.update { it.copy(descriptionSave = SaveState.Saving) }
        viewModelScope.launch {
            when (val result = profiles.saveDescription(sent)) {
                is ProfileResult.Success -> {
                    loadGeneration++ // um carregamento iniciado antes desta gravação já está desatualizado
                    _uiState.update {
                        val unchanged = it.editedDescription == editAtSend
                        if (unchanged) savedState.remove<String>(KEY_DESCRIPTION)
                        it.copy(
                            profile = result.profile,
                            load = ProfileLoad.Loaded,
                            editedDescription = if (unchanged) null else it.editedDescription,
                            descriptionSave = SaveState.Saved,
                        )
                    }
                }
                is ProfileResult.Failure -> _uiState.update { it.copy(descriptionSave = SaveState.Failed(result.error)) }
            }
        }
    }

    fun saveInterests() {
        val state = _uiState.value
        if (!state.canSaveInterests) return
        val sent = state.interests
        _uiState.update { it.copy(interestsSave = SaveState.Saving) }
        viewModelScope.launch {
            when (val result = profiles.saveInterests(sent)) {
                is ProfileResult.Success -> {
                    loadGeneration++
                    _uiState.update {
                        val unchanged = it.editedInterests == sent
                        if (unchanged) savedState.remove<ArrayList<String>>(KEY_INTERESTS)
                        it.copy(
                            profile = result.profile,
                            load = ProfileLoad.Loaded,
                            editedInterests = if (unchanged) null else it.editedInterests,
                            interestsSave = SaveState.Saved,
                        )
                    }
                }
                is ProfileResult.Failure -> _uiState.update { it.copy(interestsSave = SaveState.Failed(result.error)) }
            }
        }
    }

    /** Sair do ecrã: devolve true se pode sair já; senão mostra a confirmação de descartar. */
    fun requestExit(): Boolean {
        if (!_uiState.value.hasUnsavedChanges) return true
        _uiState.update { it.copy(showDiscardConfirmation = true) }
        return false
    }

    fun dismissDiscardConfirmation() = _uiState.update { it.copy(showDiscardConfirmation = false) }

    fun discardChanges() {
        savedState.remove<String>(KEY_DESCRIPTION)
        savedState.remove<ArrayList<String>>(KEY_INTERESTS)
        _uiState.update { it.copy(editedDescription = null, editedInterests = null, showDiscardConfirmation = false) }
    }

    private fun setEditedDescription(text: String) {
        savedState[KEY_DESCRIPTION] = text
        _uiState.update { it.copy(editedDescription = text) }
    }

    private fun setEditedInterests(ids: List<String>) {
        savedState[KEY_INTERESTS] = ArrayList(ids)
        _uiState.update { it.copy(editedInterests = ids) }
    }

    private fun loadProfile() {
        val generation = ++loadGeneration
        _uiState.update { if (it.profile == null) it.copy(load = ProfileLoad.Loading) else it }
        viewModelScope.launch {
            val result = profiles.load()
            if (generation != loadGeneration) return@launch
            _uiState.update {
                when (result) {
                    // Só o perfil do servidor muda; as edições locais ficam como estão.
                    is ProfileResult.Success -> it.copy(profile = result.profile, load = ProfileLoad.Loaded)
                    is ProfileResult.Failure -> it.copy(load = ProfileLoad.Failed(result.error))
                }
            }
        }
    }

    private fun loadSports() {
        _uiState.update { it.copy(sports = SportsLoad.Loading) }
        viewModelScope.launch {
            val result = catalog.getSports()
            _uiState.update {
                it.copy(
                    sports = when (result) {
                        is DataResult.Success -> SportsLoad.Loaded(result.value)
                        is DataResult.Failure -> SportsLoad.Failed
                    },
                )
            }
        }
    }

    companion object {
        const val KEY_PUBLICATION_SORT = "profile.publicationSort"
        private const val KEY_DESCRIPTION = "profile.editedDescription"
        private const val KEY_INTERESTS = "profile.editedInterests"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PortoFitApplication
                ProfileViewModel(
                    app.container.profileRepository,
                    app.container.catalogRepository,
                    app.container.experiencesRepository,
                    app.container.experienceChanges,
                    app.container.sessionManager.state,
                    createSavedStateHandle(),
                )
            }
        }
    }
}
