# Drink Shortcuts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add one-tap drink shortcut buttons to the log screen — configurable via Settings, synced offline-first via `ShortcutSyncService`.

**Architecture:** Mirror 9 (One-tap meal buttons)'s pattern exactly. `ShortcutEntity`/`ShortcutDto`/server endpoints already exist; this story wires them into the UI and adds the push-sync path that was missing. No schema changes, no Room migration.

**Tech Stack:** Kotlin, Jetpack Compose, Room (in-memory for tests), Ktor `MockEngine`, Robolectric.

---

## File Map

**Create:**
- `app/src/main/kotlin/org/branneman/health/sync/ShortcutSyncService.kt`
- `app/src/main/kotlin/org/branneman/health/ui/DrinkButtonsViewModel.kt`
- `app/src/main/kotlin/org/branneman/health/ui/DrinkButtonsScreen.kt`
- `app/src/test/kotlin/org/branneman/health/sync/ShortcutSyncServiceTest.kt`
- `app/src/test/kotlin/org/branneman/health/ui/DrinkButtonsViewModelTest.kt`
- `app/src/test/kotlin/org/branneman/health/ui/DrinkButtonsScreenTest.kt`

**Modify:**
- `app/src/test/kotlin/org/branneman/health/TestFactories.kt` — add `syncStatus` param to `aShortcut()`
- `app/src/main/kotlin/org/branneman/health/db/dao/ShortcutDao.kt` — add `getByStatus()`, `updateSyncStatus()`
- `app/src/test/kotlin/org/branneman/health/db/dao/ShortcutDaoTest.kt` — extend with 2 new tests
- `app/src/main/kotlin/org/branneman/health/sync/LoginSyncService.kt` — replace 7-line inline shortcut pull with `ShortcutSyncService(api, db).pull(token, userId)`
- `app/src/main/kotlin/org/branneman/health/sync/SyncWorker.kt` — add `ShortcutSyncService.pushPending()` after `MealTemplateSyncService` call
- `app/src/test/kotlin/org/branneman/health/sync/SyncWorkerTest.kt` — extend with shortcut push test
- `app/src/main/kotlin/org/branneman/health/log/LogViewModel.kt` — add `shortcuts` StateFlow and `logFromShortcut()`
- `app/src/main/kotlin/org/branneman/health/ui/LogScreen.kt` — add drink shortcuts row to `LogContent`; add `onSetUpDrinkButtons`/`onLogShortcut` to `LogScreen`
- `app/src/test/kotlin/org/branneman/health/ui/LogScreenTest.kt` — extend with 4 shortcut row tests
- `app/src/main/kotlin/org/branneman/health/ui/SettingsScreen.kt` — add `onNavigateDrinkButtons` param and "Drink buttons →" row
- `app/src/test/kotlin/org/branneman/health/ui/SettingsScreenTest.kt` — extend with drink buttons row test
- `app/src/main/kotlin/org/branneman/health/App.kt` — extend `SettingsPage` enum; add `DrinkButtons` branch; wire new LogScreen callbacks

---

### Task 1: ShortcutDao — add getByStatus + updateSyncStatus

**Files:**
- Modify: `app/src/test/kotlin/org/branneman/health/TestFactories.kt`
- Modify: `app/src/main/kotlin/org/branneman/health/db/dao/ShortcutDao.kt`
- Modify: `app/src/test/kotlin/org/branneman/health/db/dao/ShortcutDaoTest.kt`

- [ ] **Step 1: Add `syncStatus` param to `aShortcut()` in TestFactories.kt**

The current `aShortcut()` hardcodes `SYNCED`. Upcoming sync tests need `PENDING_CREATE`. Replace the function:

```kotlin
fun aShortcut(
    id: String = uuid(),
    userId: String = uuid(),
    emoji: String = "🍎",
    label: String = "Apple",
    kcal: Int = 52,
    sortOrder: Int = 0,
    syncStatus: SyncStatus = SyncStatus.SYNCED,
) = ShortcutEntity(
    id = id, userId = userId, emoji = emoji, label = label,
    kcal = kcal, sortOrder = sortOrder, syncStatus = syncStatus,
)
```

- [ ] **Step 2: Write the two failing tests in ShortcutDaoTest.kt**

Add these after the existing `deleteAllForUser` test. Both reference `getByStatus` and `updateSyncStatus` which don't exist yet:

```kotlin
@Test
fun `getByStatus returns only shortcuts with matching status`() = runTest {
    val userId = uuid()
    dao.upsertAll(listOf(
        aShortcut(userId = userId, label = "Pending", sortOrder = 0, syncStatus = SyncStatus.PENDING_CREATE),
        aShortcut(userId = userId, label = "Synced",  sortOrder = 1, syncStatus = SyncStatus.SYNCED),
    ))
    val result = dao.getByStatus(SyncStatus.PENDING_CREATE)
    assertEquals(1, result.size)
    assertEquals("Pending", result[0].label)
}

@Test
fun `updateSyncStatus changes status for specified row without affecting others`() = runTest {
    val userId = uuid()
    val a = aShortcut(userId = userId, label = "A", sortOrder = 0, syncStatus = SyncStatus.PENDING_CREATE)
    val b = aShortcut(userId = userId, label = "B", sortOrder = 1, syncStatus = SyncStatus.PENDING_CREATE)
    dao.upsertAll(listOf(a, b))
    dao.updateSyncStatus(a.id, SyncStatus.SYNCED)
    assertEquals(1, dao.getByStatus(SyncStatus.SYNCED).size)
    assertEquals(1, dao.getByStatus(SyncStatus.PENDING_CREATE).size)
    assertEquals("B", dao.getByStatus(SyncStatus.PENDING_CREATE).single().label)
}
```

