package com.example.portofit.notifications

import com.example.portofit.data.auth.SessionState
import com.example.portofit.data.model.NotificationTarget

/**
 * Toque numa notificação do sistema (docs/notifications-api.md §15.6): a conta destinatária vai no PendingIntent.
 * [notificationId], [seq] e [target] são nulos no resumo "e mais N", que abre só a caixa.
 */
data class NotificationTap(
    val account: String,
    val notificationId: String? = null,
    val seq: Long? = null,
    val target: NotificationTarget? = null,
)

sealed interface TapAction {
    /** A caixa (com o convite para entrar, sem sessão). Nunca o conteúdo. */
    data object OpenInbox : TapAction

    /** A caixa e, por cima, o destino; a notificação é marcada como lida. */
    data class OpenTarget(val notificationId: String, val seq: Long, val target: NotificationTarget) : TapAction
}

/**
 * Só abre o conteúdo se a sessão (já lida, nunca `Loading`) for da conta destinatária. Outra conta ou sem sessão: só a
 * caixa, e depois de entrar nada se abre sozinho.
 */
fun resolveTap(tap: NotificationTap, session: SessionState): TapAction {
    val sameAccount = (session as? SessionState.SignedIn)?.userId == tap.account
    val id = tap.notificationId
    val seq = tap.seq
    val target = tap.target
    return if (sameAccount && id != null && seq != null && target != null) TapAction.OpenTarget(id, seq, target) else TapAction.OpenInbox
}
