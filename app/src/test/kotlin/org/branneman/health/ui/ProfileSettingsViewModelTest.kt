package org.branneman.health.ui

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.branneman.health.UserProfileDto
import org.branneman.health.aUserProfile
import org.branneman.health.auth.TokenStore
import org.branneman.health.db.HealthDatabase
import org.branneman.health.network.HealthApiClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProfileSettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val userId = "user-settings-vm-test"

    private lateinit var db: HealthDatabase
    private lateinit var tokenStore: TokenStore

    private val profileDto = UserProfileDto(
        heightCm      = 182,
        birthYear     = 1990,
        sex           = "male",
        goalWeightKg  = 78.0,
        activityLevel = "lightly_active",
        targetDeficit = 300,
        phase         = "loss",
        vacationMode  = false,
        wakeTime      = "07:00",
        bedtime       = "23:00",
    )

    @Before fun setUp() {
        Dispatchers.setMain(testDispatcher)
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HealthDatabase::class.java,
        ).allowMainThreadQueries().build()
        val dataStore = PreferenceDataStoreFactory.create(
            scope        = CoroutineScope(testDispatcher),
            produceFile  = { File.createTempFile("test_auth_profilevm", ".preferences_pb").also { it.deleteOnExit() } },
        )
        tokenStore = TokenStore(dataStore)
    }

    @After fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun apiClient(dto: UserProfileDto? = profileDto): HealthApiClient {
        val body   = if (dto != null) Json.encodeToString(dto) else ""
        val status = if (dto != null) HttpStatusCode.OK else HttpStatusCode.NotFound
        val engine = MockEngine { respond(body, status, headersOf("Content-Type", "application/json")) }
        return HealthApiClient("http://test", HttpClient(engine) { install(ContentNegotiation) { json() } })
    }

    private suspend fun signIn() {
        val farFuture = java.time.OffsetDateTime.now().plusDays(30).toString()
        tokenStore.save("test-token", farFuture, userId)
    }

    private fun viewModel(apiClient: HealthApiClient = apiClient()) =
        ProfileSettingsViewModel(db, tokenStore, apiClient)

    @Test fun `load populates state from server`() = runTest {
        signIn()
        val vm = viewModel()
        vm.load()
        val s = vm.uiState.first { !it.isLoading }
        assertEquals("male", s.sex)
        assertEquals("182", s.heightCm)
        assertEquals((LocalDate.now().year - 1990).toString(), s.age)
        assertEquals("78.0", s.goalWeightKg)
        assertEquals("lightly_active", s.activityLevel)
        assertEquals(300, s.targetDeficit)
        assertEquals("07:00", s.wakeTime)
        assertEquals("23:00", s.bedtime)
        assertFalse(s.isLoading)
    }

    @Test fun `profileDirty is false after load`() = runTest {
        signIn()
        val vm = viewModel()
        vm.load()
        vm.uiState.first { !it.isLoading }
        assertFalse(vm.uiState.value.profileDirty)
    }

    @Test fun `profileDirty is true after editing a profile field`() = runTest {
        signIn()
        val vm = viewModel()
        vm.load()
        vm.uiState.first { !it.isLoading }
        vm.update { copy(age = "35") }
        assertTrue(vm.uiState.value.profileDirty)
    }

    @Test fun `goalDirty is false after load, true after editing deficit`() = runTest {
        signIn()
        val vm = viewModel()
        vm.load()
        vm.uiState.first { !it.isLoading }
        assertFalse(vm.uiState.value.goalDirty)
        vm.update { copy(targetDeficit = 400) }
        assertTrue(vm.uiState.value.goalDirty)
    }

    @Test fun `scheduleDirty is false after load, true after editing wake time`() = runTest {
        signIn()
        val vm = viewModel()
        vm.load()
        vm.uiState.first { !it.isLoading }
        assertFalse(vm.uiState.value.scheduleDirty)
        vm.update { copy(wakeTime = "06:30") }
        assertTrue(vm.uiState.value.scheduleDirty)
    }

    @Test fun `save calls onSuccess and resets dirty flag`() = runTest {
        signIn()
        var putCalled = false
        val engine = MockEngine { request ->
            if (request.method.value == "GET")
                respond(Json.encodeToString(profileDto), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            else {
                putCalled = true
                respond("", HttpStatusCode.OK)
            }
        }
        val client = HealthApiClient("http://test", HttpClient(engine) { install(ContentNegotiation) { json() } })
        val vm = ProfileSettingsViewModel(db, tokenStore, client)
        vm.load()
        vm.uiState.first { !it.isLoading }
        vm.update { copy(age = "35") }
        assertTrue(vm.uiState.value.profileDirty)

        var successCalled = false
        vm.save(onSuccess = { successCalled = true })
        vm.uiState.first { !it.profileDirty }

        assertTrue(putCalled)
        assertTrue(successCalled)
        assertNull(vm.uiState.value.saveError)
    }

    @Test fun `save sets saveError on API failure`() = runTest {
        signIn()
        val engine = MockEngine { request ->
            if (request.method.value == "GET")
                respond(Json.encodeToString(profileDto), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            else
                respond("", HttpStatusCode.InternalServerError)
        }
        val client = HealthApiClient("http://test", HttpClient(engine) { install(ContentNegotiation) { json() } })
        val vm = ProfileSettingsViewModel(db, tokenStore, client)
        vm.load()
        vm.uiState.first { !it.isLoading }
        vm.update { copy(age = "35") }

        var successCalled = false
        vm.save(onSuccess = { successCalled = true })
        vm.uiState.first { it.saveError != null }

        assertFalse(successCalled)
        assertTrue(vm.uiState.value.saveError != null)
    }

    @Test fun `save upserts Room cache on success`() = runTest {
        signIn()
        db.userProfileDao().upsert(aUserProfile(userId = userId))
        val engine = MockEngine { request ->
            if (request.method.value == "GET")
                respond(Json.encodeToString(profileDto), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            else
                respond("", HttpStatusCode.OK)
        }
        val client = HealthApiClient("http://test", HttpClient(engine) { install(ContentNegotiation) { json() } })
        val vm = ProfileSettingsViewModel(db, tokenStore, client)
        vm.load()
        vm.uiState.first { !it.isLoading }
        vm.update { copy(targetDeficit = 400) }
        vm.save(onSuccess = {})
        vm.uiState.first { !it.goalDirty }

        assertEquals(400, db.userProfileDao().get()?.targetDeficit)
    }
}
