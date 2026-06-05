package org.branneman.health.auth

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.util.UUID

object Users : Table("users") {
    val id = uuid("id")
    val username = text("username")
    val passwordHash = text("password_hash")
    override val primaryKey = PrimaryKey(id)
}

object Sessions : Table("sessions") {
    val token = text("token")
    val userId = uuid("user_id").references(Users.id)
    val expiresAt = timestampWithTimeZone("expires_at")
    override val primaryKey = PrimaryKey(token)
}

sealed class LoginResult {
    data object Failure : LoginResult()
    data class Success(val token: String, val expiresAt: OffsetDateTime) : LoginResult()
}

class AuthService {

    companion object {
        // Random preimage — no one can know it, so checkpw always returns false.
        // Computed once at startup to pay the BCrypt cost up front.
        val DUMMY_HASH: String = BCrypt.hashpw(UUID.randomUUID().toString(), BCrypt.gensalt(12))
    }

    fun generateToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun computeExpiry(now: OffsetDateTime = OffsetDateTime.now()): OffsetDateTime = now.plusDays(30)

    fun login(username: String, password: String): LoginResult {
        val user = transaction {
            Users.selectAll().where { Users.username eq username }.singleOrNull()
        }
        val hash = user?.get(Users.passwordHash) ?: DUMMY_HASH
        val valid = BCrypt.checkpw(password, hash)

        if (!valid || user == null) return LoginResult.Failure

        val token = generateToken()
        val expiresAt = computeExpiry()
        val userId = user[Users.id]

        transaction {
            Sessions.deleteWhere {
                Op.build { (Sessions.userId eq userId) and (Sessions.expiresAt less OffsetDateTime.now()) }
            }
            Sessions.insert {
                it[Sessions.token] = token
                it[Sessions.userId] = userId
                it[Sessions.expiresAt] = expiresAt
            }
        }

        return LoginResult.Success(token, expiresAt)
    }

    fun lookupToken(token: String): UUID? = transaction {
        Sessions.selectAll()
            .where {
                (Sessions.token eq token) and (Sessions.expiresAt greater OffsetDateTime.now())
            }
            .singleOrNull()
            ?.get(Sessions.userId)
    }

    fun refresh(token: String): LoginResult {
        val userId = lookupToken(token) ?: return LoginResult.Failure
        val newToken = generateToken()
        val expiresAt = computeExpiry()
        transaction {
            Sessions.deleteWhere { Op.build { Sessions.token eq token } }
            Sessions.insert {
                it[Sessions.token] = newToken
                it[Sessions.userId] = userId
                it[Sessions.expiresAt] = expiresAt
            }
        }
        return LoginResult.Success(newToken, expiresAt)
    }

    fun logout(token: String) {
        transaction {
            Sessions.deleteWhere { Op.build { Sessions.token eq token } }
        }
    }
}
