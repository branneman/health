package org.branneman.health

import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.branneman.health.ai.AnthropicGateway
import org.branneman.health.ai.ClaudeEstimate
import org.branneman.health.ai.ClaudeEstimateException
import org.branneman.health.auth.Users
import org.branneman.health.data.AiConfig
import org.branneman.health.polar.TokenCipher
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import java.util.UUID
import kotlin.test.*

class AiIntegrationTest {

    companion object {
        private val ds = TestDatabase.dataSource
        private val testUserId  = UUID.fromString("00000000-0000-0000-0000-000000000011")
        private val testUserId2 = UUID.fromString("00000000-0000-0000-0000-000000000012")
        private const val TEST_EMAIL  = "ai-test@test.local"
        private const val TEST_EMAIL2 = "ai-test-b@test.local"
        private const val TEST_PASSWORD = "testpassword"
        private val TEST_HASH = BCrypt.hashpw(TEST_PASSWORD, BCrypt.gensalt(4))

        private val testCipher = TokenCipher(ByteArray(32) { it.toByte() })

        private val fakeGateway = object : AnthropicGateway {
            var nextResult: (() -> ClaudeEstimate)? = null
            override fun estimate(apiKey: String, text: String?, imageBase64: String?, imageMimeType: String?): ClaudeEstimate {
                return nextResult?.invoke()
                    ?: ClaudeEstimate(500, "Default test estimate.")
            }
        }

        private val failingGateway = object : AnthropicGateway {
            override fun estimate(apiKey: String, text: String?, imageBase64: String?, imageMimeType: String?): ClaudeEstimate {
                throw ClaudeEstimateException("Claude unavailable")
            }
        }

        init {
            Database.connect(ds)
            transaction {
                Users.deleteWhere { Users.username eq TEST_EMAIL }
                Users.deleteWhere { Users.id eq testUserId }
                Users.insert {
                    it[id]           = testUserId
                    it[username]     = TEST_EMAIL
                    it[passwordHash] = TEST_HASH
                }
                Users.deleteWhere { Users.username eq TEST_EMAIL2 }
                Users.deleteWhere { Users.id eq testUserId2 }
                Users.insert {
                    it[id]           = testUserId2
                    it[username]     = TEST_EMAIL2
                    it[passwordHash] = TEST_HASH
                }
            }
        }
    }

    @BeforeTest
    fun setUp() {
        transaction {
            AiConfig.deleteWhere { AiConfig.userId eq testUserId }
            AiConfig.deleteWhere { AiConfig.userId eq testUserId2 }
        }
        fakeGateway.nextResult = null
    }

