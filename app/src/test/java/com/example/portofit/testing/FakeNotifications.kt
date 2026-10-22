package com.example.portofit.testing

import com.example.portofit.data.model.AppNotification
import com.example.portofit.data.model.NotificationTarget
import com.example.portofit.data.model.NotificationType
import com.example.portofit.data.model.NotificationsPage
import com.example.portofit.data.repository.NotificationsRepository
import com.example.portofit.data.repository.SessionResult
import com.example.portofit.notifications.AccountCursor
import com.example.portofit.notifications.NotificationCursorStore
import com.example.portofit.notifications.SystemNotifier
import com.example.portofit.notifications.WorkScheduler
import java.time.Instant

/** notifications-service controlado pelos testes: cada resposta é uma função (que pode suspender); os pedidos ficam registados. */
class FakeNotificationsRepository : NotificationsRepository {
    data class PageCall(val before: Long?, val after: Long?, val limit: Int)

    val pageCalls = mutableListOf<PageCall>()
    val markReadCalls = mutableListOf<String>()
    val markAllCalls = mutableListOf<Long>()
    var unreadCalls = 0

    var onPage: suspend (PageCall) -> SessionResult<NotificationsPage> = { SessionResult.Success(pageOf(emptyList())) }
    var onUnread: suspend () -> SessionResult<Int> = { SessionResult.Success(0) }
    var onMarkRead: suspend (String) -> SessionResult<Int> = { SessionResult.Success(0) }
    var onMarkAll: suspend (Long) -> SessionResult<Int> = { SessionResult.Success(0) }

    override suspend fun page(before: Long?, after: Long?, limit: Int): SessionResult<NotificationsPage> {
        val call = PageCall(before, after, limit)
        pageCalls += call
        return onPage(call)
    }

    override suspend fun unreadCount(): SessionResult<Int> {
        unreadCalls++
        return onUnread()
    }

    override suspend fun markRead(notificationId: String): SessionResult<Int> {
        markReadCalls += notificationId
        return onMarkRead(notificationId)
    }

    override suspend fun markAllRead(upToSeq: Long): SessionResult<Int> {
        markAllCalls += upToSeq
        return onMarkAll(upToSeq)
    }

    companion object {
        /** Instante fixo dos testes; [notificationOf] cria por omissão notificações deste instante. */
        val NOW: Instant = Instant.parse("2026-10-22T12:00:00Z")

        fun notificationOf(
            seq: Long,
            type: NotificationType = NotificationType.COMMENT_CREATED,
            read: Boolean = false,
            createdAt: Instant = NOW,
            actor: String = "rui",
        ) = AppNotification(
            id = "n$seq",
            seq = seq,
            type = type,
            actorId = "$actor-id",
            actorUsername = actor,
            shared = false,
            target = when (type) {
                NotificationType.MESSAGE_RECEIVED -> NotificationTarget.Conversation("c$seq", actor)
                NotificationType.USER_FOLLOWED -> NotificationTarget.Person("$actor-id")
                else -> NotificationTarget.Experience("e$seq")
            },
            createdAt = createdAt,
            read = read,
        )

        /**
         * Responde como o notifications-service (docs/notifications-api.md §5.1) sobre as notificações de [all]: sem
         * cursor, as mais recentes; `before`, as anteriores (decrescente); `after`, as novas (crescente). [lastSeq] pode
         * ser maior do que o maior `seq` que resta (retenção).
         */
        fun serve(all: () -> List<AppNotification>, lastSeq: () -> Long = { all().maxOfOrNull { it.seq } ?: 0 }): suspend (PageCall) -> SessionResult<NotificationsPage> = { call ->
            val ascending = all().sortedBy { it.seq }
            val last = lastSeq()
            SessionResult.Success(
                when {
                    call.after != null -> ascending.filter { it.seq > call.after }.let { NotificationsPage(it.take(call.limit), last, false, it.size > call.limit) }
                    call.before != null -> ascending.filter { it.seq < call.before }.reversed().let { NotificationsPage(it.take(call.limit), last, it.size > call.limit, false) }
                    else -> ascending.reversed().let { NotificationsPage(it.take(call.limit), last, it.size > call.limit, false) }
                },
            )
        }

        /** [items] pela ordem em que o servidor os devolve. */
        fun pageOf(items: List<AppNotification>, lastSeq: Long = items.maxOfOrNull { it.seq } ?: 0, hasMoreBefore: Boolean = false, hasMoreAfter: Boolean = false) =
            NotificationsPage(items, lastSeq, hasMoreBefore, hasMoreAfter)
    }
}

/** Notificações do sistema em memória: o que está "visível" e o que foi pedido. */
class FakeSystemNotifier(var allowed: Boolean = true) : SystemNotifier {
    data class Shown(val account: String, val seqs: List<Long>, val more: Int)

    val shown = mutableListOf<Shown>()
    val visible = sortedSetOf<Long>()
    var summaryVisible = false
    val cancelled = mutableListOf<Long>()
    var cancelAllCalls = 0

    override fun canPost() = allowed

    override fun show(account: String, items: List<AppNotification>, more: Int) {
        shown += Shown(account, items.map { it.seq }, more)
        visible += items.map { it.seq }
        if (more > 0) summaryVisible = true
    }

    override fun cancel(seq: Long) {
        cancelled += seq
        visible -= seq
    }

    override fun cancelUpTo(seq: Long) {
        visible.removeAll { it <= seq }
    }

    override fun cancelAll() {
        cancelAllCalls++
        visible.clear()
        summaryVisible = false
    }
}

class FakeWorkScheduler : WorkScheduler {
    /** A conta com trabalho agendado (null: nenhum). */
    var scheduled: String? = null
    val history = mutableListOf<String>()

    override fun schedule(account: String) {
        scheduled = account
        history += "schedule:$account"
    }

    override fun cancel() {
        scheduled = null
        history += "cancel"
    }
}

class InMemoryCursorStore : NotificationCursorStore {
    val cursors = mutableMapOf<String, AccountCursor>()

    override fun get(account: String) = cursors[account]

    override fun ensureBaseline(account: String, nowMillis: Long) = cursors.getOrPut(account) { AccountCursor(null, nowMillis) }

    override fun saveSeq(account: String, seq: Long) {
        val current = cursors[account] ?: return
        if (current.seq == null || seq > current.seq) cursors[account] = current.copy(seq = seq)
    }

    override fun retainOnly(account: String?) {
        cursors.keys.retainAll { it == account }
    }
}
