# Story 12 Implementation Spec — Dynamic Calorie Budget

**Scope:** What needs to be built to complete story 12. The math model and
dynamic budget formula are already specified in `docs/specs/dynamic-budget.md`
and `docs/specs/math-model.md`. This document is the implementation guide: what
files to create or change, in what order, and what the tests look like.

---

## Context: what's already done

The following groundwork was laid while building other stories:

- `BudgetComputer.computeDynamic()` — fully implemented server-side
- `GET /summary/today` — already computes and returns all 8 dynamic params
- `TodaySummaryDto` and `UserProfileDto` — already have `wakeTime`, `bedtime`,
  `expectedTodaySport/NonSport`, `eatingFractionSport/NonSport`,
  `actualBurnedSoFar`, `postWorkoutModeSport/NonSport`
- `UserProfileEntity` — already has `wakeTime` and `bedtime` fields
- Flyway `V8__wake_bedtime.sql` — already added columns to Postgres `user_profile`
- Onboarding step 4 (wake/bedtime UI, `TimeAdjustRow`, `adjustTime`) — already built

**What this story adds:**
server tests → Room entity + migration → client formula → ViewModel wiring →
dashboard display → settings schedule UI → all required tests.

---

## 1. Server tests

### 1.1 `BudgetComputerTest` additions (Tier 1)

File: `server/src/test/kotlin/org/branneman/health/budget/BudgetComputerTest.kt`

Nine new tests for `computeDynamic` and its helpers. Use inline `HistoricalDay`
lists — no fakes or external data needed:

```kotlin
private fun sportDay(out: Int, caloriesIn: Int? = null) =
    HistoricalDay(LocalDate.of(2026, 1, 1), caloriesOut = out,
                  caloriesIn = caloriesIn, isSportDay = true)
private fun nonSportDay(out: Int, caloriesIn: Int? = null) =
    HistoricalDay(LocalDate.of(2026, 1, 1), caloriesOut = out,
                  caloriesIn = caloriesIn, isSportDay = false)
```

| Test name | Verifies |
|---|---|
| `no history → expected and fraction both null` | Both `expectedTodaySport/NonSport` and both fractions are null |
| `< 5 logged sport days → Approach 1 fraction` | `eatingFractionSport = (expected − D) / expected` |
| `≥ 5 logged sport days → Approach 2 fraction` | `eatingFractionSport = avg_in / avg_out` over all logged days |
| `≥ 10 qualifying sport days → Approach 3 fraction` | Fraction filters to qualifying days |
| `qualifying day boundary passes at +100` | `caloriesIn = expected − D + 100` qualifies |
| `qualifying day boundary fails at +101` | `caloriesIn = expected − D + 101` does not qualify |
| `maintenance D=0 qualifying threshold = expected+100` | `caloriesIn ≤ expected + 100` qualifies |
| `each bucket upgrades independently` | Sport on Approach 3 while non-sport still on Approach 2 |
| `post-workout triggers at 90%` | `actual = 0.9 × expected` → `postWorkoutModeSport = true` |
| `post-workout does not trigger at 89%` | `actual = 0.89 × expected` → `postWorkoutModeSport = false` |
| `post-workout stays off when actualBurnedToday is null` | Both post-workout flags = false |

### 1.2 `DynamicBudgetIntegrationTest` (Tier 2a, slot #10)

New file: `server/src/test/kotlin/org/branneman/health/DynamicBudgetIntegrationTest.kt`

```
UUID:  00000000-0000-0000-0000-000000000010
Email: dynamic-budget-test@test.local
```

Follows the standard `init {}` pattern (delete by both UUID and email, then
insert). Claims slot #10 in the testing-manifesto UUID registry.

**Seed data (inserted once in `companion object { init {} }`):**
- 30 `daily_energy` rows covering the last 30 calendar days (yesterday to 30 days ago)
- 15 of those dates also have a `workout` row (= sport days)
- 10 of the 15 sport days have a `log_entry` quick-add with
  `caloriesIn ≤ expected_sport − D + 100` (qualifying for Approach 3)
- Cleaned per-user by `@Before` (delete by userId)

