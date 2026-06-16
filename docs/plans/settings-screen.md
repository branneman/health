# Settings Screen Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose all onboarding-configurable data as editable Settings sub-pages (Profile, Goal, Schedule), redesign the main Settings screen with proper sections and consistent typography, and fix the bare-Text typography bug in the server-status area.

**Architecture:** One shared `ProfileSettingsViewModel` loads the profile via `GET /profile` and saves via `PUT /profile`; dirty-state detection is built into `ProfileSettingsUiState` using saved-snapshot fields. Three new sub-page composables (Profile, Goal, Schedule) each call `viewModel.load()` on entry and `viewModel.save(onSuccess = onBack)` on tap. The main `SettingsContent` composable is replaced with a section-based `ListItem` layout. Navigation uses the existing `SettingsPage` enum in `App.kt`, extended with three new values.

**Tech Stack:** Kotlin, Jetpack Compose, Material3 (`ListItem`, `BackHandler` from `activity-compose`), Room (`UserProfileDao`, `BodyWeightDao`), Ktor `MockEngine` for ViewModel tests, Robolectric for Composable tests.

---

## File Map

| Action | File |
|--------|------|
| Modify | `app/src/main/kotlin/org/branneman/health/ui/SettingsScreen.kt` |
| Modify | `app/src/main/kotlin/org/branneman/health/App.kt` |
| Modify | `app/src/test/kotlin/org/branneman/health/ui/SettingsScreenTest.kt` |
| Create | `app/src/main/kotlin/org/branneman/health/ui/ProfileSettingsViewModel.kt` |
| Create | `app/src/main/kotlin/org/branneman/health/ui/ProfileSettingsScreen.kt` |
| Create | `app/src/main/kotlin/org/branneman/health/ui/GoalSettingsScreen.kt` |
| Create | `app/src/main/kotlin/org/branneman/health/ui/ScheduleSettingsScreen.kt` |
| Create | `app/src/test/kotlin/org/branneman/health/ui/ProfileSettingsViewModelTest.kt` |
| Create | `app/src/test/kotlin/org/branneman/health/ui/ProfileSettingsScreenTest.kt` |
| Create | `app/src/test/kotlin/org/branneman/health/ui/GoalSettingsScreenTest.kt` |
| Create | `app/src/test/kotlin/org/branneman/health/ui/ScheduleSettingsScreenTest.kt` |

---

## Task 1: Redesign main SettingsScreen and extend SettingsPage enum

**Files:**
- Modify: `app/src/main/kotlin/org/branneman/health/ui/SettingsScreen.kt`
- Modify: `app/src/main/kotlin/org/branneman/health/App.kt`
- Modify: `app/src/test/kotlin/org/branneman/health/ui/SettingsScreenTest.kt`

- [ ] **Step 1: Write failing tests for the new layout**

Replace `SettingsScreenTest.kt` entirely:

```kotlin
package org.branneman.health.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SettingsScreenTest {

    @get:Rule val compose = createComposeRule()

    private fun render(
        onNavigateProfile: () -> Unit = {},
        onNavigateGoal: () -> Unit = {},
        onNavigateSchedule: () -> Unit = {},
        onNavigateMealButtons: () -> Unit = {},
        onNavigateDrinkButtons: () -> Unit = {},
        serverReachable: Boolean? = true,
        lastSyncedAt: Long? = null,
        polarStatus: PolarStatus = PolarStatus.Connected,
        onConnectPolar: (() -> Unit)? = null,
        onSyncNow: (() -> Unit)? = null,
    ) {
        compose.setContent {
            MaterialTheme {
                SettingsContent(
                    onNavigateProfile = onNavigateProfile,
                    onNavigateGoal = onNavigateGoal,
                    onNavigateSchedule = onNavigateSchedule,
                    onNavigateMealButtons = onNavigateMealButtons,
                    onNavigateDrinkButtons = onNavigateDrinkButtons,
                    onSignOut = {},
                    serverReachable = serverReachable,
                    lastSyncedAt = lastSyncedAt,
                    polarStatus = polarStatus,
                    onConnectPolar = onConnectPolar,
                    onSyncNow = onSyncNow,
                )
            }
        }
    }

    @Test fun `Profile row is present and navigates`() {
        var tapped = false
        render(onNavigateProfile = { tapped = true })
        compose.onNodeWithText("Profile", substring = true).performClick()
        assertTrue(tapped)
    }

    @Test fun `Goal row is present and navigates`() {
        var tapped = false
        render(onNavigateGoal = { tapped = true })
        compose.onNodeWithText("Goal", substring = true).performClick()
        assertTrue(tapped)
    }

    @Test fun `Schedule row is present and navigates`() {
        var tapped = false
        render(onNavigateSchedule = { tapped = true })
        compose.onNodeWithText("Schedule", substring = true).performClick()
        assertTrue(tapped)
    }

    @Test fun `Meal buttons row is present and navigates`() {
        var tapped = false
        render(onNavigateMealButtons = { tapped = true })
        compose.onNodeWithText("Meal buttons", substring = true).performClick()
        assertTrue(tapped)
    }

    @Test fun `Drink buttons row is present and navigates`() {
        var tapped = false
        render(onNavigateDrinkButtons = { tapped = true })
        compose.onNodeWithText("Drink buttons", substring = true).performClick()
        assertTrue(tapped)
    }

    @Test fun `shows Connect button when Polar not connected`() {
        var tapped = false
        render(polarStatus = PolarStatus.NotConnected, onConnectPolar = { tapped = true })
        compose.onNodeWithText("Connect", substring = true).performClick()
        assertTrue(tapped)
    }

    @Test fun `shows Connected status when Polar connected`() {
        render(polarStatus = PolarStatus.Connected)
        compose.onNodeWithText("Connected", substring = true).assertExists()
    }

    @Test fun `Sync now button is present and calls onSyncNow`() {
        var tapped = false
        render(onSyncNow = { tapped = true })
        compose.onNodeWithText("Sync now", substring = true).performClick()
        assertTrue(tapped)
    }

    @Test fun `server status text uses consistent style - Online shown`() {
        render(serverReachable = true)
        compose.onNodeWithText("Online", substring = true).assertExists()
    }

    @Test fun `server status shows Offline when server unreachable`() {
        render(serverReachable = false)
        compose.onNodeWithText("Offline", substring = true).assertExists()
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
./gradlew :app:test --tests "org.branneman.health.ui.SettingsScreenTest" 2>&1 | tail -20
```

