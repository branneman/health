package org.branneman.health

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.branneman.health.auth.Users
import org.branneman.health.data.BodyWeight
import org.branneman.health.data.DailyEnergy
import org.branneman.health.data.LogEntry
import org.branneman.health.data.UserProfile
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Before
import org.mindrot.jbcrypt.BCrypt
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SummaryIntegrationTest {

    companion object {
        private val ds = TestDatabase.dataSource
        val testUserId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000008")
        private const val TEST_EMAIL = "summary-test@test.local"
        private const val TEST_PASSWORD = "testpassword"
        private val TEST_HASH = BCrypt.hashpw(TEST_PASSWORD, BCrypt.gensalt(4))

        init {
            Database.connect(ds)
            transaction {
                Users.deleteWhere { username eq TEST_EMAIL }
                Users.deleteWhere { id eq testUserId }
                Users.insert {
                    it[id]           = testUserId
                    it[username]     = TEST_EMAIL
                    it[passwordHash] = TEST_HASH
                }
                UserProfile.deleteWhere { userId eq testUserId }
                UserProfile.insert {
                    it[userId]        = testUserId
                    it[heightCm]      = 177
                    it[birthYear]     = 1986
                    it[sex]           = "male"
                    it[goalWeightKg]  = 74.0.toBigDecimal()
                    it[activityLevel] = "lightly_active"
                    it[targetDeficit] = 300
                    it[phase]         = "loss"
                    it[vacationMode]  = false
                    it[updatedAt]     = OffsetDateTime.now()
                }
                BodyWeight.deleteWhere { userId eq testUserId }
                BodyWeight.insert {
                    it[id]        = UUID.randomUUID()
                    it[userId]    = testUserId
                    it[date]      = LocalDate.now()
                    it[kg]        = 84.0.toBigDecimal()
                    it[createdAt] = OffsetDateTime.now()
                }
            }
        }
    }

    @Before fun cleanMutableRows() {
        transaction {
            LogEntry.deleteWhere { userId eq testUserId }
            DailyEnergy.deleteWhere { userId eq testUserId }
        }
    }

    private fun appTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(ds) }
        block()
    }

    private suspend fun ApplicationTestBuilder.login(): String {
        val r = client.post("/auth/token") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$TEST_EMAIL","password":"$TEST_PASSWORD"}""")
        }
        return Json.parseToJsonElement(r.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content
    }

    @Test fun `GET summary-today returns 401 without token`() = appTest {
        val r = client.get("/summary/today?date=2026-06-10")
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    @Test fun `GET summary-today returns 400 when date param missing`() = appTest {
        val token = login()
        val r = client.get("/summary/today") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test fun `GET summary-today returns estimate when no Polar data`() = appTest {
        val token = login()
        val today = LocalDate.now().toString()
        val r = client.get("/summary/today?date=$today") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals("estimate", body["caloriesOutSource"]!!.jsonPrimitive.content)
        assertEquals(0, body["caloriesIn"]!!.jsonPrimitive.content.toInt())
        assertEquals(300, body["targetDeficit"]!!.jsonPrimitive.content.toInt())
        assertTrue(body["caloriesOut"]!!.jsonPrimitive.content.toInt() > 0)
        assertNotNull(body["budgetRemaining"])
    }

    private fun insertQuickAddEntry(userId: UUID, loggedAt: OffsetDateTime, mealType: String, kcal: Int) =
        transaction {
            exec(
                "INSERT INTO log_entry (id, user_id, logged_at, meal_type, quick_add_kcal, created_at) " +
                "VALUES (gen_random_uuid(), '$userId', '$loggedAt', '$mealType'::meal_type, $kcal, NOW())"
            )
        }

    @Test fun `GET summary-today includes quick-add caloriesIn for today`() = appTest {
        val token = login()
        val today = LocalDate.now()
        insertQuickAddEntry(testUserId, today.atTime(8, 0).atOffset(ZoneOffset.UTC), "breakfast", 520)
        insertQuickAddEntry(testUserId, today.atTime(12, 30).atOffset(ZoneOffset.UTC), "lunch", 680)
        val r = client.get("/summary/today?date=${today}") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals(1200, body["caloriesIn"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun `GET summary-today excludes log entries from other days`() = appTest {
        val token = login()
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        insertQuickAddEntry(testUserId, yesterday.atTime(19, 0).atOffset(ZoneOffset.UTC), "dinner", 800)
        val r = client.get("/summary/today?date=${today}") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals(0, body["caloriesIn"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun `GET summary-today uses Polar data when available`() = appTest {
        val token = login()
        val today = LocalDate.now()
        transaction {
            DailyEnergy.insert {
                it[userId]     = testUserId
                it[date]       = today
                it[bmrKcal]    = 1800
                it[activeKcal] = 500
                it[totalKcal]  = 2300
                it[dataSource] = "polar"
            }
        }
        val r = client.get("/summary/today?date=${today}") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals(2300, body["caloriesOut"]!!.jsonPrimitive.content.toInt())
        assertEquals("polar_today", body["caloriesOutSource"]!!.jsonPrimitive.content)
    }
}
