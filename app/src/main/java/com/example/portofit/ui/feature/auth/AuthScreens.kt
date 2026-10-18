package com.example.portofit.ui.feature.auth

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.portofit.R
import com.example.portofit.data.auth.FieldProblem

@Composable
fun LoginScreen(
    onBack: () -> Unit,
    onRegisterClick: () -> Unit,
    onDone: () -> Unit,
    viewModel: LoginViewModel = viewModel(factory = LoginViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.completed) { if (state.completed) onDone() }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    AuthScaffold(title = stringResource(R.string.login_title), onBack = onBack) {
        OutlinedTextField(
            value = state.email,
            onValueChange = viewModel::onEmailChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.field_email)) },
            singleLine = true,
            enabled = !state.isSubmitting,
            isError = state.emailProblem != null,
            supportingText = state.emailProblem?.let { { Text(stringResource(emailMessage(it))) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
        )
        PasswordField(
            value = state.password,
            onValueChange = viewModel::onPasswordChange,
            visible = state.passwordVisible,
            onToggleVisibility = viewModel::togglePasswordVisibility,
            enabled = !state.isSubmitting,
            problemMessage = state.passwordProblem?.let { R.string.error_password_required },
            onDone = { keyboard?.hide(); viewModel.submit() },
        )
        FormErrorText(state.formError, state.retryAfterMinutes)
        SubmitButton(R.string.login_action, state.isSubmitting) { keyboard?.hide(); viewModel.submit() }
        TextButton(onClick = onRegisterClick, enabled = !state.isSubmitting, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(R.string.login_go_register))
        }
    }
}

@Composable
fun RegisterScreen(
    onBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: RegisterViewModel = viewModel(factory = RegisterViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.completed) { if (state.completed) onDone() }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    AuthScaffold(title = stringResource(R.string.register_title), onBack = onBack) {
        OutlinedTextField(
            value = state.username,
            onValueChange = viewModel::onUsernameChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.field_username)) },
            singleLine = true,
            enabled = !state.isSubmitting,
            isError = state.usernameProblem != null,
            supportingText = {
                Text(stringResource(state.usernameProblem?.let(::usernameMessage) ?: R.string.hint_username))
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
        )
        OutlinedTextField(
            value = state.email,
            onValueChange = viewModel::onEmailChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.field_email)) },
            singleLine = true,
            enabled = !state.isSubmitting,
            isError = state.emailProblem != null,
            supportingText = state.emailProblem?.let { { Text(stringResource(emailMessage(it))) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
        )
        PasswordField(
            value = state.password,
            onValueChange = viewModel::onPasswordChange,
            visible = state.passwordVisible,
            onToggleVisibility = viewModel::togglePasswordVisibility,
            enabled = !state.isSubmitting,
            problemMessage = state.passwordProblem?.let(::newPasswordMessage),
            hint = R.string.hint_password,
            onDone = { keyboard?.hide(); viewModel.submit() },
        )
        FormErrorText(state.formError, state.retryAfterMinutes)
        SubmitButton(R.string.register_action, state.isSubmitting) { keyboard?.hide(); viewModel.submit() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthScaffold(title: String, onBack: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.navigate_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        // Com o teclado aberto, imePadding encolhe a área e o conteúdo continua a poder ser deslocado.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    visible: Boolean,
    onToggleVisibility: () -> Unit,
    enabled: Boolean,
    @StringRes problemMessage: Int?,
    onDone: () -> Unit,
    @StringRes hint: Int? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.field_password)) },
        singleLine = true,
        enabled = enabled,
        isError = problemMessage != null,
        supportingText = (problemMessage ?: hint)?.let { { Text(stringResource(it)) } },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        trailingIcon = {
            TextButton(onClick = onToggleVisibility, enabled = enabled) {
                Text(stringResource(if (visible) R.string.password_hide else R.string.password_show))
            }
        },
    )
}

@Composable
private fun SubmitButton(@StringRes label: Int, isSubmitting: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = !isSubmitting, modifier = Modifier.fillMaxWidth()) {
        if (isSubmitting) {
            Box(contentAlignment = Alignment.Center) { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) }
        } else {
            Text(stringResource(label))
        }
    }
}

@Composable
private fun FormErrorText(error: FormError?, retryAfterMinutes: Long?) {
    if (error == null) return
    val text = when (error) {
        FormError.INVALID_CREDENTIALS -> stringResource(R.string.error_invalid_credentials)
        FormError.TOO_MANY_ATTEMPTS -> retryAfterMinutes?.toInt()?.let {
            pluralStringResource(R.plurals.error_too_many_attempts_minutes, it, it)
        } ?: stringResource(R.string.error_too_many_attempts)
        FormError.NETWORK -> stringResource(R.string.error_network)
        FormError.SERVER -> stringResource(R.string.error_auth_server)
        FormError.NOT_CONFIGURED -> stringResource(R.string.error_auth_not_configured)
        FormError.UNEXPECTED -> stringResource(R.string.error_unexpected)
    }
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

@StringRes
private fun usernameMessage(problem: FieldProblem): Int = when (problem) {
    FieldProblem.REQUIRED -> R.string.error_username_required
    FieldProblem.RESERVED -> R.string.error_username_reserved
    FieldProblem.ALREADY_USED -> R.string.error_username_taken
    else -> R.string.error_username_format
}

@StringRes
private fun emailMessage(problem: FieldProblem): Int = when (problem) {
    FieldProblem.REQUIRED -> R.string.error_email_required
    FieldProblem.ALREADY_USED -> R.string.error_email_taken
    else -> R.string.error_email_format
}

@StringRes
private fun newPasswordMessage(problem: FieldProblem): Int = when (problem) {
    FieldProblem.REQUIRED -> R.string.error_password_required
    FieldProblem.SAME_AS_IDENTITY -> R.string.error_password_same
    else -> R.string.error_password_length
}
