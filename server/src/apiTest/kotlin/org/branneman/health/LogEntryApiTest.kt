package org.branneman.health

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogEntryApiTest : ApiTestBase() {

    @Test
    fun `POST quick-add then GET then DELETE`() = runTest {
        val token = login()
        val id = UUID.randomUUID().toString()

        val postResp = client.post("$serverUrl/in/log/quick-add") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(QuickAddRequestDto(id = id, quickAddKcal = 350, quickAddLabel = "API test entry"))
        }
        assertEquals(HttpStatusCode.Created, postResp.status)

        val entries = client.get("$serverUrl/in/log") { bearerAuth(token) }
            .body<List<LogEntryDto>>()
        assertTrue(entries.any { it.id == id }, "Created entry must appear in GET /in/log")

        val delResp = client.delete("$serverUrl/in/log/$id") { bearerAuth(token) }
        assertEquals(HttpStatusCode.NoContent, delResp.status)

        val entriesAfter = client.get("$serverUrl/in/log") { bearerAuth(token) }
            .body<List<LogEntryDto>>()
        assertTrue(entriesAfter.none { it.id == id }, "Deleted entry must not appear in GET /in/log")
    }
}
