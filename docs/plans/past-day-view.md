# Past-day view Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add date navigation to the Log tab (swipe or pick any past day), allow full CRUD on past days, drag-to-reorder entries with server-stored sort order, remove per-entry timestamps, and shift the day boundary to 04:00.

**Architecture:** Five focused commits in dependency order: 04:00 rollover first (foundational), then UI cleanup (time removal), then date navigation (HorizontalPager + DAO), then past-day CRUD, then sort order (data model + drag UI + server sync). Each commit leaves the app in a working, testable state.

**Tech Stack:** Kotlin, Jetpack Compose (HorizontalPager from `androidx.compose.foundation.pager`), Room, Ktor, Exposed, `sh.calvin.reorderable:reorderable` for drag handles.

## Global Constraints

- UUIDs everywhere — no auto-increment IDs (CLAUDE.md)
- Conventional commits: `type(scope): message` (CLAUDE.md)
- Run tests before committing: server changes → `./gradlew :server:test`; app/shared changes → `./gradlew :app:test` (CLAUDE.md)
- Spec: `docs/specs/past-day-view.md`
- Flyway migrations: `V{n}__{description}.sql` (double underscore), never edit applied migrations
- Room migrations: explicit `Migration` objects in `HealthApplication.kt`; bump `HealthDatabase.version`
- No future dates accessible in the date picker or pager

---

## File Structure

**New files:**
- `app/src/main/kotlin/org/branneman/health/util/EffectiveDate.kt` — `effectiveDate()` pure function + `effectiveDateFlow()` reactive flow
- `app/src/test/kotlin/org/branneman/health/util/EffectiveDateTest.kt` — unit tests for `effectiveDate()`
- `server/src/main/resources/db/migration/V11__log_entry_sort_order.sql` — adds `sort_order` column

**Modified files (by task):**

| Task | Files |
|------|-------|
| 1 (rollover) | `LogViewModel.kt`, `DashboardViewModel.kt` |
| 2 (time) | `LogScreen.kt`, `LogScreenTest.kt` |
| 3 (nav) | `LogEntryDao.kt`, `LogViewModel.kt`, `App.kt`, `LogEntryDaoTest.kt` |
| 4 (CRUD) | `LogViewModel.kt`, `QuickAddViewModel.kt`, `BuildFromScratchViewModel.kt`, `App.kt` |
| 5 (sort) | `LogEntryDto.kt`, `QuickAddRequestDto.kt`, `FoodLogRequestDto.kt`, `LogEntryEntity.kt`, `HealthDatabase.kt`, `HealthApplication.kt`, `LogEntryDao.kt`, `LogScreen.kt`, `Tables.kt`, `Application.kt`, `HealthApiClient.kt`, `LogEntrySyncService.kt`, `libs.versions.toml`, `app/build.gradle.kts`, `TestFactories.kt`, `LogEntryDaoTest.kt`, `LogEntryIntegrationTest.kt` |

---

## Task 1 — 04:00 effective-date rollover

**Scope item 5.** Pure app change; no server, no DTO changes.

**Files:**
- Create: `app/src/main/kotlin/org/branneman/health/util/EffectiveDate.kt`
- Create: `app/src/test/kotlin/org/branneman/health/util/EffectiveDateTest.kt`
- Modify: `app/src/main/kotlin/org/branneman/health/log/LogViewModel.kt`
- Modify: `app/src/main/kotlin/org/branneman/health/dashboard/DashboardViewModel.kt`

**Interfaces:**
- Produces: `fun effectiveDate(now: LocalDateTime = LocalDateTime.now()): LocalDate` and `fun effectiveDateFlow(context: Context): Flow<LocalDate>` — used by Tasks 3 and 4

---

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/org/branneman/health/util/EffectiveDateTest.kt`:

```kotlin
package org.branneman.health.util

import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.assertEquals

class EffectiveDateTest {

    @Test fun `midnight returns previous day`() {
        assertEquals(
            LocalDate.of(2026, 6, 27),
            effectiveDate(LocalDateTime.of(2026, 6, 28, 0, 0))
        )
    }

    @Test fun `03h59 returns previous day`() {
        assertEquals(
            LocalDate.of(2026, 6, 27),
            effectiveDate(LocalDateTime.of(2026, 6, 28, 3, 59))
        )
    }

    @Test fun `04h00 returns current day`() {
        assertEquals(
            LocalDate.of(2026, 6, 28),
            effectiveDate(LocalDateTime.of(2026, 6, 28, 4, 0))
        )
    }