Expected: compile errors or test failures (SettingsContent signature doesn't match yet).

- [ ] **Step 3: Replace SettingsScreen.kt with the new sectioned layout**

Replace `SettingsScreen.kt` entirely:

```kotlin
package org.branneman.health.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.branneman.health.BuildConfig
import org.branneman.health.HealthApplication
import org.branneman.health.network.HealthApiClient
import org.branneman.health.sync.SyncWorker
import org.branneman.health.sync.lastSyncedAtFlow
import org.branneman.health.sync.syncDataStore
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val syncTimestampFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm")

@Composable
fun SettingsScreen(
    onSignOut: () -> Unit,
    onNavigateProfile: () -> Unit = {},
    onNavigateGoal: () -> Unit = {},
    onNavigateSchedule: () -> Unit = {},
    onNavigateMealButtons: () -> Unit = {},
    onNavigateDrinkButtons: () -> Unit = {},
) {
    val context = LocalContext.current
    var serverReachable by remember { mutableStateOf<Boolean?>(null) }
    val lastSyncedAt by context.syncDataStore.lastSyncedAtFlow.collectAsState(initial = null)
    val viewModel: SettingsViewModel = viewModel()
    val polarStatus by viewModel.polarStatus.collectAsState()
    val polarCallbackPending by (context.applicationContext as HealthApplication)
        .polarCallbackPending.collectAsState()

    LaunchedEffect(Unit) {
        serverReachable = HealthApiClient().isServerReachable()
    }

    LaunchedEffect(polarCallbackPending) {
        if (polarCallbackPending) {
            (context.applicationContext as HealthApplication).clearPolarCallback()
            viewModel.recheckPolarStatus()
        }
    }

    SettingsContent(
        onNavigateProfile      = onNavigateProfile,
        onNavigateGoal         = onNavigateGoal,
        onNavigateSchedule     = onNavigateSchedule,
        onNavigateMealButtons  = onNavigateMealButtons,
        onNavigateDrinkButtons = onNavigateDrinkButtons,
        onSignOut              = onSignOut,
        serverReachable        = serverReachable,
        lastSyncedAt           = lastSyncedAt,
        polarStatus            = polarStatus,
        onConnectPolar         = if (polarStatus == PolarStatus.NotConnected) {
            {
                viewModel.connectPolar { url ->
                    CustomTabsIntent.Builder().build()
                        .launchUrl(context, Uri.parse(url))
                }
            }
        } else null,
        onSyncNow              = { SyncWorker.syncNow(context) },
        versionName            = BuildConfig.VERSION_NAME,
    )
}

@Composable
fun SettingsContent(
    onNavigateProfile: () -> Unit,
    onNavigateGoal: () -> Unit,
    onNavigateSchedule: () -> Unit,
    onNavigateMealButtons: () -> Unit,
    onNavigateDrinkButtons: () -> Unit,
    onSignOut: () -> Unit,
    serverReachable: Boolean? = null,
    lastSyncedAt: Long? = null,
    polarStatus: PolarStatus = PolarStatus.Unknown,
    onConnectPolar: (() -> Unit)? = null,
    onSyncNow: (() -> Unit)? = null,
    versionName: String = "",
) {
    var showSignOutConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        SettingsSectionHeader("Profile")
        SettingsNavRow("Profile", "Sex, height, age, goal weight", onNavigateProfile)
        SettingsNavRow("Goal", "Activity level · calorie deficit", onNavigateGoal)
        SettingsNavRow("Schedule", "Wake time · Bedtime", onNavigateSchedule)

        HorizontalDivider()
        SettingsSectionHeader("Quick buttons")
        SettingsNavRow("Meal buttons", null, onNavigateMealButtons)
        SettingsNavRow("Drink buttons", null, onNavigateDrinkButtons)

        HorizontalDivider()
        SettingsSectionHeader("Connections")
        ListItem(
            headlineContent   = { Text("Polar watch") },
            supportingContent = {
                Text(
                    text  = when (polarStatus) {
                        PolarStatus.Connected    -> "Connected"
                        PolarStatus.NotConnected -> "Not connected"
                        PolarStatus.Loading      -> "Checking…"
                        PolarStatus.Unknown      -> "Unknown"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingContent = when {
                polarStatus == PolarStatus.Connected -> {{ Text("✓", color = MaterialTheme.colorScheme.primary) }}
                onConnectPolar != null               -> {{ OutlinedButton(onClick = onConnectPolar) { Text("Connect") } }}
                else                                 -> null
            },
        )

        HorizontalDivider()
        SettingsSectionHeader("Sync")
        ListItem(
            headlineContent   = { Text("Server") },
            supportingContent = {
                Text(
                    text  = when (serverReachable) {
                        null  -> "Checking…"
                        true  -> "Online${lastSyncedAt?.let {
                            val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault())
                            " · synced ${dt.format(syncTimestampFormatter)}"
                        } ?: ""}"
                        false -> "Offline"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingContent = if (onSyncNow != null) {{
                TextButton(onClick = onSyncNow) { Text("Sync now") }
            }} else null,
        )

        Spacer(Modifier.height(16.dp))
        Text(
            text     = "Version: $versionName",
            style    = MaterialTheme.typography.bodySmall,
            color    = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
        TextButton(
            onClick  = { showSignOutConfirm = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Sign out", color = MaterialTheme.colorScheme.error)
        }
    }

    if (showSignOutConfirm) {
        AlertDialog(
            onDismissRequest = { showSignOutConfirm = false },
            title            = { Text("Sign out?") },
            text             = { Text("Your data will be removed from this device. It's all saved on the server.") },
            confirmButton    = {
                TextButton(onClick = { showSignOutConfirm = false; onSignOut() }) {
                    Text("Sign out")
                }
            },
            dismissButton    = {
                TextButton(onClick = { showSignOutConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text     = title,
        style    = MaterialTheme.typography.labelLarge,
        color    = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingsNavRow(title: String, subtitle: String?, onClick: () -> Unit) {
    ListItem(
        headlineContent   = { Text(title) },
        supportingContent = subtitle?.let { sub -> { Text(sub) } },
        trailingContent   = { Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        modifier          = Modifier.clickable(onClick = onClick),
    )
}
```

- [ ] **Step 4: Extend SettingsPage enum in App.kt**

In `App.kt`, find the line:
```kotlin
private enum class SettingsPage { Main, MealButtons, DrinkButtons }
```
Replace with:
```kotlin
private enum class SettingsPage { Main, MealButtons, DrinkButtons, Profile, Goal, Schedule }
```

Also update the `SettingsScreen(...)` call in `MainNav` to pass the new callbacks (wired to `{}` for now — Task 6 will connect them):
```kotlin
SettingsPage.Main -> SettingsScreen(
    onSignOut              = { authViewModel.logout() },
    onNavigateMealButtons  = { settingsPage = SettingsPage.MealButtons },
    onNavigateDrinkButtons = { settingsPage = SettingsPage.DrinkButtons },
    onNavigateProfile      = { settingsPage = SettingsPage.Profile },
    onNavigateGoal         = { settingsPage = SettingsPage.Goal },
    onNavigateSchedule     = { settingsPage = SettingsPage.Schedule },
)
```

Also add placeholder branches so the `when` is exhaustive (Task 6 will replace these):
```kotlin
SettingsPage.Profile  -> { /* Task 6 */ }
SettingsPage.Goal     -> { /* Task 6 */ }
SettingsPage.Schedule -> { /* Task 6 */ }
```

- [ ] **Step 5: Run tests**

```bash
./gradlew :app:test --tests "org.branneman.health.ui.SettingsScreenTest" 2>&1 | tail -20
```

Expected: all tests pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/ui/SettingsScreen.kt \
        app/src/main/kotlin/org/branneman/health/App.kt \
        app/src/test/kotlin/org/branneman/health/ui/SettingsScreenTest.kt
git commit -m "feat(app): redesign Settings main screen with sections and ListItem rows"
```

---

## Task 2: ProfileSettingsViewModel

**Files:**
- Create: `app/src/main/kotlin/org/branneman/health/ui/ProfileSettingsViewModel.kt`
- Create: `app/src/test/kotlin/org/branneman/health/ui/ProfileSettingsViewModelTest.kt`

- [ ] **Step 1: Write failing ViewModel tests**

Create `ProfileSettingsViewModelTest.kt`:

```kotlin
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

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HealthDatabase::class.java,
        ).allowMainThreadQueries().build()
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(testDispatcher),
            produceFile = {
                File.createTempFile("test_auth_profilevm", ".preferences_pb")
                    .also { it.deleteOnExit() }
            },
        )
        tokenStore = TokenStore(dataStore)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun apiClient(dto: UserProfileDto? = profileDto): HealthApiClient {
        val body = if (dto != null) Json.encodeToString(dto) else ""
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
        testDispatcher.scheduler.advanceUntilIdle()
        val state = vm.uiState.value
        assertEquals("male", state.sex)
        assertEquals("182", state.heightCm)
        assertEquals((LocalDate.now().year - 1990).toString(), state.age)
        assertEquals("78.0", state.goalWeightKg)
        assertEquals("lightly_active", state.activityLevel)
        assertEquals(300, state.targetDeficit)
        assertEquals("07:00", state.wakeTime)
        assertEquals("23:00", state.bedtime)
        assertFalse(state.isLoading)
    }

    @Test fun `profileDirty is false after load`() = runTest {
        signIn()
        val vm = viewModel()
        vm.load()
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.uiState.value.profileDirty)
    }

    @Test fun `profileDirty is true after editing a profile field`() = runTest {
        signIn()
        val vm = viewModel()
        vm.load()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.update { copy(age = "35") }
        assertTrue(vm.uiState.value.profileDirty)
    }

    @Test fun `goalDirty is false after load, true after editing deficit`() = runTest {
        signIn()
        val vm = viewModel()
        vm.load()
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.uiState.value.goalDirty)
        vm.update { copy(targetDeficit = 400) }
        assertTrue(vm.uiState.value.goalDirty)
    }

    @Test fun `scheduleDirty is false after load, true after editing wake time`() = runTest {
        signIn()
        val vm = viewModel()
        vm.load()
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.uiState.value.scheduleDirty)
        vm.update { copy(wakeTime = "06:30") }
        assertTrue(vm.uiState.value.scheduleDirty)
    }

    @Test fun `save calls onSuccess and resets dirty flag`() = runTest {
        signIn()
        var putCalled = false
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/profile") && request.method.value == "GET") {
                respond(Json.encodeToString(profileDto), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            } else {
                putCalled = true
                respond("", HttpStatusCode.OK)
            }
        }
        val client = HealthApiClient("http://test", HttpClient(engine) { install(ContentNegotiation) { json() } })
        val vm = ProfileSettingsViewModel(db, tokenStore, client)
        vm.load()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.update { copy(age = "35") }
        assertTrue(vm.uiState.value.profileDirty)

        var successCalled = false
        vm.save(onSuccess = { successCalled = true })
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(putCalled)
        assertTrue(successCalled)
        assertFalse(vm.uiState.value.profileDirty)
        assertNull(vm.uiState.value.saveError)
    }

    @Test fun `save sets saveError on API failure`() = runTest {
        signIn()
        val engine = MockEngine { request ->
            if (request.method.value == "GET") {
                respond(Json.encodeToString(profileDto), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            } else {
                respond("", HttpStatusCode.InternalServerError)
            }
        }
        val client = HealthApiClient("http://test", HttpClient(engine) { install(ContentNegotiation) { json() } })
        val vm = ProfileSettingsViewModel(db, tokenStore, client)
        vm.load()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.update { copy(age = "35") }

        var successCalled = false
        vm.save(onSuccess = { successCalled = true })
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(successCalled)
        assertTrue(vm.uiState.value.saveError != null)
    }

    @Test fun `save upserts Room cache on success`() = runTest {
        signIn()
        db.userProfileDao().upsert(aUserProfile(userId = userId))
        val engine = MockEngine { request ->
            if (request.method.value == "GET") {
                respond(Json.encodeToString(profileDto), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            } else {
                respond("", HttpStatusCode.OK)
            }
        }
        val client = HealthApiClient("http://test", HttpClient(engine) { install(ContentNegotiation) { json() } })
        val vm = ProfileSettingsViewModel(db, tokenStore, client)
        vm.load()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.update { copy(targetDeficit = 400) }
        vm.save(onSuccess = {})
        testDispatcher.scheduler.advanceUntilIdle()

        val entity = db.userProfileDao().get()
        assertEquals(400, entity?.targetDeficit)
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
./gradlew :app:test --tests "org.branneman.health.ui.ProfileSettingsViewModelTest" 2>&1 | tail -10
```

Expected: compile error — `ProfileSettingsViewModel` does not exist yet.

- [ ] **Step 3: Create ProfileSettingsViewModel.kt**

```kotlin
package org.branneman.health.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.branneman.health.HealthApplication
import org.branneman.health.UserProfileDto
import org.branneman.health.auth.TokenStore
import org.branneman.health.auth.authDataStore
import org.branneman.health.db.HealthDatabase
import org.branneman.health.db.SyncStatus
import org.branneman.health.db.entities.UserProfileEntity
import org.branneman.health.network.HealthApiClient
import java.time.LocalDate

data class ProfileSettingsUiState(
    val isLoading: Boolean = true,
    // Editable fields
    val sex: String = "",
    val heightCm: String = "",
    val age: String = "",
    val goalWeightKg: String = "",
    val activityLevel: String = "lightly_active",
    val targetDeficit: Int = 300,
    val wakeTime: String = "07:00",
    val bedtime: String = "23:00",
    // Save feedback
    val isSaving: Boolean = false,
    val saveError: String? = null,
    // Pass-through (not shown in UI, preserved when PUTting)
    val phase: String = "loss",
    val vacationMode: Boolean = false,
    val userId: String = "",
    // Saved snapshots for dirty detection (updated on load and successful save)
    val savedSex: String = "",
    val savedHeightCm: String = "",
    val savedAge: String = "",
    val savedGoalWeightKg: String = "",
    val savedActivityLevel: String = "lightly_active",
    val savedTargetDeficit: Int = 300,
    val savedWakeTime: String = "07:00",
    val savedBedtime: String = "23:00",
) {
    val profileDirty: Boolean get() = !isLoading && (
        sex != savedSex || heightCm != savedHeightCm ||
        age != savedAge || goalWeightKg != savedGoalWeightKg
    )
    val goalDirty: Boolean get() = !isLoading && (
        activityLevel != savedActivityLevel || targetDeficit != savedTargetDeficit
    )
    val scheduleDirty: Boolean get() = !isLoading && (
        wakeTime != savedWakeTime || bedtime != savedBedtime
    )
}

class ProfileSettingsViewModel private constructor(
    application: Application,
    private val db: HealthDatabase,
    private val tokenStore: TokenStore,
    private val apiClient: HealthApiClient,
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application = application,
        db          = (application as HealthApplication).db,
        tokenStore  = TokenStore(application.authDataStore),
        apiClient   = HealthApiClient(),
    )

    internal constructor(
        db: HealthDatabase,
        tokenStore: TokenStore,
        apiClient: HealthApiClient,
    ) : this(
        application = Application(),
        db          = db,
        tokenStore  = tokenStore,
        apiClient   = apiClient,
    )

    private val _state = MutableStateFlow(ProfileSettingsUiState())
    val uiState: StateFlow<ProfileSettingsUiState> = _state.asStateFlow()

    val latestWeightKg: StateFlow<Double?> = db.bodyWeightDao().observeAll()
        .map { it.firstOrNull()?.kg }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, saveError = null) }
            val stored = tokenStore.tokenFlow.first() ?: return@launch
            runCatching {
                val dto = apiClient.getProfile(stored.token) ?: return@launch
                val age = (LocalDate.now().year - dto.birthYear).toString()
                _state.value = ProfileSettingsUiState(
                    isLoading         = false,
                    sex               = dto.sex,
                    heightCm          = dto.heightCm.toString(),
                    age               = age,
                    goalWeightKg      = dto.goalWeightKg.toString(),
                    activityLevel     = dto.activityLevel,
                    targetDeficit     = dto.targetDeficit,
                    wakeTime          = dto.wakeTime,
                    bedtime           = dto.bedtime,
                    phase             = dto.phase,
                    vacationMode      = dto.vacationMode,
                    userId            = stored.userId,
                    savedSex          = dto.sex,
                    savedHeightCm     = dto.heightCm.toString(),
                    savedAge          = age,
                    savedGoalWeightKg = dto.goalWeightKg.toString(),
                    savedActivityLevel = dto.activityLevel,
                    savedTargetDeficit = dto.targetDeficit,
                    savedWakeTime     = dto.wakeTime,
                    savedBedtime      = dto.bedtime,
                )
            }.onFailure {
                _state.update { it.copy(isLoading = false, saveError = "Couldn't load profile.") }
            }
        }
    }

    fun update(block: ProfileSettingsUiState.() -> ProfileSettingsUiState) =
        _state.update(block)

    fun save(onSuccess: () -> Unit) {
        viewModelScope.launch {
            val s = _state.value
            val heightCm     = s.heightCm.toIntOrNull()     ?: return@launch
            val goalWeightKg = s.goalWeightKg.toDoubleOrNull() ?: return@launch
            val age          = s.age.toIntOrNull()           ?: return@launch
            val birthYear    = LocalDate.now().year - age
            val stored       = tokenStore.tokenFlow.first()  ?: return@launch

            _state.update { it.copy(isSaving = true, saveError = null) }

            runCatching {
                val dto = UserProfileDto(
                    heightCm      = heightCm,
                    birthYear     = birthYear,
                    sex           = s.sex,
                    goalWeightKg  = goalWeightKg,
                    activityLevel = s.activityLevel,
                    targetDeficit = s.targetDeficit,
                    phase         = s.phase,
                    vacationMode  = s.vacationMode,
                    wakeTime      = s.wakeTime,
                    bedtime       = s.bedtime,
                )
                apiClient.putProfile(stored.token, dto)
                db.userProfileDao().upsert(
                    UserProfileEntity(
                        userId        = s.userId,
                        heightCm      = heightCm,
                        birthYear     = birthYear,
                        sex           = s.sex,
                        goalWeightKg  = goalWeightKg,
                        activityLevel = s.activityLevel,
                        targetDeficit = s.targetDeficit,
                        phase         = s.phase,
                        vacationMode  = s.vacationMode,
                        wakeTime      = s.wakeTime,
                        bedtime       = s.bedtime,
                        syncStatus    = SyncStatus.SYNCED,
                    )
                )
            }.onSuccess {
                _state.update { it.copy(
                    isSaving           = false,
                    savedSex           = it.sex,
                    savedHeightCm      = it.heightCm,
                    savedAge           = it.age,
                    savedGoalWeightKg  = it.goalWeightKg,
                    savedActivityLevel = it.activityLevel,
                    savedTargetDeficit = it.targetDeficit,
                    savedWakeTime      = it.wakeTime,
                    savedBedtime       = it.bedtime,
                )}
                onSuccess()
            }.onFailure {
                _state.update { it.copy(
                    isSaving   = false,
                    saveError  = "Couldn't reach the server — check your connection and try again.",
                )}
            }
        }
    }
}
```

- [ ] **Step 4: Run tests**

```bash
./gradlew :app:test --tests "org.branneman.health.ui.ProfileSettingsViewModelTest" 2>&1 | tail -20
```

Expected: all 7 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/ui/ProfileSettingsViewModel.kt \
        app/src/test/kotlin/org/branneman/health/ui/ProfileSettingsViewModelTest.kt
git commit -m "feat(app): add ProfileSettingsViewModel with load/save and dirty detection"
```

