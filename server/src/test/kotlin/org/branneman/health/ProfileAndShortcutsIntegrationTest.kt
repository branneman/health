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
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import java.util.UUID
import kotlin.test.*

class ProfileAndShortcutsIntegrationTest {

    companion object {
        private val ds = TestDatabase.dataSource
        private val testUserId = UUID.fromString("00000000-0000-0000-0000-000000000002")
        private const val TEST_EMAIL = "profile-test@test.local"
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

    @Test
    fun `GET profile returns 401 without token`() = appTest {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/profile").status)
    }

    @Test
    fun `GET profile returns 404 when no profile exists`() = appTest {
        val token = login()
        val r = client.get("/profile") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.NotFound, r.status)
    }

    @Test
    fun `PUT profile returns 200 then GET returns same data`() = appTest {
        val token = login()
        val profileJson = """{"heightCm":177,"birthYear":1986,"sex":"male","goalWeightKg":74.0,"activityLevel":"lightly_active","targetDeficit":300,"phase":"loss","vacationMode":false}"""

        val putResp = client.put("/profile") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(profileJson)
        }
        assertEquals(HttpStatusCode.OK, putResp.status)

        val getResp = client.get("/profile") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, getResp.status)
        val body = Json.parseToJsonElement(getResp.bodyAsText()).jsonObject
        assertEquals(177, body["heightCm"]!!.jsonPrimitive.content.toInt())
        assertEquals("male", body["sex"]!!.jsonPrimitive.content)
    }

    @Test
    fun `GET shortcuts returns 401 without token`() = appTest {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/shortcuts").status)
    }

    @Test
    fun `PUT shortcuts returns 200 with server-assigned UUIDs then GET returns saved list`() = appTest {
        val token = login()

        val putResp = client.put("/shortcuts") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""[{"id":"","emoji":"🍺","label":"Pils","kcal":140,"sortOrder":0}]""")
        }
        assertEquals(HttpStatusCode.OK, putResp.status)
        val saved = Json.parseToJsonElement(putResp.bodyAsText()).jsonArray
        val serverId = saved[0].jsonObject["id"]!!.jsonPrimitive.content
        assertNotEquals("", serverId)
        UUID.fromString(serverId) // throws if not a valid UUID

        val getResp = client.get("/shortcuts") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, getResp.status)
        val list = Json.parseToJsonElement(getResp.bodyAsText()).jsonArray
        assertEquals(1, list.size)
        assertEquals("Pils", list[0].jsonObject["label"]!!.jsonPrimitive.content)
        assertEquals(140, list[0].jsonObject["kcal"]!!.jsonPrimitive.content.toInt())
    }
}