You'll also need to add `import org.branneman.health.db.SyncStatus` to `ShortcutDaoTest.kt`.

- [ ] **Step 3: Run tests to confirm they fail**

```
./gradlew :app:testDebugUnitTest --tests "org.branneman.health.db.dao.ShortcutDaoTest" 2>&1 | tail -20
```

Expected: the two new tests FAIL with "Unresolved reference: getByStatus".

- [ ] **Step 4: Add the two methods to ShortcutDao.kt**

Full file after changes:

```kotlin
package org.branneman.health.db.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.branneman.health.db.SyncStatus
import org.branneman.health.db.entities.ShortcutEntity

@Dao
interface ShortcutDao {
    @Query("SELECT * FROM shortcut ORDER BY sortOrder ASC")
    fun observeAll(): Flow<List<ShortcutEntity>>

    @Upsert
    suspend fun upsertAll(entities: List<ShortcutEntity>)

    @Query("DELETE FROM shortcut WHERE userId = :userId")
    suspend fun deleteAllForUser(userId: String)

    @Query("SELECT * FROM shortcut WHERE syncStatus = :status")
    suspend fun getByStatus(status: SyncStatus): List<ShortcutEntity>

    @Query("UPDATE shortcut SET syncStatus = :status WHERE id = :id")
    suspend fun updateSyncStatus(id: String, status: SyncStatus)
}
```

- [ ] **Step 5: Run tests to confirm they pass**

```
./gradlew :app:testDebugUnitTest --tests "org.branneman.health.db.dao.ShortcutDaoTest" 2>&1 | tail -20
```

Expected: all 4 tests PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/db/dao/ShortcutDao.kt \
        app/src/test/kotlin/org/branneman/health/db/dao/ShortcutDaoTest.kt \
        app/src/test/kotlin/org/branneman/health/TestFactories.kt
