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
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Before
import org.junit.Test
import org.mindrot.jbcrypt.BCrypt
import java.time.OffsetDateTime
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
        transaction { LogEntry.deleteWhere { userId eq testUserId } }
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
}