---

## Task 3: ProfileSettingsScreen

**Files:**
- Create: `app/src/main/kotlin/org/branneman/health/ui/ProfileSettingsScreen.kt`
- Create: `app/src/test/kotlin/org/branneman/health/ui/ProfileSettingsScreenTest.kt`

- [ ] **Step 1: Write failing composable tests**

Create `ProfileSettingsScreenTest.kt`:

```kotlin
package org.branneman.health.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProfileSettingsScreenTest {

    @get:Rule val compose = createComposeRule()

    private val loadedState = ProfileSettingsUiState(
        isLoading         = false,
        sex               = "male",
        heightCm          = "182",
        age               = "34",
        goalWeightKg      = "78.0",
        savedSex          = "male",
        savedHeightCm     = "182",
        savedAge          = "34",
        savedGoalWeightKg = "78.0",
    )

    @Test fun `shows loading indicator while isLoading`() {
        compose.setContent {
            MaterialTheme {
                ProfileSettingsContent(
                    state          = ProfileSettingsUiState(isLoading = true),
                    latestWeightKg = null,
                    onUpdate       = {},
                    onSave         = {},
                    onBack         = {},
                )
            }
        }
        compose.onNodeWithText("Loading…").assertExists()
    }

    @Test fun `pre-populates fields from state`() {
        compose.setContent {
            MaterialTheme {
                ProfileSettingsContent(
                    state          = loadedState,
                    latestWeightKg = null,
                    onUpdate       = {},
                    onSave         = {},
                    onBack         = {},
                )
            }
        }
        compose.onNodeWithText("182").assertExists()
        compose.onNodeWithText("34").assertExists()
        compose.onNodeWithText("78.0").assertExists()
    }

    @Test fun `Save button disabled when profileDirty is false`() {
        compose.setContent {
            MaterialTheme {
                ProfileSettingsContent(
                    state          = loadedState,
                    latestWeightKg = null,
                    onUpdate       = {},
                    onSave         = {},
                    onBack         = {},
                )
            }
        }
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test fun `Save button enabled and unsaved note shown when profileDirty`() {
        val dirtyState = loadedState.copy(age = "35")
        compose.setContent {
            MaterialTheme {
                ProfileSettingsContent(
                    state          = dirtyState,
                    latestWeightKg = null,
                    onUpdate       = {},
                    onSave         = {},
                    onBack         = {},
                )
            }
        }
        compose.onNodeWithText("Save").assertIsEnabled()
        compose.onNodeWithText("Unsaved changes", substring = true).assertExists()
    }

    @Test fun `Save button calls onSave`() {
        val dirtyState = loadedState.copy(age = "35")
        var saved = false
        compose.setContent {
            MaterialTheme {
                ProfileSettingsContent(
                    state          = dirtyState,
                    latestWeightKg = null,
                    onUpdate       = {},
                    onSave         = { saved = true },
                    onBack         = {},
                )
            }
        }
        compose.onNodeWithText("Save").performClick()
        assertTrue(saved)
    }

    @Test fun `goal weight warning shown when goal exceeds latest weight`() {
        compose.setContent {
            MaterialTheme {
                ProfileSettingsContent(
                    state          = loadedState.copy(goalWeightKg = "90.0"),
                    latestWeightKg = 82.0,
                    onUpdate       = {},
                    onSave         = {},
                    onBack         = {},
                )
            }
        }
        compose.onNodeWithText("above your current weight", substring = true).assertExists()
    }

    @Test fun `back button calls onBack when no dirty state`() {
        var backCalled = false
        compose.setContent {
            MaterialTheme {
                ProfileSettingsContent(
                    state          = loadedState,
                    latestWeightKg = null,
                    onUpdate       = {},
                    onSave         = {},
                    onBack         = { backCalled = true },
                )
            }
        }
        compose.onNodeWithText("← Back").performClick()
        assertTrue(backCalled)
    }

    @Test fun `back button shows discard dialog when profileDirty`() {
        compose.setContent {
            MaterialTheme {
                ProfileSettingsContent(
                    state          = loadedState.copy(age = "35"),
                    latestWeightKg = null,
                    onUpdate       = {},
                    onSave         = {},
                    onBack         = {},
                )
            }
        }
        compose.onNodeWithText("← Back").performClick()
        compose.onNodeWithText("Discard unsaved changes?").assertExists()
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
./gradlew :app:test --tests "org.branneman.health.ui.ProfileSettingsScreenTest" 2>&1 | tail -10
```

