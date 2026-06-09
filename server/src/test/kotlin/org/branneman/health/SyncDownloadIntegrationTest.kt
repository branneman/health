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

class SyncDownloadIntegrationTest {

    companion object {
        private val ds = TestDatabase.dataSource
        private val testUserId = UUID.fromString("00000000-0000-0000-0000-000000000003")
        private const val TEST_EMAIL = "sync-test@test.local"
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
    fun `GET out-energy returns 401 without token`() = appTest {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/out/energy").status)
    }

    @Test
    fun `GET out-energy returns empty list for new user`() = appTest {
        val token = login()
        val r = client.get("/out/energy") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(0, Json.parseToJsonElement(r.bodyAsText()).jsonArray.size)
    }

    @Test
    fun `GET out-workouts returns 401 without token`() = appTest {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/out/workouts").status)
    }

    @Test
    fun `GET out-workouts returns empty list for new user`() = appTest {
        val token = login()
        val r = client.get("/out/workouts") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(0, Json.parseToJsonElement(r.bodyAsText()).jsonArray.size)
    }

    @Test
    fun `GET in-food-items returns 401 without token`() = appTest {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/in/food-items").status)
    }

    @Test
    fun `GET in-food-items returns empty list for new user`() = appTest {
        val token = login()
        val r = client.get("/in/food-items") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(0, Json.parseToJsonElement(r.bodyAsText()).jsonArray.size)
    }

    @Test
    fun `GET in-templates returns 401 without token`() = appTest {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/in/templates").status)
    }

    @Test
    fun `GET in-templates returns empty list for new user`() = appTest {
        val token = login()
        val r = client.get("/in/templates") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(0, Json.parseToJsonElement(r.bodyAsText()).jsonArray.size)
    }

    @Test
    fun `GET in-log returns 401 without token`() = appTest {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/in/log").status)
    }

    @Test
    fun `GET in-log returns empty list for new user`() = appTest {
        val token = login()
        val r = client.get("/in/log") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(0, Json.parseToJsonElement(r.bodyAsText()).jsonArray.size)
    }
}
