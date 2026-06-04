package org.branneman.health.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
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
}