Expected: compile error — `ProfileSettingsContent` does not exist.

- [ ] **Step 3: Create ProfileSettingsScreen.kt**

```kotlin
package org.branneman.health.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun ProfileSettingsScreen(
    onBack: () -> Unit,
    viewModel: ProfileSettingsViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val latestWeightKg by viewModel.latestWeightKg.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }
    ProfileSettingsContent(
        state          = state,
        latestWeightKg = latestWeightKg,
        onUpdate       = viewModel::update,
        onSave         = { viewModel.save(onSuccess = onBack) },
        onBack         = onBack,
    )
}

@Composable
fun ProfileSettingsContent(
    state: ProfileSettingsUiState,
    latestWeightKg: Double?,
    onUpdate: (ProfileSettingsUiState.() -> ProfileSettingsUiState) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    var showDiscardDialog by remember { mutableStateOf(false) }

    fun requestBack() {
        if (state.profileDirty) showDiscardDialog = true else onBack()
    }

    BackHandler { requestBack() }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier          = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            TextButton(onClick = ::requestBack) { Text("← Back") }
            Text(
                text     = "Profile",
                style    = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
        }

        if (state.isLoading) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Loading…")
            }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Spacer(Modifier.height(4.dp))

                // Sex
                Text("Sex", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("male" to "Male", "female" to "Female").forEach { (value, label) ->
                        FilterChip(
                            selected = state.sex == value,
                            onClick  = { onUpdate { copy(sex = value) } },
                            label    = { Text(label) },
                        )
                    }
                }

                OutlinedTextField(
                    value           = state.heightCm,
                    onValueChange   = { onUpdate { copy(heightCm = it) } },
                    label           = { Text("Height (cm)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier        = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value           = state.age,
                    onValueChange   = { onUpdate { copy(age = it) } },
                    label           = { Text("Age") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier        = Modifier.fillMaxWidth(),
                )

                val goalAboveCurrent = latestWeightKg != null &&
                    (state.goalWeightKg.toDoubleOrNull() ?: 0.0) > latestWeightKg
                OutlinedTextField(
                    value           = state.goalWeightKg,
                    onValueChange   = { onUpdate { copy(goalWeightKg = it) } },
                    label           = { Text("Goal weight (kg)") },
                    isError         = goalAboveCurrent,
                    supportingText  = if (goalAboveCurrent) {
                        { Text("Goal is above your current weight") }
                    } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier        = Modifier.fillMaxWidth(),
                )

                state.saveError?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (state.profileDirty) {
                Text(
                    text     = "⚠ Unsaved changes",
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Button(
                onClick  = onSave,
                enabled  = state.profileDirty && !state.isSaving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.isSaving) CircularProgressIndicator(modifier = Modifier.size(18.dp))
                else Text("Save")
            }
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title            = { Text("Discard unsaved changes?") },
            text             = { Text("Your changes will be lost.") },
            confirmButton    = {
                TextButton(onClick = { showDiscardDialog = false; onBack() }) { Text("Discard") }
            },
            dismissButton    = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("Keep editing") }
            },
        )
    }
}
```

