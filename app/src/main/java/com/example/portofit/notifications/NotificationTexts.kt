package com.example.portofit.notifications

import androidx.annotation.StringRes
import com.example.portofit.R
import com.example.portofit.data.model.AppNotification
import com.example.portofit.data.model.NotificationType

/**
 * Texto de uma notificação, na caixa e na barra do sistema (docs/notifications-api.md §9): só quem e o quê, com o
 * username (argumento do texto). Nunca o texto de uma mensagem ou de um comentário — o servidor nem o tem.
 */
@StringRes
fun notificationText(n: AppNotification): Int = when (n.type) {
    NotificationType.MESSAGE_RECEIVED -> if (n.shared) R.string.notification_text_shared else R.string.notification_text_message
    NotificationType.COMMENT_CREATED -> R.string.notification_text_comment
    NotificationType.USER_FOLLOWED -> R.string.notification_text_follow
    NotificationType.EXPERIENCE_PUBLISHED -> R.string.notification_text_published
}

/** Os quatro canais (§15.4); o utilizador pode desligar cada um nas definições do Android. */
enum class NotificationChannelKind(val id: String, @StringRes val label: Int, val important: Boolean) {
    MESSAGES("messages", R.string.notification_channel_messages, important = true),
    COMMENTS("comments", R.string.notification_channel_comments, important = true),
    FOLLOWERS("followers", R.string.notification_channel_followers, important = false),
    EXPERIENCES("experiences", R.string.notification_channel_experiences, important = false),
    ;

    companion object {
        fun of(type: NotificationType) = when (type) {
            NotificationType.MESSAGE_RECEIVED -> MESSAGES
            NotificationType.COMMENT_CREATED -> COMMENTS
            NotificationType.USER_FOLLOWED -> FOLLOWERS
            NotificationType.EXPERIENCE_PUBLISHED -> EXPERIENCES
        }
    }
}
