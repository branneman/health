package org.branneman.health.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthPluginTest {

    @Test
    fun `non-401 response passes through unchanged`() = runTest {
        val client = HttpClient(MockEngine { respond("ok", HttpStatusCode.OK) }) {
            install(AuthPlugin) {
                onRefreshNeeded = { "new-token" }
                onExpired = {}
            }
        }
        val response = client.get("http://test/data")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `on 401 calls refresh and retries with new token`() = runTest {
        var requestCount = 0
        var lastAuthHeader: String? = null
        val engine = MockEngine { request ->
            requestCount++
            lastAuthHeader = request.headers[HttpHeaders.Authorization]
            if (requestCount == 1) respond("", HttpStatusCode.Unauthorized)
            else respond("ok", HttpStatusCode.OK)
        }
        val client = HttpClient(engine) {
            install(AuthPlugin) {
                onRefreshNeeded = { "new-token" }
                onExpired = {}
            }
        }
        val response = client.get("http://test/data")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(2, requestCount)
        assertEquals("Bearer new-token", lastAuthHeader)
    }

    @Test
    fun `on 401 when refresh returns null calls onExpired and propagates 401`() = runTest {
        var expiredCalled = false
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.Unauthorized) }) {
            install(AuthPlugin) {
                onRefreshNeeded = { null }
                onExpired = { expiredCalled = true }
            }
        }
        val response = client.get("http://test/data")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(expiredCalled)
    }

    @Test
    fun `on 401 from auth path does not retry and calls onExpired`() = runTest {
        var requestCount = 0
        var expiredCalled = false
        val client = HttpClient(MockEngine {
            requestCount++
            respond("", HttpStatusCode.Unauthorized)
        }) {
            install(AuthPlugin) {
                onRefreshNeeded = { "new-token" }
                onExpired = { expiredCalled = true }
            }
        }
        val response = client.get("http://test/auth/refresh")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(1, requestCount)  // no retry
        assertTrue(expiredCalled)
    }

    @Test
    fun `on 401 from auth token path does not retry and does not call onExpired`() = runTest {
        var expiredCalled = false
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.Unauthorized) }) {
            install(AuthPlugin) {
                onRefreshNeeded = { "new-token" }
                onExpired = { expiredCalled = true }
            }
        }
        client.get("http://test/auth/token")
        assertFalse(expiredCalled)
    }
}