- [ ] **Step 4: Run tests**

```bash
./gradlew :app:test --tests "org.branneman.health.ui.ProfileSettingsScreenTest" 2>&1 | tail -20
```

Expected: all 8 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/ui/ProfileSettingsScreen.kt \
        app/src/test/kotlin/org/branneman/health/ui/ProfileSettingsScreenTest.kt
git commit -m "feat(app): add Profile settings sub-page"
```

---

## Task 4: GoalSettingsScreen

**Files:**
- Create: `app/src/main/kotlin/org/branneman/health/ui/GoalSettingsScreen.kt`
- Create: `app/src/test/kotlin/org/branneman/health/ui/GoalSettingsScreenTest.kt`

- [ ] **Step 1: Write failing composable tests**

Create `GoalSettingsScreenTest.kt`:

```kotlin
package org.branneman.health.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GoalSettingsScreenTest {

    @get:Rule val compose = createComposeRule()

    private val loadedState = ProfileSettingsUiState(
        isLoading              = false,
        activityLevel          = "lightly_active",
        targetDeficit          = 300,
        savedActivityLevel     = "lightly_active",
        savedTargetDeficit     = 300,
    )

    @Test fun `shows activity level options`() {
        compose.setContent {
            MaterialTheme {
                GoalSettingsContent(
                    state          = loadedState,
                    latestWeightKg = null,
                    onUpdate       = {},
                    onSave         = {},
                    onBack         = {},
                )
            }
        }
        compose.onNodeWithText("Mostly sitting", substring = true).assertExists()
        compose.onNodeWithText("Lightly active", substring = true).assertExists()
        compose.onNodeWithText("Moderately active", substring = true).assertExists()
    }

    @Test fun `Save disabled when goalDirty is false`() {
        compose.setContent {
            MaterialTheme {
                GoalSettingsContent(
                    state          = loadedState,
                    latestWeightKg = null,
                    onUpdate       = {},
                    onSave         = {},
                    onBack         = {},
                )
            }
        }
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test fun `Save enabled and unsaved note shown when goalDirty`() {
        val dirty = loadedState.copy(targetDeficit = 400)
        compose.setContent {
            MaterialTheme {
                GoalSettingsContent(
                    state          = dirty,
                    latestWeightKg = null,
                    onUpdate       = {},
                    onSave         = {},
                    onBack         = {},
                )
            }
        }
        compose.onNodeWithText("Save").assertIsEnabled()
        compose.onNodeWithText("Unsaved changes", substring = true).assertExists()
    }

    @Test fun `Save button calls onSave`() {
        val dirty = loadedState.copy(targetDeficit = 400)
        var saved = false
        compose.setContent {
            MaterialTheme {
                GoalSettingsContent(
                    state          = dirty,
                    latestWeightKg = null,
                    onUpdate       = {},
                    onSave         = { saved = true },
                    onBack         = {},
                )
            }
        }
        compose.onNodeWithText("Save").performClick()
        assertTrue(saved)
    }

    @Test fun `shows kg-per-week estimate when deficit is nonzero`() {
        compose.setContent {
            MaterialTheme {
                GoalSettingsContent(
                    state          = loadedState,
                    latestWeightKg = null,
                    onUpdate       = {},
                    onSave         = {},
                    onBack         = {},
                )
            }
        }
        compose.onNodeWithText("kg/week", substring = true).assertExists()
    }

    @Test fun `warning shown when deficit exceeds 500`() {
        compose.setContent {
            MaterialTheme {
                GoalSettingsContent(
                    state          = loadedState.copy(targetDeficit = 550),
                    latestWeightKg = null,
                    onUpdate       = {},
                    onSave         = {},
                    onBack         = {},
                )
            }
        }
        compose.onNodeWithText("muscle loss", substring = true).assertExists()
    }

    @Test fun `back shows discard dialog when goalDirty`() {
        compose.setContent {
            MaterialTheme {
                GoalSettingsContent(
                    state          = loadedState.copy(targetDeficit = 400),
                    latestWeightKg = null,
                    onUpdate       = {},
                    onSave         = {},
                    onBack         = {},
                )
            }
        }
        compose.onNodeWithText("← Back").performClick()
        compose.onNodeWithText("Discard unsaved changes?").assertExists()
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
./gradlew :app:test --tests "org.branneman.health.ui.GoalSettingsScreenTest" 2>&1 | tail -10
```

Expected: compile error — `GoalSettingsContent` does not exist.

- [ ] **Step 3: Create GoalSettingsScreen.kt**

`kgPerWeek` and `monthsToGoal` are computed locally from `state.targetDeficit`.

```kotlin
package org.branneman.health.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt

private data class ActivityOption(val value: String, val label: String, val subtitle: String)

private val activityOptions = listOf(
    ActivityOption("sedentary",         "Mostly sitting",    "Desk job, ≤1 sport/week"),
    ActivityOption("lightly_active",    "Lightly active",    "2–4 sport sessions/week"),
    ActivityOption("moderately_active", "Moderately active", "5+ sessions/week"),
)

@Composable
fun GoalSettingsScreen(
    onBack: () -> Unit,
    viewModel: ProfileSettingsViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val latestWeightKg by viewModel.latestWeightKg.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }
    GoalSettingsContent(
        state          = state,
        latestWeightKg = latestWeightKg,
        onUpdate       = viewModel::update,
        onSave         = { viewModel.save(onSuccess = onBack) },
        onBack         = onBack,
    )
}

@Composable
fun GoalSettingsContent(
    state: ProfileSettingsUiState,
    latestWeightKg: Double?,
    onUpdate: (ProfileSettingsUiState.() -> ProfileSettingsUiState) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    var showDiscardDialog by remember { mutableStateOf(false) }

    fun requestBack() {
        if (state.goalDirty) showDiscardDialog = true else onBack()
    }

    BackHandler { requestBack() }

    val kgPerWeek = if (state.targetDeficit == 0) null else state.targetDeficit / 7700.0
    val monthsToGoal = latestWeightKg?.let { currentKg ->
        val goalKg = state.goalWeightKg.toDoubleOrNull() ?: return@let null
        if (state.targetDeficit == 0 || currentKg <= goalKg) null
        else (currentKg - goalKg) * 7700.0 / state.targetDeficit / 30.0
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier          = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            TextButton(onClick = ::requestBack) { Text("← Back") }
            Text(
                text     = "Goal",
                style    = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
        }

        if (state.isLoading) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Loading…")
            }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Spacer(Modifier.height(4.dp))

                Text("Activity level", style = MaterialTheme.typography.labelLarge)
                activityOptions.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = state.activityLevel == option.value,
                                onClick  = { onUpdate { copy(activityLevel = option.value) } },
                            )
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = state.activityLevel == option.value,
                            onClick  = { onUpdate { copy(activityLevel = option.value) } },
                        )
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(option.label, style = MaterialTheme.typography.bodyLarge)
                            Text(option.subtitle, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text("Target deficit", style = MaterialTheme.typography.labelLarge)
                Text("${state.targetDeficit} kcal/day", style = MaterialTheme.typography.titleMedium)
                Slider(
                    value         = state.targetDeficit.toFloat(),
                    onValueChange = { onUpdate { copy(targetDeficit = (it / 25).roundToInt() * 25) } },
                    valueRange    = 0f..600f,
                    steps         = 23,
                    modifier      = Modifier.fillMaxWidth(),
                )
                Text("Recommended range: 250–400 kcal/day", style = MaterialTheme.typography.bodySmall)

                kgPerWeek?.let { Text("≈ ${"%.2f".format(it)} kg/week", style = MaterialTheme.typography.bodyMedium) }
                monthsToGoal?.let { Text("Goal reached in ~${"%.0f".format(it)} months", style = MaterialTheme.typography.bodyMedium) }

                if (state.targetDeficit > 500) {
                    Text(
                        "⚠ Above 500 kcal/day risks muscle loss.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                state.saveError?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (state.goalDirty) {
                Text(
                    text     = "⚠ Unsaved changes",
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Button(
                onClick  = onSave,
                enabled  = state.goalDirty && !state.isSaving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.isSaving) CircularProgressIndicator(modifier = Modifier.size(18.dp))
                else Text("Save")
            }
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title            = { Text("Discard unsaved changes?") },
            text             = { Text("Your changes will be lost.") },
            confirmButton    = {
                TextButton(onClick = { showDiscardDialog = false; onBack() }) { Text("Discard") }
            },
            dismissButton    = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("Keep editing") }
            },
        )
    }
}
```

- [ ] **Step 4: Run tests**

```bash
./gradlew :app:test --tests "org.branneman.health.ui.GoalSettingsScreenTest" 2>&1 | tail -20
```

Expected: all 7 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/ui/GoalSettingsScreen.kt \
        app/src/test/kotlin/org/branneman/health/ui/GoalSettingsScreenTest.kt
git commit -m "feat(app): add Goal settings sub-page"
```