    @Test fun `noon returns current day`() {
        assertEquals(
            LocalDate.of(2026, 6, 28),
            effectiveDate(LocalDateTime.of(2026, 6, 28, 12, 0))
        )
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```
./gradlew :app:test --tests "org.branneman.health.util.EffectiveDateTest" 2>&1 | tail -10
```
Expected: FAILED — `effectiveDate` not yet defined.

- [ ] **Step 3: Implement `EffectiveDate.kt`**

Create `app/src/main/kotlin/org/branneman/health/util/EffectiveDate.kt`:

```kotlin
package org.branneman.health.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

fun effectiveDate(now: LocalDateTime = LocalDateTime.now()): LocalDate =
    if (now.toLocalTime() < LocalTime.of(4, 0)) now.toLocalDate().minusDays(1)
    else now.toLocalDate()

fun effectiveDateFlow(context: Context): Flow<LocalDate> =
    callbackFlow<Unit> {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) { trySend(Unit) }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_TIMEZONE_CHANGED))
        trySend(Unit)
        awaitClose { context.unregisterReceiver(receiver) }
    }.flatMapLatest {
        flow {
            while (true) {
                val now = LocalDateTime.now()
                emit(effectiveDate(now))
                val next4am = if (now.toLocalTime() < LocalTime.of(4, 0)) {
                    now.toLocalDate().atTime(4, 0)
                } else {
                    now.toLocalDate().plusDays(1).atTime(4, 0)
                }
                delay(ChronoUnit.MILLIS.between(now, next4am))
            }
        }
    }
```

- [ ] **Step 4: Run tests to confirm they pass**

```
./gradlew :app:test --tests "org.branneman.health.util.EffectiveDateTest" 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL, 4 tests passed.

- [ ] **Step 5: Update `LogViewModel.kt` — replace `dateFlow()` with `effectiveDateFlow()`**

In `log/LogViewModel.kt`, replace the `entries` StateFlow (currently uses `dateFlow()`):

```kotlin
// Remove this import if present: import java.time.Clock
// Add this import:
import org.branneman.health.util.effectiveDateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

// Replace the entries StateFlow:
val entries: StateFlow<List<LogEntryWithKcal>> =
    effectiveDateFlow(application)
        .flatMapLatest { today ->
            db.logEntryDao().observeAllWithKcal().map { all ->
                all.filter { it.entry.loggedAt.startsWith(today.toString()) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
```

Delete the entire `internal fun dateFlow(clock: Clock = Clock.systemDefaultZone()): Flow<LocalDate>` function at the bottom of `LogViewModel.kt` (lines 140–150).

- [ ] **Step 6: Update `DashboardViewModel.kt` — replace `LocalDate.now()` with `effectiveDate()`**

In `dashboard/DashboardViewModel.kt`, add import:
```kotlin
import org.branneman.health.util.effectiveDate
```

Replace every `LocalDate.now()` used for "today's date" with `effectiveDate()`. Do NOT change the one at line 191: `val age = LocalDate.now().year - profile.birthYear` — age calculation doesn't need the 4am boundary.

The lines to change are around 137, 146, 216, 230, 239. Each reads `LocalDate.now().toString()` or similar — replace `.now()` with `effectiveDate()`.

- [ ] **Step 7: Run all app tests**

```
./gradlew :app:test 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/util/EffectiveDate.kt \
        app/src/test/kotlin/org/branneman/health/util/EffectiveDateTest.kt \
        app/src/main/kotlin/org/branneman/health/log/LogViewModel.kt \
        app/src/main/kotlin/org/branneman/health/dashboard/DashboardViewModel.kt
git commit -m "$(cat <<'EOF'
feat(app): 04:00 effective-date rollover with timezone-change awareness

Day boundary moves from midnight to 04:00. effectiveDateFlow replaces
dateFlow() and reacts to ACTION_TIMEZONE_CHANGED within ~1 second.
EOF
)"
```

---

## Task 2 — Remove per-entry time display

**Scope item 4.** Pure UI change; no data model changes.

**Files:**
- Modify: `app/src/main/kotlin/org/branneman/health/ui/LogScreen.kt`
- Modify: `app/src/test/kotlin/org/branneman/health/ui/LogScreenTest.kt`

**Interfaces:**
- Consumes: nothing new
- Produces: `LogEntryRow` no longer renders a time; footer reads "X kcal logged"; empty state reads "Nothing logged."

---

- [ ] **Step 1: Update `LogScreenTest.kt` — fix copy assertions**

Update the empty-state test in `LogScreenTest.kt`:
```kotlin
@Test fun `empty state shows nothing-logged message`() {
    render(entries = emptyList())
    compose.onNodeWithText("Nothing logged.", substring = true).assertExists()
}
```

Add a new test asserting time is not shown:
```kotlin
@Test fun `entry row does not show a timestamp`() {
    val entries = listOf(
        aLogEntryWithKcal(aQuickAddEntry(loggedAt = "2026-06-11T13:00:00Z", quickAddKcal = 560, quickAddLabel = "Lunch")),
    )
    render(entries = entries)
    compose.onNodeWithText("13:00", substring = true).assertDoesNotExist()
}

@Test fun `footer shows kcal logged without the word today`() {
    val entries = listOf(
        aLogEntryWithKcal(aQuickAddEntry(quickAddKcal = 400)),
    )
    render(entries = entries)
    compose.onNodeWithText("400 kcal logged", substring = true).assertExists()
    compose.onNodeWithText("today", substring = true).assertDoesNotExist()
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```
./gradlew :app:test --tests "org.branneman.health.ui.LogScreenTest" 2>&1 | tail -10
```
Expected: some tests FAILED.

- [ ] **Step 3: Update `LogScreen.kt`**

In `LogScreen.kt`:

1. Delete `private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")` (line 249).

2. Rewrite `LogEntryRow` — remove the time column entirely:
```kotlin
@Composable
private fun LogEntryRow(entry: LogEntryWithKcal, onClick: () -> Unit) {
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        Text(text = entry.displayLabel, style = MaterialTheme.typography.bodyMedium)
        Text(
            text  = "${entry.totalKcal} kcal",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
```

3. Change the footer text (was `"$total kcal logged today"`):
```kotlin
Text(
    text     = "$total kcal logged",
    style    = MaterialTheme.typography.bodySmall,
    color    = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.align(Alignment.End).padding(top = 8.dp),
)
```

4. Change the empty state (was `"Nothing logged today."`):
```kotlin
Text(
    text     = "Nothing logged.",
    style    = MaterialTheme.typography.bodyMedium,
    color    = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(top = 12.dp),
)
```

5. In `DeleteConfirmDialog`, remove `$time` from the title:
```kotlin
val title = "${entry.displayLabel} — ${entry.totalKcal} kcal"
```
Remove the `val time = remember { ... }` block in that composable.

6. In `EditEntryDialog`, replace the `Text(time)` title with the entry label:
```kotlin
title = { Text(entry.entry.quickAddLabel ?: "Edit entry") },
```
Remove the `val time = remember { ... }` block in that composable.

7. Remove unused imports: `DateTimeFormatter`, `OffsetDateTime` if no longer used anywhere in the file.

- [ ] **Step 4: Run tests**

```
./gradlew :app:test --tests "org.branneman.health.ui.LogScreenTest" 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/ui/LogScreen.kt \
        app/src/test/kotlin/org/branneman/health/ui/LogScreenTest.kt
git commit -m "$(cat <<'EOF'
feat(app): remove per-entry time display from log entries
EOF
)"
```

---

## Task 3 — Date navigation in Log tab

**Scope item 1.** Adds `observeForDate` DAO, makes `LogViewModel` date-aware, wraps Log content in `HorizontalPager`.

**Files:**
- Modify: `app/src/main/kotlin/org/branneman/health/db/dao/LogEntryDao.kt`
- Modify: `app/src/main/kotlin/org/branneman/health/log/LogViewModel.kt`
- Modify: `app/src/main/kotlin/org/branneman/health/App.kt`
- Modify: `app/src/test/kotlin/org/branneman/health/db/dao/LogEntryDaoTest.kt`

**Interfaces:**
- Produces: `LogEntryDao.observeForDate(userId, datePrefix)` used by `LogViewModel.entries`; `LogViewModel.setSelectedDate(date)` called from MainNav pager

---

- [ ] **Step 1: Write failing DAO tests**

Add to `LogEntryDaoTest.kt`:

```kotlin
@Test
fun `observeForDate returns only entries for the given date`() = runTest {
    val userId = uuid()
    dao.upsert(aQuickAddEntry(userId = userId, loggedAt = "2026-06-11T08:00:00Z", quickAddKcal = 400))
    dao.upsert(aQuickAddEntry(userId = userId, loggedAt = "2026-06-10T19:00:00Z", quickAddKcal = 800))

    val result = dao.observeForDate(userId, "2026-06-11%").first()

    assertEquals(1, result.size)
    assertEquals(400, result[0].totalKcal)
}

@Test
fun `observeForDate excludes PENDING_DELETE entries`() = runTest {
    val userId = uuid()
    val id = uuid()
    dao.upsert(aQuickAddEntry(id = id, userId = userId, loggedAt = "2026-06-11T08:00:00Z"))
    dao.updateSyncStatus(id, SyncStatus.PENDING_DELETE)

    val result = dao.observeForDate(userId, "2026-06-11%").first()

    assertTrue(result.isEmpty())
}

@Test
fun `observeForDate excludes other users entries`() = runTest {
    val userId = uuid()
    val otherId = uuid()
    dao.upsert(aQuickAddEntry(userId = userId,  loggedAt = "2026-06-11T08:00:00Z", quickAddKcal = 400))
    dao.upsert(aQuickAddEntry(userId = otherId, loggedAt = "2026-06-11T09:00:00Z", quickAddKcal = 600))

    val result = dao.observeForDate(userId, "2026-06-11%").first()

    assertEquals(1, result.size)
    assertEquals(400, result[0].totalKcal)
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```
./gradlew :app:test --tests "org.branneman.health.db.dao.LogEntryDaoTest" 2>&1 | tail -10
```
Expected: FAILED — `observeForDate` not defined.

- [ ] **Step 3: Add `observeForDate` to `LogEntryDao.kt`**

```kotlin
@Query("""
    SELECT le.*, COALESCE((
        SELECT CAST(SUM(lei.grams * lei.kcalPer100g / 100.0) AS INTEGER)
        FROM log_entry_item lei WHERE lei.logEntryId = le.id
    ), 0) AS itemKcal
    FROM log_entry le
    WHERE le.userId = :userId AND le.loggedAt LIKE :datePrefix AND le.syncStatus != 'PENDING_DELETE'
    ORDER BY le.loggedAt ASC
""")
fun observeForDate(userId: String, datePrefix: String): Flow<List<LogEntryWithKcal>>
```

- [ ] **Step 4: Run DAO tests to confirm they pass**

```
./gradlew :app:test --tests "org.branneman.health.db.dao.LogEntryDaoTest" 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Update `LogViewModel.kt` — add `selectedDate` and switch to `observeForDate`**

Add at the top of the class body (below existing fields):
```kotlin
import org.branneman.health.util.effectiveDate
import kotlinx.coroutines.flow.mapNotNull

private val _selectedDate = MutableStateFlow(effectiveDate())

fun setSelectedDate(date: LocalDate) { _selectedDate.value = date }

private val userId: Flow<String> = tokenStore.tokenFlow.mapNotNull { it?.userId }
```

Replace the `entries` StateFlow (currently uses `effectiveDateFlow + observeAllWithKcal`):
```kotlin
val entries: StateFlow<List<LogEntryWithKcal>> =
    combine(_selectedDate, userId) { date, uid ->
        db.logEntryDao().observeForDate(uid, "$date%")
    }
    .flatMapLatest { it }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
```

The `effectiveDateFlow` import is no longer needed in `LogViewModel.kt` — remove it if the only remaining usage was `entries`.

- [ ] **Step 6: Update `App.kt` — wrap LogPage.Main in HorizontalPager**

In `MainNav`, add at the top of the function body:
```kotlin
val coroutineScope = rememberCoroutineScope()
```

Replace the `LogPage.Main -> LogScreen(...)` branch with:

```kotlin
LogPage.Main -> {
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 365 })
    var showDatePicker by remember { mutableStateOf(false) }

    LaunchedEffect(pagerState.currentPage) {
        logVm.setSelectedDate(effectiveDate().minusDays(pagerState.currentPage.toLong()))
    }

    Column(modifier = Modifier.fillMaxSize()) {
        DateNavigationHeader(
            page        = pagerState.currentPage,
            onPickDate  = { showDatePicker = true },
        )
        HorizontalPager(
            state    = pagerState,
            modifier = Modifier.weight(1f),
        ) {
            LogScreen(
                viewModel           = logVm,
                onSetUpMealButtons  = {
                    currentTab   = Tab.Settings
                    settingsPage = SettingsPage.MealButtons
                },
                shortcuts           = logVm.shortcuts.collectAsStateWithLifecycle().value,
                onSetUpDrinkButtons = {
                    currentTab   = Tab.Settings
                    settingsPage = SettingsPage.DrinkButtons
                },
                onLogShortcut           = { shortcut -> logVm.logFromShortcut(shortcut) },
                onOpenLogFlow           = { showLogSheet = true },
                externalUndo            = pendingLogUndoAction,
                onExternalUndoConsumed  = { pendingLogUndoAction = null },
            )
        }
    }

    if (showDatePicker) {
        val today = effectiveDate()
        val initialMillis = today
            .minusDays(pagerState.currentPage.toLong())
            .atStartOfDay(java.time.ZoneOffset.UTC)
            .toInstant().toEpochMilli()
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = initialMillis,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val picked = java.time.Instant.ofEpochMilli(utcTimeMillis)
                        .atZone(java.time.ZoneOffset.UTC).toLocalDate()
                    return !picked.isAfter(today)
                }
            },
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val millis = pickerState.selectedDateMillis
                    if (millis != null) {
                        val picked = java.time.Instant.ofEpochMilli(millis)
                            .atZone(java.time.ZoneOffset.UTC).toLocalDate()
                        val page = java.time.temporal.ChronoUnit.DAYS
                            .between(picked, today).toInt().coerceIn(0, 364)
                        coroutineScope.launch { pagerState.animateScrollToPage(page) }
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) { DatePicker(state = pickerState) }
    }
}
```

Add the `DateNavigationHeader` private composable at the bottom of `App.kt` (before `LogFlowSheet`):

```kotlin
@Composable
private fun DateNavigationHeader(page: Int, onPickDate: () -> Unit) {
    val today  = remember { effectiveDate() }
    val label  = remember(page) {
        when (page) {
            0    -> "Today"
            1    -> "Yesterday"
            else -> today.minusDays(page.toLong())
                .format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM"))
        }
    }
    Row(
        modifier            = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        AssistChip(onClick = onPickDate, label = { Text(label) })
    }
}
```

Add necessary imports to `App.kt`:
```kotlin
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import org.branneman.health.util.effectiveDate
```

- [ ] **Step 7: Run all app tests**

```
./gradlew :app:test 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/db/dao/LogEntryDao.kt \
        app/src/main/kotlin/org/branneman/health/log/LogViewModel.kt \
        app/src/main/kotlin/org/branneman/health/App.kt \
        app/src/test/kotlin/org/branneman/health/db/dao/LogEntryDaoTest.kt
git commit -m "$(cat <<'EOF'
feat(app): date navigation between days in Log tab via HorizontalPager

Swipe left/right to move between days; tap date chip to pick from a
calendar. Page 0 = today (effective date), max 365 pages back, no future.
EOF
)"
```

---

## Task 4 — Full CRUD on past days

**Scope item 2.** Log entries written from a past day get `loggedAt` set to noon on that effective date.

**Files:**
- Modify: `app/src/main/kotlin/org/branneman/health/log/LogViewModel.kt`
- Modify: `app/src/main/kotlin/org/branneman/health/log/QuickAddViewModel.kt`
- Modify: `app/src/main/kotlin/org/branneman/health/ui/BuildFromScratchViewModel.kt`
- Modify: `app/src/main/kotlin/org/branneman/health/App.kt`

**Interfaces:**
- Consumes: `LogViewModel._selectedDate` (from Task 3)
- Produces: `LogViewModel.loggedAtForSelectedDate(): String` (internal); `QuickAddViewModel.logQuickAdd(kcal, label, loggedAt)` updated signature

---

- [ ] **Step 1: Add `loggedAtForSelectedDate()` to `LogViewModel.kt`**

Add this private helper anywhere in the class body:

```kotlin
import java.time.ZoneId

fun loggedAtForSelectedDate(): String {
    val date = _selectedDate.value
    return if (date == effectiveDate()) {
        OffsetDateTime.now().toString()
    } else {
        val noon   = date.atTime(12, 0)
        val offset = ZoneId.systemDefault().rules.getOffset(noon)
        OffsetDateTime.of(noon, offset).toString()
    }
}
```

Note: `loggedAtForSelectedDate()` is `internal` visibility for testability (change `fun` to `internal fun`).

Update `logFromTemplate`, `logFromShortcut`, and `logSingleItem` to use it:
```kotlin
// In each of these methods, replace:
loggedAt = OffsetDateTime.now().toString(),
// with:
loggedAt = loggedAtForSelectedDate(),
```

- [ ] **Step 2: Update `QuickAddViewModel.kt` — accept `loggedAt` parameter**

Open `app/src/main/kotlin/org/branneman/health/log/QuickAddViewModel.kt`. Find the method that calls `db.logEntryDao().upsert(...)` with `loggedAt = OffsetDateTime.now().toString()`. Change the method signature to accept `loggedAt: String` and use it:

```kotlin
fun logQuickAdd(kcal: Int, label: String?, loggedAt: String) {
    viewModelScope.launch {
        val userId = tokenStore.tokenFlow.first()?.userId ?: return@launch
        val entity = LogEntryEntity(
            userId        = userId,
            loggedAt      = loggedAt,
            mealType      = "unknown",
            quickAddKcal  = kcal,
            quickAddLabel = label?.trim()?.ifEmpty { null },
        )
        db.logEntryDao().upsert(entity)
        _undoPending.value = entity
    }
}
```

- [ ] **Step 3: Update `App.kt` — pass `loggedAt` when opening QuickAdd and BuildFromScratch**

In the `LogPage.QuickAdd` branch in `App.kt`, find where `QuickAddScreen` calls its ViewModel to log. The `loggedAt` for the selected date should be passed through. Add a `var currentLoggedAt by remember { mutableStateOf("") }` in `MainNav` (alongside other state vars), and set it when opening the log flow:

In the `LogFlowSheet` callbacks, before navigating to QuickAdd or BuildFromScratch:
```kotlin
onQuickAdd = {
    showLogSheet = false
    currentLoggedAt = logVm.loggedAtForSelectedDate()
    logPage = LogPage.QuickAdd
},
onBuildFromScratch = {
    showLogSheet = false
    buildVm.reset()
    currentLoggedAt = logVm.loggedAtForSelectedDate()
    logPage = LogPage.BuildFromScratch
},
```

Pass `currentLoggedAt` through to `QuickAddScreen` as a parameter, and ensure `QuickAddScreen` passes it to `QuickAddViewModel.logQuickAdd()`.

Similarly update `BuildFromScratchViewModel`'s log method to accept `loggedAt: String`.

- [ ] **Step 4: Update `BuildFromScratchViewModel.kt`**

Find the method that creates `LogEntryEntity` in `BuildFromScratchViewModel`. Add `loggedAt: String` parameter and use it instead of `OffsetDateTime.now().toString()`.

Pass `loggedAt = currentLoggedAt` from `App.kt` when calling the log method from `BuildFromScratchScreen`.

- [ ] **Step 5: Run all app tests**

```
./gradlew :app:test 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/log/LogViewModel.kt \
        app/src/main/kotlin/org/branneman/health/log/QuickAddViewModel.kt \
        app/src/main/kotlin/org/branneman/health/ui/BuildFromScratchViewModel.kt \
        app/src/main/kotlin/org/branneman/health/App.kt
git commit -m "$(cat <<'EOF'
feat(app): log and edit entries on past days

Entries logged from a past day get loggedAt set to noon on that date.
All log paths (quick-add, template, build-from-scratch, single item) work
from any past day via the selected date in LogViewModel.
EOF
)"
```

---

## Task 5 — Drag-to-reorder with server-stored sort order

**Scope item 3.** Data model (Room + Postgres) + drag UI + sync.

**Files:**
- Modify: `shared/src/commonMain/kotlin/org/branneman/health/LogEntryDto.kt`
- Modify: `shared/src/commonMain/kotlin/org/branneman/health/QuickAddRequestDto.kt`
- Modify: `shared/src/commonMain/kotlin/org/branneman/health/FoodLogRequestDto.kt`
- Modify: `app/src/main/kotlin/org/branneman/health/db/entities/LogEntryEntity.kt`
- Modify: `app/src/main/kotlin/org/branneman/health/db/HealthDatabase.kt`
- Modify: `app/src/main/kotlin/org/branneman/health/HealthApplication.kt`
- Modify: `app/src/main/kotlin/org/branneman/health/db/dao/LogEntryDao.kt`
- Modify: `app/src/main/kotlin/org/branneman/health/ui/LogScreen.kt`
- Create: `server/src/main/resources/db/migration/V11__log_entry_sort_order.sql`
- Modify: `server/src/main/kotlin/org/branneman/health/data/Tables.kt`
- Modify: `server/src/main/kotlin/org/branneman/health/Application.kt`
- Modify: `app/src/main/kotlin/org/branneman/health/network/HealthApiClient.kt`
- Modify: `app/src/main/kotlin/org/branneman/health/sync/LogEntrySyncService.kt`
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/test/kotlin/org/branneman/health/TestFactories.kt`
- Modify: `app/src/test/kotlin/org/branneman/health/db/dao/LogEntryDaoTest.kt`
- Modify: `server/src/test/kotlin/org/branneman/health/LogEntryIntegrationTest.kt`

---

- [ ] **Step 1: Write failing app tests for sortOrder**

Add to `LogEntryDaoTest.kt`:

```kotlin
@Test
fun `observeForDate orders entries by sortOrder ascending`() = runTest {
    val userId = uuid()
    dao.upsert(aQuickAddEntry(userId = userId, loggedAt = "2026-06-11T08:00:00Z",
                              quickAddKcal = 400, sortOrder = 2))
    dao.upsert(aQuickAddEntry(userId = userId, loggedAt = "2026-06-11T09:00:00Z",
                              quickAddKcal = 600, sortOrder = 0))
    dao.upsert(aQuickAddEntry(userId = userId, loggedAt = "2026-06-11T10:00:00Z",
                              quickAddKcal = 200, sortOrder = 1))

    val result = dao.observeForDate(userId, "2026-06-11%").first()

    assertEquals(listOf(600, 200, 400), result.map { it.totalKcal })
}

@Test
fun `updateSortOrders persists new order and marks PENDING_UPDATE`() = runTest {
    val e1 = aQuickAddEntry(userId = uuid(), loggedAt = "2026-06-11T08:00:00Z",
                             sortOrder = 0, syncStatus = SyncStatus.SYNCED)
    val e2 = aQuickAddEntry(userId = e1.userId, loggedAt = "2026-06-11T09:00:00Z",
                             sortOrder = 1, syncStatus = SyncStatus.SYNCED)
    dao.upsert(e1)
    dao.upsert(e2)

    dao.updateSortOrders(listOf(e1.id to 1, e2.id to 0))

    val result = dao.observeForDate(e1.userId, "2026-06-11%").first()
    assertEquals(e2.id, result[0].entry.id)
    assertEquals(e1.id, result[1].entry.id)
    assertEquals(SyncStatus.PENDING_UPDATE, result[0].entry.syncStatus)
    assertEquals(SyncStatus.PENDING_UPDATE, result[1].entry.syncStatus)
}
```

These tests reference `aQuickAddEntry(sortOrder = N)` and `aLogEntry(sortOrder = N)` — the factories need the new field. For now run and expect compilation failure.

- [ ] **Step 2: Add `sortOrder` to `LogEntryEntity.kt`**

```kotlin
@Entity(tableName = "log_entry")
data class LogEntryEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val loggedAt: String,
    val mealType: String,
    val quickAddKcal: Int?,
    val quickAddLabel: String?,
    val sortOrder: Int = 0,                          // NEW
    val syncStatus: SyncStatus = SyncStatus.PENDING_CREATE,
    val createdAt: Long = System.currentTimeMillis(),
)
```

- [ ] **Step 3: Update `TestFactories.kt` — add `sortOrder` to factory helpers**

```kotlin
fun aLogEntry(
    id: String = uuid(),
    userId: String = uuid(),
    loggedAt: String = "2026-01-01T08:00:00Z",
    mealType: String = "breakfast",
    sortOrder: Int = 0,                              // NEW
) = LogEntryEntity(
    id = id, userId = userId, loggedAt = loggedAt, mealType = mealType,
    quickAddKcal = null, quickAddLabel = null, sortOrder = sortOrder,
    syncStatus = SyncStatus.PENDING_CREATE,
)

fun aQuickAddEntry(
    id: String = uuid(),
    userId: String = uuid(),
    loggedAt: String = "2026-01-01T08:00:00Z",
    quickAddKcal: Int = 350,
    quickAddLabel: String? = null,
    sortOrder: Int = 0,                              // NEW
    syncStatus: SyncStatus = SyncStatus.PENDING_CREATE,
) = LogEntryEntity(
    id            = id,
    userId        = userId,
    loggedAt      = loggedAt,
    mealType      = "unknown",
    quickAddKcal  = quickAddKcal,
    quickAddLabel = quickAddLabel,
    sortOrder     = sortOrder,                       // NEW
    syncStatus    = syncStatus,
)
```

- [ ] **Step 4: Add Room migration — bump DB version to 8**

In `HealthApplication.kt`, add before the class declaration:

```kotlin
private val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE log_entry ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
    }
}
```

In `HealthApplication.onCreate()`, add `MIGRATION_7_8` to `addMigrations(...)`:
```kotlin
.addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
               MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
```

In `HealthDatabase.kt`, change `version = 7` to `version = 8`.

- [ ] **Step 5: Add `updateSortOrders` to `LogEntryDao.kt`**

Add two methods:

```kotlin
@Query("UPDATE log_entry SET sortOrder = :order, syncStatus = 'PENDING_UPDATE' WHERE id = :id")
suspend fun updateSortOrder(id: String, order: Int)

@Transaction
open suspend fun updateSortOrders(updates: List<Pair<String, Int>>) {
    updates.forEach { (id, order) -> updateSortOrder(id, order) }
}
```

Update `observeForDate` query — change `ORDER BY le.loggedAt ASC` to `ORDER BY le.sortOrder ASC`:

```kotlin
@Query("""
    SELECT le.*, COALESCE((
        SELECT CAST(SUM(lei.grams * lei.kcalPer100g / 100.0) AS INTEGER)
        FROM log_entry_item lei WHERE lei.logEntryId = le.id
    ), 0) AS itemKcal
    FROM log_entry le
    WHERE le.userId = :userId AND le.loggedAt LIKE :datePrefix AND le.syncStatus != 'PENDING_DELETE'
    ORDER BY le.sortOrder ASC
""")
fun observeForDate(userId: String, datePrefix: String): Flow<List<LogEntryWithKcal>>
```

- [ ] **Step 6: Run app tests to confirm they pass**

```
./gradlew :app:test --tests "org.branneman.health.db.dao.LogEntryDaoTest" 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL, all tests including the new sortOrder tests pass.

- [ ] **Step 7: Add `sortOrder` to shared DTOs**

`LogEntryDto.kt`:
```kotlin
@Serializable
data class LogEntryDto(
    val id: String,
    val loggedAt: String,
    val mealType: String,
    val quickAddKcal: Int?,
    val quickAddLabel: String?,
    val sortOrder: Int = 0,    // NEW
    val items: List<LogEntryItemDto>,
)
```

`QuickAddRequestDto.kt` — open the file and add `sortOrder: Int = 0`.

`FoodLogRequestDto.kt` — open the file and add `sortOrder: Int = 0`.

- [ ] **Step 8: Add Flyway migration**

Create `server/src/main/resources/db/migration/V11__log_entry_sort_order.sql`:

```sql
ALTER TABLE log_entry ADD COLUMN sort_order INT NOT NULL DEFAULT 0;
```

- [ ] **Step 9: Update server `Tables.kt`**

In `object LogEntry : Table("log_entry")`, add after `quickAddLabel`:

```kotlin
val sortOrder = integer("sort_order").default(0)
```

- [ ] **Step 10: Write failing server integration test**

In `LogEntryIntegrationTest.kt`, add:

```kotlin
@Test fun `POST quick-add stores and returns sort_order`() = appTest {
    val token = login()
    val id = UUID.randomUUID().toString()

    val postResponse = client.post("/in/log/quick-add") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody("""{"id":"$id","quickAddKcal":350,"sortOrder":5}""")
    }
    assertEquals(HttpStatusCode.Created, postResponse.status)

    val getResponse = client.get("/in/entries") {
        bearerAuth(token)
    }
    val body = getResponse.bodyAsText()
    assertTrue(body.contains("\"sortOrder\":5") || body.contains("\"sort_order\":5"))
}
```

Run: `./gradlew :server:test --tests "org.branneman.health.LogEntryIntegrationTest"` — expect FAILED (server doesn't handle `sortOrder` yet).

- [ ] **Step 11: Update `Application.kt` — handle `sortOrder` in log entry routes**

In `Application.kt`, find where `QuickAddRequestDto` is deserialized and the row is inserted into `LogEntry`. Add `it[LogEntry.sortOrder] = dto.sortOrder` to the insert statement.

Find where `LogEntry` rows are read and mapped to `LogEntryDto`. Add `sortOrder = it[LogEntry.sortOrder]` to the mapping.

Find the `FoodLogRequestDto` insert path similarly and add `it[LogEntry.sortOrder] = dto.sortOrder`.

Find any `PATCH` / update endpoint for quick-add entries (searches for `QuickAddUpdateRequestDto`). After updating kcal and label, also update `sort_order` from the DTO if present.

Add a new `PATCH /in/log/{id}/sort-order` route:
```kotlin
patch("/in/log/{id}/sort-order") {
    val userId = call.principal<UserIdPrincipal>()!!.name.let(UUID::fromString)
    val id     = call.parameters["id"]!!.let(UUID::fromString)
    val body   = call.receive<kotlinx.serialization.json.JsonObject>()
    val order  = body["sortOrder"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.int } ?: run {
        call.respond(HttpStatusCode.BadRequest); return@patch
    }
    transaction {
        LogEntry.update({ (LogEntry.id eq id) and (LogEntry.userId eq userId) }) {
            it[sortOrder] = order
        }
    }
    call.respond(HttpStatusCode.NoContent)
}
```

- [ ] **Step 12: Run server tests**

```
./gradlew :server:test 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL, including the new `sort_order` test.

- [ ] **Step 13: Update `HealthApiClient.kt` — add `patchSortOrder`**

Add a new method:
```kotlin
suspend fun patchSortOrder(token: String, id: String, sortOrder: Int) {
    client.patch("$baseUrl/in/log/$id/sort-order") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody("""{"sortOrder":$sortOrder}""")
    }
}
```

Also update `postQuickAdd` and `postFoodLog` to include `sortOrder` in their request bodies (using the updated `QuickAddRequestDto` and `FoodLogRequestDto` which now carry `sortOrder`).

- [ ] **Step 14: Update `LogEntrySyncService.kt` — sync sort order**

In the `PENDING_UPDATE` handler, call `patchSortOrder` for every entry (any type), and also call `patchQuickAdd` for quick-add entries:

```kotlin
db.logEntryDao().getByStatus(SyncStatus.PENDING_UPDATE).forEach { entity ->
    runCatching {
        api.patchSortOrder(token, entity.id, entity.sortOrder)
        if (entity.quickAddKcal != null) {
            api.patchQuickAdd(
                token = token,
                id    = entity.id,
                dto   = QuickAddUpdateRequestDto(
                    kcal  = entity.quickAddKcal,
                    label = entity.quickAddLabel,
                ),
            )
        }
    }.onSuccess {
        db.logEntryDao().updateSyncStatus(entity.id, SyncStatus.SYNCED)
    }
}
```

In the `PENDING_CREATE` handlers, pass `entity.sortOrder` in `QuickAddRequestDto(...)` and `FoodLogRequestDto(...)`.

- [ ] **Step 15: Add `reorderable` library**

In `gradle/libs.versions.toml`, add under `[versions]`:
```toml
reorderable = "2.4.0"
```

Add under `[libraries]`:
```toml
reorderable = { module = "sh.calvin.reorderable:reorderable", version.ref = "reorderable" }
```

In `app/build.gradle.kts`, add in the `dependencies` block:
```kotlin
implementation(libs.reorderable)
```

- [ ] **Step 16: Update `LogScreen.kt` — add drag-to-reorder UI**

Add import:
```kotlin
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
```

Update `LogContent` signature to add reorder callback:
```kotlin
fun LogContent(
    entries: List<LogEntryWithKcal>,
    onDelete: (LogEntryEntity) -> Unit,
    onEdit: (LogEntryEntity, Int, String?) -> Unit = { _, _, _ -> },
    onReorder: (List<Pair<String, Int>>) -> Unit = {},   // NEW
    modifier: Modifier = Modifier,
    // ... rest unchanged
)
```

In `LogContent`, replace the `LazyColumn` with a reorderable version:

```kotlin
var orderedEntries by remember(entries) { mutableStateOf(entries) }
val listState = rememberLazyListState()
val reorderState = rememberReorderableLazyListState(listState) { from, to ->
    orderedEntries = orderedEntries.toMutableList().apply {
        add(to.index, removeAt(from.index))
    }
}

LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
    items(orderedEntries, key = { it.entry.id }) { ewk ->
        ReorderableItem(reorderState, key = ewk.entry.id) { isDragging ->
            LogEntryRow(
                entry    = ewk,
                onClick  = {
                    if (ewk.entry.quickAddKcal != null) entryToEdit = ewk
                    else entryToDelete = ewk
                },
                isDragging = isDragging,
                dragHandle = {
                    IconButton(
                        modifier = Modifier.draggableHandle(
                            onDragStopped = {
                                val updates = orderedEntries.mapIndexed { i, e -> e.entry.id to i }
                                onReorder(updates)
                            }
                        )
                    ) {
                        Icon(Icons.Default.DragHandle, contentDescription = "Reorder")
                    }
                },
            )
        }
        HorizontalDivider()
    }
}
```

Update `LogEntryRow` to accept drag handle content:
```kotlin
@Composable
private fun LogEntryRow(
    entry: LogEntryWithKcal,
    onClick: () -> Unit,
    isDragging: Boolean = false,
    dragHandle: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        Text(
            text     = entry.displayLabel,
            style    = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text  = "${entry.totalKcal} kcal",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (dragHandle != null) dragHandle()
    }
}
```

Update `LogScreen` to wire `onReorder` through to `LogViewModel`:

In `LogScreen`, add:
```kotlin
onReorder = { updates -> viewModel.reorderEntries(updates) },
```

- [ ] **Step 17: Add `reorderEntries` to `LogViewModel.kt`**

```kotlin
fun reorderEntries(updates: List<Pair<String, Int>>) {
    viewModelScope.launch {
        db.logEntryDao().updateSortOrders(updates)
    }
}
```

Also update `logFromTemplate`, `logFromShortcut`, `logSingleItem`: set `sortOrder = entries.value.size` so new entries append at the end of today's list.

Change the log methods to compute sortOrder:
```kotlin
val nextOrder = entries.value.size
val entity = LogEntryEntity(
    ...,
    sortOrder = nextOrder,
)
```

- [ ] **Step 18: Run all tests**

```
./gradlew :app:test :server:test 2>&1 | tail -15
```
Expected: BUILD SUCCESSFUL on both modules.

- [ ] **Step 19: Commit**

```bash
git add \
  shared/src/commonMain/kotlin/org/branneman/health/LogEntryDto.kt \
  shared/src/commonMain/kotlin/org/branneman/health/QuickAddRequestDto.kt \
  shared/src/commonMain/kotlin/org/branneman/health/FoodLogRequestDto.kt \
  app/src/main/kotlin/org/branneman/health/db/entities/LogEntryEntity.kt \
  app/src/main/kotlin/org/branneman/health/db/HealthDatabase.kt \
  app/src/main/kotlin/org/branneman/health/HealthApplication.kt \
  app/src/main/kotlin/org/branneman/health/db/dao/LogEntryDao.kt \
  app/src/main/kotlin/org/branneman/health/ui/LogScreen.kt \
  app/src/main/kotlin/org/branneman/health/log/LogViewModel.kt \
  app/src/main/kotlin/org/branneman/health/network/HealthApiClient.kt \
  app/src/main/kotlin/org/branneman/health/sync/LogEntrySyncService.kt \
  server/src/main/resources/db/migration/V11__log_entry_sort_order.sql \
  server/src/main/kotlin/org/branneman/health/data/Tables.kt \
  server/src/main/kotlin/org/branneman/health/Application.kt \
  gradle/libs.versions.toml \
  app/build.gradle.kts \
  app/src/test/kotlin/org/branneman/health/TestFactories.kt \
  app/src/test/kotlin/org/branneman/health/db/dao/LogEntryDaoTest.kt \
  server/src/test/kotlin/org/branneman/health/LogEntryIntegrationTest.kt
git commit -m "$(cat <<'EOF'
feat(app,server,shared): drag-to-reorder log entries with server-stored sort order

Adds sortOrder to LogEntryEntity, LogEntryDto, and Postgres log_entry
(V11 migration). Drag handles in the entry list persist order via
PENDING_UPDATE sync. New PATCH /in/log/{id}/sort-order server endpoint
handles food-item entries.
EOF
)"
```

---

## Self-Review

**Spec coverage:**

| Spec requirement | Covered by |
|-----------------|------------|
| Date navigation — HorizontalPager, swipe, date chip, date picker | Task 3 |
| No future access | Task 3 (SelectableDates + page 0 hard stop) |
| Full CRUD on past days | Tasks 3 + 4 |
| loggedAt = noon on effective date for past-day entries | Task 4 |
| "X kcal logged" (no "today") | Task 2 |
| Drag-to-reorder, persistent sortOrder | Task 5 |
| sortOrder in Room + Postgres + DTO | Task 5 |
| Remove per-entry time from UI | Task 2 |
| 04:00 rollover | Task 1 |
| effectiveDateFlow reacts to timezone change | Task 1 |
| No timezone setting needed | addressed in spec (no task needed) |

**Placeholder scan:** No TBDs found.

**Type consistency:**
- `observeForDate(userId: String, datePrefix: String): Flow<List<LogEntryWithKcal>>` — used consistently in Tasks 3 and 5.
- `updateSortOrders(updates: List<Pair<String, Int>>)` — defined in Task 5 DAO, called in Task 5 LogViewModel.
- `setSelectedDate(date: LocalDate)` — defined in Task 3, called in Task 3 App.kt.
- `loggedAtForSelectedDate(): String` — defined in Task 4, called internally in Task 4 LogViewModel.
- `reorderEntries(updates: List<Pair<String, Int>>)` — defined in Task 5 LogViewModel, called in Task 5 LogScreen.