**Scenarios (`GET /summary/today?date=…` after seeding):**

| Scenario | Additional seed | Assertion |
|---|---|---|
| No Polar history | Profile only (no energy rows) | `expectedTodaySport` is null; `eatingFractionSport` is null |
| 5 logged sport days | 5 energy rows with log entries in bucket | Approach 2 fraction returned (non-null) |
| 10 qualifying sport days | Full 30-day seed | Approach 3 fraction returned |
| `wakeTime`/`bedtime` in response | Profile with `wakeTime='06:30'`, `bedtime='22:30'` | Response has `"wakeTime":"06:30"` and `"bedtime":"22:30"` |
| Profile `PUT` round-trip | `PUT /profile` with new times; `GET /profile` | Values match |

Update `docs/specs/testing-manifesto.md` UUID registry table to add row #10.

---

## 2. Room entity + migration

### 2.1 `DynamicBudgetParamsEntity`

New file: `app/src/main/kotlin/org/branneman/health/db/entities/DynamicBudgetParamsEntity.kt`

```kotlin
@Entity(tableName = "dynamic_budget_params")
data class DynamicBudgetParamsEntity(
    @PrimaryKey val date: String,
    val expectedTodaySport: Int?,
    val expectedTodayNonSport: Int?,
    val eatingFractionSport: Double?,
    val eatingFractionNonSport: Double?,
    val postWorkoutModeSport: Boolean,
    val postWorkoutModeNonSport: Boolean,
)
```

### 2.2 `DynamicBudgetParamsDao`

New file: `app/src/main/kotlin/org/branneman/health/db/dao/DynamicBudgetParamsDao.kt`

```kotlin
@Dao
interface DynamicBudgetParamsDao {
    @Upsert
    suspend fun upsert(params: DynamicBudgetParamsEntity)

    @Query("SELECT * FROM dynamic_budget_params WHERE date = :date LIMIT 1")
    suspend fun getForDate(date: String): DynamicBudgetParamsEntity?
}
```

### 2.3 `MIGRATION_4_5` in `HealthApplication.kt`

```kotlin
private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `dynamic_budget_params` (
                `date` TEXT NOT NULL,
                `expectedTodaySport` INTEGER,
                `expectedTodayNonSport` INTEGER,
                `eatingFractionSport` REAL,
                `eatingFractionNonSport` REAL,
                `postWorkoutModeSport` INTEGER NOT NULL DEFAULT 0,
                `postWorkoutModeNonSport` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`date`)
            )
        """)
    }
}
```

Add to `Room.databaseBuilder(…).addMigrations(…, MIGRATION_4_5)`.

### 2.4 `HealthDatabase` update

- Add `DynamicBudgetParamsEntity::class` to the `entities` list
- Bump `version` from 4 to 5
- Add `abstract fun dynamicBudgetParamsDao(): DynamicBudgetParamsDao`

### 2.5 `DynamicBudgetParamsDaoTest` (Tier 2b)

New file: `app/src/test/kotlin/org/branneman/health/db/dao/DynamicBudgetParamsDaoTest.kt`

Pattern: Robolectric + in-memory Room (matches existing DAO tests). One test:

```
insert DynamicBudgetParamsEntity for "2026-06-15" with
  expectedTodaySport = 2400, eatingFractionNonSport = 0.82,
  postWorkoutModeSport = true (and reasonable nulls/values for other fields)
→ getForDate("2026-06-15")
→ assert all fields match
```

---

## 3. Client-side formula

### 3.1 `computeDynamicCaloriesLeft` (pure function)

Add to `app/src/main/kotlin/org/branneman/health/dashboard/DashboardViewModel.kt`
alongside the existing `computeSportEstimate` and `isValidWeightInput` functions.

```kotlin
fun computeDynamicCaloriesLeft(
    wakeTimeStr: String,      // "HH:mm"
    bedtimeStr: String,       // "HH:mm"
    targetDeficit: Int,
    expectedToday: Int,       // for the active bucket; caller resolves fallback
    eatingFraction: Double,   // for the active bucket; caller resolves fallback
    burnedSoFar: Int?,        // today's Polar totalKcal; null → estimated from time
    caloriesIn: Int,
    postWorkoutMode: Boolean,
    nowMinutes: Int,          // minutes since midnight (injectable for testing)
): Int
```

