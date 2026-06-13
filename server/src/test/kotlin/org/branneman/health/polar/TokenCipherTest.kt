package org.branneman.health.polar

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFailsWith

class TokenCipherTest {

    private val key32 = ByteArray(32) { it.toByte() }
    private val cipher = TokenCipher(key32)

    @Test
    fun `encrypt then decrypt returns original plaintext`() {
        val plaintext = "polar-access-token-abc123"
        val encrypted = cipher.encrypt(plaintext)
        assertEquals(plaintext, cipher.decrypt(encrypted))
    }

    @Test
    fun `two encryptions of same plaintext produce different ciphertexts`() {
        val plaintext = "some-token"
        assertNotEquals(cipher.encrypt(plaintext), cipher.encrypt(plaintext))
    }

    @Test
    fun `fromBase64 constructs cipher from base64-encoded key`() {
        val base64Key = Base64.getEncoder().encodeToString(key32)
        val cipher2 = TokenCipher.fromBase64(base64Key)
        val plaintext = "test-token"
        assertEquals(plaintext, cipher2.decrypt(cipher2.encrypt(plaintext)))
    }

    @Test
    fun `tampered ciphertext throws on decrypt`() {
        val encrypted = cipher.encrypt("token")
        val bytes = Base64.getDecoder().decode(encrypted)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0xFF).toByte()
        val tampered = Base64.getEncoder().encodeToString(bytes)
        assertFailsWith<javax.crypto.AEADBadTagException> {
            cipher.decrypt(tampered)
        }
    }

    @Test
    fun `key must be 32 bytes`() {
        assertFailsWith<IllegalArgumentException> { TokenCipher(ByteArray(16)) }
        assertFailsWith<IllegalArgumentException> { TokenCipher(ByteArray(64)) }
    }
}
