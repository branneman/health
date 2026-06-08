package org.branneman.health.auth

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.branneman.health.network.HealthApiClient
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthRepositoryTest {

    private fun testTokenStore(): TokenStore {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            produceFile = {
                File.createTempFile("test_auth", ".preferences_pb").also { it.deleteOnExit() }
            }
        )
        return TokenStore(dataStore)
    }

    private fun apiClient(handler: MockRequestHandler): HealthApiClient {
        val engine = MockEngine(handler)
        return HealthApiClient(
            baseUrl = "http://test",
            client = HttpClient(engine) {
                install(ContentNegotiation) { json() }
            }
        )
    }

    @Test
    fun `no token emits LoggedOut`() = runTest {
        val store = testTokenStore()
        val repo = AuthRepository(store, apiClient { respond("", HttpStatusCode.OK) })
        assertEquals(AuthState.LoggedOut, repo.authState.first())
    }

    @Test
    fun `expired token emits Expired`() = runTest {
        val store = testTokenStore()
        store.save("old-token", "2020-01-01T00:00:00Z", "00000000-0000-0000-0000-000000000001")
        val repo = AuthRepository(store, apiClient { respond("", HttpStatusCode.OK) })
        assertEquals(AuthState.Expired, repo.authState.first())
    }

    @Test
    fun `valid token with more than 7 days remaining emits LoggedIn`() = runTest {
        val store = testTokenStore()
        val farFuture = java.time.OffsetDateTime.now().plusDays(30).toString()
        store.save("valid-token", farFuture, "00000000-0000-0000-0000-000000000001")
        val repo = AuthRepository(store, apiClient { respond("", HttpStatusCode.OK) })
        assertEquals(AuthState.LoggedIn, repo.authState.first())
    }

    @Test
    fun `token expiring within 7 days triggers refresh and emits LoggedIn on success`() = runTest {
        val store = testTokenStore()
        val soonExpiry = java.time.OffsetDateTime.now().plusDays(3).toString()
        val newExpiry = java.time.OffsetDateTime.now().plusDays(30).toString()
        store.save("soon-expiring-token", soonExpiry, "00000000-0000-0000-0000-000000000001")

        val client = apiClient { _ ->
            respond(
                """{"token":"refreshed-token","expiresAt":"$newExpiry","userId":"00000000-0000-0000-0000-000000000001"}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val repo = AuthRepository(store, client)
        repo.proactiveRefreshIfNeeded()

        assertEquals(AuthState.LoggedIn, repo.authState.first())
        assertEquals("refreshed-token", store.tokenFlow.first()?.token)
    }

    @Test
    fun `token expiring within 7 days emits Expired when refresh fails`() = runTest {
        val store = testTokenStore()
        val soonExpiry = java.time.OffsetDateTime.now().plusDays(3).toString()
        store.save("soon-expiring-token", soonExpiry, "00000000-0000-0000-0000-000000000001")

        val client = apiClient { respond("", HttpStatusCode.Unauthorized) }
        val repo = AuthRepository(store, client)
        repo.proactiveRefreshIfNeeded()

        assertEquals(AuthState.Expired, repo.authState.first())
    }
}
