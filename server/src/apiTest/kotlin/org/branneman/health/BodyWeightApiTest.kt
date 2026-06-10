package org.branneman.health

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.branneman.health.WeightEntryDto
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BodyWeightApiTest : ApiTestBase() {

    // A fixed past date used as a stable test anchor.
    // No delete endpoint exists, so this entry accumulates across runs —
    // 201 on first run, 409 (already exists) on subsequent runs; both are correct.
    private val testDate = "2020-01-01"

    @Test
    fun `POST body weight returns 201 or 409 and GET includes the entry`() = runTest {
        val token = login()

        val postResp = client.post("$serverUrl/body/weight") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(WeightEntryDto(testDate, 80.0))
        }
        assertTrue(
            postResp.status == HttpStatusCode.Created || postResp.status == HttpStatusCode.Conflict,
            "Expected 201 or 409, got ${postResp.status}",
        )

        val entries = client.get("$serverUrl/body/weight") { bearerAuth(token) }
            .body<List<WeightEntryDto>>()
        assertTrue(
            entries.any { it.date == testDate },
            "Expected entry for $testDate in response",
        )
    }
}
