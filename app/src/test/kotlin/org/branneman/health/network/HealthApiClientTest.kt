package org.branneman.health.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class HealthApiClientTest {

    @Test
    fun `isServerReachable returns true when server responds 200`() = runBlocking {
        val httpClient = HttpClient(MockEngine { _ ->
            respond("", HttpStatusCode.OK)
        })
        val client = HealthApiClient("http://test", httpClient)
        assertTrue(client.isServerReachable())
    }

    @Test
    fun `isServerReachable returns false when server responds 500`() = runBlocking {
        val httpClient = HttpClient(MockEngine { _ ->
            respond("", HttpStatusCode.InternalServerError)
        })
        val client = HealthApiClient("http://test", httpClient)
        assertFalse(client.isServerReachable())
    }

    @Test
    fun `isServerReachable returns false when connection fails`() = runBlocking {
        val httpClient = HttpClient(MockEngine { _ ->
            error("simulated connection failure")
        })
        val client = HealthApiClient("http://test", httpClient)
        assertFalse(client.isServerReachable())
    }

    private fun mockClient(handler: MockRequestHandler): HttpClient =
        HttpClient(MockEngine(handler)) {
            install(ContentNegotiation) { json() }
            expectSuccess = true
        }

    @Test
    fun `login returns TokenResponse on 200`() = runBlocking {
        val client = mockClient { _ ->
            respond(
                """{"token":"abc123","expiresAt":"2026-07-05T14:00:00Z"}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val result = HealthApiClient("http://test", client).login("user@example.com", "pass")
        assertEquals("abc123", result.token)
        assertEquals("2026-07-05T14:00:00Z", result.expiresAt)
    }

    @Test
    fun `login throws on 401`() = runBlocking {
        val client = mockClient { _ -> respond("", HttpStatusCode.Unauthorized) }
        assertFailsWith<io.ktor.client.plugins.ClientRequestException> {
            HealthApiClient("http://test", client).login("user@example.com", "wrong")
        }
        Unit
    }

    @Test
    fun `login throws on network failure`() = runBlocking {
        val client = HttpClient(MockEngine { error("connection refused") })
        assertFailsWith<Exception> {
            HealthApiClient("http://test", client).login("user@example.com", "pass")
        }
        Unit
    }

    @Test
    fun `refresh returns new TokenResponse on 200`() = runBlocking {
        val client = mockClient { _ ->
            respond(
                """{"token":"newtoken","expiresAt":"2026-08-05T14:00:00Z"}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val result = HealthApiClient("http://test", client).refresh("old-token")
        assertEquals("newtoken", result.token)
    }

    @Test
    fun `logout completes without error on 204`() = runBlocking {
        val client = mockClient { _ -> respond("", HttpStatusCode.NoContent) }
        HealthApiClient("http://test", client).logout("some-token")
        // no assertion needed — just must not throw
    }
}
