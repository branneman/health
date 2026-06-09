package org.branneman.health

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncDownloadApiTest : ApiTestBase() {

    private val syncEndpoints = listOf(
        "/shortcuts",
        "/out/energy",
        "/out/workouts",
        "/in/food-items",
        "/in/templates",
        "/in/log",
        "/body/weight",
    )

    @Test
    fun `all sync endpoints return 200 with valid token`() = runTest {
        val token = login()
        for (path in syncEndpoints) {
            val response = client.get("$serverUrl$path") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, response.status, "Expected 200 for $path")
        }
    }

    @Test
    fun `profile returns 200 or 404 with valid token`() = runTest {
        val token = login()
        val response = client.get("$serverUrl/profile") { bearerAuth(token) }
        assertTrue(
            response.status == HttpStatusCode.OK || response.status == HttpStatusCode.NotFound,
            "Expected 200 or 404 for /profile, got ${response.status}"
        )
    }

    @Test
    fun `all sync endpoints return 401 without token`() = runTest {
        for (path in syncEndpoints + "/profile") {
            val response = client.get("$serverUrl$path")
            assertEquals(HttpStatusCode.Unauthorized, response.status, "Expected 401 for $path")
        }
    }

    private suspend fun login(): String {
        val response = client.post("$serverUrl/auth/token") {
            contentType(ContentType.Application.Json)
            setBody(TokenRequest(apiEmail, apiPassword))
        }
        return response.body<TokenResponse>().token
    }
}
