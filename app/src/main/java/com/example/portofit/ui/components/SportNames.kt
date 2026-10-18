package com.example.portofit.ui.components

import com.example.portofit.data.model.Sport
import java.util.Locale

/** Nome de uma modalidade a mostrar e se veio do catálogo. */
data class SportLabel(val id: String, val name: String, val fromCatalog: Boolean)

object SportNames {

    /** Nome legível a partir do id quando o catálogo não o indica (ex.: "artes-marciais" → "Artes marciais"). */
    fun fallback(id: String): String =
        id.replace('-', ' ').replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }

    /** Nomes pela ordem dos ids; usa o catálogo quando disponível e o nome alternativo caso contrário. */
    fun labels(ids: List<String>, catalog: List<Sport>?): List<SportLabel> {
        val names = catalog.orEmpty().associate { it.id to it.name }
        return ids.map { id -> names[id]?.let { SportLabel(id, it, true) } ?: SportLabel(id, fallback(id), false) }
    }
}
