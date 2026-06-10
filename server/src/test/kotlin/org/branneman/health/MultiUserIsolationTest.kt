package org.branneman.health

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.branneman.health.auth.Users
import org.branneman.health.data.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Before
import org.junit.Test
import org.mindrot.jbcrypt.BCrypt
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

import kotlin.test.assertEquals

class MultiUserIsolationTest {

    companion object {
        private val ds = TestDatabase.dataSource
        private val userAId = UUID.fromString("00000000-0000-0000-0000-000000000006")
        private val userBId = UUID.fromString("00000000-0000-0000-0000-000000000007")
        private const val USER_A_EMAIL = "isolation-a@test.local"
        private const val USER_B_EMAIL = "isolation-b@test.local"
        private const val PASSWORD = "testpassword"
        private val HASH = BCrypt.hashpw(PASSWORD, BCrypt.gensalt(4))

        init {
            Database.connect(ds)
            transaction {
                listOf(USER_A_EMAIL to userAId, USER_B_EMAIL to userBId).forEach { (email, id) ->
                    Users.deleteWhere { username eq email }
                    Users.deleteWhere { Users.id eq id }
                    Users.insert {
                        it[Users.id]           = id
                        it[Users.username]     = email
                        it[Users.passwordHash] = HASH
                    }
                }
            }
        }
    }

    @Before fun cleanAll() {
        transaction {
            listOf(userAId, userBId).forEach { uid ->
                MealTemplate.deleteWhere { userId eq uid }
                LogEntry.deleteWhere { userId eq uid }
                FoodItem.deleteWhere { userId eq uid }
                Workout.deleteWhere { userId eq uid }
                DailyEnergy.deleteWhere { userId eq uid }
                BodyWeight.deleteWhere { userId eq uid }
                Shortcut.deleteWhere { userId eq uid }
                UserProfile.deleteWhere { userId eq uid }
            }
        }
    }

    private fun appTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(ds) }
        block()
    }

    private suspend fun ApplicationTestBuilder.login(email: String): String {
        val r = client.post("/auth/token") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$email","password":"$PASSWORD"}""")
        }
        return Json.parseToJsonElement(r.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content
    }

    @Test
    fun `user B cannot see user A profile`() = appTest {
        val tokenA = login(USER_A_EMAIL)
        client.put("/profile") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
            contentType(ContentType.Application.Json)
            setBody("""{"heightCm":177,"birthYear":1986,"sex":"male","goalWeightKg":74.0,"activityLevel":"lightly_active","targetDeficit":300,"phase":"loss","vacationMode":false}""")
        }
        val tokenB = login(USER_B_EMAIL)
        val r = client.get("/profile") { header(HttpHeaders.Authorization, "Bearer $tokenB") }
        assertEquals(HttpStatusCode.NotFound, r.status)
    }

    @Test
    fun `user B cannot see user A shortcuts`() = appTest {
        val tokenA = login(USER_A_EMAIL)
        client.put("/shortcuts") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
            contentType(ContentType.Application.Json)
            setBody("""[{"id":"","emoji":"🍎","label":"Apple","kcal":52,"sortOrder":0}]""")
        }
        val tokenB = login(USER_B_EMAIL)
        val r = client.get("/shortcuts") { header(HttpHeaders.Authorization, "Bearer $tokenB") }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(0, Json.parseToJsonElement(r.bodyAsText()).jsonArray.size)
    }

    @Test
    fun `user B cannot see user A body weight`() = appTest {
        val tokenA = login(USER_A_EMAIL)
        client.post("/body/weight") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
            contentType(ContentType.Application.Json)
            setBody("""{"date":"2026-01-15","kg":82.0}""")
        }
        val tokenB = login(USER_B_EMAIL)
        val r = client.get("/body/weight") { header(HttpHeaders.Authorization, "Bearer $tokenB") }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(0, Json.parseToJsonElement(r.bodyAsText()).jsonArray.size)
    }

    @Test
    fun `user B cannot see user A energy data`() = appTest {
        transaction {
            DailyEnergy.insert {
                it[userId]     = userAId
                it[date]       = LocalDate.of(2026, 1, 15)
                it[bmrKcal]    = 1800
                it[activeKcal] = 400
                it[totalKcal]  = 2200
                it[steps]      = 8000
                it[dataSource] = "polar"
            }
        }
        val tokenB = login(USER_B_EMAIL)
        val r = client.get("/out/energy") { header(HttpHeaders.Authorization, "Bearer $tokenB") }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(0, Json.parseToJsonElement(r.bodyAsText()).jsonArray.size)
    }

    @Test
    fun `user B cannot see user A workout data`() = appTest {
        transaction {
            Workout.insert {
                it[id]     = UUID.randomUUID()
                it[userId] = userAId
                it[date]   = LocalDate.of(2026, 1, 15)
                it[type]   = "running"
            }
        }
        val tokenB = login(USER_B_EMAIL)
        val r = client.get("/out/workouts") { header(HttpHeaders.Authorization, "Bearer $tokenB") }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(0, Json.parseToJsonElement(r.bodyAsText()).jsonArray.size)
    }

    @Test
    fun `user B cannot see user A food items`() = appTest {
        transaction {
            FoodItem.insert {
                it[id]          = UUID.randomUUID()
                it[userId]      = userAId
                it[name]        = "User A's food"
                it[kcalPer100g] = 200.toBigDecimal()
                it[dataSource]  = "manual"
            }
        }
        val tokenB = login(USER_B_EMAIL)
        val r = client.get("/in/food-items") { header(HttpHeaders.Authorization, "Bearer $tokenB") }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(0, Json.parseToJsonElement(r.bodyAsText()).jsonArray.size)
    }

    @Test
    fun `user B cannot see user A log entries`() = appTest {
        // meal_type is a Postgres custom enum; use raw SQL to avoid Exposed's text→enum cast issue
        transaction {
            exec("INSERT INTO log_entry (id, user_id, logged_at, meal_type, created_at) VALUES ('${UUID.randomUUID()}', '$userAId', now(), 'breakfast'::meal_type, now())")
        }
        val tokenB = login(USER_B_EMAIL)
        val r = client.get("/in/log") { header(HttpHeaders.Authorization, "Bearer $tokenB") }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(0, Json.parseToJsonElement(r.bodyAsText()).jsonArray.size)
    }

    @Test
    fun `user B cannot see user A meal templates`() = appTest {
        val now = OffsetDateTime.now()
        transaction {
            MealTemplate.insert {
                it[id]        = UUID.randomUUID()
                it[userId]    = userAId
                it[name]      = "User A's template"
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
        val tokenB = login(USER_B_EMAIL)
        val r = client.get("/in/templates") { header(HttpHeaders.Authorization, "Bearer $tokenB") }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(0, Json.parseToJsonElement(r.bodyAsText()).jsonArray.size)
    }
}
