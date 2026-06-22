package org.branneman.health.ai

import org.branneman.health.AiConfigStatusDto
import org.branneman.health.auth.Users
import org.branneman.health.data.AiConfig
import org.branneman.health.polar.TokenCipher
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.branneman.health.TestDatabase
import org.mindrot.jbcrypt.BCrypt
import java.time.LocalDate
import java.util.UUID
import kotlin.test.*

class AiConfigServiceTest {

    companion object {
        private val ds = TestDatabase.dataSource
        private val cipher = TokenCipher(ByteArray(32) { it.toByte() })
        private val service = AiConfigService(cipher)
        private val userId = UUID.fromString("00000000-0000-0000-0000-000000000099")

        init {
            Database.connect(ds)
            transaction {
                AiConfig.deleteWhere { AiConfig.userId eq userId }
                Users.deleteWhere { Users.id eq userId }
                Users.insert {
                    it[id] = userId
                    it[username] = "ai-config-test@test.local"
                    it[passwordHash] = BCrypt.hashpw("x", BCrypt.gensalt(4))
                }
            }
        }
    }

    @BeforeTest
    fun setUp() {
        transaction {
            AiConfig.deleteWhere { AiConfig.userId eq userId }
        }
    }

    @Test
    fun `getStatus returns configured false when no row exists`() {
        val status = service.getStatus(userId)
        assertEquals(AiConfigStatusDto(configured = false, expiresAt = null), status)
    }

    @Test
    fun `upsert creates row and getStatus returns configured true`() {
        service.upsert(userId, "sk-ant-test-key", expiresAt = null)
        val status = service.getStatus(userId)
        assertEquals(true, status.configured)
        assertNull(status.expiresAt)
    }

    @Test
    fun `upsert with future expiry returns configured true with expiresAt set`() {
        val future = LocalDate.now().plusYears(1)
        service.upsert(userId, "sk-ant-test-key", expiresAt = future)
        val status = service.getStatus(userId)
        assertEquals(true, status.configured)
        assertEquals(future.toString(), status.expiresAt)
    }

    @Test
    fun `upsert replaces existing key on second call`() {
        service.upsert(userId, "sk-ant-key-1", expiresAt = null)
        service.upsert(userId, "sk-ant-key-2", expiresAt = null)
        val keyResult = service.getDecryptedKey(userId)
        assertIs<AiKeyResult.Available>(keyResult)
        assertEquals("sk-ant-key-2", (keyResult as AiKeyResult.Available).apiKey)
    }

    @Test
    fun `getStatus returns configured false when expires_at is in the past`() {
        val past = LocalDate.now().minusDays(1)
        service.upsert(userId, "sk-ant-test-key", expiresAt = past)
        val status = service.getStatus(userId)
        assertEquals(false, status.configured)
    }

    @Test
    fun `getDecryptedKey returns NotConfigured when no row`() {
        val result = service.getDecryptedKey(userId)
        assertIs<AiKeyResult.NotConfigured>(result)
    }

    @Test
    fun `getDecryptedKey returns Expired when expires_at is in the past`() {
        service.upsert(userId, "sk-ant-test-key", expiresAt = LocalDate.now().minusDays(1))
        val result = service.getDecryptedKey(userId)
        assertIs<AiKeyResult.Expired>(result)
    }

    @Test
    fun `getDecryptedKey returns Available with decrypted key when valid`() {
        service.upsert(userId, "sk-ant-real-key", expiresAt = null)
        val result = service.getDecryptedKey(userId)
        assertIs<AiKeyResult.Available>(result)
        assertEquals("sk-ant-real-key", (result as AiKeyResult.Available).apiKey)
    }

    @Test
    fun `getStatus response never contains key value`() {
        service.upsert(userId, "sk-ant-secret", expiresAt = null)
        val status = service.getStatus(userId)
        val json = kotlinx.serialization.json.Json.encodeToString(AiConfigStatusDto.serializer(), status)
        assertFalse(json.contains("sk-ant-secret"))
    }

    @Test
    fun `delete removes row and getStatus returns not configured`() {
        service.upsert(userId, "sk-ant-test-key", expiresAt = null)
        service.delete(userId)
        val status = service.getStatus(userId)
        assertEquals(false, status.configured)
    }
}
