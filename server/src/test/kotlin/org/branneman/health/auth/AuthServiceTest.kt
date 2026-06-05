package org.branneman.health.auth

import org.mindrot.jbcrypt.BCrypt
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.*

class AuthServiceTest {

    private val service = AuthService()

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
    fun `computeExpiry returns now plus 30 days`() {
        val now = OffsetDateTime.of(2026, 6, 5, 14, 0, 0, 0, ZoneOffset.UTC)
        val expiry = service.computeExpiry(now)
        assertEquals(now.plusDays(30), expiry)
    }

    @Test
    fun `DUMMY_HASH rejects all passwords`() {
        assertFalse(BCrypt.checkpw("", AuthService.DUMMY_HASH))
        assertFalse(BCrypt.checkpw("password", AuthService.DUMMY_HASH))
        assertFalse(BCrypt.checkpw("admin", AuthService.DUMMY_HASH))
    }
}
