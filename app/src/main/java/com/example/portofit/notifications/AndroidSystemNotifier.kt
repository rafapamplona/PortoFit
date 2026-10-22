package com.example.portofit.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.portofit.MainActivity
import com.example.portofit.R
import com.example.portofit.data.model.AppNotification
import com.example.portofit.data.model.NotificationTarget

/**
 * Notificações do sistema (docs/notifications-api.md §15.4–15.5):
 * - um canal por tipo; visibilidade privada, com uma versão pública genérica para o ecrã bloqueado;
 * - o id Android deriva do `seq` (uma repetição substitui, nunca duplica);
 * - o toque abre a MainActivity com a conta destinatária; dispensar não faz nada (não há deleteIntent).
 */
class AndroidSystemNotifier(context: Context) : SystemNotifier {

    private val context = context.applicationContext
    private val manager = NotificationManagerCompat.from(this.context)

    /** Cria (ou atualiza o nome de) os quatro canais; é seguro repetir (minSdk 26: há sempre canais). */
    fun createChannels() {
        val system = context.getSystemService(NotificationManager::class.java)
        NotificationChannelKind.entries.forEach { kind ->
            val importance = if (kind.important) NotificationManager.IMPORTANCE_DEFAULT else NotificationManager.IMPORTANCE_LOW
            system.createNotificationChannel(
                NotificationChannel(kind.id, context.getString(kind.label), importance).apply {
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
                },
            )
        }
    }

    override fun canPost(): Boolean {
        val permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return permitted && manager.areNotificationsEnabled()
    }

    override fun show(account: String, items: List<AppNotification>, more: Int) {
        if (!canPost()) return
        try {
            items.forEach { manager.notify(TAG, idOf(it.seq), build(account, it)) }
            if (more > 0) manager.notify(TAG, SUMMARY_ID, summary(account, more))
        } catch (e: SecurityException) {
            // Permissão retirada entre a verificação e a publicação: nada é mostrado (a caixa continua).
        }
    }

    override fun cancel(seq: Long) = manager.cancel(TAG, idOf(seq))

    override fun cancelUpTo(seq: Long) {
        manager.activeNotifications.filter { it.tag == TAG && it.id != SUMMARY_ID && it.id <= idOf(seq) }.forEach { manager.cancel(TAG, it.id) }
    }

    override fun cancelAll() = manager.cancelAll()

    private fun build(account: String, n: AppNotification) =
        base(NotificationChannelKind.of(n.type))
            .setContentTitle(context.getString(notificationText(n), n.actorUsername))
            .setContentText(context.getString(R.string.notification_tap_to_open))
            .setWhen(n.createdAt.toEpochMilli())
            .setShowWhen(true)
            .setGroup(NotificationChannelKind.of(n.type).id)
            .setContentIntent(pendingIntent(idOf(n.seq), NotificationTap(account, n.id, n.seq, n.target)))
            .build()

    private fun summary(account: String, more: Int) =
        base(NotificationChannelKind.EXPERIENCES)
            .setContentTitle(context.resources.getQuantityString(R.plurals.notification_summary, more, more))
            .setContentText(context.getString(R.string.notification_tap_to_open))
            .setContentIntent(pendingIntent(SUMMARY_ID, NotificationTap(account)))
            .build()

    private fun base(kind: NotificationChannelKind) = NotificationCompat.Builder(context, kind.id)
        .setSmallIcon(R.drawable.ic_notification)
        .setAutoCancel(true)
        .setCategory(if (kind == NotificationChannelKind.MESSAGES) NotificationCompat.CATEGORY_MESSAGE else NotificationCompat.CATEGORY_SOCIAL)
        .setPriority(if (kind.important) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_LOW)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setPublicVersion(
            NotificationCompat.Builder(context, kind.id)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(R.string.notification_public_text))
                .build(),
        )

    private fun pendingIntent(requestCode: Int, tap: NotificationTap): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            NotificationIntents.intent(context, tap),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private companion object {
        const val TAG = "portofit"
        const val SUMMARY_ID = 0

        /** `seq` > 0; muito acima do limite de um Int só por milhares de milhões de notificações. */
        fun idOf(seq: Long): Int = (seq % Int.MAX_VALUE).toInt()
    }
}

/** O que vai no PendingIntent: só ids e a conta destinatária (nada de conteúdo). */
object NotificationIntents {
    const val ACTION_OPEN = "com.example.portofit.action.OPEN_NOTIFICATION"
    private const val ACCOUNT = "account"
    private const val ID = "notificationId"
    private const val SEQ = "seq"
    private const val KIND = "targetKind"
    private const val REF = "targetId"
    private const val NAME = "targetName"

    fun intent(context: Context, tap: NotificationTap): Intent = Intent(context, MainActivity::class.java).apply {
        action = ACTION_OPEN
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra(ACCOUNT, tap.account)
        tap.notificationId?.let { putExtra(ID, it) }
        tap.seq?.let { putExtra(SEQ, it) }
        when (val target = tap.target) {
            is NotificationTarget.Conversation -> putExtra(KIND, "conversation").putExtra(REF, target.conversationId).putExtra(NAME, target.username)
            is NotificationTarget.Experience -> putExtra(KIND, "experience").putExtra(REF, target.experienceId)
            is NotificationTarget.Person -> putExtra(KIND, "person").putExtra(REF, target.userId)
            null -> Unit
        }
    }

    /** null para outro intent (ex.: abrir a app pelo ícone). */
    fun tapOf(intent: Intent?): NotificationTap? {
        if (intent?.action != ACTION_OPEN) return null
        val account = intent.getStringExtra(ACCOUNT) ?: return null
        val ref = intent.getStringExtra(REF)
        val target = when (intent.getStringExtra(KIND)) {
            "conversation" -> ref?.let { NotificationTarget.Conversation(it, intent.getStringExtra(NAME).orEmpty()) }
            "experience" -> ref?.let { NotificationTarget.Experience(it) }
            "person" -> ref?.let { NotificationTarget.Person(it) }
            else -> null
        }
        return NotificationTap(account, intent.getStringExtra(ID), intent.getLongExtra(SEQ, -1).takeIf { it > 0 }, target)
    }
}
