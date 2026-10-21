package com.example.portofit.ui.feature.messaging

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Consulta periódica só com o ecrã ativo (docs/messaging-api.md §12):
 * - [start] faz logo uma consulta (ao voltar ao ecrã) e repete de [baseMillis] em [baseMillis];
 * - [stop] cancela o ciclo (o ecrã deixou de estar RESUMED ou a sessão mudou).
 *
 * Com erros, o intervalo duplica até [maxMillis] e volta ao normal na primeira resposta boa. Nunca há duas consultas em
 * simultâneo: um pedido de [pollNow] durante uma consulta fica para logo a seguir a ela, uma única vez.
 *
 * [tick] devolve false quando a consulta falhou.
 */
class Poller(
    private val scope: CoroutineScope,
    private val baseMillis: Long,
    private val maxMillis: Long = MAX_INTERVAL_MILLIS,
    private val tick: suspend () -> Boolean,
) {
    private val running = Mutex()
    private var loop: Job? = null
    private var immediate: Job? = null
    private var again = false

    /** Intervalo até à próxima consulta (sobe com os erros). */
    var intervalMillis: Long = baseMillis
        private set

    val isRunning: Boolean get() = loop?.isActive == true

    /** [immediate] = false quando os dados acabaram de ser carregados: a primeira consulta fica para o fim do intervalo. */
    fun start(immediate: Boolean = true) {
        if (loop?.isActive == true) return
        loop = scope.launch {
            if (!immediate) delay(intervalMillis)
            while (isActive) {
                runOnce()
                delay(intervalMillis)
            }
        }
    }

    fun stop() {
        loop?.cancel()
        immediate?.cancel()
        loop = null
        immediate = null
        again = false
    }

    /** Consulta já (ex.: depois de enviar), sem esperar pelo intervalo; só com o ciclo ativo. */
    fun pollNow() {
        if (!isRunning) return
        immediate = scope.launch { runOnce() }
    }

    /** Volta ao intervalo normal (ex.: depois de uma troca de conta). */
    fun reset() {
        intervalMillis = baseMillis
    }

    private suspend fun runOnce() {
        if (!running.tryLock()) {
            again = true
            return
        }
        try {
            do {
                again = false
                val ok = tick()
                intervalMillis = if (ok) baseMillis else (intervalMillis * 2).coerceAtMost(maxMillis)
            } while (again && isRunning)
        } finally {
            running.unlock()
        }
    }

    companion object {
        const val CONVERSATION_MILLIS = 10_000L
        const val INBOX_MILLIS = 30_000L
        const val MAX_INTERVAL_MILLIS = 60_000L
    }
}