    private fun appTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(ds, aiCipher = testCipher, anthropicGateway = fakeGateway) }
        block()
    }

    private suspend fun ApplicationTestBuilder.loginToken(): String {
        val r = client.post("/auth/token") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$TEST_EMAIL","password":"$TEST_PASSWORD"}""")
        }
        return Json.parseToJsonElement(r.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.loginToken2(): String {
        val r = client.post("/auth/token") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$TEST_EMAIL2","password":"$TEST_PASSWORD"}""")
        }
        return Json.parseToJsonElement(r.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content
    }

    @Test
    fun `GET ai-config returns 401 without token`() = appTest {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/ai/config").status)
    }

    @Test
    fun `PUT ai-config returns 401 without token`() = appTest {
        assertEquals(HttpStatusCode.Unauthorized, client.put("/ai/config").status)
    }

    @Test
    fun `POST ai-estimate returns 401 without token`() = appTest {
        assertEquals(HttpStatusCode.Unauthorized, client.post("/ai/estimate").status)
    }

    @Test
    fun `GET ai-config when no row returns configured false`() = appTest {
        val token = loginToken()
        val r = client.get("/ai/config") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals(false, body["configured"]!!.jsonPrimitive.boolean)
        assertTrue(body.containsKey("expiresAt"))
    }

    @Test
    fun `PUT ai-config stores key and GET returns configured true`() = appTest {
        val token = loginToken()
        val put = client.put("/ai/config") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"apiKey":"sk-ant-test-key","expiresAt":"2027-01-01"}""")
        }
        assertEquals(HttpStatusCode.OK, put.status)
        val putBody = Json.parseToJsonElement(put.bodyAsText()).jsonObject
        assertEquals(true, putBody["configured"]!!.jsonPrimitive.boolean)
        assertEquals("2027-01-01", putBody["expiresAt"]!!.jsonPrimitive.content)

        val get = client.get("/ai/config") { bearerAuth(token) }
        val getBody = Json.parseToJsonElement(get.bodyAsText()).jsonObject
        assertEquals(true, getBody["configured"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `GET ai-config response never contains the key value`() = appTest {
        val token = loginToken()
        client.put("/ai/config") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"apiKey":"sk-ant-super-secret"}""")
        }
        val r = client.get("/ai/config") { bearerAuth(token) }
        assertFalse(r.bodyAsText().contains("sk-ant-super-secret"))
    }

    @Test
    fun `GET ai-config returns configured false when expires_at is in the past`() = appTest {
        val token = loginToken()
        client.put("/ai/config") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"apiKey":"sk-ant-key","expiresAt":"2020-01-01"}""")
        }
        val r = client.get("/ai/config") { bearerAuth(token) }
        val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals(false, body["configured"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `DELETE ai-config removes key and GET returns not configured`() = appTest {
        val token = loginToken()
        client.put("/ai/config") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"apiKey":"sk-ant-key"}""")
        }
        val del = client.delete("/ai/config") { bearerAuth(token) }
        assertEquals(HttpStatusCode.NoContent, del.status)
        val r = client.get("/ai/config") { bearerAuth(token) }
        assertEquals(false, Json.parseToJsonElement(r.bodyAsText()).jsonObject["configured"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `POST ai-estimate with no parts returns 400`() = appTest {
        val token = loginToken()
        val r = client.post("/ai/estimate") { bearerAuth(token) }
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test
    fun `POST ai-estimate with text over 500 chars returns 400`() = appTest {
        val token = loginToken()
        client.put("/ai/config") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"apiKey":"sk-ant-key"}""")
        }
        val r = client.post("/ai/estimate") {
            bearerAuth(token)
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("text", "x".repeat(501))
                    }
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test
    fun `POST ai-estimate with no ai_config returns 422 ai_not_configured`() = appTest {
        val token = loginToken()
        val r = client.post("/ai/estimate") {
            bearerAuth(token)
            setBody(MultiPartFormDataContent(formData { append("text", "tiramisu") }))
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, r.status)
        assertTrue(r.bodyAsText().contains("ai_not_configured"))
    }

    @Test
    fun `POST ai-estimate with expired key returns 422 ai_key_expired`() = appTest {
        val token = loginToken()
        client.put("/ai/config") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"apiKey":"sk-ant-key","expiresAt":"2020-01-01"}""")
        }
        val r = client.post("/ai/estimate") {
            bearerAuth(token)
            setBody(MultiPartFormDataContent(formData { append("text", "tiramisu") }))
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, r.status)
        assertTrue(r.bodyAsText().contains("ai_key_expired"))
    }

    @Test
    fun `POST ai-estimate with valid config and fake gateway returns 200 with kcal and explanation`() = appTest {
        val token = loginToken()
        client.put("/ai/config") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"apiKey":"sk-ant-key"}""")
        }
        fakeGateway.nextResult = { ClaudeEstimate(650, "Standard tiramisu portion.") }
        val r = client.post("/ai/estimate") {
            bearerAuth(token)
            setBody(MultiPartFormDataContent(formData { append("text", "tiramisu") }))
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals(650, body["kcal"]!!.jsonPrimitive.content.toInt())
        assertEquals("Standard tiramisu portion.", body["explanation"]!!.jsonPrimitive.content)
    }

    @Test
    fun `POST ai-estimate when gateway throws returns 422 ai_estimate_failed`() = testApplication {
        application { module(ds, aiCipher = testCipher, anthropicGateway = failingGateway) }
        val token = client.post("/auth/token") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$TEST_EMAIL","password":"$TEST_PASSWORD"}""")
        }.let { Json.parseToJsonElement(it.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content }

        transaction {
            AiConfig.insert {
                it[AiConfig.id]        = UUID.randomUUID()
                it[AiConfig.userId]    = testUserId
                it[AiConfig.apiKey]    = testCipher.encrypt("sk-ant-key")
                it[AiConfig.expiresAt] = null
                it[AiConfig.createdAt] = java.time.OffsetDateTime.now()
            }
        }
        val r = client.post("/ai/estimate") {
            bearerAuth(token)
            setBody(MultiPartFormDataContent(formData { append("text", "pizza") }))
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, r.status)
        assertTrue(r.bodyAsText().contains("ai_estimate_failed"))
    }

    @Test
    fun `user A ai_config is not accessible via user B token`() = appTest {
        val tokenA = loginToken()
        val tokenB = loginToken2()
        client.put("/ai/config") {
            bearerAuth(tokenA)
            contentType(ContentType.Application.Json)
            setBody("""{"apiKey":"sk-ant-key-a"}""")
        }
        val statusB = client.get("/ai/config") { bearerAuth(tokenB) }
        val body = Json.parseToJsonElement(statusB.bodyAsText()).jsonObject
        assertEquals(false, body["configured"]!!.jsonPrimitive.boolean)
        assertFalse(statusB.bodyAsText().contains("sk-ant-key-a"))
    }
}
