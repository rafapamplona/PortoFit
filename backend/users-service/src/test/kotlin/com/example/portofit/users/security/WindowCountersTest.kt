package com.example.portofit.users.security

import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Contadores de tentativas por origem e por conta, com um relógio controlado. */
class WindowCountersTest {

	private class MutableClock(var now: Instant) : Clock() {
		override fun instant() = now
		override fun getZone() = ZoneOffset.UTC
		override fun withZone(zone: java.time.ZoneId?) = this
	}

	private val clock = MutableClock(Instant.parse("2026-10-18T10:00:00Z"))
	private val counters = WindowCounters(Duration.ofMinutes(15), clock)

	@Test
	fun `limite por origem recusa a partir do limite e indica a espera`() {
		repeat(3) { assertNull(counters.tryAcquire("login-origin:10.0.0.1", 3)) }
		val wait = counters.tryAcquire("login-origin:10.0.0.1", 3)
		assertNotNull(wait)
		assertTrue(wait in 1..900, "espera: $wait")
		// Outra origem não é afetada.
		assertNull(counters.tryAcquire("login-origin:10.0.0.2", 3))
	}

	@Test
	fun `janela termina e o contador recomeca`() {
		repeat(3) { counters.tryAcquire("k", 3) }
		assertNotNull(counters.blockedFor("k", 3))
		clock.now = clock.now.plus(Duration.ofMinutes(15))
		assertNull(counters.blockedFor("k", 3))
		assertNull(counters.tryAcquire("k", 3))
	}

	@Test
	fun `falhas por conta contam so quando registadas e repoem com sucesso`() {
		repeat(4) { counters.increment("acc") }
		assertNull(counters.blockedFor("acc", 5))
		counters.increment("acc")
		assertNotNull(counters.blockedFor("acc", 5))
		counters.reset("acc")
		assertNull(counters.blockedFor("acc", 5))
	}

	@Test
	fun `limpeza remove contadores expirados`() {
		counters.increment("a")
		clock.now = clock.now.plus(Duration.ofMinutes(16))
		counters.removeExpired()
		assertEquals(null, counters.blockedFor("a", 1))
	}
}
