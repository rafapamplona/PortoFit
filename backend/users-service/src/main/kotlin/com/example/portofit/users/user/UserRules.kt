package com.example.portofit.users.user

import com.example.portofit.users.api.FieldError
import java.util.Locale

/** Normalização e validação dos dados de conta e perfil (docs/users-api.md §2.1, §3.1, §7). */
object UserRules {

	const val MAX_DESCRIPTION = 300
	const val MAX_INTERESTS = 10
	const val MIN_PASSWORD_CHARS = 10
	const val MAX_PASSWORD_CHARS = 64

	/** O BCrypt ignora tudo a partir do 73.º byte: palavras-passe maiores são rejeitadas, nunca truncadas. */
	const val MAX_PASSWORD_BYTES = 72

	private val USERNAME = Regex("^[a-z0-9][a-z0-9._]{1,28}[a-z0-9]$")
	private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
	private val SPORT_ID = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
	private val RESERVED = setOf("admin", "portofit", "suporte", "api", "me")

	fun normalizeUsername(value: String) = value.trim().lowercase(Locale.ROOT)

	fun normalizeEmail(value: String) = value.trim().lowercase(Locale.ROOT)

	fun usernameError(username: String): FieldError? = when {
		!USERNAME.matches(username) -> FieldError(
			"username", "O nome de utilizador deve ter 3 a 30 caracteres: letras minúsculas, números, '.' ou '_'.",
		)
		username.startsWith("demo_") || username in RESERVED -> FieldError("username", "Este nome de utilizador está reservado.")
		else -> null
	}

	fun emailError(email: String): FieldError? =
		if (email.length > 254 || !EMAIL.matches(email)) FieldError("email", "Indica um email válido.") else null

	/** Caracteres e bytes verificados antes de qualquer cálculo de hash. */
	fun passwordError(password: String, username: String, email: String): FieldError? {
		val chars = password.codePointCount(0, password.length)
		val bytes = password.toByteArray(Charsets.UTF_8).size
		return when {
			chars !in MIN_PASSWORD_CHARS..MAX_PASSWORD_CHARS || bytes > MAX_PASSWORD_BYTES ->
				FieldError("password", "A palavra-passe deve ter entre 10 e 64 caracteres (no máximo 72 bytes).")
			password.equals(username, ignoreCase = true) || password.equals(email, ignoreCase = true) ->
				FieldError("password", "A palavra-passe não pode ser igual ao nome de utilizador nem ao email.")
			else -> null
		}
	}

	/** Pode ser usada no login: uma palavra-passe fora destes limites nunca foi aceite no registo. */
	fun passwordWithinLimits(password: String): Boolean {
		val chars = password.codePointCount(0, password.length)
		return chars in MIN_PASSWORD_CHARS..MAX_PASSWORD_CHARS && password.toByteArray(Charsets.UTF_8).size <= MAX_PASSWORD_BYTES
	}

	fun descriptionError(description: String): FieldError? =
		if (description.codePointCount(0, description.length) > MAX_DESCRIPTION) {
			FieldError("description", "A descrição pode ter no máximo 300 caracteres.")
		} else {
			null
		}

	fun interestsErrors(interests: List<String>): List<FieldError> {
		val errors = mutableListOf<FieldError>()
		if (interests.size > MAX_INTERESTS) errors += FieldError("interests", "Podes escolher no máximo 10 modalidades.")
		if (interests.toSet().size != interests.size) errors += FieldError("interests", "As modalidades não se podem repetir.")
		interests.filterNot { SPORT_ID.matches(it) && it.length in 2..40 }.forEach {
			errors += FieldError("interests", "Identificador de modalidade inválido.")
		}
		return errors.distinct()
	}
}
