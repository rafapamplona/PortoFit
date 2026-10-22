package com.example.portofit.notifications

import com.example.portofit.data.auth.SessionState
import com.example.portofit.data.model.AppNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Estado local por conta (docs/notifications-api.md §15.3): o último `seq` já tratado pela consulta em segundo plano
 * ([seq], null até à primeira consulta) e o instante em que a conta passou a receber notificações do sistema
 * ([baselineMillis]): o que é anterior fica só na caixa. Nunca conteúdo nem tokens.
 */
data class AccountCursor(val seq: Long?, val baselineMillis: Long)

interface NotificationCursorStore {
    fun get(account: String): AccountCursor?

    /** Cria o estado da conta se ainda não existir, com o início em [nowMillis]; devolve o estado atual. */
    fun ensureBaseline(account: String, nowMillis: Long): AccountCursor

    /** Só avança (o maior entre o guardado e [seq]). */
    fun saveSeq(account: String, seq: Long)

    /** Apaga o estado de todas as contas exceto [account] (null: de todas). */
    fun retainOnly(account: String?)
}

/** Notificações do sistema desta app (implementação Android em AndroidSystemNotifier). */
interface SystemNotifier {
    /** Notificações permitidas pelo utilizador (permissão em Android 13+ e notificações da app ligadas). */
    fun canPost(): Boolean

    /** [items] por `seq` crescente; [more] = quantas ficaram de fora (resumo "e mais N"). */
    fun show(account: String, items: List<AppNotification>, more: Int)

    fun cancel(seq: Long)

    /** Retira as visíveis com `seq` ≤ [seq] (depois de "marcar todas como lidas"). */
    fun cancelUpTo(seq: Long)

    fun cancelAll()
}

/** Trabalho periódico do WorkManager (implementação em WorkManagerScheduler). */
interface WorkScheduler {
    fun schedule(account: String)
    fun cancel()
}

/**
 * Notificações do sistema por conta (docs/notifications-api.md §15.2, §15.6):
 * - só com sessão **e** notificações permitidas é que o trabalho periódico fica agendado, para a conta da sessão;
 * - ao terminar sessão ou trocar de conta: cancela o trabalho, retira as notificações do sistema e apaga os cursores das
 *   outras contas, antes de agendar a nova;
 * - [withAccount] corre um bloco só se a conta ainda for a da sessão, com o mesmo bloqueio das trocas: uma resposta
 *   tardia nunca mostra notificações de uma conta que já saiu.
 */
class NotificationsCoordinator(
    private val session: StateFlow<SessionState>,
    private val scheduler: WorkScheduler,
    private val notifier: SystemNotifier,
    private val cursors: NotificationCursorStore,
    scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()

    /** A conta para a qual o estado local está preparado; null sem sessão. */
    private var account: String? = null

    init {
        scope.launch {
            session.map { key(it) }.filterNotNull().distinctUntilChanged().collect { onAccount(it.takeUnless { k -> k == NO_ACCOUNT }) }
        }
    }

    /** Volta a decidir o agendamento (ao voltar à app, ou depois de a permissão mudar). */
    suspend fun sync() = lock.withLock { syncLocked() }

    suspend fun <T> withAccount(account: String, block: () -> T): T? = lock.withLock {
        if (this.account == account && (session.value as? SessionState.SignedIn)?.userId == account) block() else null
    }

    private suspend fun onAccount(new: String?) = lock.withLock {
        val previous = account
        if (new == null || (previous != null && previous != new)) {
            scheduler.cancel()
            notifier.cancelAll()
        }
        cursors.retainOnly(new)
        account = new
        syncLocked()
    }

    private fun syncLocked() {
        val current = account
        if (current != null && notifier.canPost()) {
            cursors.ensureBaseline(current, clock())
            scheduler.schedule(current)
        } else {
            scheduler.cancel()
        }
    }

    private fun key(state: SessionState): String? = when (state) {
        SessionState.Loading -> null
        is SessionState.SignedOut -> NO_ACCOUNT
        is SessionState.SignedIn -> state.userId
    }

    private companion object {
        const val NO_ACCOUNT = "\u0000sem-conta"
    }
}