---

## Task 5: ScheduleSettingsScreen

**Files:**
- Create: `app/src/main/kotlin/org/branneman/health/ui/ScheduleSettingsScreen.kt`
- Create: `app/src/test/kotlin/org/branneman/health/ui/ScheduleSettingsScreenTest.kt`

- [ ] **Step 1: Write failing composable tests**

Create `ScheduleSettingsScreenTest.kt`:

```kotlin
package org.branneman.health.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ScheduleSettingsScreenTest {

    @get:Rule val compose = createComposeRule()

    private val loadedState = ProfileSettingsUiState(
        isLoading      = false,
        wakeTime       = "07:00",
        bedtime        = "23:00",
        savedWakeTime  = "07:00",
        savedBedtime   = "23:00",
    )

    @Test fun `shows wake time and bedtime fields`() {
        compose.setContent {
            MaterialTheme {
                ScheduleSettingsContent(
                    state    = loadedState,
                    onUpdate = {},
                    onSave   = {},
                    onBack   = {},
                )
            }
        }
        compose.onNodeWithText("Wake time", substring = true).assertExists()
        compose.onNodeWithText("Bedtime", substring = true).assertExists()
        compose.onNodeWithText("07:00").assertExists()
        compose.onNodeWithText("23:00").assertExists()
    }

    @Test fun `Save disabled when scheduleDirty is false`() {
        compose.setContent {
            MaterialTheme {
                ScheduleSettingsContent(
                    state    = loadedState,
                    onUpdate = {},
                    onSave   = {},
                    onBack   = {},
                )
            }
        }
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test fun `Save enabled and unsaved note shown when scheduleDirty`() {
        val dirty = loadedState.copy(wakeTime = "06:30")
        compose.setContent {
            MaterialTheme {
                ScheduleSettingsContent(
                    state    = dirty,
                    onUpdate = {},
                    onSave   = {},
                    onBack   = {},
                )
            }
        }
        compose.onNodeWithText("Save").assertIsEnabled()
        compose.onNodeWithText("Unsaved changes", substring = true).assertExists()
    }

    @Test fun `Save calls onSave`() {
        val dirty = loadedState.copy(wakeTime = "06:30")
        var saved = false
        compose.setContent {
            MaterialTheme {
                ScheduleSettingsContent(
                    state    = dirty,
                    onUpdate = {},
                    onSave   = { saved = true },
                    onBack   = {},
                )
            }
        }
        compose.onNodeWithText("Save").performClick()
        assertTrue(saved)
    }

    @Test fun `back shows discard dialog when scheduleDirty`() {
        compose.setContent {
            MaterialTheme {
                ScheduleSettingsContent(
                    state    = loadedState.copy(wakeTime = "06:30"),
                    onUpdate = {},
                    onSave   = {},
                    onBack   = {},
                )
            }
        }
        compose.onNodeWithText("← Back").performClick()
        compose.onNodeWithText("Discard unsaved changes?").assertExists()
    }

    @Test fun `back calls onBack immediately when clean`() {
        var backCalled = false
        compose.setContent {
            MaterialTheme {
                ScheduleSettingsContent(
                    state    = loadedState,
                    onUpdate = {},
                    onSave   = {},
                    onBack   = { backCalled = true },
                )
            }
        }
        compose.onNodeWithText("← Back").performClick()
        assertTrue(backCalled)
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
./gradlew :app:test --tests "org.branneman.health.ui.ScheduleSettingsScreenTest" 2>&1 | tail -10
```

