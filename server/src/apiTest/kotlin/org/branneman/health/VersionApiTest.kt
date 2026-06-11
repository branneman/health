package org.branneman.health

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VersionApiTest : ApiTestBase() {

    @Test
    fun `version endpoint returns 200 with non-blank sha`() = runTest {
        val response = client.get("$serverUrl/version")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["sha"]!!.jsonPrimitive.content.isNotBlank())
    }
}
