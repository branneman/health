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
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Before
import org.mindrot.jbcrypt.BCrypt
import java.util.UUID
import kotlin.test.*

class BodyWeightIntegrationTest {

    companion object {
        private val ds = TestDatabase.dataSource
        private val testUserId = UUID.fromString("00000000-0000-0000-0000-000000000005")
        private const val TEST_EMAIL = "bodyweight-test@test.local"
        private const val TEST_PASSWORD = "testpassword"
        private val TEST_HASH = BCrypt.hashpw(TEST_PASSWORD, BCrypt.gensalt(4))

        init {
            Database.connect(ds)
            transaction {
                Users.deleteWhere { username eq TEST_EMAIL }
                Users.insert {
                    it[id]           = testUserId
                    it[username]     = TEST_EMAIL
                    it[passwordHash] = TEST_HASH
                }
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

    @Before fun cleanWeightRows() {
        transaction {
            BodyWeight.deleteWhere { userId eq testUserId }
        }
    }

    @Test
    fun `POST body-weight returns 401 without token`() = appTest {
        val r = client.post("/body/weight") {
            contentType(ContentType.Application.Json)
            setBody("""{"date":"2026-06-10","kg":84.0}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    @Test
    fun `POST body-weight creates entry and returns 201 with date and kg`() = appTest {
        val token = login()
        val r = client.post("/body/weight") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"date":"2026-06-10","kg":84.0}""")
        }
        assertEquals(HttpStatusCode.Created, r.status)
        val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals("2026-06-10", body["date"]!!.jsonPrimitive.content)
        assertEquals(84.0, body["kg"]!!.jsonPrimitive.content.toDouble())
    }

    @Test
    fun `POST body-weight returns 409 when entry already exists for this date`() = appTest {
        val token = login()
        client.post("/body/weight") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"date":"2026-06-10","kg":84.0}""")
        }
        val r = client.post("/body/weight") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"date":"2026-06-10","kg":84.5}""")
        }
        assertEquals(HttpStatusCode.Conflict, r.status)
    }
}