Expected: compile error — `ScheduleSettingsContent` does not exist.

- [ ] **Step 3: Create ScheduleSettingsScreen.kt**

`adjustTime` is defined as `internal` in `OnboardingScreen.kt` in the same package `org.branneman.health.ui`, so it's accessible directly with no import.

```kotlin
package org.branneman.health.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun ScheduleSettingsScreen(
    onBack: () -> Unit,
    viewModel: ProfileSettingsViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }
    ScheduleSettingsContent(
        state    = state,
        onUpdate = viewModel::update,
        onSave   = { viewModel.save(onSuccess = onBack) },
        onBack   = onBack,
    )
}

@Composable
fun ScheduleSettingsContent(
    state: ProfileSettingsUiState,
    onUpdate: (ProfileSettingsUiState.() -> ProfileSettingsUiState) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    var showDiscardDialog by remember { mutableStateOf(false) }

    fun requestBack() {
        if (state.scheduleDirty) showDiscardDialog = true else onBack()
    }

    BackHandler { requestBack() }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier          = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            TextButton(onClick = ::requestBack) { Text("← Back") }
            Text(
                text     = "Schedule",
                style    = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
        }

        if (state.isLoading) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Loading…")
            }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Used to calculate how much eating time is left in your day.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))

                ScheduleTimeRow(
                    label   = "Wake time",
                    time    = state.wakeTime,
                    onMinus = { onUpdate { copy(wakeTime = adjustTime(wakeTime, -30)) } },
                    onPlus  = { onUpdate { copy(wakeTime = adjustTime(wakeTime, +30)) } },
                )
                ScheduleTimeRow(
                    label   = "Bedtime",
                    time    = state.bedtime,
                    onMinus = { onUpdate { copy(bedtime = adjustTime(bedtime, -30)) } },
                    onPlus  = { onUpdate { copy(bedtime = adjustTime(bedtime, +30)) } },
                )

                state.saveError?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (state.scheduleDirty) {
                Text(
                    text     = "⚠ Unsaved changes",
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Button(
                onClick  = onSave,
                enabled  = state.scheduleDirty && !state.isSaving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.isSaving) CircularProgressIndicator(modifier = Modifier.size(18.dp))
                else Text("Save")
            }
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title            = { Text("Discard unsaved changes?") },
            text             = { Text("Your changes will be lost.") },
            confirmButton    = {
                TextButton(onClick = { showDiscardDialog = false; onBack() }) { Text("Discard") }
            },
            dismissButton    = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("Keep editing") }
            },
        )
    }
}

@Composable
private fun ScheduleTimeRow(label: String, time: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier              = Modifier.fillMaxWidth(),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onMinus, modifier = Modifier.size(40.dp), contentPadding = PaddingValues(0.dp)) {
            Text("−")
        }
        Text(time, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
        OutlinedButton(onClick = onPlus, modifier = Modifier.size(40.dp), contentPadding = PaddingValues(0.dp)) {
            Text("+")
        }
    }
}
```

