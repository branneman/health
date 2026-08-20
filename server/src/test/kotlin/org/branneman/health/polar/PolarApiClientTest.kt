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

    // Fixtures below are verbatim captures from the live Polar AccessLink API
    // (2026-08-20). Both collection endpoints return a bare JSON array — there is no
    // object envelope. Do not "tidy" these into a wrapper object: an invented envelope
    // is exactly what let a 5-week production outage pass a green test suite.

    @Test
    fun `getActivities maps startTime to date, calories to totalKcal, activeCalories to activeKcal`() = runBlocking {
        val c = client { _ ->
            respond(
                """[{"start_time":"2026-06-10T00:00","end_time":"2026-06-10T23:59:59","active_duration":"PT8H19M","inactive_duration":"PT7H12M30S","daily_activity":164.44,"calories":2100,"active_calories":400,"steps":8500,"inactivity_alert_count":1,"distance_from_steps":5877.54}]""",
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
    fun `getActivities maps every day in a multi-day array`() = runBlocking {
        val c = client { _ ->
            respond(
                """[{"start_time":"2026-08-17T00:00","calories":3551,"active_calories":1682,"steps":9195},{"start_time":"2026-08-18T00:00","calories":2770,"active_calories":913,"steps":4852},{"start_time":"2026-08-19T00:00","calories":3028,"active_calories":1168,"steps":8608}]""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val result = c.getActivities("tok", LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 19))
        assertEquals(3, result.size)
        assertEquals(listOf(3551, 2770, 3028), result.map { it.totalKcal })
        assertEquals(LocalDate.of(2026, 8, 19), result[2].date)
    }

    @Test
    fun `getActivities returns empty list on an empty array`() = runBlocking {
        val c = client { _ ->
            respond("[]", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        assertEquals(emptyList(), c.getActivities("tok", LocalDate.now(), LocalDate.now()))
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
                """[{"id":"2AC312F","start_time":"2026-06-09T18:00:00","sport":"RUNNING","duration":"PT1H5M30S","calories":450,"heart_rate":{"average":142}}]""",
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
    fun `getExercises parses a real payload with fractional duration and unknown fields`() = runBlocking {
        val c = client { _ ->
            respond(
                """[{"id":"y3BwKd7Y","upload_time":"2026-08-17T22:14:10Z","polar_user":"https://www.polaraccesslink.com/v3/users/64323403","device":"Polar Ignite 3","device_id":"12C52633","start_time":"2026-08-17T20:14:48","start_time_utc_offset":120,"duration":"PT14295.773S","heart_rate":{"average":96,"maximum":158},"sport":"OTHER","has_route":false,"detailed_sport_info":"VERTICALSPORTS_WALLCLIMBING","calories":1231,"fat_percentage":61,"carbohydrate_percentage":38,"protein_percentage":1}]""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val result = c.getExercises("tok")
        assertEquals(1, result.size)
        assertEquals("y3BwKd7Y", result[0].polarId)
        assertEquals(LocalDate.of(2026, 8, 17), result[0].date)
        assertEquals("OTHER", result[0].sport)
        assertEquals(14295, result[0].durationSecs)  // fractional seconds truncated
        assertEquals(1231, result[0].kcal)
        assertEquals(96, result[0].avgHr)
    }

    @Test
    fun `getExercises returns empty list on an empty array`() = runBlocking {
        val c = client { _ ->
            respond("[]", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        assertEquals(emptyList(), c.getExercises("tok"))
    }

    @Test
    fun `getExercises returns empty list on 204`() = runBlocking {
        val c = client { _ -> respond("", HttpStatusCode.NoContent) }
        assertEquals(emptyList(), c.getExercises("tok"))
    }
}
