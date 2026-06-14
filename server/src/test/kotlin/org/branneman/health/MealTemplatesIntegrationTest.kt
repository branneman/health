package org.branneman.health

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.branneman.health.auth.Users
import org.branneman.health.data.MealTemplate
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import java.util.UUID
import kotlin.test.*

class MealTemplatesIntegrationTest {

    companion object {
        private val ds = TestDatabase.dataSource
        private val testUserId = UUID.fromString("00000000-0000-0000-0000-000000000009")
        private const val TEST_EMAIL = "mealtemplates-test@test.local"
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

    @BeforeTest fun cleanTemplates() {
        transaction {
            MealTemplate.deleteWhere { Op.build { MealTemplate.userId eq testUserId } }
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

    @Test fun `GET templates requires auth`() = appTest {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/in/templates").status)
    }

    @Test fun `GET templates returns empty list when none saved`() = appTest {
        val token = login()
        val r = client.get("/in/templates") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(0, Json.parseToJsonElement(r.bodyAsText()).jsonArray.size)
    }

    @Test fun `PUT then GET round-trips sortOrder and quickAddKcal`() = appTest {
        val token = login()
        val body = """[{"id":"ignored","name":"Usual breakfast","sortOrder":0,"quickAddKcal":450,"items":[]}]"""

        val put = client.put("/in/templates") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, put.status)

        val get = client.get("/in/templates") { header(HttpHeaders.Authorization, "Bearer $token") }
        val arr = Json.parseToJsonElement(get.bodyAsText()).jsonArray
        assertEquals(1, arr.size)
        assertEquals("Usual breakfast", arr[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(0,   arr[0].jsonObject["sortOrder"]!!.jsonPrimitive.int)
        assertEquals(450, arr[0].jsonObject["quickAddKcal"]!!.jsonPrimitive.int)
    }

    @Test fun `PUT twice replaces first list`() = appTest {
        val token = login()
        client.put("/in/templates") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""[{"id":"x","name":"Breakfast","sortOrder":0,"quickAddKcal":400,"items":[]}]""")
        }
        client.put("/in/templates") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""[{"id":"x","name":"Lunch","sortOrder":0,"quickAddKcal":600,"items":[]}]""")
        }
        val arr = Json.parseToJsonElement(
            client.get("/in/templates") { header(HttpHeaders.Authorization, "Bearer $token") }.bodyAsText()
        ).jsonArray
        assertEquals(1, arr.size)
        assertEquals("Lunch", arr[0].jsonObject["name"]!!.jsonPrimitive.content)
    }
}
