package org.branneman.health.e2e

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.branneman.health.auth.Users
import org.branneman.health.data.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

private val E2E_USER_ID     = UUID.fromString("00000000-0000-0000-0000-000000000020")
private val E2E_TEMPLATE_ID = UUID.fromString("00000000-0000-0000-0000-000000000021")
private const val E2E_EMAIL = "test+e2e@bran.name"

fun Route.e2eSeedRoute(e2ePassword: String) {
    post("/internal/e2e/reset") {
        val bearer = call.request.headers["Authorization"]
            ?.removePrefix("Bearer ")
            ?: return@post call.respond(HttpStatusCode.Unauthorized)

        if (bearer != e2ePassword) return@post call.respond(HttpStatusCode.Unauthorized)

        val hash  = BCrypt.hashpw(e2ePassword, BCrypt.gensalt())
        val today = LocalDate.now()
        val now   = OffsetDateTime.now(ZoneOffset.UTC)

        transaction {
            // Delete by both UUID and email to handle any stale state (same pattern as integration tests).
            // ON DELETE CASCADE handles profile, templates, log_entry, body_weight, etc.
            Users.deleteWhere { id eq E2E_USER_ID }
            Users.deleteWhere { username eq E2E_EMAIL }

            Users.insert {
                it[id]           = E2E_USER_ID
                it[username]     = E2E_EMAIL
                it[passwordHash] = hash
            }

            UserProfile.insert {
                it[userId]        = E2E_USER_ID
                it[heightCm]      = 182
                it[birthYear]     = 1985
                it[sex]           = "male"
                it[goalWeightKg]  = BigDecimal("78.0")
                it[activityLevel] = "lightly_active"
                it[targetDeficit] = 400
                it[phase]         = "loss"
                it[vacationMode]  = false
                it[updatedAt]     = now
            }

            // Pinned meal button — appears as a one-tap button on the log screen.
            MealTemplate.insert {
                it[id]           = E2E_TEMPLATE_ID
                it[userId]       = E2E_USER_ID
                it[name]         = "Breakfast"
                it[quickAddKcal] = 550
                it[sortOrder]    = 1
                it[createdAt]    = now
                it[updatedAt]    = now
            }

            // Seven days of calorie data so the dashboard renders real numbers.
            for (n in 1..7) {
                DailyEnergy.insert {
                    it[userId]     = E2E_USER_ID
                    it[date]       = today.minusDays(n.toLong())
                    it[bmrKcal]    = 1900
                    it[activeKcal] = 280 + (n * 15)
                    it[totalKcal]  = 2180 + (n * 15)
                    it[steps]      = 7200 + (n * 80)
                    it[dataSource] = "polar"
                }
            }

            // Seven days of body weight — no entry for today so the weight chip starts blank.
            for (n in 1..7) {
                BodyWeight.insert {
                    it[id]        = UUID.randomUUID()
                    it[userId]    = E2E_USER_ID
                    it[date]      = today.minusDays(n.toLong())
                    it[kg]        = BigDecimal("83.0").subtract(BigDecimal("0.1").multiply(BigDecimal(n)))
                    it[createdAt] = now
                }
            }
        }

        call.respond(HttpStatusCode.OK)
    }
}
