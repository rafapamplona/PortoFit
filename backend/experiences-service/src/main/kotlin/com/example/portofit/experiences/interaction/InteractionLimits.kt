package com.example.portofit.experiences.interaction

import com.example.portofit.experiences.api.ApiException
import com.example.portofit.experiences.api.ApiErrors
import com.example.portofit.experiences.config.InteractionProperties
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Contador por pessoa com janela fixa, em memória, como o limite de publicações (PublishLimiter): perde-se ao
 * reiniciar, não é partilhado entre instâncias e, com pedidos simultâneos da mesma pessoa, pode ser ultrapassado
 * em poucas unidades. Serve para travar abusos e erros, não como contagem exata.
 */
class WindowLimiter(
	private val name: String,
	private val max: Int,
	private val window: Duration,
	private val clock: Clock,
	private val error: (retryAfterSeconds: Long) -> ApiException,
) {
	private data class Counter(val windowStart: Instant, val count: Int)

	private val log = LoggerFactory.getLogger(javaClass)
	private val counters = ConcurrentHashMap<UUID, Counter>()

	/** Antes de escrever: recusa com 429 e Retry-After se a pessoa já atingiu o limite. */
	fun check(userId: UUID) {
		val now = clock.instant()
		val counter = counters[userId]?.takeUnless { expired(it, now) } ?: return
		if (counter.count >= max) {
			log.warn("Limite de {} atingido pelo utilizador {}", name, userId)
			val millis = Duration.between(now, counter.windowStart.plus(window)).toMillis()
			throw error(maxOf(1, (millis + 999) / 1000))
		}
	}

	/** Depois de uma escrita que mudou alguma coisa. */
	fun record(userId: UUID) {
		val now = clock.instant()
		counters.compute(userId) { _, old -> if (old == null || expired(old, now)) Counter(now, 1) else old.copy(count = old.count + 1) }
	}

	fun removeExpired() {
		val now = clock.instant()
		counters.entries.removeIf { expired(it.value, now) }
	}

	private fun expired(counter: Counter, now: Instant) = !now.isBefore(counter.windowStart.plus(window))
}

/** Limites de comentários criados e de mudanças de gosto (docs/experiences-api.md §13.6). */
@Component
class InteractionLimits(properties: InteractionProperties, clock: Clock) {
	val comments = WindowLimiter("comentários", properties.maxCommentsPerAuthor, properties.commentWindow, clock, ApiErrors::tooManyComments)
	val likes = WindowLimiter("gostos", properties.maxLikeChangesPerUser, properties.likeWindow, clock, ApiErrors::tooManyLikes)

	@Scheduled(fixedDelayString = "PT10M")
	fun removeExpired() {
		comments.removeExpired()
		likes.removeExpired()
	}
}
