package org.branneman.health

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.branneman.health.auth.Users
import org.branneman.health.data.DailyEnergy
import org.branneman.health.data.LogEntry
import org.branneman.health.data.UserProfile
import org.branneman.health.data.Workout
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.mindrot.jbcrypt.BCrypt
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DynamicBudgetIntegrationTest {

    companion object {
        private val ds = TestDatabase.dataSource
        val testUserId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000010")
        private const val TEST_EMAIL = "dynamic-budget-test@test.local"
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
            }
        }
    }

    @org.junit.Before fun cleanMutableRows() {
        transaction {
            DailyEnergy.deleteWhere { userId eq testUserId }
            Workout.deleteWhere    { userId eq testUserId }
            LogEntry.deleteWhere   { userId eq testUserId }
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
                // wakeTime and bedtime use column defaults: 07:00 and 23:00
            }
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

    private fun insertEnergy(date: LocalDate, totalKcal: Int) = transaction {
        DailyEnergy.insert {
            it[userId]     = testUserId
            it[DailyEnergy.date]       = date
            it[bmrKcal]    = 1800
            it[activeKcal] = totalKcal - 1800
            it[DailyEnergy.totalKcal]  = totalKcal
            it[dataSource] = "polar"
        }
    }

    private fun insertWorkout(date: LocalDate) = transaction {
        Workout.insert {
            it[id]     = UUID.randomUUID()
            it[userId] = testUserId
            it[Workout.date] = date
            it[type]   = "climbing"
        }
    }

    private fun insertQuickAdd(date: LocalDate, kcal: Int) = transaction {
        exec(
            "INSERT INTO log_entry (id, user_id, logged_at, meal_type, quick_add_kcal, created_at) " +
            "VALUES (gen_random_uuid(), '$testUserId', '${date.atTime(12, 0).atOffset(ZoneOffset.UTC)}', " +
            "'unknown'::meal_type, $kcal, NOW())"
        )
    }

    @Test fun `no history - dynamic params are null`() = appTest {
        val token = login()
        val today = LocalDate.now().toString()
        val r = client.get("/summary/today?date=$today") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals(JsonNull, body["expectedTodaySport"])
        assertEquals(JsonNull, body["eatingFractionSport"])
        assertFalse(body["postWorkoutModeSport"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test fun `5 logged sport days - Approach 2 fraction in response`() = appTest {
        val token = login()
        val today = LocalDate.now()
        // Insert 5 sport days with food logs (yesterday through 5 days ago)
        for (i in 1..5) {
            val d = today.minusDays(i.toLong())
            insertEnergy(d, totalKcal = 2400)
            insertWorkout(d)
            insertQuickAdd(d, kcal = 2000)
        }
        val r = client.get("/summary/today?date=$today") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertNotNull(body["expectedTodaySport"])
        assertEquals(2400, body["expectedTodaySport"]!!.jsonPrimitive.content.toInt())
        // Approach 2: avg_in / avg_out = 2000 / 2400
        val fraction = body["eatingFractionSport"]!!.jsonPrimitive.content.toDouble()
        assertTrue(abs(fraction - (2000.0 / 2400.0)) < 0.001)
    }

    @Test fun `10 qualifying sport days - Approach 3 fraction in response`() = appTest {
        val token = login()
        val today = LocalDate.now()
        // 10 qualifying days (caloriesIn = 2100 ≤ 2400-300+100=2200) + 2 non-qualifying (caloriesIn = 2500)
        for (i in 1..10) {
            val d = today.minusDays(i.toLong())
            insertEnergy(d, totalKcal = 2400)
            insertWorkout(d)
            insertQuickAdd(d, kcal = 2100)
        }
        for (i in 11..12) {
            val d = today.minusDays(i.toLong())
            insertEnergy(d, totalKcal = 2400)
            insertWorkout(d)
            insertQuickAdd(d, kcal = 2500)
        }
        val r = client.get("/summary/today?date=$today") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        // Approach 3: filters to 10 qualifying days → avg_in=2100, avg_out=2400
        val fraction = body["eatingFractionSport"]!!.jsonPrimitive.content.toDouble()
        assertTrue(abs(fraction - (2100.0 / 2400.0)) < 0.001)
        // Confirm it differs from Approach 2 (2500 non-qualifying days would shift it)
        assertTrue(abs(fraction - (2167.0 / 2400.0)) > 0.001)
    }

    @Test fun `post-workout mode triggers when today burn is at least 90 percent of expected`() = appTest {
        val token = login()
        val today = LocalDate.now()
        // Establish sport history (5 logged days, expectedSport = 2400)
        for (i in 1..5) {
            val d = today.minusDays(i.toLong())
            insertEnergy(d, totalKcal = 2400)
            insertWorkout(d)
            insertQuickAdd(d, kcal = 2000)
        }
        // Today's burn = 2160 = 90% of 2400
        insertEnergy(today, totalKcal = 2160)

        val r = client.get("/summary/today?date=$today") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals(2160, body["actualBurnedSoFar"]!!.jsonPrimitive.content.toInt())
        assertTrue(body["postWorkoutModeSport"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test fun `wakeTime and bedtime from user profile appear in response`() = appTest {
        // Override profile with non-default times
        transaction {
            UserProfile.update({ UserProfile.userId eq testUserId }) {
                it[wakeTime] = LocalTime.of(6, 30)
                it[bedtime]  = LocalTime.of(22, 0)
            }
        }
        val token = login()
        val today = LocalDate.now().toString()
        val r = client.get("/summary/today?date=$today") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals("06:30", body["wakeTime"]!!.jsonPrimitive.content)
        assertEquals("22:00", body["bedtime"]!!.jsonPrimitive.content)
    }
}
