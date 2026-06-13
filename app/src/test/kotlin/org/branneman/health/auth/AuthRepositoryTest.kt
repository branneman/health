package org.branneman.health.auth

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.branneman.health.aUserProfile
import org.branneman.health.db.HealthDatabase
import org.branneman.health.network.HealthApiClient
import org.branneman.health.uuid
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AuthRepositoryTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun testTokenStore(): TokenStore {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            produceFile = {
                File.createTempFile("test_auth", ".preferences_pb").also { it.deleteOnExit() }
            }
        )
        return TokenStore(dataStore)
    }

    private fun testDb(): HealthDatabase =
        Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HealthDatabase::class.java
        ).allowMainThreadQueries().build()

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun testPolarPreferences(): PolarPreferences {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            produceFile = {
                File.createTempFile("test_polar", ".preferences_pb").also { it.deleteOnExit() }
            }
        )
        return PolarPreferences(dataStore)
    }

    private fun apiClient(handler: MockRequestHandler): HealthApiClient {
        val engine = MockEngine(handler)
        return HealthApiClient(
            baseUrl = "http://test",
            client = HttpClient(engine) { install(ContentNegotiation) { json() } }
        )
    }

    @Test
    fun `no token emits LoggedOut`() = runTest {
        val repo = AuthRepository(testTokenStore(), apiClient { respond("", HttpStatusCode.OK) }, testDb())
        assertEquals(AuthState.LoggedOut, repo.authState.first())
    }

    @Test
    fun `expired token emits Expired`() = runTest {
        val store = testTokenStore()
        store.save("old-token", "2020-01-01T00:00:00Z", uuid())
        val repo = AuthRepository(store, apiClient { respond("", HttpStatusCode.OK) }, testDb())
        assertEquals(AuthState.Expired, repo.authState.first())
    }

    @Test
    fun `valid token without profile emits NeedsOnboarding`() = runTest {
        val store = testTokenStore()
        val farFuture = java.time.OffsetDateTime.now().plusDays(30).toString()
        store.save("valid-token", farFuture, uuid())
        val repo = AuthRepository(store, apiClient { respond("", HttpStatusCode.OK) }, testDb())
        assertEquals(AuthState.NeedsOnboarding, repo.authState.first())
    }

    @Test
    fun `valid token with profile emits LoggedIn`() = runTest {
        val store = testTokenStore()
        val farFuture = java.time.OffsetDateTime.now().plusDays(30).toString()
        val userId = uuid()
        store.save("valid-token", farFuture, userId)
        val db = testDb()
        db.userProfileDao().upsert(aUserProfile(userId = userId))
        val repo = AuthRepository(store, apiClient { respond("", HttpStatusCode.OK) }, db)
        assertEquals(AuthState.LoggedIn, repo.authState.first())
    }

    @Test
    fun `valid token with profile and polar setup not shown emits NeedsPolarSetup`() = runTest {
        val store = testTokenStore()
        val farFuture = java.time.OffsetDateTime.now().plusDays(30).toString()
        val userId = uuid()
        store.save("valid-token", farFuture, userId)
        val db = testDb()
        db.userProfileDao().upsert(aUserProfile(userId = userId))
        val polarPrefs = testPolarPreferences() // polarSetupShown defaults to false
        val repo = AuthRepository(store, apiClient { respond("", HttpStatusCode.OK) }, db, polarPreferences = polarPrefs)
        assertEquals(AuthState.NeedsPolarSetup, repo.authState.first())
    }

    @Test
    fun `valid token with profile and polar setup shown emits LoggedIn`() = runTest {
        val store = testTokenStore()
        val farFuture = java.time.OffsetDateTime.now().plusDays(30).toString()
        val userId = uuid()
        store.save("valid-token", farFuture, userId)
        val db = testDb()
        db.userProfileDao().upsert(aUserProfile(userId = userId))
        val polarPrefs = testPolarPreferences()
        polarPrefs.markPolarSetupShown()
        val repo = AuthRepository(store, apiClient { respond("", HttpStatusCode.OK) }, db, polarPreferences = polarPrefs)
        assertEquals(AuthState.LoggedIn, repo.authState.first())
    }

    @Test
    fun `token expiring within 7 days triggers refresh and emits LoggedIn when profile exists`() = runTest {
        val store = testTokenStore()
        val soonExpiry = java.time.OffsetDateTime.now().plusDays(3).toString()
        val newExpiry  = java.time.OffsetDateTime.now().plusDays(30).toString()
        val userId = uuid()
        store.save("soon-expiring-token", soonExpiry, userId)
        val db = testDb()
        db.userProfileDao().upsert(aUserProfile(userId = userId))

        val client = apiClient { _ ->
            respond(
                """{"token":"refreshed-token","expiresAt":"$newExpiry","userId":"$userId"}""",
                HttpStatusCode.OK,
                io.ktor.http.headersOf(
                    io.ktor.http.HttpHeaders.ContentType,
                    io.ktor.http.ContentType.Application.Json.toString()
                )
            )
        }
        val repo = AuthRepository(store, client, db)
        repo.proactiveRefreshIfNeeded()

        assertEquals(AuthState.LoggedIn, repo.authState.first())
        assertEquals("refreshed-token", store.tokenFlow.first()?.token)
    }

    @Test
    fun `token expiring within 7 days emits Expired when refresh fails`() = runTest {
        val store = testTokenStore()
        val soonExpiry = java.time.OffsetDateTime.now().plusDays(3).toString()
        store.save("soon-expiring-token", soonExpiry, uuid())

        val repo = AuthRepository(
            store,
            apiClient { respond("", HttpStatusCode.Unauthorized) },
            testDb()
        )
        repo.proactiveRefreshIfNeeded()
        assertEquals(AuthState.Expired, repo.authState.first())
    }
}
