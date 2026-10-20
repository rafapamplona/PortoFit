package com.example.portofit.messaging.conversation

import com.example.portofit.messaging.config.MessagingProperties
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Contador por pessoa com janela fixa, em memória (docs/messaging-api.md §10). [tryAcquire] reserva uma unidade de forma
 * atómica, por isso pedidos simultâneos nunca passam do limite; quem reservou e acabou por não criar nada (repetição,
 * recusa, falha) devolve a unidade com [release]. Perde-se ao reiniciar e não é partilhado entre instâncias.
 */
class WindowCounter(private val limit: Int, private val window: Duration, private val clock: Clock) {

	private data class Counter(val windowStart: Instant, val count: Int)

	private val counters = ConcurrentHashMap<UUID, Counter>()

	/** null = reservado; senão, segundos até ao fim da janela (para `Retry-After`). */
	fun tryAcquire(userId: UUID): Long? {
		var retryAfter: Long? = null
		counters.compute(userId) { _, old ->
			val now = clock.instant()
			when {
				old == null || expired(old, now) -> Counter(now, 1)
				old.count >= limit -> old.also { retryAfter = secondsLeft(it, now) }
				else -> old.copy(count = old.count + 1)
			}
		}
		return retryAfter
	}

	fun release(userId: UUID) {
		counters.computeIfPresent(userId) { _, old ->
			if (expired(old, clock.instant())) null else old.copy(count = maxOf(0, old.count - 1))
		}
	}

	fun removeExpired() {
		val now = clock.instant()
		counters.entries.removeIf { expired(it.value, now) }
	}

	private fun expired(counter: Counter, now: Instant) = !now.isBefore(counter.windowStart.plus(window))

	/** Segundos até ao fim da janela, arredondados para cima (mínimo 1). */
	private fun secondsLeft(counter: Counter, now: Instant): Long {
		val millis = Duration.between(now, counter.windowStart.plus(window)).toMillis()
		return maxOf(1, (millis + 999) / 1000)
	}
}

/** Os dois limites de frequência do contrato: mensagens enviadas e conversas novas. */
@Component
class MessagingLimits(properties: MessagingProperties, clock: Clock) {
	val messages = WindowCounter(properties.maxMessagesPerWindow, properties.rateWindow, clock)
	val conversations = WindowCounter(properties.maxNewConversationsPerWindow, properties.rateWindow, clock)

	@Scheduled(fixedDelayString = "PT10M")
	fun removeExpired() {
		messages.removeExpired()
		conversations.removeExpired()
	}
}
