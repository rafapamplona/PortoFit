package com.example.portofit.users.security

import com.example.portofit.users.api.ApiErrors
import com.example.portofit.users.config.RateLimitProperties
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap

/**
 * Contadores de janela fixa em memória (docs/users-api.md §6.8). Perdem-se ao reiniciar e não são
 * partilhados entre instâncias.
 */
class WindowCounters(private val window: Duration, private val clock: Clock) {

	private data class Counter(val windowStart: Instant, val count: Int)

	private val counters = ConcurrentHashMap<String, Counter>()

	/** Segundos até a janela terminar, se o contador já atingiu [limit]; senão null. */
	fun blockedFor(key: String, limit: Int): Long? {
		val counter = current(key) ?: return null
		return if (counter.count >= limit) secondsLeft(counter) else null
	}

	/** Conta mais uma ocorrência. */
	fun increment(key: String) {
		val now = clock.instant()
		counters.compute(key) { _, old ->
			if (old == null || expired(old, now)) Counter(now, 1) else old.copy(count = old.count + 1)
		}
	}

	/** Conta a ocorrência e indica se ficou dentro do limite; senão devolve os segundos de espera. */
	fun tryAcquire(key: String, limit: Int): Long? {
		blockedFor(key, limit)?.let { return it }
		increment(key)
		return null
	}

	fun reset(key: String) {
		counters.remove(key)
	}

	fun removeExpired() {
		val now = clock.instant()
		counters.entries.removeIf { expired(it.value, now) }
	}

	private fun current(key: String): Counter? {
		val counter = counters[key] ?: return null
		return if (expired(counter, clock.instant())) null else counter
	}

	private fun expired(counter: Counter, now: Instant) = !now.isBefore(counter.windowStart.plus(window))

	/** Segundos até ao fim da janela, arredondados para cima (mínimo 1). */
	private fun secondsLeft(counter: Counter): Long {
		val millis = Duration.between(clock.instant(), counter.windowStart.plus(window)).toMillis()
		return maxOf(1, (millis + 999) / 1000)
	}
}

/** Aplica os limites de login, registo e renovação, por conta e por origem. */
@Component
class AttemptLimiter(private val properties: RateLimitProperties) {

	private val log = LoggerFactory.getLogger(javaClass)
	private val counters = WindowCounters(properties.window, Clock.systemUTC())

	/** Antes de verificar a palavra-passe: limite por origem (todas as tentativas) e por conta (falhas). */
	fun beforeLogin(normalizedEmail: String, origin: String) {
		acquire("login-origin:$origin", properties.loginAttemptsPerOrigin, "login por origem", origin)
		counters.blockedFor(accountKey(normalizedEmail), properties.loginFailuresPerAccount)?.let {
			log.warn("Limite de tentativas atingido: login por conta (origem {})", origin)
			throw ApiErrors.tooManyAttempts(it)
		}
	}

	fun loginFailed(normalizedEmail: String) = counters.increment(accountKey(normalizedEmail))

	fun loginSucceeded(normalizedEmail: String) = counters.reset(accountKey(normalizedEmail))

	fun beforeRegister(origin: String) =
		acquire("register-origin:$origin", properties.registerPerOrigin, "registo por origem", origin)

	fun beforeRefresh(origin: String) =
		acquire("refresh-origin:$origin", properties.refreshPerOrigin, "refresh por origem", origin)

	@Scheduled(fixedDelayString = "PT5M")
	fun removeExpired() = counters.removeExpired()

	private fun acquire(key: String, limit: Int, kind: String, origin: String) {
		counters.tryAcquire(key, limit)?.let {
			log.warn("Limite de tentativas atingido: {} (origem {})", kind, origin)
			throw ApiErrors.tooManyAttempts(it)
		}
	}

	/** O email não fica em memória nem em logs: a chave é o seu hash SHA-256. */
	private fun accountKey(normalizedEmail: String): String {
		val digest = MessageDigest.getInstance("SHA-256").digest(normalizedEmail.toByteArray(Charsets.UTF_8))
		return "login-account:" + HexFormat.of().formatHex(digest)
	}
}
