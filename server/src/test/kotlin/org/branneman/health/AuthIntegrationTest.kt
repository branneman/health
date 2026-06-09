package org.branneman.health

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.branneman.health.auth.Users
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import java.util.UUID
import kotlin.test.*

class AuthIntegrationTest {

    companion object {
        private val ds = TestDatabase.dataSource
        private val testUserId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        private const val TEST_EMAIL = "integration@test.local"
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

    @Test
    fun `GET server-health returns 200 with status ok`() = appTest {
        val r = client.get("/server-health")
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("ok"))
    }

    @Test
    fun `POST auth-token returns 401 for wrong password`() = appTest {
        val r = client.post("/auth/token") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$TEST_EMAIL","password":"wrong"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    @Test
    fun `POST auth-token returns 200 and token for correct credentials`() = appTest {
        val r = client.post("/auth/token") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$TEST_EMAIL","password":"$TEST_PASSWORD"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertNotNull(body["token"]?.jsonPrimitive?.content)
    }

    @Test
    fun `POST auth-refresh returns 200 with valid token`() = appTest {
        val loginResp = client.post("/auth/token") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$TEST_EMAIL","password":"$TEST_PASSWORD"}""")
        }
        val token = Json.parseToJsonElement(loginResp.bodyAsText())
            .jsonObject["token"]!!.jsonPrimitive.content

        val r = client.post("/auth/refresh") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
    }

    @Test
    fun `POST auth-refresh returns 401 with no token`() = appTest {
        assertEquals(HttpStatusCode.Unauthorized, client.post("/auth/refresh").status)
    }

    @Test
    fun `POST auth-logout returns 204 with valid token`() = appTest {
        val loginResp = client.post("/auth/token") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$TEST_EMAIL","password":"$TEST_PASSWORD"}""")
        }
        val token = Json.parseToJsonElement(loginResp.bodyAsText())
            .jsonObject["token"]!!.jsonPrimitive.content

        val r = client.post("/auth/logout") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.NoContent, r.status)
    }

    @Test
    fun `protected route returns 401 without token`() = appTest {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/body/weight").status)
    }
}
