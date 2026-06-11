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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.branneman.health.QuickAddRequestDto
import org.branneman.health.UserProfileDto
import org.branneman.health.ShortcutDto
import org.branneman.health.WeightEntryDto
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
                """{"token":"abc123","expiresAt":"2026-07-05T14:00:00Z","userId":"00000000-0000-0000-0000-000000000001"}""",
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
                """{"token":"newtoken","expiresAt":"2026-08-05T14:00:00Z","userId":"00000000-0000-0000-0000-000000000001"}""",
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

    @Test
    fun `getProfile returns UserProfileDto on 200`() = runBlocking {
        val client = mockClient { _ ->
            respond(
                """{"heightCm":177,"birthYear":1986,"sex":"male","goalWeightKg":74.0,"activityLevel":"lightly_active","targetDeficit":300,"phase":"loss","vacationMode":false}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val result = HealthApiClient("http://test", client).getProfile("token")
        assertEquals(177, result?.heightCm)
    }

    @Test
    fun `getProfile returns null on 404`() = runBlocking {
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }) {
            install(ContentNegotiation) { json() }
        }
        val result = HealthApiClient("http://test", client).getProfile("token")
        assertNull(result)
    }

    @Test
    fun `getShortcuts returns list on 200`() = runBlocking {
        val client = mockClient { _ ->
            respond(
                """[{"id":"abc","emoji":"🍺","label":"Pils","kcal":140,"sortOrder":0}]""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val result = HealthApiClient("http://test", client).getShortcuts("token")
        assertEquals(1, result.size)
        assertEquals("🍺", result[0].emoji)
    }

    @Test
    fun `getBodyWeight returns list on 200`() = runBlocking {
        val client = mockClient { _ ->
            respond(
                """[{"date":"2026-06-01","kg":82.0}]""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val result = HealthApiClient("http://test", client).getBodyWeight("token")
        assertEquals(1, result.size)
    }

    @Test
    fun `postBodyWeight returns on 201`() = runBlocking {
        val client = mockClient { _ ->
            respond(
                """{"date":"2026-06-10","kg":84.0}""",
                HttpStatusCode.Created,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        // Should not throw
        HealthApiClient("http://test", client).postBodyWeight("token", WeightEntryDto("2026-06-10", 84.0))
    }

    @Test
    fun `postBodyWeight does not throw on 409 Conflict`() = runBlocking {
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.Conflict) }) {
            install(ContentNegotiation) { json() }
        }
        // 409 = weight already exists for this date; treat as success
        HealthApiClient("http://test", client).postBodyWeight("token", WeightEntryDto("2026-06-10", 84.0))
    }

    @Test
    fun `postBodyWeight throws on server error`() = runBlocking {
        val httpClient = HttpClient(MockEngine { _ -> respond("", HttpStatusCode.InternalServerError) }) {
            install(ContentNegotiation) { json() }
        }
        assertFailsWith<Exception> {
            HealthApiClient("http://test", httpClient).postBodyWeight("token", WeightEntryDto("2026-06-10", 84.0))
        }
        Unit
    }

    @Test
    fun `postQuickAdd returns LogEntryDto on 201`() = runBlocking {
        val entryId = "00000000-0000-0000-0000-000000000099"
        val client = mockClient { _ ->
            respond(
                """{"id":"$entryId","loggedAt":"2026-06-11T12:00:00Z","mealType":"unknown","quickAddKcal":350,"quickAddLabel":"Lunch","items":[]}""",
                HttpStatusCode.Created,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val result = HealthApiClient("http://test", client).postQuickAdd(
            "token",
            QuickAddRequestDto(id = entryId, quickAddKcal = 350, quickAddLabel = "Lunch"),
        )
        assertEquals(entryId, result.id)
        assertEquals(350, result.quickAddKcal)
    }

    @Test
    fun `deleteLogEntry completes without error on 204`() = runBlocking {
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.NoContent) }) {
            install(ContentNegotiation) { json() }
        }
        HealthApiClient("http://test", client).deleteLogEntry("token", "some-uuid")
    }

    @Test
    fun `deleteLogEntry completes without error on 404`() = runBlocking {
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }) {
            install(ContentNegotiation) { json() }
        }
        HealthApiClient("http://test", client).deleteLogEntry("token", "some-uuid")
    }

    @Test
    fun `deleteLogEntry throws on server error`() = runBlocking {
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.InternalServerError) }) {
            install(ContentNegotiation) { json() }
        }
        assertFailsWith<Exception> {
            HealthApiClient("http://test", client).deleteLogEntry("token", "some-uuid")
        }
        Unit
    }

    @Test
    fun `putProfile throws on server error`() = runBlocking {
        val httpClient = HttpClient(MockEngine { _ -> respond("", HttpStatusCode.InternalServerError) }) {
            install(ContentNegotiation) { json() }
        }
        assertFailsWith<Exception> {
            HealthApiClient("http://test", httpClient).putProfile(
                "token",
                UserProfileDto(177, 1986, "male", 74.0, "lightly_active", 300, "loss", false)
            )
        }
        Unit
    }
}
