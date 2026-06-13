package org.branneman.health

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.branneman.health.auth.Users
import org.branneman.health.data.PolarAuth
import org.branneman.health.data.PolarConnectState
import org.branneman.health.polar.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.*

class PolarIntegrationTest {
    companion object {
        private val ds = TestDatabase.dataSource
        private val testUserId = UUID.fromString("00000000-0000-0000-0000-000000000009")
        private const val TEST_EMAIL = "polar-test@test.local"
        private const val TEST_PASSWORD = "testpassword"
        private val TEST_HASH = BCrypt.hashpw(TEST_PASSWORD, BCrypt.gensalt(4))
        private val testKey = ByteArray(32) { 0x42 }
        private val testCipher = TokenCipher(testKey)
        private val fakePolar = FakePolarApiClient()

        init {
            Database.connect(ds)
            transaction {
                Users.deleteWhere { Users.id eq testUserId }
                Users.insert { it[id] = testUserId; it[username] = TEST_EMAIL; it[passwordHash] = TEST_HASH }
            }
        }
    }

    private fun appTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(ds, fakePolar, testCipher) }
        block()
    }

    private suspend fun ApplicationTestBuilder.login(): String {
        val r = client.post("/auth/token") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$TEST_EMAIL","password":"$TEST_PASSWORD"}""")
        }
        return Json.parseToJsonElement(r.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content
    }

    @BeforeTest fun cleanUp() {
        transaction {
            PolarAuth.deleteWhere { Op.build { PolarAuth.healthUserId eq testUserId } }
            PolarConnectState.deleteWhere { Op.build { PolarConnectState.userId eq testUserId } }
        }
    }

    @Test
    fun `GET polar-connect-url returns url with state and client_id, requires auth`() = appTest {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/polar/connect-url").status)

        val token = login()
        val r = client.get("/polar/connect-url") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, r.status)
        val url = Json.parseToJsonElement(r.bodyAsText()).jsonObject["url"]!!.jsonPrimitive.content
        assertTrue(url.contains("state="))
        assertTrue(url.contains("client_id="))
    }

    @Test
    fun `GET polar-callback with valid state stores polar_auth row and returns HTML with deep link`() = appTest {
        val token = login()
        client.get("/polar/connect-url") { header(HttpHeaders.Authorization, "Bearer $token") }

        val state = transaction {
            PolarConnectState.selectAll().where { PolarConnectState.userId eq testUserId }.single()[PolarConnectState.state]
        }

        val r = client.get("/polar/callback?code=test-code&state=$state")
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("branneman-health://polar/connected"))

        val connected = transaction {
            PolarAuth.selectAll().where { PolarAuth.healthUserId eq testUserId }.count() > 0
        }
        assertTrue(connected)
    }

    @Test
    fun `GET polar-callback with expired state returns 400, no row written`() = appTest {
        transaction {
            PolarConnectState.insert {
                it[state] = "expired-state-xyz"
                it[PolarConnectState.userId] = testUserId
                it[expiresAt] = OffsetDateTime.now().minusMinutes(1)
            }
        }
        val r = client.get("/polar/callback?code=x&state=expired-state-xyz")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        val connected = transaction { PolarAuth.selectAll().where { PolarAuth.healthUserId eq testUserId }.count() > 0 }
        assertFalse(connected)
    }

    @Test
    fun `GET polar-callback with replayed state returns 400`() = appTest {
        val token = login()
        client.get("/polar/connect-url") { header(HttpHeaders.Authorization, "Bearer $token") }
        val state = transaction {
            PolarConnectState.selectAll().where { PolarConnectState.userId eq testUserId }.single()[PolarConnectState.state]
        }
        client.get("/polar/callback?code=test-code&state=$state")
        val r = client.get("/polar/callback?code=test-code&state=$state")
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test
    fun `GET polar-status returns false before OAuth, true after`() = appTest {
        val token = login()
        var r = client.get("/polar/status") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertFalse(Json.parseToJsonElement(r.bodyAsText()).jsonObject["connected"]!!.jsonPrimitive.boolean)

        client.get("/polar/connect-url") { header(HttpHeaders.Authorization, "Bearer $token") }
        val state = transaction {
            PolarConnectState.selectAll().where { PolarConnectState.userId eq testUserId }.single()[PolarConnectState.state]
        }
        client.get("/polar/callback?code=x&state=$state")

        r = client.get("/polar/status") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertTrue(Json.parseToJsonElement(r.bodyAsText()).jsonObject["connected"]!!.jsonPrimitive.boolean)
    }
}