`nowMinutes` is the injectable clock; callers pass `LocalTime.now().hour * 60 + LocalTime.now().minute`.

**Formula (from `docs/specs/dynamic-budget.md §3`):**

Parse `wakeTimeStr` / `bedtimeStr` to minutes-since-midnight:
```
wakeMinutes = HH * 60 + mm
bedMinutes  = HH * 60 + mm
total_awake = bedMinutes - wakeMinutes   // clamp to > 0
elapsed     = clamp(nowMinutes - wakeMinutes, 0, total_awake)
```

If `postWorkoutMode`:
```
return (expectedToday * eatingFraction - targetDeficit - caloriesIn).toInt()
```
Negative values are valid and preserved — the display layer shows `−X kcal` when negative.

Normal mode:
```
burned_so_far  = burnedSoFar ?: (expectedToday * elapsed / total_awake)
remaining_burn = expectedToday - burned_so_far
allowance_so_far = burned_so_far * eatingFraction
                   - targetDeficit * elapsed / total_awake
overshoot     = max(0.0, caloriesIn - allowance_so_far)
deficit_remaining = targetDeficit * (total_awake - elapsed) / total_awake
calories_left = remaining_burn * eatingFraction - deficit_remaining - overshoot
return calories_left.toInt()
```

**Fallback resolution (caller's responsibility, in ViewModel):**

When `expectedToday` is null for the active bucket:
- If sport tonight and `sportTonight.estimatedKcal` is set: use `sportTonight.estimatedKcal`
- Otherwise: use `caloriesOut` from the last resolved value (BMR estimate or Polar yesterday)

When `eatingFraction` is null for the active bucket:
- Use `max(0.0, (expectedToday - targetDeficit).toDouble() / expectedToday)`

This resolution happens in the ViewModel before calling `computeDynamicCaloriesLeft`.

### 3.2 `DashboardLogicTest` additions (Tier 1)

Add to the existing `DashboardLogicTest.kt`:

| Test | Pins |
|---|---|
| `normal mode mid-day = expected result` | hand-calculated value against formula |
| `post-workout mode uses simple formula` | switches to `expected × fraction − D − in` |
| `overshoot when eating ahead of pace` | `caloriesIn > allowance_so_far` reduces `caloriesLeft` |
| `under-eating gives no bonus` | `caloriesIn < allowance_so_far` → overshoot = 0 (not negative) |
| `null burnedSoFar uses time-fraction estimate` | `burnedSoFar = expected × elapsed / total` |
| `at wake time returns full day budget` | `elapsed = 0` → `caloriesLeft = expected × fraction − D` |
| `at bedtime returns consumed budget` | `elapsed = total` → time-decay exhausted |
| `maintenance D=0` | no deficit deducted; result = `expected × fraction − in` |
| `negative result when over budget` | returned value is negative |

---

## 4. DashboardViewModel wiring

### 4.1 `DashboardUiState` changes + existing test updates

**Rename note:** All existing `DashboardScreenTest` tests construct
`DashboardUiState(adjustedBudgetRemaining = X)`. When `adjustedBudgetRemaining`
is removed and replaced by `caloriesLeft`, those test callsites must be updated
to `caloriesLeft = X`. This is mechanical — do it as part of step 8 (display
changes) in the implementation order.



```kotlin
data class DashboardUiState(
    val isLoading: Boolean = true,
    val caloriesIn: Int = 0,
    val caloriesOut: Int = 0,           // kept for the "in/out" secondary row
    val caloriesOutSource: String = "estimate",
    val targetDeficit: Int = 0,
    val caloriesLeft: Int = 0,          // replaces adjustedBudgetRemaining
    val budgetLabel: String = "left (estimated)",
    val sportTonight: SportTonightEntity? = null,
    val weightKgToday: Double? = null,
    // dynamic params (for formula on next tick):
    val expectedTodaySport: Int? = null,
    val expectedTodayNonSport: Int? = null,
    val eatingFractionSport: Double? = null,
    val eatingFractionNonSport: Double? = null,
    val postWorkoutModeSport: Boolean = false,
    val postWorkoutModeNonSport: Boolean = false,
    val wakeTime: String = "07:00",
    val bedtime: String = "23:00",
)
```

`budgetRemaining` and `adjustedBudgetRemaining` are removed. `caloriesLeft`
is the single number shown on the dashboard.

### 4.2 ViewModel coroutine structure

On init (replacing the existing `init` block):
```kotlin
init {
    viewModelScope.launch { observeLogEntries() }
    viewModelScope.launch { load() }
    viewModelScope.launch { tickBudget() }
}
```

`tickBudget()`:
```kotlin
private suspend fun tickBudget() {
    while (true) {
        refreshCaloriesLeft()
        delay(60.seconds)
    }
}
```

`refreshCaloriesLeft()`: reads current `_uiState.value`, resolves fallbacks, calls
`computeDynamicCaloriesLeft(nowMinutes = LocalTime.now().toMinutes())`, and
updates `_uiState` with new `caloriesLeft` and `budgetLabel`.

### 4.3 `load()` changes

After the server response:
1. Store dynamic params to Room: `app.db.dynamicBudgetParamsDao().upsert(DynamicBudgetParamsEntity(today, …))`
2. Update `_uiState` with all dynamic params + existing fields
3. Call `refreshCaloriesLeft()` immediately

### 4.4 `observeLogEntries()` changes

Remove the inline budget computation (was: `budget = caloriesOut − D − caloriesIn`).
Instead, after updating `caloriesIn` in state, call `refreshCaloriesLeft()`.

### 4.5 `setSportTonight()` / `clearSportTonight()`

After the DAO write and state update, call `refreshCaloriesLeft()`.

### 4.6 `computeLocalState()` changes

This offline-first initializer reads `DynamicBudgetParamsEntity` for today from
Room (if available) and populates all dynamic param fields. `refreshCaloriesLeft()`
then computes `caloriesLeft` from those params.

**Fallback when no params are cached yet** (e.g. first launch after an app update
before the next server sync): fall back to the static formula
(`caloriesOut − targetDeficit − caloriesIn`) and set `budgetLabel = "left (estimated)"`.
This avoids showing `0` while the first sync is in flight.

### 4.7 `budgetLabel` computation (in `refreshCaloriesLeft()`)

```
val isSportTonight  = _uiState.value.sportTonight != null
val fraction = if (isSportTonight) state.eatingFractionSport
               else state.eatingFractionNonSport
val estimated = (fraction == null) || (state.caloriesOutSource == "estimate")

budgetLabel = when {
    newCaloriesLeft < 0           -> "kcal over"
    state.targetDeficit == 0      -> "left (balance)"
    estimated                     -> "left (estimated)"
    else                          -> "left"
}
```

---

## 5. Dashboard display + Settings schedule

### 5.1 `DashboardScreen` changes

`BudgetSection`:
- Replace `state.adjustedBudgetRemaining` with `state.caloriesLeft`
- Replace inline `sourceLabel` computation with `state.budgetLabel`
- Remove the separate "in/out includes sport estimate" label (sport is now baked in)

The sport-tonight section itself (`SportTonightSection`) is unchanged — it still
shows activity/intensity chips. The MET-estimate label ("~600 kcal") is removed;
only the activity type and intensity chips remain.

### 5.2 `DashboardScreenTest` additions (Tier 2b)

Add three tests to the existing `DashboardScreenTest.kt`:

```kotlin
@Test fun `shows estimated label when budgetLabel is 'left (estimated)'`() {
    render(state = DashboardUiState(isLoading = false, caloriesLeft = 1400,
                                   budgetLabel = "left (estimated)"))
    compose.onNodeWithText("estimated", substring = true, ignoreCase = true).assertExists()
}

@Test fun `shows balance label when budgetLabel is 'left (balance)'`() {
    render(state = DashboardUiState(isLoading = false, caloriesLeft = 1200,
                                   budgetLabel = "left (balance)"))
    compose.onNodeWithText("balance", substring = true, ignoreCase = true).assertExists()
}

@Test fun `shows negative number when over budget`() {
    render(state = DashboardUiState(isLoading = false, caloriesLeft = -150,
                                   budgetLabel = "kcal over"))
    compose.onNodeWithText("-150", substring = true).assertExists()
    compose.onNodeWithText("kcal over", substring = true, ignoreCase = true).assertExists()
}
```

### 5.3 `TimeAdjustRow` visibility change

In `OnboardingScreen.kt`, change `TimeAdjustRow` from `private` to `internal`.
Both `OnboardingScreen.kt` and `SettingsScreen.kt` live in `org.branneman.health.ui`,
and `internal` in Kotlin means "same Gradle module" — so this makes it accessible
from Settings without moving the function.

(`adjustTime` is already `internal`.)

### 5.4 `SettingsContent` — Schedule section

Add after the Polar block in `SettingsContent`, before the nav buttons:

```
HorizontalDivider
Text("Schedule", style = bodyMedium)
TimeAdjustRow(
    label   = "Wake time",
    time    = wakeTime,
    onMinus = { onWakeTimeChange(adjustTime(wakeTime, -30)) },
    onPlus  = { onWakeTimeChange(adjustTime(wakeTime, +30)) },
)
TimeAdjustRow(
    label   = "Bedtime",
    time    = bedtime,
    onMinus = { onBedtimeChange(adjustTime(bedtime, -30)) },
    onPlus  = { onBedtimeChange(adjustTime(bedtime, +30)) },
)
if (scheduleChanged) {
    TextButton(onClick = onSaveSchedule, enabled = !isSavingSchedule) {
        Text(if (isSavingSchedule) "Saving…" else "Save schedule")
    }
    saveScheduleError?.let { Text(it, color = error) }
}
```

New parameters on `SettingsContent`:
```kotlin
wakeTime: String = "07:00",
bedtime: String = "23:00",
scheduleChanged: Boolean = false,
isSavingSchedule: Boolean = false,
saveScheduleError: String? = null,
onWakeTimeChange: (String) -> Unit = {},
onBedtimeChange: (String) -> Unit = {},
onSaveSchedule: () -> Unit = {},
```

### 5.5 `SettingsViewModel` — Schedule state

Add a `ScheduleState` data class and `_scheduleState: MutableStateFlow<ScheduleState>`:

```kotlin
data class ScheduleState(
    val wakeTime: String = "07:00",
    val bedtime: String = "23:00",
    val savedWakeTime: String = "07:00",   // original from Room
    val savedBedtime: String = "23:00",
    val isSaving: Boolean = false,
    val saveError: String? = null,
) {
    val changed: Boolean get() = wakeTime != savedWakeTime || bedtime != savedBedtime
}
```

On `init {}`: load from Room (`userProfileDao().get()`) and set `wakeTime`, `bedtime`,
`savedWakeTime`, `savedBedtime` from the profile entity.

`fun updateWakeTime(t: String)` / `fun updateBedtime(t: String)`: update local state only.

`fun saveSchedule()`:
1. Set `isSaving = true`
2. Read the current full profile from Room
3. Build a `UserProfileDto` with updated `wakeTime`/`bedtime`, other fields unchanged
4. Call `PUT /profile` via `apiClient`
5. On success: upsert to Room; update `savedWakeTime`/`savedBedtime`
6. On failure: set `saveError = "Couldn't reach the server…"`

`SettingsScreen` wires `viewModel.scheduleState` into `SettingsContent`'s new parameters.

### 5.6 `SettingsScreenTest` additions (Tier 2b)

Add four tests to the existing `SettingsScreenTest.kt`:

```kotlin
@Test fun `schedule section shows Wake time and Bedtime labels`()
@Test fun `tapping + on Wake time calls onWakeTimeChange with +30 min`()
@Test fun `Save schedule button visible when scheduleChanged = true`()
@Test fun `Save schedule button is disabled while isSavingSchedule = true`()
```

---

## 6. Remaining app tests

### 6.1 `OnboardingScreenTest` additions (Tier 2b)

Add two tests to the existing `OnboardingScreenTest.kt`:

```kotlin
private fun renderStep4(
    state: OnboardingUiState = OnboardingUiState(
        sex = "male", heightCm = "177", currentWeightKg = "84.0",
        goalWeightKg = "74.0", age = "39",
    ),
    onUpdate: (OnboardingUiState.() -> OnboardingUiState) -> Unit = {},
    onBack: () -> Unit = {},
    onSave: () -> Unit = {},
) {
    compose.setContent {
        OnboardingStep4(state = state, onUpdate = onUpdate, onBack = onBack, onSave = onSave)
    }
}

@Test fun `step 4 shows Wake time and Bedtime labels`() {
    renderStep4()
    compose.onNodeWithText("Wake time", substring = true).assertExists()
    compose.onNodeWithText("Bedtime", substring = true).assertExists()
}

@Test fun `step 4 tapping + on Wake time increments by 30 min`() {
    var captured: OnboardingUiState? = null
    renderStep4(
        state    = OnboardingUiState(wakeTime = "07:00"),
        onUpdate = { block -> captured = OnboardingUiState(wakeTime = "07:00").block() },
    )
    compose.onAllNodesWithText("+").onFirst().performClick()
    assertEquals("07:30", captured?.wakeTime)
}
```

### 6.2 `UserProfileDaoTest` additions (Tier 2b)

Add one test to the existing `UserProfileDaoTest.kt`:

```kotlin
@Test fun `wakeTime and bedtime round-trip through upsert`() {
    val profile = aUserProfile(wakeTime = "06:30", bedtime = "22:00")
    runBlocking { dao.upsert(profile) }
    val loaded = runBlocking { dao.get() }
    assertEquals("06:30", loaded?.wakeTime)
    assertEquals("22:00", loaded?.bedtime)
}
```

### 6.3 `TestFactories.kt` additions

Add `wakeTime` and `bedtime` overrides to `aUserProfile(...)`:

```kotlin
fun aUserProfile(
    // … existing params …
    wakeTime: String = "07:00",
    bedtime: String = "23:00",
) = UserProfileEntity(…, wakeTime = wakeTime, bedtime = bedtime, …)
```

---

## 7. Testing-manifesto update

Add row #10 to the UUID registry in `docs/specs/testing-manifesto.md`:

| 10 | `...000010` | `DynamicBudgetIntegrationTest` | `dynamic-budget-test@test.local` |

---

## 8. Implementation order

Write tests before implementation at each layer (TDD):

1. `BudgetComputerTest` additions → run → all pass (logic already exists)
2. `DynamicBudgetIntegrationTest` → run → all pass (endpoint already exists)
3. `DynamicBudgetParamsEntity` + `DynamicBudgetParamsDao` + `MIGRATION_4_5` + `HealthDatabase v5`
4. `DynamicBudgetParamsDaoTest` → run → passes
5. `UserProfileDaoTest` additions → run → passes (entity already has fields)
6. `computeDynamicCaloriesLeft` pure function + `DashboardLogicTest` additions (write test first)
7. `DashboardUiState` refactor + `DashboardViewModel` wiring + `refreshCaloriesLeft()`
8. `DashboardScreen` display changes (replace `adjustedBudgetRemaining` → `caloriesLeft`, `budgetLabel`)
9. `DashboardScreenTest` additions → run → passes
10. `TimeAdjustRow` → `internal`; `SettingsContent` Schedule section; `SettingsViewModel` schedule state
11. `SettingsScreenTest` additions → run → passes
12. `OnboardingScreenTest` step 4 additions → run → passes
13. `TestFactories.kt` update
14. Testing-manifesto UUID registry update

---

## 9. Out of scope for story 12

- Widget (reads Room; will use the same `DynamicBudgetParamsEntity` later — story 20)
- `DashboardLogicTest` coverage of `budgetLabel` computation (it's simple conditional logic; covered by the `DashboardScreenTest` label assertions)
- Server-side `MealTemplatesIntegrationTest` — not affected by this story
