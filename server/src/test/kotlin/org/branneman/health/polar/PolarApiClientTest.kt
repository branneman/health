package org.branneman.health.polar

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.util.UUID
import kotlin.test.*

class PolarApiClientTest {

    private fun client(handler: MockRequestHandler) = HttpPolarApiClient(
        httpClient = HttpClient(MockEngine(handler)) { install(ContentNegotiation) { json() } },
        clientId = "test-client-id",
        clientSecret = "test-client-secret",
        redirectUri = "https://example.com/polar/callback",
    )

    @Test
    fun `buildAuthorizationUrl contains client_id, redirect_uri and state`() {
        val c = client { _ -> respond("", HttpStatusCode.OK) }
        val url = c.buildAuthorizationUrl("abc123state")
        assertTrue(url.contains("client_id=test-client-id"))
        assertTrue(url.contains("state=abc123state"))
        assertTrue(url.contains("redirect_uri="))
        assertTrue(url.contains("accesslink.read_all"))
    }

    @Test
    fun `exchangeCode sends Basic auth and form body, returns token and xUserId`() = runBlocking {
        val c = client { req ->
            assertEquals("POST", req.method.value)
            val authHeader = req.headers[HttpHeaders.Authorization] ?: ""
            assertTrue(authHeader.startsWith("Basic "))
            respond(
                """{"access_token":"tok123","token_type":"bearer","expires_in":31535999,"x_user_id":99}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val result = c.exchangeCode("auth-code-xyz")
        assertEquals("tok123", result.accessToken)
        assertEquals(99L, result.xUserId)
    }

    @Test
    fun `exchangeCode throws PolarRateLimitException on 429`() = runBlocking {
        val c = client { _ -> respond("", HttpStatusCode.TooManyRequests) }
        assertFailsWith<PolarRateLimitException> { c.exchangeCode("code") }
        Unit
    }

    @Test
    fun `registerUser succeeds on 200`() = runBlocking {
        var body = ""
        val c = client { req ->
            body = req.body.toByteArray().decodeToString()
            respond("", HttpStatusCode.OK)
        }
        c.registerUser("tok", UUID.fromString("00000000-0000-0000-0000-000000000001"))
        assertTrue(body.contains("00000000-0000-0000-0000-000000000001"))
    }

    @Test
    fun `registerUser does not throw on 409 Conflict`() = runBlocking {
        val c = client { _ -> respond("", HttpStatusCode.Conflict) }
        c.registerUser("tok", UUID.randomUUID())  // should not throw
        Unit
    }

    @Test
    fun `getActivities maps startTime to date, calories to totalKcal, activeCalories to activeKcal`() = runBlocking {
        val c = client { _ ->
            respond(
                """{"activities":[{"start_time":"2026-06-10T06:00:00","calories":2100,"active_calories":400,"steps":8500}]}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val result = c.getActivities("tok", LocalDate.of(2026, 6, 10), LocalDate.of(2026, 6, 10))
        assertEquals(1, result.size)
        assertEquals(LocalDate.of(2026, 6, 10), result[0].date)
        assertEquals(2100, result[0].totalKcal)
        assertEquals(400, result[0].activeKcal)
        assertEquals(8500, result[0].steps)
    }

    @Test
    fun `getActivities returns empty list on 204`() = runBlocking {
        val c = client { _ -> respond("", HttpStatusCode.NoContent) }
        assertEquals(emptyList(), c.getActivities("tok", LocalDate.now(), LocalDate.now()))
    }

    @Test
    fun `getActivities throws PolarRateLimitException on 429`() = runBlocking {
        val c = client { _ -> respond("", HttpStatusCode.TooManyRequests) }
        assertFailsWith<PolarRateLimitException> { c.getActivities("tok", LocalDate.now(), LocalDate.now()) }
        Unit
    }

    @Test
    fun `getExercises maps id, sport, ISO-8601 duration to seconds, heart_rate average`() = runBlocking {
        val c = client { _ ->
            respond(
                """{"exercises":[{"id":"2AC312F","start_time":"2026-06-09T18:00:00","sport":"RUNNING","duration":"PT1H5M30S","calories":450,"heart_rate":{"average":142}}]}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val result = c.getExercises("tok")
        assertEquals(1, result.size)
        assertEquals("2AC312F", result[0].polarId)
        assertEquals(LocalDate.of(2026, 6, 9), result[0].date)
        assertEquals("RUNNING", result[0].sport)
        assertEquals(3930, result[0].durationSecs)  // 1h5m30s = 3930s
        assertEquals(450, result[0].kcal)
        assertEquals(142, result[0].avgHr)
    }

    @Test
    fun `getExercises returns empty list on 204`() = runBlocking {
        val c = client { _ -> respond("", HttpStatusCode.NoContent) }
        assertEquals(emptyList(), c.getExercises("tok"))
    }
}