git commit -m "feat(app): add getByStatus + updateSyncStatus to ShortcutDao"
```

---

### Task 2: ShortcutSyncService + LoginSyncService refactor

**Files:**
- Create: `app/src/test/kotlin/org/branneman/health/sync/ShortcutSyncServiceTest.kt`
- Create: `app/src/main/kotlin/org/branneman/health/sync/ShortcutSyncService.kt`
- Modify: `app/src/main/kotlin/org/branneman/health/sync/LoginSyncService.kt`

- [ ] **Step 1: Create ShortcutSyncServiceTest.kt**

```kotlin
package org.branneman.health.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.branneman.health.aShortcut
import org.branneman.health.db.HealthDatabase
import org.branneman.health.db.SyncStatus
import org.branneman.health.network.HealthApiClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ShortcutSyncServiceTest {

    private lateinit var db: HealthDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), HealthDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() { db.close() }

    private fun mockApi(handler: MockRequestHandler) = HealthApiClient(
        "http://test",
        HttpClient(MockEngine(handler)) { install(ContentNegotiation) { json() } }
    )

    @Test
    fun `pushPending sends PENDING_CREATE shortcuts and marks them SYNCED`() = runTest {
        val userId = "u1"
        val s = aShortcut(userId = userId, emoji = "🍺", label = "Pils", kcal = 150,
            sortOrder = 0, syncStatus = SyncStatus.PENDING_CREATE)
        db.shortcutDao().upsertAll(listOf(s))

        var body = ""
        val api = mockApi { req ->
            body = req.body.toByteArray().toString(Charsets.UTF_8)
            respond("", HttpStatusCode.OK, headersOf())
        }

        ShortcutSyncService(api, db).pushPending("token", userId)

        assertEquals(1, db.shortcutDao().getByStatus(SyncStatus.SYNCED).size)
        assertTrue(body.contains("Pils"))
    }

    @Test
    fun `pushPending does nothing when no pending shortcuts`() = runTest {
        val userId = "u1"
        db.shortcutDao().upsertAll(listOf(
            aShortcut(userId = userId, syncStatus = SyncStatus.SYNCED)
        ))

        var called = false
        val api = mockApi { _ -> called = true; respond("", HttpStatusCode.OK, headersOf()) }

        ShortcutSyncService(api, db).pushPending("token", userId)

        assertTrue(!called)
    }

    @Test
    fun `pull replaces Room shortcuts with server response marked SYNCED`() = runTest {
        val userId = "u1"
        db.shortcutDao().upsertAll(listOf(aShortcut(userId = userId, label = "Old")))

        val api = mockApi { _ ->
            respond(
                """[{"id":"sc-new","emoji":"🍷","label":"Wine","kcal":120,"sortOrder":0}]""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        ShortcutSyncService(api, db).pull("token", userId)

        val all = db.shortcutDao().observeAll().first()
        assertEquals(1, all.size)
        assertEquals("Wine", all[0].label)
        assertEquals(SyncStatus.SYNCED, all[0].syncStatus)
    }

    @Test
    fun `pushPending leaves status PENDING_CREATE on network error`() = runTest {
        val userId = "u1"
        db.shortcutDao().upsertAll(listOf(
            aShortcut(userId = userId, syncStatus = SyncStatus.PENDING_CREATE)
        ))

        val api = HealthApiClient("http://test",
            HttpClient(MockEngine { error("network error") }) { install(ContentNegotiation) { json() } })

        runCatching { ShortcutSyncService(api, db).pushPending("token", userId) }

        assertEquals(1, db.shortcutDao().getByStatus(SyncStatus.PENDING_CREATE).size)
    }
}
```

- [ ] **Step 2: Run to confirm all 4 tests fail**

```
./gradlew :app:testDebugUnitTest --tests "org.branneman.health.sync.ShortcutSyncServiceTest" 2>&1 | tail -20
```

Expected: FAIL with "Unresolved reference: ShortcutSyncService".

- [ ] **Step 3: Create ShortcutSyncService.kt**

```kotlin
package org.branneman.health.sync

import org.branneman.health.ShortcutDto
import org.branneman.health.db.HealthDatabase
import org.branneman.health.db.SyncStatus
import org.branneman.health.db.entities.ShortcutEntity
import org.branneman.health.network.HealthApiClient

class ShortcutSyncService(
    private val api: HealthApiClient,
    private val db: HealthDatabase,
) {
    suspend fun pushPending(token: String, userId: String) {
        val pending = db.shortcutDao().getByStatus(SyncStatus.PENDING_CREATE)
        if (pending.isEmpty()) return
        val allActive = pending + db.shortcutDao().getByStatus(SyncStatus.SYNCED)
        runCatching {
            api.putShortcuts(token, allActive.map { it.toDto() })
        }.onSuccess {
            allActive.forEach { db.shortcutDao().updateSyncStatus(it.id, SyncStatus.SYNCED) }
        }
    }

    suspend fun pull(token: String, userId: String) {
        val shortcuts = api.getShortcuts(token)
        db.shortcutDao().deleteAllForUser(userId)
        db.shortcutDao().upsertAll(shortcuts.map { dto ->
            ShortcutEntity(
                id         = dto.id,
                userId     = userId,
                emoji      = dto.emoji,
                label      = dto.label,
                kcal       = dto.kcal,
                sortOrder  = dto.sortOrder,
                syncStatus = SyncStatus.SYNCED,
            )
        })
    }

    private fun ShortcutEntity.toDto() = ShortcutDto(
        id        = id,
        emoji     = emoji,
        label     = label,
        kcal      = kcal,
        sortOrder = sortOrder,
    )
}
```

- [ ] **Step 4: Run tests to confirm all 4 pass**

```
./gradlew :app:testDebugUnitTest --tests "org.branneman.health.sync.ShortcutSyncServiceTest" 2>&1 | tail -20
```

Expected: all 4 PASS.

- [ ] **Step 5: Replace inline shortcut pull in LoginSyncService.kt**

Find and replace this block in `LoginSyncService.sync()` (lines 19–27):

```kotlin
db.shortcutDao().deleteAllForUser(userId)
val shortcuts = api.getShortcuts(token)
db.shortcutDao().upsertAll(shortcuts.map { dto ->
    ShortcutEntity(
        id = dto.id, userId = userId, emoji = dto.emoji,
        label = dto.label, kcal = dto.kcal, sortOrder = dto.sortOrder,
        syncStatus = SyncStatus.SYNCED,
    )
})
```

Replace with:

```kotlin
ShortcutSyncService(api, db).pull(token, userId)
```

Remove the now-unused `ShortcutEntity` from the wildcard import if it becomes unused (the `import org.branneman.health.db.entities.*` covers it, so no change needed).

- [ ] **Step 6: Confirm LoginSyncServiceTest still passes**

```
./gradlew :app:testDebugUnitTest --tests "org.branneman.health.sync.LoginSyncServiceTest" 2>&1 | tail -20
```

Expected: all existing tests PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/sync/ShortcutSyncService.kt \
        app/src/main/kotlin/org/branneman/health/sync/LoginSyncService.kt \
        app/src/test/kotlin/org/branneman/health/sync/ShortcutSyncServiceTest.kt
git commit -m "feat(app): add ShortcutSyncService; refactor LoginSyncService to delegate shortcut pull"
```

---

### Task 3: SyncWorker — wire ShortcutSyncService.pushPending

**Files:**
- Modify: `app/src/test/kotlin/org/branneman/health/sync/SyncWorkerTest.kt`
- Modify: `app/src/main/kotlin/org/branneman/health/sync/SyncWorker.kt`

- [ ] **Step 1: Add imports and a new test to SyncWorkerTest.kt**

Add to imports:

```kotlin
import org.branneman.health.aShortcut
import org.branneman.health.db.SyncStatus
import org.branneman.health.sync.ShortcutSyncService
```

Add this test after the existing three:

```kotlin
@Test
fun `shortcut pushPending wires correctly in sync pass`() = runTest {
    val api = fakeApiClient()
    db.shortcutDao().upsertAll(listOf(
        aShortcut(userId = userId, label = "Pils", sortOrder = 0,
            syncStatus = SyncStatus.PENDING_CREATE)
    ))
    ShortcutSyncService(api, db).pushPending(token, userId)
    assertEquals(SyncStatus.SYNCED,
        db.shortcutDao().getByStatus(SyncStatus.SYNCED).single().syncStatus)
}
```

Note: `fakeApiClient()` already returns 200 for any unrecognised path (the `else` branch), so no change to the fake is needed.

- [ ] **Step 2: Run to confirm the new test passes**

```
./gradlew :app:testDebugUnitTest --tests "org.branneman.health.sync.SyncWorkerTest" 2>&1 | tail -20
```

Expected: all 4 tests PASS (the new one exercises `ShortcutSyncService` directly, confirming it works via the shared fake client).

- [ ] **Step 3: Wire ShortcutSyncService.pushPending into SyncWorker.doWork()**

In `SyncWorker.kt`, add one line directly after the existing `MealTemplateSyncService` call and add the import:

```kotlin
import org.branneman.health.sync.ShortcutSyncService
```

```kotlin
MealTemplateSyncService(apiClient, db).pushPending(stored.token, stored.userId)
ShortcutSyncService(apiClient, db).pushPending(stored.token, stored.userId)
runCatching { apiClient.triggerPolarSync(stored.token) }
```

- [ ] **Step 4: Run the full sync suite**

```
./gradlew :app:testDebugUnitTest --tests "org.branneman.health.sync.*" 2>&1 | tail -20
```

Expected: all sync tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/sync/SyncWorker.kt \
        app/src/test/kotlin/org/branneman/health/sync/SyncWorkerTest.kt
git commit -m "feat(app): wire ShortcutSyncService.pushPending into SyncWorker"
```

---

### Task 4: LogViewModel — shortcuts flow + logFromShortcut

**Files:**
- Modify: `app/src/main/kotlin/org/branneman/health/log/LogViewModel.kt`

- [ ] **Step 1: Add shortcuts StateFlow and logFromShortcut**

Add this import at the top of `LogViewModel.kt`:

```kotlin
import org.branneman.health.db.entities.ShortcutEntity
```

Add `shortcuts` after the `pinnedTemplates` declaration (around line 34):

```kotlin
val shortcuts: StateFlow<List<ShortcutEntity>> = db.shortcutDao().observeAll()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
```

Add `logFromShortcut` after `logFromTemplate` (around line 73):

```kotlin
fun logFromShortcut(shortcut: ShortcutEntity) {
    val kcal = shortcut.kcal.takeIf { it > 0 } ?: return
    viewModelScope.launch {
        val userId = tokenStore.tokenFlow.first()?.userId ?: return@launch
        val entity = LogEntryEntity(
            userId        = userId,
            loggedAt      = OffsetDateTime.now().toString(),
            mealType      = "unknown",
            quickAddKcal  = kcal,
            quickAddLabel = "${shortcut.emoji} ${shortcut.label}",
        )
        db.logEntryDao().upsert(entity)
        _undoPending.value = entity to SyncStatus.PENDING_CREATE
    }
}
```

- [ ] **Step 2: Confirm the module compiles**

```
./gradlew :app:compileDebugKotlin 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/log/LogViewModel.kt
git commit -m "feat(app): add shortcuts flow and logFromShortcut to LogViewModel"
```

---

### Task 5: LogScreen — drink shortcuts row

**Files:**
- Modify: `app/src/test/kotlin/org/branneman/health/ui/LogScreenTest.kt`
- Modify: `app/src/main/kotlin/org/branneman/health/ui/LogScreen.kt`

- [ ] **Step 1: Write 4 failing tests in LogScreenTest.kt**

Add these imports at the top:

```kotlin
import org.branneman.health.aShortcut
import org.branneman.health.db.entities.ShortcutEntity
```

Add this helper and four tests after the existing template tests:

```kotlin
private fun renderWithShortcuts(
    entries: List<LogEntryEntity> = emptyList(),
    shortcuts: List<ShortcutEntity> = emptyList(),
    onSetUpDrinkButtons: () -> Unit = {},
    onLogShortcut: (ShortcutEntity) -> Unit = {},
) {
    compose.setContent {
        MaterialTheme {
            LogContent(
                entries             = entries,
                shortcuts           = shortcuts,
                onAdd               = { _, _ -> },
                onDelete            = {},
                onSetUpDrinkButtons = onSetUpDrinkButtons,
                onLogShortcut       = onLogShortcut,
            )
        }
    }
}

@Test fun `shows set-up drink buttons when no shortcuts configured`() {
    renderWithShortcuts(shortcuts = emptyList())
    compose.onNodeWithText("Set up drink buttons", substring = true).assertExists()
}

@Test fun `tapping set-up drink buttons calls onSetUpDrinkButtons`() {
    var tapped = false
    renderWithShortcuts(shortcuts = emptyList(), onSetUpDrinkButtons = { tapped = true })
    compose.onNodeWithText("Set up drink buttons", substring = true).performClick()
    assertTrue(tapped)
}

@Test fun `shows shortcut button with emoji and label when configured`() {
    val shortcut = aShortcut(emoji = "🍺", label = "Pils", sortOrder = 0)
    renderWithShortcuts(shortcuts = listOf(shortcut))
    compose.onNodeWithText("🍺 Pils").assertExists()
}

@Test fun `tapping a shortcut button calls onLogShortcut with the correct entity`() {
    var logged: ShortcutEntity? = null
    val shortcut = aShortcut(emoji = "🍷", label = "Wine", sortOrder = 0)
    renderWithShortcuts(shortcuts = listOf(shortcut), onLogShortcut = { logged = it })
    compose.onNodeWithText("🍷 Wine").performClick()
    assertEquals(shortcut.id, logged?.id)
}
```

- [ ] **Step 2: Run to confirm new tests fail**

```
./gradlew :app:testDebugUnitTest --tests "org.branneman.health.ui.LogScreenTest" 2>&1 | tail -20
```

Expected: 4 new tests FAIL with "Unresolved reference: shortcuts" on `LogContent`.

- [ ] **Step 3: Update LogScreen.kt**

Add the import at the top:

```kotlin
import org.branneman.health.db.entities.ShortcutEntity
```

Change `LogContent`'s signature to add three new parameters with defaults:

```kotlin
@Composable
fun LogContent(
    entries: List<LogEntryEntity>,
    pinnedTemplates: List<MealTemplateEntity> = emptyList(),
    shortcuts: List<ShortcutEntity> = emptyList(),
    onAdd: (kcal: String, label: String) -> Unit,
    onDelete: (LogEntryEntity) -> Unit,
    onSetUpMealButtons: () -> Unit = {},
    onLogTemplate: (MealTemplateEntity) -> Unit = {},
    onSetUpDrinkButtons: () -> Unit = {},
    onLogShortcut: (ShortcutEntity) -> Unit = {},
    modifier: Modifier = Modifier,
)
```

Insert the drink shortcuts row immediately after the existing `Spacer(Modifier.height(12.dp))` that follows the meal-button block, and before the quick-add `Row`:

```kotlin
// --- Drink shortcuts row ---
if (shortcuts.isEmpty()) {
    OutlinedButton(
        onClick  = onSetUpDrinkButtons,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Set up drink buttons →")
    }
} else {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .fillMaxWidth(),
    ) {
        shortcuts.forEach { shortcut ->
            Button(onClick = { onLogShortcut(shortcut) }) {
                Text("${shortcut.emoji} ${shortcut.label}")
            }
        }
    }
}

Spacer(Modifier.height(12.dp))
```

Change `LogScreen`'s signature and body to collect shortcuts and pass the new callbacks:

```kotlin
@Composable
fun LogScreen(
    viewModel: LogViewModel = viewModel(),
    onSetUpMealButtons: () -> Unit = {},
    onSetUpDrinkButtons: () -> Unit = {},
) {
    val entries         by viewModel.entries.collectAsStateWithLifecycle()
    val pinnedTemplates by viewModel.pinnedTemplates.collectAsStateWithLifecycle()
    val shortcuts       by viewModel.shortcuts.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var lastAction by remember { mutableStateOf<LogAction?>(null) }

    LaunchedEffect(lastAction) {
        val action = lastAction ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message     = action.message,
            actionLabel = "Undo",
            duration    = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) {
            when (action) {
                is LogAction.Added   -> viewModel.undoAdd()
                is LogAction.Deleted -> viewModel.undoDelete()
            }
        }
        lastAction = null
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        LogContent(
            entries             = entries,
            pinnedTemplates     = pinnedTemplates,
            shortcuts           = shortcuts,
            onAdd               = { kcal, label ->
                viewModel.addEntry(kcal, label)
                lastAction = LogAction.Added("Logged")
            },
            onDelete            = { entry ->
                viewModel.deleteEntry(entry)
                lastAction = LogAction.Deleted("Deleted")
            },
            onSetUpMealButtons  = onSetUpMealButtons,
            onLogTemplate       = { template ->
                viewModel.logFromTemplate(template)
                lastAction = LogAction.Added("Logged")
            },
            onSetUpDrinkButtons = onSetUpDrinkButtons,
            onLogShortcut       = { shortcut ->
                viewModel.logFromShortcut(shortcut)
                lastAction = LogAction.Added("Logged")
            },
            modifier            = Modifier.padding(padding),
        )
    }
}
```

- [ ] **Step 4: Run all LogScreen tests**

```
./gradlew :app:testDebugUnitTest --tests "org.branneman.health.ui.LogScreenTest" 2>&1 | tail -20
```

Expected: all tests PASS (existing 11 + new 4).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/ui/LogScreen.kt \
        app/src/test/kotlin/org/branneman/health/ui/LogScreenTest.kt
git commit -m "feat(app): add drink shortcuts row to LogScreen"
```

---

### Task 6: DrinkButtonsViewModel

**Files:**
- Create: `app/src/test/kotlin/org/branneman/health/ui/DrinkButtonsViewModelTest.kt`
- Create: `app/src/main/kotlin/org/branneman/health/ui/DrinkButtonsViewModel.kt`

- [ ] **Step 1: Create DrinkButtonsViewModelTest.kt**

```kotlin
package org.branneman.health.ui

import org.branneman.health.aShortcut
import org.branneman.health.db.entities.ShortcutEntity
import org.junit.Test
import kotlin.test.assertEquals

class DrinkButtonsViewModelTest {

    @Test fun `reindexed assigns sequential sortOrder starting at 0`() {
        val list = listOf(
            aShortcut(label = "A", sortOrder = 5),
            aShortcut(label = "B", sortOrder = 2),
            aShortcut(label = "C", sortOrder = 9),
        )
        val result = list.reindexed()
        assertEquals(0, result[0].sortOrder)
        assertEquals(1, result[1].sortOrder)
        assertEquals(2, result[2].sortOrder)
        assertEquals("A", result[0].label)
    }

    @Test fun `reindexed on empty list returns empty`() {
        assertEquals(emptyList(), emptyList<ShortcutEntity>().reindexed())
    }
}
```

- [ ] **Step 2: Run to confirm tests fail**

```
./gradlew :app:testDebugUnitTest --tests "org.branneman.health.ui.DrinkButtonsViewModelTest" 2>&1 | tail -20
```

Expected: FAIL with "Unresolved reference: reindexed".

- [ ] **Step 3: Create DrinkButtonsViewModel.kt**

```kotlin
package org.branneman.health.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.branneman.health.HealthApplication
import org.branneman.health.auth.TokenStore
import org.branneman.health.auth.authDataStore
import org.branneman.health.db.SyncStatus
import org.branneman.health.db.entities.ShortcutEntity

class DrinkButtonsViewModel(application: Application) : AndroidViewModel(application) {

    private val db = (application as HealthApplication).db
    private val tokenStore = TokenStore(application.authDataStore)

    private var userId: String? = null

    val draft: StateFlow<List<ShortcutEntity>?> = flow<List<ShortcutEntity>?> {
        emit(null)
        userId = tokenStore.tokenFlow.first()?.userId
        emitAll(db.shortcutDao().observeAll())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun save(rows: List<ShortcutEntity>) {
        val uid = userId ?: return
        viewModelScope.launch {
            db.shortcutDao().deleteAllForUser(uid)
            db.shortcutDao().upsertAll(rows.mapIndexed { i, entity ->
                entity.copy(userId = uid, sortOrder = i, syncStatus = SyncStatus.PENDING_CREATE)
            })
        }
    }
}

internal fun List<ShortcutEntity>.reindexed(): List<ShortcutEntity> =
    mapIndexed { i, e -> e.copy(sortOrder = i) }
```

- [ ] **Step 4: Run tests to confirm they pass**

```
./gradlew :app:testDebugUnitTest --tests "org.branneman.health.ui.DrinkButtonsViewModelTest" 2>&1 | tail -20
```

Expected: both PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/ui/DrinkButtonsViewModel.kt \
        app/src/test/kotlin/org/branneman/health/ui/DrinkButtonsViewModelTest.kt
git commit -m "feat(app): add DrinkButtonsViewModel"
```

---

### Task 7: DrinkButtonsScreen

**Files:**
- Create: `app/src/test/kotlin/org/branneman/health/ui/DrinkButtonsScreenTest.kt`
- Create: `app/src/main/kotlin/org/branneman/health/ui/DrinkButtonsScreen.kt`

- [ ] **Step 1: Create DrinkButtonsScreenTest.kt**

The `DrinkButtonsContent` composable is tested by injecting draft state and asserting on UI. The `AddDrinkDialog` fields are found by their label text — the same technique used in `MealButtonsScreenTest`.

```kotlin
package org.branneman.health.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.branneman.health.aShortcut
import org.branneman.health.db.entities.ShortcutEntity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DrinkButtonsScreenTest {

    @get:Rule val compose = createComposeRule()

    private fun render(
        draft: List<ShortcutEntity> = emptyList(),
        onSave: (List<ShortcutEntity>) -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                DrinkButtonsContent(draft = draft, onSave = onSave, onBack = onBack)
            }
        }
    }

    @Test fun `empty state shows add button and disabled save`() {
        render(draft = emptyList())
        compose.onNodeWithText("Add button").assertExists()
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test fun `save is enabled when row has emoji, label, and positive kcal`() {
        val shortcut = aShortcut(emoji = "🍺", label = "Pils", kcal = 150, sortOrder = 0)
        render(draft = listOf(shortcut))
        compose.onNodeWithText("Save").assertIsEnabled()
    }

    @Test fun `save is disabled when emoji is blank`() {
        val shortcut = aShortcut(emoji = "", label = "Pils", kcal = 150, sortOrder = 0)
        render(draft = listOf(shortcut))
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test fun `save is disabled when label is blank`() {
        val shortcut = aShortcut(emoji = "🍺", label = "", kcal = 150, sortOrder = 0)
        render(draft = listOf(shortcut))
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test fun `save is disabled when kcal is zero`() {
        val shortcut = aShortcut(emoji = "🍺", label = "Pils", kcal = 0, sortOrder = 0)
        render(draft = listOf(shortcut))
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test fun `tapping save calls onSave with current draft`() {
        var saved: List<ShortcutEntity>? = null
        val shortcut = aShortcut(emoji = "🍺", label = "Pils", kcal = 150, sortOrder = 0)
        render(draft = listOf(shortcut), onSave = { saved = it })
        compose.onNodeWithText("Save").performClick()
        assertEquals(1, saved?.size)
        assertEquals("Pils", saved?.first()?.label)
    }

    @Test fun `tapping delete removes row from displayed list`() {
        val pils = aShortcut(emoji = "🍺", label = "Pils", kcal = 150, sortOrder = 0)
        val wine = aShortcut(emoji = "🍷", label = "Wine", kcal = 120, sortOrder = 1)
        render(draft = listOf(pils, wine))
        compose.onNodeWithContentDescription("Delete Pils").performClick()
        compose.onNodeWithText("Pils").assertDoesNotExist()
        compose.onNodeWithText("Wine").assertExists()
    }

    @Test fun `adding a row via dialog then saving passes the new row to onSave`() {
        var saved: List<ShortcutEntity>? = null
        render(onSave = { saved = it })

        compose.onNodeWithText("Add button").performClick()
        compose.onNodeWithText("Emoji").performTextInput("🍺")
        compose.onNodeWithText("Label").performTextInput("Pils")
        compose.onAllNodesWithText("kcal")[0].performTextInput("150")
        compose.onNodeWithText("Add").performClick()

        compose.onNodeWithText("Save").performClick()

        assertEquals(1, saved?.size)
        assertEquals("🍺", saved?.first()?.emoji)
        assertEquals("Pils", saved?.first()?.label)
        assertEquals(150, saved?.first()?.kcal)
    }

    @Test fun `saving after deleting a row excludes it from onSave`() {
        val pils = aShortcut(emoji = "🍺", label = "Pils", kcal = 150, sortOrder = 0)
        val wine = aShortcut(emoji = "🍷", label = "Wine", kcal = 120, sortOrder = 1)
        var saved: List<ShortcutEntity>? = null
        render(draft = listOf(pils, wine), onSave = { saved = it })

        compose.onNodeWithContentDescription("Delete Pils").performClick()
        compose.onNodeWithText("Save").performClick()

        assertEquals(1, saved?.size)
        assertEquals("Wine", saved?.first()?.label)
    }
}
```

- [ ] **Step 2: Run to confirm all 8 tests fail**

```
./gradlew :app:testDebugUnitTest --tests "org.branneman.health.ui.DrinkButtonsScreenTest" 2>&1 | tail -20
```

Expected: FAIL with "Unresolved reference: DrinkButtonsContent".

- [ ] **Step 3: Create DrinkButtonsScreen.kt**

```kotlin
package org.branneman.health.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.branneman.health.db.entities.ShortcutEntity
import java.util.UUID

@Composable
fun DrinkButtonsScreen(
    onBack: () -> Unit,
    viewModel: DrinkButtonsViewModel = viewModel(),
) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    DrinkButtonsContent(
        draft  = draft ?: emptyList(),
        onSave = { rows -> viewModel.save(rows); onBack() },
        onBack = onBack,
    )
}

@Composable
fun DrinkButtonsContent(
    draft: List<ShortcutEntity>,
    onSave: (List<ShortcutEntity>) -> Unit,
    onBack: () -> Unit,
) {
    var rows by remember(draft) { mutableStateOf(draft) }
    var showAddDialog by remember { mutableStateOf(false) }

    val saveEnabled = rows.isNotEmpty() &&
            rows.all { it.emoji.isNotBlank() && it.label.isNotBlank() && it.kcal > 0 }

    if (showAddDialog) {
        AddDrinkDialog(
            onConfirm = { emoji, label, kcal ->
                val nextOrder = (rows.maxOfOrNull { it.sortOrder } ?: -1) + 1
                rows = rows + ShortcutEntity(
                    id        = UUID.randomUUID().toString(),
                    userId    = rows.firstOrNull()?.userId ?: "",
                    emoji     = emoji,
                    label     = label,
                    kcal      = kcal,
                    sortOrder = nextOrder,
                )
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text(
                text     = "Drink buttons",
                style    = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick  = { onSave(rows) },
                enabled  = saveEnabled,
                modifier = Modifier.padding(start = 8.dp),
            ) { Text("Save") }
        }

        Spacer(Modifier.height(16.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            itemsIndexed(rows, key = { _, r -> r.id }) { index, row ->
                DrinkButtonRow(
                    row      = row,
                    onUp     = {
                        if (index > 0) {
                            val m = rows.toMutableList()
                            val t = m[index - 1]; m[index - 1] = m[index]; m[index] = t
                            rows = m.reindexed()
                        }
                    },
                    onDown   = {
                        if (index < rows.size - 1) {
                            val m = rows.toMutableList()
                            val t = m[index + 1]; m[index + 1] = m[index]; m[index] = t
                            rows = m.reindexed()
                        }
                    },
                    onDelete = {
                        rows = rows.toMutableList().also { it.removeAt(index) }.reindexed()
                    },
                    onChange = { updated ->
                        rows = rows.toMutableList().also { it[index] = updated }
                    },
                )
                HorizontalDivider()
            }
        }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick  = { showAddDialog = true },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Add button") }
    }
}

@Composable
private fun DrinkButtonRow(
    row: ShortcutEntity,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onDelete: () -> Unit,
    onChange: (ShortcutEntity) -> Unit,
) {
    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier              = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column {
            TextButton(onClick = onUp,   modifier = Modifier.size(40.dp)) { Text("↑") }
            TextButton(onClick = onDown, modifier = Modifier.size(40.dp)) { Text("↓") }
        }
        OutlinedTextField(
            value         = row.emoji,
            onValueChange = { onChange(row.copy(emoji = it)) },
            label         = { Text("Emoji") },
            singleLine    = true,
            modifier      = Modifier.width(72.dp),
        )
        OutlinedTextField(
            value         = row.label,
            onValueChange = { onChange(row.copy(label = it)) },
            label         = { Text("Label") },
            singleLine    = true,
            modifier      = Modifier.weight(1f),
        )
        OutlinedTextField(
            value           = row.kcal.takeIf { it > 0 }?.toString() ?: "",
            onValueChange   = { onChange(row.copy(kcal = it.filter(Char::isDigit).toIntOrNull() ?: 0)) },
            label           = { Text("kcal") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine      = true,
            modifier        = Modifier.width(72.dp),
        )
        TextButton(
            onClick  = onDelete,
            modifier = Modifier.semantics { contentDescription = "Delete ${row.label}" },
        ) { Text("✕") }
    }
}

@Composable
private fun AddDrinkDialog(onConfirm: (String, String, Int) -> Unit, onDismiss: () -> Unit) {
    var emoji by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var kcal  by remember { mutableStateOf("") }
    val confirmEnabled = emoji.isNotBlank() && label.isNotBlank() && (kcal.toIntOrNull() ?: 0) > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title   = { Text("New drink button") },
        text    = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value         = emoji,
                    onValueChange = { emoji = it },
                    label         = { Text("Emoji") },
                    singleLine    = true,
                    modifier      = Modifier.width(80.dp),
                )
                OutlinedTextField(
                    value         = label,
                    onValueChange = { label = it },
                    label         = { Text("Label") },
                    singleLine    = true,
                )
                OutlinedTextField(
                    value           = kcal,
                    onValueChange   = { kcal = it.filter(Char::isDigit) },
                    label           = { Text("kcal") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine      = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick  = { onConfirm(emoji, label, kcal.toInt()) },
                enabled  = confirmEnabled,
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
```

- [ ] **Step 4: Run all DrinkButtonsScreenTest tests**

```
./gradlew :app:testDebugUnitTest --tests "org.branneman.health.ui.DrinkButtonsScreenTest" 2>&1 | tail -20
```

Expected: all 8 PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/ui/DrinkButtonsScreen.kt \
        app/src/test/kotlin/org/branneman/health/ui/DrinkButtonsScreenTest.kt
git commit -m "feat(app): add DrinkButtonsScreen"
```

---

### Task 8: SettingsScreen — drink buttons row

**Files:**
- Modify: `app/src/test/kotlin/org/branneman/health/ui/SettingsScreenTest.kt`
- Modify: `app/src/main/kotlin/org/branneman/health/ui/SettingsScreen.kt`

- [ ] **Step 1: Write failing test in SettingsScreenTest.kt**

Add after the existing meal-buttons test:

```kotlin
@Test fun `Drink buttons row is present and calls onNavigateDrinkButtons`() {
    var tapped = false
    compose.setContent {
        MaterialTheme {
            SettingsContent(
                onNavigateMealButtons  = {},
                onNavigateDrinkButtons = { tapped = true },
                onSignOut              = {},
            )
        }
    }
    compose.onNodeWithText("Drink buttons", substring = true).assertExists()
    compose.onNodeWithText("Drink buttons", substring = true).performClick()
    assertTrue(tapped)
}
```

- [ ] **Step 2: Run to confirm it fails**

```
./gradlew :app:testDebugUnitTest --tests "org.branneman.health.ui.SettingsScreenTest" 2>&1 | tail -20
```

Expected: FAIL — `SettingsContent` does not yet have `onNavigateDrinkButtons`.

- [ ] **Step 3: Update SettingsScreen.kt**

Add `onNavigateDrinkButtons: () -> Unit = {}` to `SettingsContent`'s signature (after `onNavigateMealButtons`).

Add a "Drink buttons →" row directly below the existing "Meal buttons →" `TextButton`:

```kotlin
TextButton(
    onClick  = onNavigateMealButtons,
    modifier = Modifier.fillMaxWidth(),
) {
    Text("Meal buttons →")
}
TextButton(
    onClick  = onNavigateDrinkButtons,
    modifier = Modifier.fillMaxWidth(),
) {
    Text("Drink buttons →")
}
```

Add `onNavigateDrinkButtons: () -> Unit = {}` to `SettingsScreen`'s signature and pass it through to `SettingsContent`.

- [ ] **Step 4: Run all SettingsScreen tests**

```
./gradlew :app:testDebugUnitTest --tests "org.branneman.health.ui.SettingsScreenTest" 2>&1 | tail -20
```

Expected: both tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/ui/SettingsScreen.kt \
        app/src/test/kotlin/org/branneman/health/ui/SettingsScreenTest.kt
git commit -m "feat(app): add drink buttons row to SettingsScreen"
```

---

### Task 9: Navigation wiring — App.kt

**Files:**
- Modify: `app/src/main/kotlin/org/branneman/health/App.kt`

- [ ] **Step 1: Update App.kt**

Add the import:

```kotlin
import org.branneman.health.ui.DrinkButtonsScreen
```

Change the `SettingsPage` enum:

```kotlin
private enum class SettingsPage { Main, MealButtons, DrinkButtons }
```

In `MainNav`, change the `Tab.Log` branch:

```kotlin
Tab.Log -> LogScreen(
    onSetUpMealButtons  = {
        currentTab   = Tab.Settings
        settingsPage = SettingsPage.MealButtons
    },
    onSetUpDrinkButtons = {
        currentTab   = Tab.Settings
        settingsPage = SettingsPage.DrinkButtons
    },
)
```

Change the `SettingsPage.Main` branch:

```kotlin
SettingsPage.Main -> SettingsScreen(
    onSignOut              = { authViewModel.logout() },
    onNavigateMealButtons  = { settingsPage = SettingsPage.MealButtons },
    onNavigateDrinkButtons = { settingsPage = SettingsPage.DrinkButtons },
)
```

Add the `DrinkButtons` branch to the `when (settingsPage)` block:

```kotlin
SettingsPage.MealButtons  -> MealButtonsScreen(onBack = { settingsPage = SettingsPage.Main })
SettingsPage.DrinkButtons -> DrinkButtonsScreen(onBack = { settingsPage = SettingsPage.Main })
```

- [ ] **Step 2: Confirm the project compiles cleanly**

```
./gradlew :app:compileDebugKotlin 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL, no unresolved references.

- [ ] **Step 3: Run the full app test suite**

```
./gradlew :app:testDebugUnitTest 2>&1 | tail -30
```

Expected: all tests PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/App.kt
git commit -m "feat(app): wire drink shortcuts navigation in App.kt"
```
