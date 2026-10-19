package com.example.portofit.data.model

import java.time.Instant

/**
 * Experiência publicada (docs/experiences-api.md §4.1). Os ids são referências; os nomes (autor, local,
 * atividade, modalidade) são cópias do momento da publicação e podem estar desatualizados.
 */
data class Experience(
    val id: String,
    val authorId: String,
    val authorUsername: String,
    val placeId: String,
    val placeName: String,
    /** Local de dados de demonstração (fictício). */
    val placeDemo: Boolean,
    val offeringId: String,
    val offeringName: String,
    val sportName: String,
    val text: String,
    val stars: Int,
    val createdAt: Instant,
    val editedAt: Instant?,
)

enum class ExperienceSort(val apiValue: String) { RECENT("recent"), STARS("stars") }

/** Problema do texto de uma experiência, segundo as regras do contrato (§4.1). */
enum class ExperienceTextProblem { EMPTY, TOO_LONG, INVALID_CHARACTERS }

/** Validação local igual à do servidor: a app não envia o que o servidor recusaria. */
object ExperienceRules {
    const val MAX_TEXT_LENGTH = 2000

    /** `\r\n` e `\r` passam a `\n`; espaços nas pontas são removidos (o que o servidor guarda). */
    fun normalizeText(raw: String): String = raw.replace("\r\n", "\n").replace('\r', '\n').trim()

    /** Comprimento em pontos de código (um emoji conta 1), como no servidor. */
    fun length(text: String): Int = text.codePointCount(0, text.length)

    fun textProblem(raw: String): ExperienceTextProblem? {
        val text = normalizeText(raw)
        return when {
            text.isEmpty() -> ExperienceTextProblem.EMPTY
            length(text) > MAX_TEXT_LENGTH -> ExperienceTextProblem.TOO_LONG
            text.codePoints().anyMatch { c ->
                val type = Character.getType(c)
                (type == Character.CONTROL.toInt() && c != '\n'.code && c != '\t'.code) || type == Character.SURROGATE.toInt()
            } -> ExperienceTextProblem.INVALID_CHARACTERS
            else -> null
        }
    }

    fun validStars(stars: Int?): Boolean = stars != null && stars in 1..5
}
