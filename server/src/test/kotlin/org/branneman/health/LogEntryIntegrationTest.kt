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
import org.branneman.health.data.LogEntry
import org.branneman.health.data.UserProfile
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Before
import org.junit.Test
import org.mindrot.jbcrypt.BCrypt
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogEntryIntegrationTest {

    companion object {
        private val ds = TestDatabase.dataSource
        val testUserId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000004")
        private const val TEST_EMAIL = "logentry-test@test.local"
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

    @Before fun cleanMutableRows() {
        transaction {
            LogEntry.deleteWhere { userId eq testUserId }
            UserProfile.deleteWhere { userId eq testUserId }
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

    @Test fun `POST quick-add returns 401 without token`() = appTest {
        val r = client.post("/in/log/quick-add") {
            contentType(ContentType.Application.Json)
            setBody("""{"id":"${UUID.randomUUID()}","quickAddKcal":350}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    @Test fun `POST quick-add creates entry and returns 201`() = appTest {
        val token = login()
        val id = UUID.randomUUID().toString()
        val r = client.post("/in/log/quick-add") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"id":"$id","quickAddKcal":350,"quickAddLabel":"Lunch","loggedAt":"2026-06-11T12:00:00Z"}""")
        }
        assertEquals(HttpStatusCode.Created, r.status)
        val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals(id, body["id"]!!.jsonPrimitive.content)
        assertEquals(350, body["quickAddKcal"]!!.jsonPrimitive.content.toInt())
        assertEquals("Lunch", body["quickAddLabel"]!!.jsonPrimitive.content)
    }

    @Test fun `POST quick-add is idempotent — second POST with same id returns 409`() = appTest {
        val token = login()
        val id = UUID.randomUUID().toString()
        val body = """{"id":"$id","quickAddKcal":350}"""
        client.post("/in/log/quick-add") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(body)
        }
        val r2 = client.post("/in/log/quick-add") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(body)
        }
        assertEquals(HttpStatusCode.Conflict, r2.status)
    }

    @Test fun `GET in-log returns created entry`() = appTest {
        val token = login()
        val id = UUID.randomUUID().toString()
        client.post("/in/log/quick-add") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"id":"$id","quickAddKcal":500,"loggedAt":"2026-06-11T08:00:00Z"}""")
        }
        val r = client.get("/in/log") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, r.status)
        val arr = Json.parseToJsonElement(r.bodyAsText()).jsonArray
        assertTrue(arr.any { it.jsonObject["id"]!!.jsonPrimitive.content == id })
    }

    @Test fun `DELETE in-log removes entry and returns 204`() = appTest {
        val token = login()
        val id = UUID.randomUUID().toString()
        client.post("/in/log/quick-add") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"id":"$id","quickAddKcal":350}""")
        }
        val del = client.delete("/in/log/$id") { bearerAuth(token) }
        assertEquals(HttpStatusCode.NoContent, del.status)

        val r = client.get("/in/log") { bearerAuth(token) }
        val arr = Json.parseToJsonElement(r.bodyAsText()).jsonArray
        assertTrue(arr.none { it.jsonObject["id"]!!.jsonPrimitive.content == id })
    }

    @Test fun `GET summary today caloriesIn reflects quick-add entry`() = appTest {
        val token = login()
        client.put("/profile") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"heightCm":175,"birthYear":1985,"sex":"male","goalWeightKg":75.0,"activityLevel":"moderately_active","targetDeficit":500,"phase":"loss","vacationMode":false}""")
        }
        val today = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
        client.post("/in/log/quick-add") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"id":"${UUID.randomUUID()}","quickAddKcal":450,"loggedAt":"${today}T12:00:00Z"}""")
        }
        val r = client.get("/summary/today?date=$today") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals(450, body["caloriesIn"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun `DELETE in-log for another user's entry returns 404`() = appTest {
        val token = login()
        val otherId = UUID.randomUUID()
        val entryId = UUID.randomUUID()
        transaction {
            Users.insert {
                it[id]           = otherId
                it[username]     = "other-${System.currentTimeMillis()}@test.local"
                it[passwordHash] = TEST_HASH
            }
            LogEntry.insert {
                it[LogEntry.id]           = entryId
                it[LogEntry.userId]       = otherId
                it[LogEntry.loggedAt]     = OffsetDateTime.now()
                it[LogEntry.mealType]     = "unknown"
                it[LogEntry.quickAddKcal] = 200
                it[LogEntry.createdAt]    = OffsetDateTime.now()
            }
        }
        val r = client.delete("/in/log/$entryId") { bearerAuth(token) }
        assertEquals(HttpStatusCode.NotFound, r.status)
        transaction { Users.deleteWhere { Users.id eq otherId } }
    }

    @Test fun `PATCH in-log updates kcal and label and returns 204`() = appTest {
        val token = login()
        val id = UUID.randomUUID().toString()
        client.post("/in/log/quick-add") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"id":"$id","quickAddKcal":300,"quickAddLabel":"old","loggedAt":"2026-06-11T12:00:00Z"}""")
        }

        val patch = client.patch("/in/log/$id") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"kcal":500,"label":"updated"}""")
        }
        assertEquals(HttpStatusCode.NoContent, patch.status)

        val entries = client.get("/in/log") { bearerAuth(token) }
        val arr = Json.parseToJsonElement(entries.bodyAsText()).jsonArray
        val entry = arr.first { it.jsonObject["id"]!!.jsonPrimitive.content == id }.jsonObject
        assertEquals(500, entry["quickAddKcal"]!!.jsonPrimitive.content.toInt())
        assertEquals("updated", entry["quickAddLabel"]!!.jsonPrimitive.content)
    }

    @Test fun `PATCH in-log returns 404 for unknown entry`() = appTest {
        val token = login()
        val r = client.patch("/in/log/${UUID.randomUUID()}") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"kcal":500,"label":null}""")
        }
        assertEquals(HttpStatusCode.NotFound, r.status)
    }

    @Test fun `PATCH in-log returns 404 for another user's entry`() = appTest {
        val token = login()
        val otherId = UUID.randomUUID()
        val entryId = UUID.randomUUID()
        transaction {
            Users.insert {
                it[id]           = otherId
                it[username]     = "other-patch@test.local"
                it[passwordHash] = TEST_HASH
            }
            LogEntry.insert {
                it[LogEntry.id]           = entryId
                it[LogEntry.userId]       = otherId
                it[LogEntry.loggedAt]     = OffsetDateTime.now(ZoneOffset.UTC)
                it[LogEntry.mealType]     = "unknown"
                it[LogEntry.quickAddKcal] = 300
                it[LogEntry.createdAt]    = OffsetDateTime.now(ZoneOffset.UTC)
            }
        }
        val r = client.patch("/in/log/$entryId") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"kcal":500,"label":null}""")
        }
        assertEquals(HttpStatusCode.NotFound, r.status)
        transaction {
            LogEntry.deleteWhere { LogEntry.id eq entryId }
            Users.deleteWhere { Users.id eq otherId }
        }
    }

    @Test fun `PATCH in-log returns 422 for food-item entry`() = appTest {
        val token = login()
        val id = UUID.randomUUID()
        transaction {
            LogEntry.insert {
                it[LogEntry.id]           = id
                it[LogEntry.userId]       = testUserId
                it[LogEntry.loggedAt]     = OffsetDateTime.now(ZoneOffset.UTC)
                it[LogEntry.mealType]     = "unknown"
                it[LogEntry.quickAddKcal] = null   // food-item entry
                it[LogEntry.createdAt]    = OffsetDateTime.now(ZoneOffset.UTC)
            }
        }
        val r = client.patch("/in/log/${id}") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"kcal":500,"label":null}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, r.status)
        transaction { LogEntry.deleteWhere { LogEntry.id eq id } }
    }

    @Test fun `PATCH in-log returns 401 without token`() = appTest {
        val r = client.patch("/in/log/${UUID.randomUUID()}") {
            contentType(ContentType.Application.Json)
            setBody("""{"kcal":500,"label":null}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }
}
