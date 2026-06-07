package org.branneman.health.auth

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

private class FakeLoginAttemptsStore(
    initial: Map<String, AttemptRecord> = emptyMap()
) : LoginAttemptsStore {
    val records = initial.toMutableMap()
    override fun loadAll() = records.toMap()
    override fun save(key: String, record: AttemptRecord) { records[key] = record }
    override fun delete(key: String) { records.remove(key) }
}

class RateLimiterTest {

    private val baseTime = Instant.parse("2026-06-03T12:00:00Z")

    @Test
    fun `4 failures produce no lockout`() {
        val limiter = RateLimiter(Clock.fixed(baseTime, ZoneOffset.UTC))
        repeat(4) { limiter.recordFailure("key") }
        assertNull(limiter.isLocked("key"))
    }

    @Test
    fun `5th failure locks for 60 seconds`() {
        val limiter = RateLimiter(Clock.fixed(baseTime, ZoneOffset.UTC))
        repeat(5) { limiter.recordFailure("key") }
        assertEquals(60L, limiter.isLocked("key"))
    }

    @Test
    fun `6th failure extends lockout to 120 seconds`() {
        val limiter = RateLimiter(Clock.fixed(baseTime, ZoneOffset.UTC))
        repeat(6) { limiter.recordFailure("key") }
        assertEquals(120L, limiter.isLocked("key"))
    }

    @Test
    fun `7th failure extends lockout to 240 seconds`() {
        val limiter = RateLimiter(Clock.fixed(baseTime, ZoneOffset.UTC))
        repeat(7) { limiter.recordFailure("key") }
        assertEquals(240L, limiter.isLocked("key"))
    }

    @Test
    fun `lockout caps at 3600 seconds`() {
        val limiter = RateLimiter(Clock.fixed(baseTime, ZoneOffset.UTC))
        repeat(20) { limiter.recordFailure("key") }
        assertEquals(3600L, limiter.isLocked("key"))
    }

    @Test
    fun `reset clears lockout`() {
        val limiter = RateLimiter(Clock.fixed(baseTime, ZoneOffset.UTC))
        repeat(5) { limiter.recordFailure("key") }
        assertNotNull(limiter.isLocked("key"))
        limiter.reset("key")
        assertNull(limiter.isLocked("key"))
    }

    @Test
    fun `unknown key is not locked`() {
        val limiter = RateLimiter(Clock.fixed(baseTime, ZoneOffset.UTC))
        assertNull(limiter.isLocked("nobody"))
    }

    @Test
    fun `different keys are independent`() {
        val limiter = RateLimiter(Clock.fixed(baseTime, ZoneOffset.UTC))
        repeat(5) { limiter.recordFailure("keyA") }
        assertNotNull(limiter.isLocked("keyA"))
        assertNull(limiter.isLocked("keyB"))
    }

    @Test
    fun `lockout expires after its duration`() {
        val limiter = RateLimiter(Clock.fixed(baseTime, ZoneOffset.UTC))
        repeat(5) { limiter.recordFailure("key") }
        assertNotNull(limiter.isLocked("key"))
        val laterLimiter = RateLimiter(Clock.fixed(baseTime.plusSeconds(61), ZoneOffset.UTC))
        assertNull(laterLimiter.isLocked("fresh_key"))
    }

    @Test
    fun `loads lockout state from store on startup`() {
        val lockedUntil = baseTime.plusSeconds(60)
        val store = FakeLoginAttemptsStore(mapOf("key" to AttemptRecord(5, lockedUntil)))
        val limiter = RateLimiter(Clock.fixed(baseTime, ZoneOffset.UTC), store)
        assertEquals(60L, limiter.isLocked("key"))
    }

    @Test
    fun `recordFailure persists to store`() {
        val store = FakeLoginAttemptsStore()
        val limiter = RateLimiter(Clock.fixed(baseTime, ZoneOffset.UTC), store)
        repeat(5) { limiter.recordFailure("key") }
        assertEquals(5, store.records["key"]?.attempts)
        assertEquals(baseTime.plusSeconds(60), store.records["key"]?.lockedUntil)
    }

    @Test
    fun `reset deletes from store`() {
        val store = FakeLoginAttemptsStore()
        val limiter = RateLimiter(Clock.fixed(baseTime, ZoneOffset.UTC), store)
        repeat(5) { limiter.recordFailure("key") }
        limiter.reset("key")
        assertNull(store.records["key"])
    }
}
