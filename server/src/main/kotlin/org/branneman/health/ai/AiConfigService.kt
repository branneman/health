package org.branneman.health.ai

import org.branneman.health.AiConfigStatusDto
import org.branneman.health.data.AiConfig
import org.branneman.health.polar.TokenCipher
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

sealed interface AiKeyResult {
    data class Available(val apiKey: String) : AiKeyResult
    data object NotConfigured : AiKeyResult
    data object Expired : AiKeyResult
}

class AiConfigService(private val cipher: TokenCipher) {

    fun getStatus(userId: UUID): AiConfigStatusDto = transaction {
        val row = AiConfig.selectAll()
            .where { AiConfig.userId eq userId }
            .singleOrNull()
            ?: return@transaction AiConfigStatusDto(configured = false, expiresAt = null)

        val expiresAt = row[AiConfig.expiresAt]
        val expired = expiresAt != null && expiresAt.isBefore(LocalDate.now())
        AiConfigStatusDto(configured = !expired, expiresAt = expiresAt?.toString())
    }

    fun upsert(userId: UUID, apiKey: String, expiresAt: LocalDate?): AiConfigStatusDto = transaction {
        AiConfig.deleteWhere { AiConfig.userId eq userId }
        AiConfig.insert {
            it[AiConfig.id]        = UUID.randomUUID()
            it[AiConfig.userId]    = userId
            it[AiConfig.apiKey]    = cipher.encrypt(apiKey)
            it[AiConfig.expiresAt] = expiresAt
            it[AiConfig.createdAt] = OffsetDateTime.now()
        }
        getStatus(userId)
    }

    fun delete(userId: UUID) = transaction {
        AiConfig.deleteWhere { AiConfig.userId eq userId }
    }

    fun getDecryptedKey(userId: UUID): AiKeyResult = transaction {
        val row = AiConfig.selectAll()
            .where { AiConfig.userId eq userId }
            .singleOrNull()
            ?: return@transaction AiKeyResult.NotConfigured

        val expiresAt = row[AiConfig.expiresAt]
        if (expiresAt != null && expiresAt.isBefore(LocalDate.now())) {
            return@transaction AiKeyResult.Expired
        }

        AiKeyResult.Available(cipher.decrypt(row[AiConfig.apiKey]))
    }
}
