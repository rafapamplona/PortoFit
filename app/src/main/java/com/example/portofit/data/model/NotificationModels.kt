package com.example.portofit.data.model

import java.time.Instant

/** Tipos da primeira versão (docs/notifications-api.md §3); gostos não geram notificações. */
enum class NotificationType { MESSAGE_RECEIVED, COMMENT_CREATED, USER_FOLLOWED, EXPERIENCE_PUBLISHED }

/** Destino de uma notificação (§15.1): só ids, o conteúdo é pedido ao dono quando se abre. */
sealed interface NotificationTarget {
    data class Conversation(val conversationId: String, val username: String) : NotificationTarget
    data class Experience(val experienceId: String) : NotificationTarget
    data class Person(val userId: String) : NotificationTarget
}

/** Uma notificação da caixa. Sem conteúdo privado: tipo, quem, referências e datas. */
data class AppNotification(
    val id: String,
    val seq: Long,
    val type: NotificationType,
    val actorId: String,
    val actorUsername: String,
    /** Mensagem com uma experiência partilhada (só o tipo; nunca o texto). */
    val shared: Boolean,
    val target: NotificationTarget,
    val createdAt: Instant,
    val read: Boolean,
)

/**
 * Uma página por `seq` (§5.1): lacunas são normais; [lastSeq] é o maior `seq` desta conta. [lowestSeq] e [highestSeq]
 * contam também itens ignorados (tipo desconhecido), para os cursores nunca ficarem parados neles.
 */
data class NotificationsPage(
    val items: List<AppNotification>,
    val lastSeq: Long,
    val hasMoreBefore: Boolean,
    val hasMoreAfter: Boolean,
    val lowestSeq: Long? = items.minOfOrNull { it.seq },
    val highestSeq: Long? = items.maxOfOrNull { it.seq },
)
