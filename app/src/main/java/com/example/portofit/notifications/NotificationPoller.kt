package com.example.portofit.notifications

import com.example.portofit.data.auth.SessionState
import com.example.portofit.data.model.AppNotification
import com.example.portofit.data.repository.NotificationsRepository
import com.example.portofit.data.repository.SessionResult
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class PollOutcome {
    /** Consulta feita (com ou sem notificações novas); o cursor avançou até onde foi lido. */
    DONE,

    /** Nada a fazer: outra conta, sem sessão, ou sessão por confirmar (sem renovar às cegas). */
    SKIPPED,

    /** Falha de rede ou do servidor: o WorkManager repete com o seu recuo; o cursor não avançou e nada foi mostrado. */
    RETRY,
}

/**
 * Uma execução da consulta em segundo plano (docs/notifications-api.md §15.3), chamada pelo NotificationsWorker:
 * 1. espera que a sessão guardada seja lida e confirma que é a da [account] do trabalho;
 * 2. pede as novas depois do cursor da conta (`after`, até [maxPages] páginas de [pageSize]); o token vem do
 *    SessionManager da app, por isso a renovação é a mesma dos ecrãs e uma renovação ambígua nunca se repete aqui;
 * 3. mostra só as não lidas, criadas depois de a conta passar a receber notificações, no máximo [maxShown] e um resumo;
 * 4. guarda o novo cursor — os passos 3 e 4 só com a conta ainda ativa.
 *
 * Lacunas de `seq` (retenção, limite por pessoa) não são erros: o cursor avança para o maior `seq` lido. Execuções
 * sobrepostas são feitas uma de cada vez, e uma repetida não mostra nada que já tenha mostrado.
 */
class NotificationPoller(
    private val repository: NotificationsRepository,
    private val session: StateFlow<SessionState>,
    private val coordinator: NotificationsCoordinator,
    private val cursors: NotificationCursorStore,
    private val notifier: SystemNotifier,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pageSize: Int = PAGE_SIZE,
    private val maxPages: Int = MAX_PAGES,
    private val maxShown: Int = MAX_SHOWN,
) {
    private val running = Mutex()

    suspend fun poll(account: String): PollOutcome = running.withLock {
        val state = session.first { it !is SessionState.Loading }
        if ((state as? SessionState.SignedIn)?.userId != account) return PollOutcome.SKIPPED
        val stored = cursors.get(account) ?: cursors.ensureBaseline(account, clock())

        var cursor = stored.seq ?: when (val first = repository.page(before = null, after = null, limit = pageSize)) {
            is SessionResult.Success -> initialCursor(first.value.items, first.value.lastSeq, stored.baselineMillis)
            is SessionResult.Failure -> return PollOutcome.RETRY
            else -> return PollOutcome.SKIPPED
        }
        val fresh = mutableListOf<AppNotification>()
        for (page in 1..maxPages) {
            when (val result = repository.page(before = null, after = cursor, limit = pageSize)) {
                is SessionResult.Success -> {
                    fresh += result.value.items
                    cursor = maxOf(cursor, result.value.highestSeq ?: cursor)
                    if (!result.value.hasMoreAfter) break
                }
                is SessionResult.Failure -> return PollOutcome.RETRY
                else -> return PollOutcome.SKIPPED
            }
        }
        val toShow = fresh.filter { !it.read && it.createdAt.toEpochMilli() >= stored.baselineMillis }.sortedBy { it.seq }
        val shown = toShow.takeLast(maxShown)
        coordinator.withAccount(account) {
            if (shown.isNotEmpty()) notifier.show(account, shown, toShow.size - shown.size)
            cursors.saveSeq(account, cursor)
        } ?: return PollOutcome.SKIPPED
        PollOutcome.DONE
    }

    /**
     * Primeira consulta desta conta: começa na mais recente criada antes do início ([baselineMillis]), para não
     * transformar o histórico em notificações do sistema. Sem nenhuma anterior na página, começa do início e o filtro
     * pela data faz o resto.
     */
    private fun initialCursor(newestFirst: List<AppNotification>, lastSeq: Long, baselineMillis: Long): Long =
        newestFirst.firstOrNull { it.createdAt.toEpochMilli() < baselineMillis }?.seq
            ?: if (newestFirst.isEmpty()) lastSeq else 0L

    companion object {
        const val PAGE_SIZE = 50
        const val MAX_PAGES = 3
        const val MAX_SHOWN = 5
    }
}
