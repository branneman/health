package org.branneman.health

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import org.junit.After
import org.junit.Before
import org.junit.Assume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiApiTest : ApiTestBase() {

    private val anthropicApiKey: String? = System.getenv("ANTHROPIC_API_KEY")
    private var token: String = ""

    @Before
    fun setUp() = runTest {
        Assume.assumeNotNull(anthropicApiKey)
        token = login()
        // clean slate
        client.delete("$serverUrl/ai/config") { bearerAuth(token) }
    }

    @After
    fun tearDown() = runTest {
        if (token.isNotBlank()) {
            client.delete("$serverUrl/ai/config") { bearerAuth(token) }
        }
    }

    @Test
    fun `PUT ai-config then GET shows configured true`() = runTest {
        val put = client.put("$serverUrl/ai/config") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"apiKey":"${anthropicApiKey!!}"}""")
        }
        assertEquals(HttpStatusCode.OK, put.status)

        val get = client.get("$serverUrl/ai/config") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, get.status)
        val body = Json.parseToJsonElement(get.bodyAsText()).jsonObject
        assertEquals(true, body["configured"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `POST ai-estimate with text makes live Claude call and returns valid estimate`() = runTest {
        client.put("$serverUrl/ai/config") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"apiKey":"${anthropicApiKey!!}"}""")
        }

        val r = client.post("$serverUrl/ai/estimate") {
            bearerAuth(token)
            setBody(MultiPartFormDataContent(formData {
                append("text", "slice of tiramisu, restaurant portion")
            }))
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        val kcal = body["kcal"]!!.jsonPrimitive.int
        assertTrue(kcal in 1..9999, "kcal $kcal out of range")
        val explanation = body["explanation"]?.jsonPrimitive?.content
        if (explanation != null) assertTrue(explanation.isNotBlank(), "explanation present but blank")
    }
}
