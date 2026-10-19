package com.example.portofit.ui.feature.experience

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.portofit.PortoFitApplication
import com.example.portofit.data.auth.SessionState
import com.example.portofit.data.model.CatalogStatus
import com.example.portofit.data.model.Experience
import com.example.portofit.data.model.ExperienceRules
import com.example.portofit.data.model.ExperienceTextProblem
import com.example.portofit.data.repository.CatalogRepository
import com.example.portofit.data.repository.DataError
import com.example.portofit.data.repository.DataResult
import com.example.portofit.data.repository.ExperienceChange
import com.example.portofit.data.repository.ExperienceChanges
import com.example.portofit.data.repository.ExperiencesRepository
import com.example.portofit.data.repository.MutationError
import com.example.portofit.data.repository.MutationResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ExperienceLoad {
    data object Loading : ExperienceLoad
    data class Error(val error: DataError) : ExperienceLoad
    data class Loaded(val experience: Experience) : ExperienceLoad
}

/** Edição em curso: só texto e estrelas (a atividade não se altera). */
data class ExperienceEdit(val text: String, val stars: Int, val showValidation: Boolean = false) {
    val textProblem: ExperienceTextProblem? get() = ExperienceRules.textProblem(text)
    val textLength: Int get() = ExperienceRules.length(ExperienceRules.normalizeText(text))
}

data class ExperienceDetailUiState(
    val load: ExperienceLoad = ExperienceLoad.Loading,
    /** Estado atual da atividade no catálogo; null se não foi possível saber (não se mostra nada). */
    val offeringStatus: CatalogStatus? = null,
    val isAuthor: Boolean = false,
    val edit: ExperienceEdit? = null,
    val saving: Boolean = false,
    val showDeleteConfirmation: Boolean = false,
    val deleting: Boolean = false,
    val actionError: MutationError? = null,
    /** Eliminada com sucesso: o ecrã regressa. */
    val deleted: Boolean = false,
) {
    val experience: Experience? get() = (load as? ExperienceLoad.Loaded)?.experience
}

/**
 * Detalhe de uma experiência (pública). Só o autor (sessão com o mesmo `userId`) vê "Editar" e "Eliminar";
 * o servidor decide sempre a autorização (403 para outra pessoa). A edição fica no SavedStateHandle.
 */
class ExperienceDetailViewModel(
    private val repository: ExperiencesRepository,
    private val catalog: CatalogRepository,
    private val changes: ExperienceChanges,
    session: StateFlow<SessionState>,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val experienceId: String = checkNotNull(savedState[EXPERIENCE_ID_ARG])

    private val content = MutableStateFlow(
        ExperienceDetailUiState(
            edit = savedState.get<String>(KEY_EDIT_TEXT)?.let { ExperienceEdit(it, savedState[KEY_EDIT_STARS] ?: 1) },
        ),
    )

    val uiState: StateFlow<ExperienceDetailUiState> = combine(content, session) { state, current ->
        val author = state.experience?.authorId
        state.copy(isAuthor = author != null && current is SessionState.SignedIn && current.userId == author)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, content.value)

    init {
        load()
    }

    fun retry() = load()

    fun startEdit() {
        val experience = uiState.value.experience ?: return
        if (!uiState.value.isAuthor) return
        setEdit(ExperienceEdit(experience.text, experience.stars))
    }

    fun onEditText(text: String) {
        val edit = content.value.edit ?: return
        if (content.value.saving) return
        setEdit(edit.copy(text = text))
    }

    fun onEditStars(stars: Int) {
        val edit = content.value.edit ?: return
        if (content.value.saving || stars !in 1..5) return
        setEdit(edit.copy(stars = stars))
    }

    fun cancelEdit() {
        if (content.value.saving) return
        setEdit(null)
        content.update { it.copy(actionError = null) }
    }

    fun saveEdit() {
        val state = uiState.value
        val edit = state.edit ?: return
        val experience = state.experience ?: return
        if (state.saving || !state.isAuthor) return
        if (edit.textProblem != null) {
            setEdit(edit.copy(showValidation = true))
            return
        }
        val text = ExperienceRules.normalizeText(edit.text)
        // Só o que mudou; sem alterações não é preciso pedido.
        val newText = text.takeIf { it != experience.text }
        val newStars = edit.stars.takeIf { it != experience.stars }
        if (newText == null && newStars == null) {
            setEdit(null)
            return
        }
        content.update { it.copy(saving = true, actionError = null) }
        viewModelScope.launch {
            when (val result = repository.edit(experienceId, newText, newStars)) {
                is MutationResult.Success -> {
                    savedState.remove<String>(KEY_EDIT_TEXT)
                    savedState.remove<Int>(KEY_EDIT_STARS)
                    changes.notify(ExperienceChange(result.value.id, result.value.placeId, result.value.authorId))
                    content.update { it.copy(load = ExperienceLoad.Loaded(result.value), edit = null, saving = false) }
                }
                is MutationResult.Failure -> content.update { it.copy(saving = false, actionError = result.error) }
            }
        }
    }

    fun requestDelete() {
        if (uiState.value.isAuthor && !content.value.deleting) content.update { it.copy(showDeleteConfirmation = true) }
    }

    fun dismissDelete() = content.update { it.copy(showDeleteConfirmation = false) }

    fun confirmDelete() {
        val experience = uiState.value.experience ?: return
        if (content.value.deleting || !uiState.value.isAuthor) return
        content.update { it.copy(showDeleteConfirmation = false, deleting = true, actionError = null) }
        viewModelScope.launch {
            when (val result = repository.delete(experienceId)) {
                is MutationResult.Success -> {
                    changes.notify(ExperienceChange(experience.id, experience.placeId, experience.authorId))
                    content.update { it.copy(deleting = false, deleted = true) }
                }
                is MutationResult.Failure -> content.update { it.copy(deleting = false, actionError = result.error) }
            }
        }
    }

    private fun setEdit(edit: ExperienceEdit?) {
        if (edit == null) {
            savedState.remove<String>(KEY_EDIT_TEXT)
            savedState.remove<Int>(KEY_EDIT_STARS)
        } else {
            savedState[KEY_EDIT_TEXT] = edit.text
            savedState[KEY_EDIT_STARS] = edit.stars
        }
        content.update { it.copy(edit = edit) }
    }

    private fun load() {
        content.update { it.copy(load = ExperienceLoad.Loading) }
        viewModelScope.launch {
            when (val result = repository.get(experienceId)) {
                is DataResult.Success -> {
                    content.update { it.copy(load = ExperienceLoad.Loaded(result.value)) }
                    // Estado atual da atividade (complemento): uma falha não afeta o detalhe.
                    val offering = catalog.getOffering(result.value.offeringId)
                    content.update { it.copy(offeringStatus = (offering as? DataResult.Success)?.value?.status) }
                }
                is DataResult.Failure -> content.update { it.copy(load = ExperienceLoad.Error(result.error)) }
            }
        }
    }

    companion object {
        const val EXPERIENCE_ID_ARG = "experienceId"
        const val KEY_EDIT_TEXT = "experience.editText"
        const val KEY_EDIT_STARS = "experience.editStars"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PortoFitApplication
                ExperienceDetailViewModel(
                    app.container.experiencesRepository,
                    app.container.catalogRepository,
                    app.container.experienceChanges,
                    app.container.sessionManager.state,
                    createSavedStateHandle(),
                )
            }
        }
    }
}
