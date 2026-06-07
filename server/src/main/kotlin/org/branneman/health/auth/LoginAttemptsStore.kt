package org.branneman.health.auth

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

data class AttemptRecord(val attempts: Int, val lockedUntil: Instant?)

interface LoginAttemptsStore {
    fun loadAll(): Map<String, AttemptRecord>
    fun save(key: String, record: AttemptRecord)
    fun delete(key: String)
}

object LoginAttempts : Table("login_attempts") {
    val key = text("key")
    val attempts = integer("attempts")
    val lockedUntil = timestampWithTimeZone("locked_until").nullable()
    override val primaryKey = PrimaryKey(key)
}

class DbLoginAttemptsStore : LoginAttemptsStore {

    override fun loadAll(): Map<String, AttemptRecord> = transaction {
        LoginAttempts.selectAll().associate { row ->
            row[LoginAttempts.key] to AttemptRecord(
                attempts = row[LoginAttempts.attempts],
                lockedUntil = row[LoginAttempts.lockedUntil]?.toInstant()
            )
        }
    }

    override fun save(key: String, record: AttemptRecord) {
        transaction {
            LoginAttempts.upsert {
                it[LoginAttempts.key] = key
                it[LoginAttempts.attempts] = record.attempts
                it[LoginAttempts.lockedUntil] = record.lockedUntil
                    ?.let { instant -> OffsetDateTime.ofInstant(instant, ZoneOffset.UTC) }
            }
        }
    }

    override fun delete(key: String) {
        transaction {
            LoginAttempts.deleteWhere { Op.build { LoginAttempts.key eq key } }
        }
    }
}
