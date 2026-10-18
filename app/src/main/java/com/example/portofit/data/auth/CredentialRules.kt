package com.example.portofit.data.auth

import java.util.Locale

/** Problemas de um campo dos formulários de conta. */
enum class FieldProblem { REQUIRED, INVALID_FORMAT, RESERVED, TOO_SHORT_OR_LONG, SAME_AS_IDENTITY, ALREADY_USED }

/**
 * Validação local coerente com o contrato (docs/users-api.md §2.1 e §3.1), para dar erros imediatos.
 * O servidor valida sempre de novo.
 */
object CredentialRules {

    private val USERNAME = Regex("^[a-z0-9][a-z0-9._]{1,28}[a-z0-9]$")
    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    private val RESERVED = setOf("admin", "portofit", "suporte", "api", "me")

    fun normalizeUsername(value: String) = value.trim().lowercase(Locale.ROOT)

    fun normalizeEmail(value: String) = value.trim().lowercase(Locale.ROOT)

    fun usernameProblem(username: String): FieldProblem? = when {
        username.isEmpty() -> FieldProblem.REQUIRED
        !USERNAME.matches(username) -> FieldProblem.INVALID_FORMAT
        username.startsWith("demo_") || username in RESERVED -> FieldProblem.RESERVED
        else -> null
    }

    fun emailProblem(email: String): FieldProblem? = when {
        email.isEmpty() -> FieldProblem.REQUIRED
        email.length > 254 || !EMAIL.matches(email) -> FieldProblem.INVALID_FORMAT
        else -> null
    }

    /** 10–64 caracteres e no máximo 72 bytes em UTF-8 (limite do BCrypt; nunca truncado). */
    fun newPasswordProblem(password: String, username: String, email: String): FieldProblem? {
        if (password.isEmpty()) return FieldProblem.REQUIRED
        val chars = password.codePointCount(0, password.length)
        return when {
            chars !in 10..64 || password.toByteArray(Charsets.UTF_8).size > 72 -> FieldProblem.TOO_SHORT_OR_LONG
            password.equals(username, ignoreCase = true) || password.equals(email, ignoreCase = true) -> FieldProblem.SAME_AS_IDENTITY
            else -> null
        }
    }
}
