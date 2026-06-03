package org.branneman.health.auth

import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private data class FailureRecord(val attempts: Int, val lockedUntil: Instant?)

class RateLimiter(private val clock: Clock = Clock.systemUTC()) {

    private val failures = ConcurrentHashMap<String, FailureRecord>()

    /** Returns null if the key may proceed, or seconds remaining until retry if locked. */
    fun isLocked(key: String): Long? {
        val record = failures[key] ?: return null
        val lockedUntil = record.lockedUntil ?: return null
        val remaining = lockedUntil.epochSecond - clock.instant().epochSecond
        return if (remaining > 0) remaining else null
    }

    fun recordFailure(key: String) {
        val current = failures[key] ?: FailureRecord(0, null)
        val attempts = current.attempts + 1
        val lockoutSeconds = lockoutSeconds(attempts)
        val lockedUntil = if (lockoutSeconds > 0) clock.instant().plusSeconds(lockoutSeconds) else null
        failures[key] = FailureRecord(attempts, lockedUntil)
    }

    fun reset(key: String) {
        failures.remove(key)
    }

    private fun lockoutSeconds(attempts: Int): Long {
        if (attempts < 5) return 0L
        val exponent = minOf(attempts - 5, 7)
        return minOf(60L * (1L shl exponent), 3600L)
    }
}
