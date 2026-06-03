package org.branneman.health.auth

import org.mindrot.jbcrypt.BCrypt
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.*

class AuthServiceTest {

    private val service = AuthService()
    private val amsterdam = ZoneId.of("Europe/Amsterdam")

    @Test
    fun `generateToken returns 64-char lowercase hex string`() {
        val token = service.generateToken()
        assertEquals(64, token.length)
        assertTrue(token.matches(Regex("[0-9a-f]+")), "token must be lowercase hex")
    }

    @Test
    fun `generateToken returns unique values`() {
        val tokens = (1..10).map { service.generateToken() }.toSet()
        assertEquals(10, tokens.size, "all generated tokens must be unique")
    }

    @Test
    fun `computeExpiry returns same-day 2am when current time is before 2am`() {
        val now = ZonedDateTime.of(2026, 6, 3, 1, 30, 0, 0, amsterdam)
        val expiry = service.computeExpiry(now).atZoneSameInstant(amsterdam)
        assertEquals(2026, expiry.year)
        assertEquals(6, expiry.monthValue)
        assertEquals(3, expiry.dayOfMonth)
        assertEquals(2, expiry.hour)
        assertEquals(0, expiry.minute)
    }

    @Test
    fun `computeExpiry returns next-day 2am when current time is after 2am`() {
        val now = ZonedDateTime.of(2026, 6, 3, 3, 0, 0, 0, amsterdam)
        val expiry = service.computeExpiry(now).atZoneSameInstant(amsterdam)
        assertEquals(4, expiry.dayOfMonth)
        assertEquals(2, expiry.hour)
        assertEquals(0, expiry.minute)
    }

    @Test
    fun `computeExpiry returns next-day 2am when current time is exactly 2am`() {
        val now = ZonedDateTime.of(2026, 6, 3, 2, 0, 0, 0, amsterdam)
        val expiry = service.computeExpiry(now).atZoneSameInstant(amsterdam)
        assertEquals(4, expiry.dayOfMonth)
        assertEquals(2, expiry.hour)
    }

    @Test
    fun `computeExpiry returns next-day 2am when current time is late at night`() {
        val now = ZonedDateTime.of(2026, 6, 3, 22, 0, 0, 0, amsterdam)
        val expiry = service.computeExpiry(now).atZoneSameInstant(amsterdam)
        assertEquals(4, expiry.dayOfMonth)
        assertEquals(2, expiry.hour)
    }

    @Test
    fun `DUMMY_HASH rejects all passwords`() {
        assertFalse(BCrypt.checkpw("", AuthService.DUMMY_HASH))
        assertFalse(BCrypt.checkpw("password", AuthService.DUMMY_HASH))
        assertFalse(BCrypt.checkpw("admin", AuthService.DUMMY_HASH))
    }
}
