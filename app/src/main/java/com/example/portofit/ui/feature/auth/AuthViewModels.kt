package com.example.portofit.ui.feature.auth

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.portofit.PortoFitApplication
import com.example.portofit.data.auth.AuthOutcome
import com.example.portofit.data.auth.CredentialRules
import com.example.portofit.data.auth.FieldProblem
import com.example.portofit.data.auth.SessionManager
import com.example.portofit.data.auth.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Erro geral de um formulário (não associado a um campo). */
enum class FormError { INVALID_CREDENTIALS, TOO_MANY_ATTEMPTS, NETWORK, SERVER, NOT_CONFIGURED, UNEXPECTED }

data class LoginUiState(
    val email: String = "",
    val password: String = "",
    val passwordVisible: Boolean = false,
    val emailProblem: FieldProblem? = null,
    val passwordProblem: FieldProblem? = null,
    val isSubmitting: Boolean = false,
    val formError: FormError? = null,
    /** Minutos de espera indicados pelo servidor (429). */
    val retryAfterMinutes: Long? = null,
    val completed: Boolean = false,
)

data class RegisterUiState(
    val username: String = "",
    val email: String = "",
    val password: String = "",
    val passwordVisible: Boolean = false,
    val usernameProblem: FieldProblem? = null,
    val emailProblem: FieldProblem? = null,
    val passwordProblem: FieldProblem? = null,
    val isSubmitting: Boolean = false,
    val formError: FormError? = null,
    val retryAfterMinutes: Long? = null,
    val completed: Boolean = false,
)

/**
 * Login por email. O email fica no SavedStateHandle (sobrevive à recriação); a palavra-passe só em
 * memória. Um segundo envio enquanto o primeiro decorre é ignorado.
 */
class LoginViewModel(private val session: SessionManager, private val savedState: SavedStateHandle) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState(email = savedState[KEY_EMAIL] ?: ""))
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun onEmailChange(value: String) {
        savedState[KEY_EMAIL] = value
        _uiState.update { it.copy(email = value, emailProblem = null, formError = null) }
    }

    fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value, passwordProblem = null, formError = null) }

    fun togglePasswordVisibility() = _uiState.update { it.copy(passwordVisible = !it.passwordVisible) }

    fun submit() {
        val current = _uiState.value
        if (current.isSubmitting || current.completed) return
        val email = CredentialRules.normalizeEmail(current.email)
        val emailProblem = CredentialRules.emailProblem(email)
        val passwordProblem = if (current.password.isEmpty()) FieldProblem.REQUIRED else null
        if (emailProblem != null || passwordProblem != null) {
            _uiState.update { it.copy(emailProblem = emailProblem, passwordProblem = passwordProblem) }
            return
        }
        _uiState.update { it.copy(isSubmitting = true, formError = null) }
        viewModelScope.launch {
            val outcome = session.login(email, current.password)
            _uiState.update { it.applyOutcome(outcome) }
        }
    }

    private fun LoginUiState.applyOutcome(outcome: AuthOutcome): LoginUiState = when (outcome) {
        AuthOutcome.Success -> copy(isSubmitting = false, password = "", completed = true)
        is AuthOutcome.FieldErrors -> copy(
            isSubmitting = false,
            emailProblem = outcome.fields["email"],
            passwordProblem = outcome.fields["password"],
            formError = if (outcome.fields.isEmpty()) FormError.UNEXPECTED else null,
        )
        else -> copy(isSubmitting = false, formError = outcome.toFormError(), retryAfterMinutes = outcome.retryAfterMinutes())
    }

    companion object {
        private const val KEY_EMAIL = "login.email"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PortoFitApplication
                LoginViewModel(app.container.sessionManager, createSavedStateHandle())
            }
        }
    }
}

/** Registo com username, email e palavra-passe, validados como no contrato antes de enviar. */
class RegisterViewModel(private val session: SessionManager, private val savedState: SavedStateHandle) : ViewModel() {

    private val _uiState = MutableStateFlow(
        RegisterUiState(username = savedState[KEY_USERNAME] ?: "", email = savedState[KEY_EMAIL] ?: ""),
    )
    val uiState: StateFlow<RegisterUiState> = _uiState.asStateFlow()

    fun onUsernameChange(value: String) {
        savedState[KEY_USERNAME] = value
        _uiState.update { it.copy(username = value, usernameProblem = null, formError = null) }
    }

    fun onEmailChange(value: String) {
        savedState[KEY_EMAIL] = value
        _uiState.update { it.copy(email = value, emailProblem = null, formError = null) }
    }

    fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value, passwordProblem = null, formError = null) }

    fun togglePasswordVisibility() = _uiState.update { it.copy(passwordVisible = !it.passwordVisible) }

    fun submit() {
        val current = _uiState.value
        if (current.isSubmitting || current.completed) return
        val username = CredentialRules.normalizeUsername(current.username)
        val email = CredentialRules.normalizeEmail(current.email)
        val usernameProblem = CredentialRules.usernameProblem(username)
        val emailProblem = CredentialRules.emailProblem(email)
        val passwordProblem = CredentialRules.newPasswordProblem(current.password, username, email)
        if (usernameProblem != null || emailProblem != null || passwordProblem != null) {
            _uiState.update { it.copy(usernameProblem = usernameProblem, emailProblem = emailProblem, passwordProblem = passwordProblem) }
            return
        }
        _uiState.update { it.copy(isSubmitting = true, formError = null) }
        viewModelScope.launch {
            val outcome = session.register(username, email, current.password)
            _uiState.update {
                when (outcome) {
                    AuthOutcome.Success -> it.copy(isSubmitting = false, password = "", completed = true)
                    is AuthOutcome.FieldErrors -> it.copy(
                        isSubmitting = false,
                        usernameProblem = outcome.fields["username"],
                        emailProblem = outcome.fields["email"],
                        passwordProblem = outcome.fields["password"],
                        formError = if (outcome.fields.isEmpty()) FormError.UNEXPECTED else null,
                    )
                    else -> it.copy(isSubmitting = false, formError = outcome.toFormError(), retryAfterMinutes = outcome.retryAfterMinutes())
                }
            }
        }
    }

    companion object {
        private const val KEY_USERNAME = "register.username"
        private const val KEY_EMAIL = "register.email"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PortoFitApplication
                RegisterViewModel(app.container.sessionManager, createSavedStateHandle())
            }
        }
    }
}

/** Estado da sessão para a barra superior e avisos; terminar sessão e "Tentar novamente". */
class AccountViewModel(private val session: SessionManager) : ViewModel() {

    val sessionState: StateFlow<SessionState> = session.state

    fun logout() = session.logout()

    fun retry() = session.retry()

    fun dismissNotice() = session.dismissNotice()

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PortoFitApplication
                AccountViewModel(app.container.sessionManager)
            }
        }
    }
}

private fun AuthOutcome.toFormError(): FormError = when (this) {
    AuthOutcome.InvalidCredentials -> FormError.INVALID_CREDENTIALS
    is AuthOutcome.TooManyAttempts -> FormError.TOO_MANY_ATTEMPTS
    AuthOutcome.NetworkUnavailable -> FormError.NETWORK
    AuthOutcome.ServerUnavailable -> FormError.SERVER
    AuthOutcome.NotConfigured -> FormError.NOT_CONFIGURED
    else -> FormError.UNEXPECTED
}

/** Arredonda para cima, com mínimo de 1 minuto. */
private fun AuthOutcome.retryAfterMinutes(): Long? =
    (this as? AuthOutcome.TooManyAttempts)?.retryAfterSeconds?.let { maxOf(1, (it + 59) / 60) }
