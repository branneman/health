package org.branneman.health

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.branneman.health.ShortcutDto
import org.junit.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShortcutsApiTest : ApiTestBase() {

    @Test
    fun `PUT shortcuts returns saved list with server-assigned UUIDs then GET returns same list`() = runTest {
        val token = login()
        val original = client.get("$serverUrl/shortcuts") { bearerAuth(token) }
            .body<List<ShortcutDto>>()

        try {
            val putResp = client.put("$serverUrl/shortcuts") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(listOf(ShortcutDto("", "🧪", "API Test", 100, 0)))
            }
            assertEquals(HttpStatusCode.OK, putResp.status)
            val saved = putResp.body<List<ShortcutDto>>()
            assertEquals(1, saved.size)
            assertEquals("API Test", saved[0].label)
            assertTrue(saved[0].id.isNotBlank())
            UUID.fromString(saved[0].id) // throws if not a valid UUID

            val list = client.get("$serverUrl/shortcuts") { bearerAuth(token) }
                .body<List<ShortcutDto>>()
            assertEquals(1, list.size)
            assertEquals("API Test", list[0].label)
            assertEquals(100, list[0].kcal)
        } finally {
            client.put("$serverUrl/shortcuts") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(original)
            }
        }
    }
}
