package com.example.portofit.experiences.experience

import com.example.portofit.experiences.api.ApiErrors
import com.example.portofit.experiences.config.PublishingProperties
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Limite de publicações por autor, com janela fixa em memória (docs/experiences-api.md §4.1). Só contam
 * publicações criadas. Perde-se ao reiniciar, não é partilhado entre instâncias e, com pedidos simultâneos
 * do mesmo autor, pode ser ultrapassado em poucas unidades (verificação e contagem não são atómicas com a
 * gravação). Serve para travar abusos e erros, não como contagem exata.
 */
@Component
class PublishLimiter(private val properties: PublishingProperties, private val clock: Clock) {

	private data class Counter(val windowStart: Instant, val count: Int)

	private val log = LoggerFactory.getLogger(javaClass)
	private val counters = ConcurrentHashMap<UUID, Counter>()

	/** Antes de publicar: recusa com 429 e Retry-After se o autor já atingiu o limite. */
	fun check(authorId: UUID) {
		val counter = current(authorId) ?: return
		if (counter.count >= properties.maxPerAuthor) {
			log.warn("Limite de publicações atingido pelo autor {}", authorId)
			throw ApiErrors.tooManyPublications(secondsLeft(counter))
		}
	}

	/** Depois de criar uma experiência. */
	fun record(authorId: UUID) {
		val now = clock.instant()
		counters.compute(authorId) { _, old ->
			if (old == null || expired(old, now)) Counter(now, 1) else old.copy(count = old.count + 1)
		}
	}

	@Scheduled(fixedDelayString = "PT10M")
	fun removeExpired() {
		val now = clock.instant()
		counters.entries.removeIf { expired(it.value, now) }
	}

	private fun current(authorId: UUID): Counter? = counters[authorId]?.takeUnless { expired(it, clock.instant()) }

	private fun expired(counter: Counter, now: Instant) = !now.isBefore(counter.windowStart.plus(properties.window))

	/** Segundos até ao fim da janela, arredondados para cima (mínimo 1). */
	private fun secondsLeft(counter: Counter): Long {
		val millis = Duration.between(clock.instant(), counter.windowStart.plus(properties.window)).toMillis()
		return maxOf(1, (millis + 999) / 1000)
	}
}
