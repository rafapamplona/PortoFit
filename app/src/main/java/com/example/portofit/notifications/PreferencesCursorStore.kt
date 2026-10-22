package com.example.portofit.notifications

import android.content.Context
import androidx.core.content.edit

/**
 * Cursores por conta em `shared_prefs/notifications_cursors.xml`, excluído das cópias de segurança
 * (res/xml/backup_rules.xml e data_extraction_rules.xml). Só números: o último `seq` tratado e o início, por id de conta.
 */
class PreferencesCursorStore(context: Context) : NotificationCursorStore {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Synchronized
    override fun get(account: String): AccountCursor? {
        if (!prefs.contains(baselineKey(account))) return null
        val seq = if (prefs.contains(seqKey(account))) prefs.getLong(seqKey(account), 0) else null
        return AccountCursor(seq, prefs.getLong(baselineKey(account), 0))
    }

    @Synchronized
    override fun ensureBaseline(account: String, nowMillis: Long): AccountCursor =
        get(account) ?: AccountCursor(null, nowMillis).also { prefs.edit(commit = true) { putLong(baselineKey(account), nowMillis) } }

    @Synchronized
    override fun saveSeq(account: String, seq: Long) {
        val current = get(account) ?: return
        if (current.seq == null || seq > current.seq) prefs.edit(commit = true) { putLong(seqKey(account), seq) }
    }

    @Synchronized
    override fun retainOnly(account: String?) {
        val keep = account?.let { setOf(seqKey(it), baselineKey(it)) }.orEmpty()
        val stale = prefs.all.keys.filter { it !in keep }
        if (stale.isNotEmpty()) prefs.edit(commit = true) { stale.forEach { remove(it) } }
    }

    private fun seqKey(account: String) = "seq:$account"

    private fun baselineKey(account: String) = "baseline:$account"

    private companion object {
        const val FILE = "notifications_cursors"
    }
}