- [ ] **Step 4: Run tests**

```bash
./gradlew :app:test --tests "org.branneman.health.ui.ScheduleSettingsScreenTest" 2>&1 | tail -20
```

Expected: all 6 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/ui/ScheduleSettingsScreen.kt \
        app/src/test/kotlin/org/branneman/health/ui/ScheduleSettingsScreenTest.kt
git commit -m "feat(app): add Schedule settings sub-page"
```

---

## Task 6: Wire Profile, Goal, Schedule into App.kt navigation

**Files:**
- Modify: `app/src/main/kotlin/org/branneman/health/App.kt`

- [ ] **Step 1: Replace the placeholder branches in App.kt**

Find the `when (settingsPage)` block in `MainNav`. Replace the three placeholder lines:
```kotlin
SettingsPage.Profile  -> { /* Task 6 */ }
SettingsPage.Goal     -> { /* Task 6 */ }
SettingsPage.Schedule -> { /* Task 6 */ }
```

With:
```kotlin
SettingsPage.Profile -> ProfileSettingsScreen(
    onBack = { settingsPage = SettingsPage.Main }
)
SettingsPage.Goal -> GoalSettingsScreen(
    onBack = { settingsPage = SettingsPage.Main }
)
SettingsPage.Schedule -> ScheduleSettingsScreen(
    onBack = { settingsPage = SettingsPage.Main }
)
```

- [ ] **Step 2: Run the full test suite**

```bash
./gradlew :app:test 2>&1 | tail -30
```

Expected: all tests pass, no regressions.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/App.kt
git commit -m "feat(app): wire Profile, Goal, Schedule sub-pages into Settings navigation"
```

---

## Final check

After all tasks are committed, verify the complete test suite is green:

```bash
./gradlew :app:test 2>&1 | grep -E "tests|failures|errors|BUILD"
```

Expected output: `BUILD SUCCESSFUL` with 0 failures and 0 errors.
