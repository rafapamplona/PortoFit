package com.example.portofit.data.model

import java.time.Instant

/** Lista pessoal (docs/collections-api.md §4). Privada: só o dono a vê. */
data class UserCollection(val id: String, val name: String, val itemCount: Long, val createdAt: Instant, val updatedAt: Instant)

/**
 * Estado de uma experiência guardada (§6.3). [DELETED]: eliminada ou inexistente, sem conteúdo. [UNKNOWN]: o serviço das
 * experiências não respondeu — **não** significa eliminada.
 */
enum class SavedAvailability { AVAILABLE, DELETED, UNKNOWN }

data class SavedItem(val experienceId: String, val addedAt: Instant, val availability: SavedAvailability, val experience: Experience?)

/** [experiencesAvailable] = false quando o servidor indicou `experiencesStatus: UNAVAILABLE`. */
data class SavedItemsPage(val page: Page<SavedItem>, val experiencesAvailable: Boolean)

/** Regras do nome de uma lista, iguais às do servidor (§5.1). */
object CollectionRules {
    const val MAX_NAME = 60

    /** Pontas removidas; 1–60 pontos de código, sem caracteres de controlo (nem mudanças de linha). Inválido → null. */
    fun cleanName(raw: String): String? {
        val name = raw.trim()
        val length = name.codePointCount(0, name.length)
        val invalid = name.codePoints().anyMatch { c ->
            val type = Character.getType(c)
            type == Character.CONTROL.toInt() || type == Character.SURROGATE.toInt()
        }
        return name.takeUnless { it.isEmpty() || length > MAX_NAME || invalid }
    }

    fun length(raw: String): Int = raw.trim().let { it.codePointCount(0, it.length) }
}
