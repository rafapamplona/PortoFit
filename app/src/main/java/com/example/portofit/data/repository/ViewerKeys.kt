package com.example.portofit.data.repository

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Chave de visualização dos visitantes (docs/experiences-api.md §13.5): um UUID aleatório gerado uma vez por
 * instalação. Não vem do aparelho (nada de identificadores de hardware), da conta, do email nem do IP.
 */
fun interface ViewerKeys {
    suspend fun key(): String
}

/**
 * Guardada em SharedPreferences (`viewer_key.xml`), excluída das cópias de segurança e das transferências entre
 * aparelhos (backup_rules.xml e data_extraction_rules.xml). Apagar os dados da app gera outra chave; aceita-se
 * (métrica aproximada).
 */
class PreferencesViewerKeys(context: Context) : ViewerKeys {
    private val preferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    @Volatile
    private var cached: String? = null

    override suspend fun key(): String = cached ?: mutex.withLock {
        cached ?: withContext(Dispatchers.IO) {
            preferences.getString(KEY, null) ?: UUID.randomUUID().toString().also { preferences.edit(commit = true) { putString(KEY, it) } }
        }.also { cached = it }
    }

    private companion object {
        const val FILE = "viewer_key"
        const val KEY = "viewerKey"
    }
}
