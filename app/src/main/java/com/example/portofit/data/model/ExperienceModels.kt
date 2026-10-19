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
    val photo: ExperiencePhoto? = null,
    /** Gostos e comentários ativos (E3). Não são estrelas: as estrelas avaliam o local/atividade. */
    val likesCount: Long = 0,
    val commentsCount: Long = 0,
    /**
     * true/false só quando o servidor confirmou a relação para a sessão que pediu; null quando não foi consultada
     * (visitante, o próprio autor, ou sessão que não foi possível usar). Nunca é tratado como false.
     */
    val likedByMe: Boolean? = null,
)

/** Comentário (docs/experiences-api.md §13.1). O autor é uma cópia do momento do comentário. */
data class Comment(
    val id: String,
    val experienceId: String,
    val authorId: String,
    val authorUsername: String,
    val text: String,
    val createdAt: Instant,
    val editedAt: Instant?,
)

/**
 * Fotografia de uma experiência (E2). [path] é o caminho relativo devolvido pelo serviço
 * (`/api/v1/media/{mediaId}`); o endereço completo resolve-se na interface com o URL configurado.
 */
data class ExperiencePhoto(val mediaId: String, val path: String, val width: Int, val height: Int)

/** Ordenações das listas (docs/experiences-api.md §9); todas disponíveis a visitantes. */
enum class ExperienceSort(val apiValue: String) { RECENT("recent"), STARS("stars"), LIKES("likes"), INTERACTIONS("interactions") }

/** Problema do texto de uma experiência ou comentário, segundo as regras do contrato (§4.1, §13.1). */
enum class ExperienceTextProblem { EMPTY, TOO_LONG, INVALID_CHARACTERS }

/** Validação local igual à do servidor: a app não envia o que o servidor recusaria. */
object ExperienceRules {
    const val MAX_TEXT_LENGTH = 2000

    /** Comentários: mesmas regras, até 1000 caracteres (§13.1). */
    const val MAX_COMMENT_LENGTH = 1000

    /** `\r\n` e `\r` passam a `\n`; espaços nas pontas são removidos (o que o servidor guarda). */
    fun normalizeText(raw: String): String = raw.replace("\r\n", "\n").replace('\r', '\n').trim()

    /** Comprimento em pontos de código (um emoji conta 1), como no servidor. */
    fun length(text: String): Int = text.codePointCount(0, text.length)

    fun textProblem(raw: String, maxLength: Int = MAX_TEXT_LENGTH): ExperienceTextProblem? {
        val text = normalizeText(raw)
        return when {
            text.isEmpty() -> ExperienceTextProblem.EMPTY
            length(text) > maxLength -> ExperienceTextProblem.TOO_LONG
            text.codePoints().anyMatch { c ->
                val type = Character.getType(c)
                (type == Character.CONTROL.toInt() && c != '\n'.code && c != '\t'.code) || type == Character.SURROGATE.toInt()
            } -> ExperienceTextProblem.INVALID_CHARACTERS
            else -> null
        }
    }

    fun validStars(stars: Int?): Boolean = stars != null && stars in 1..5
}
