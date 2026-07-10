# Weight Trend Chart Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a 7-day-smoothed weight trend line chart to the dashboard, with a
range selector (1W/1M/3M/6M/1Y/ALL) and a goal-weight reference line, per story 20
(Weight trend chart) in `docs/feature-backlog.md`.

**Architecture:** Entirely client-side (app module), no server/DTO/schema changes.
Extract the existing 7-day SMA function out of `WeeklyVerdict.kt` into a shared
`WeightSmoothing.kt` so both the weekly verdict and the trend chart use the same
calculation. A new pure-calc file `WeightTrend.kt` builds the smoothed point series,
confidence gate, and range availability. `DashboardViewModel` computes this once
alongside the existing verdict and exposes it in `DashboardUiState`. A new
hand-rolled Compose `Canvas` composable, `WeightTrendChart.kt`, renders the line,
raw-dot underlay, goal line, and range buttons, wired into `DashboardScreen.kt`
directly below the existing `WeeklyVerdictCard`.

**Tech Stack:** Kotlin, Jetpack Compose (Canvas/Path — no new charting dependency),
Room (existing `body_weight` table, no migration), JUnit + `kotlin.test` for Tier 1,
Robolectric + `ComposeTestRule` for Tier 2b.

## Global Constraints

- No server, DTO, or schema changes — this story is 100% app-local (per spec decision 1).
- No new Gradle dependency — hand-rolled Canvas rendering (per spec decision 9).
- Confidence gate (`NONE`/`PARTIAL`/`FULL`) is evaluated once over the whole reading
  history; range buttons only crop which points are visible (per spec decision 3).
- `1W`, `1M`, `ALL` always available; `3M`/`6M`/`1Y` appear only once history spans
  more than 90/180/365 days respectively, measured earliest-reading-date to today
  (per spec decision 4).
- Default selected range on load: `MONTH` (per spec decision 5).
- Raw dots are cropped to the selected range, not always shown in full (per spec decision 6).
- Goal line renders whenever a chart renders at all, independent of confidence
  (per spec decision 7).
- IDs are always UUIDs — not touched by this story, but no new integer IDs anywhere.
- Conventional commits: `type(scope): message`, scope `app` for every commit in this plan.
- Run `./gradlew :app:test` before every commit (this plan touches app/shared only).

Full spec: `docs/specs/weight-trend-chart.md`.

---

### Task 1: Extract shared 7-day SMA function into `WeightSmoothing.kt`

**Files:**
- Create: `app/src/main/kotlin/org/branneman/health/dashboard/WeightSmoothing.kt`
- Modify: `app/src/main/kotlin/org/branneman/health/dashboard/WeeklyVerdict.kt:37-43`
- Test: `app/src/test/kotlin/org/branneman/health/dashboard/WeeklyVerdictTest.kt` (unchanged — must still pass)

**Interfaces:**
- Produces: `internal fun smoothed(sortedByDate: List<BodyWeightEntity>, endpoint: LocalDate): Double` —
  consumed by both `WeeklyVerdict.kt` (Task 1) and `WeightTrend.kt` (Task 2).

This is a pure refactor — no behavior change, no new test. The existing
`WeeklyVerdictTest.kt` suite is the regression guard.

- [ ] **Step 1: Run the existing test suite to confirm it's green before refactoring**

Run: `./gradlew :app:test --tests "org.branneman.health.dashboard.WeeklyVerdictTest"`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 2: Create `WeightSmoothing.kt` with the extracted function**

```kotlin
package org.branneman.health.dashboard

import org.branneman.health.db.entities.BodyWeightEntity
import java.time.LocalDate

// Mean of the last ≤7 readings on or before endpoint (math-model §3.1).
// Shared by WeeklyVerdict (story 19) and WeightTrend (story 20) so both stay
// provably consistent — never reimplement this independently.
internal fun smoothed(sortedByDate: List<BodyWeightEntity>, endpoint: LocalDate): Double =
    sortedByDate
        .filter { LocalDate.parse(it.date) <= endpoint }
        .takeLast(7)
        .map { it.kg }
        .average()
```

- [ ] **Step 3: Remove the now-duplicate private function from `WeeklyVerdict.kt`**

Delete these lines from `WeeklyVerdict.kt` (currently lines 37-43):

```kotlin
// Mean of the last ≤7 readings on or before endpoint (math-model §3.1).
private fun smoothed(sortedByDate: List<BodyWeightEntity>, endpoint: LocalDate): Double =
    sortedByDate
        .filter { LocalDate.parse(it.date) <= endpoint }
        .takeLast(7)
        .map { it.kg }
        .average()
```

`computeWeeklyVerdict` in the same file already calls `smoothed(sorted, today)` and
`smoothed(sorted, today.minusDays(7))` — since the extracted function lives in the
same package (`org.branneman.health.dashboard`), no import is needed and no other
line in `WeeklyVerdict.kt` changes.

- [ ] **Step 4: Run the test suite again to confirm the refactor didn't break anything**

Run: `./gradlew :app:test --tests "org.branneman.health.dashboard.WeeklyVerdictTest"`
Expected: BUILD SUCCESSFUL, same tests pass as Step 1.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/dashboard/WeightSmoothing.kt \
        app/src/main/kotlin/org/branneman/health/dashboard/WeeklyVerdict.kt
git commit -m "refactor(app): extract shared 7-day SMA into WeightSmoothing"
```

---

### Task 2: `WeightTrend.kt` — confidence gate, point series, range availability

**Files:**
- Create: `app/src/main/kotlin/org/branneman/health/dashboard/WeightTrend.kt`
- Test: `app/src/test/kotlin/org/branneman/health/dashboard/WeightTrendTest.kt`

**Interfaces:**
- Consumes: `internal fun smoothed(sortedByDate: List<BodyWeightEntity>, endpoint: LocalDate): Double` (Task 1).
  `org.branneman.health.aBodyWeightEntry(...)` test factory (existing, in `app/src/test/kotlin/org/branneman/health/TestFactories.kt`).
- Produces (consumed by Task 3 — `DashboardViewModel` — and Task 4 — `WeightTrendChart`):
  - `enum class TrendConfidence { NONE, PARTIAL, FULL }`
  - `enum class TrendRange(val days: Long?) { WEEK(7), MONTH(30), THREE_MONTHS(90), SIX_MONTHS(180), YEAR(365), ALL(null) }`
  - `data class WeightTrendPoint(val date: LocalDate, val smoothedKg: Double, val rawKg: Double)`
  - `data class WeightTrendData(val points: List<WeightTrendPoint>, val confidence: TrendConfidence, val availableRanges: Set<TrendRange>)`
  - `fun computeWeightTrend(readings: List<BodyWeightEntity>, today: LocalDate): WeightTrendData`
  - `fun filterToRange(points: List<WeightTrendPoint>, range: TrendRange, today: LocalDate): List<WeightTrendPoint>`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/org/branneman/health/dashboard/WeightTrendTest.kt`:

```kotlin
package org.branneman.health.dashboard

import org.branneman.health.aBodyWeightEntry
import org.branneman.health.db.entities.BodyWeightEntity
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeightTrendTest {

    private val today = LocalDate.parse("2026-07-01")

    private fun readingsOverDays(count: Int, startDaysAgo: Int = 0, kg: Double = 80.0): List<BodyWeightEntity> =
        (0 until count).map {
            aBodyWeightEntry(date = today.minusDays((startDaysAgo + it).toLong()).toString(), kg = kg)
        }

    // --- Confidence gate (math-model §3.2, spec decision 3) ---

    @Test fun `two readings is NONE confidence with no points`() {
        val result = computeWeightTrend(readingsOverDays(2), today)
        assertEquals(TrendConfidence.NONE, result.confidence)
        assertTrue(result.points.isEmpty())
    }

    @Test fun `three readings is PARTIAL confidence`() {
        assertEquals(TrendConfidence.PARTIAL, computeWeightTrend(readingsOverDays(3), today).confidence)
    }

    @Test fun `six readings is still PARTIAL confidence`() {
        assertEquals(TrendConfidence.PARTIAL, computeWeightTrend(readingsOverDays(6), today).confidence)
    }

    @Test fun `seven readings is FULL confidence`() {
        assertEquals(TrendConfidence.FULL, computeWeightTrend(readingsOverDays(7), today).confidence)
    }

    // --- Points: one per reading date, using the shared SMA function ---

    @Test fun `one point per reading date with matching smoothed value`() {
        val readings = readingsOverDays(7, kg = 80.0)
        val result = computeWeightTrend(readings, today)
        assertEquals(7, result.points.size)
        result.points.forEach { assertEquals(80.0, it.smoothedKg, 0.0001) }
    }

    @Test fun `raw kg is the reading's own value even when smoothed differs`() {
        // 6 readings at 80.0 on days -6..-1, then one at 86.0 today:
        // smoothed(today) = mean of the last 7 = (6*80 + 86) / 7
        val readings = readingsOverDays(6, startDaysAgo = 1, kg = 80.0) +
            listOf(aBodyWeightEntry(date = today.toString(), kg = 86.0))
        val result = computeWeightTrend(readings, today)
        val last = result.points.last()
        assertEquals(today, last.date)
        assertEquals(86.0, last.rawKg, 0.0001)
        assertEquals((6 * 80.0 + 86.0) / 7, last.smoothedKg, 0.0001)
    }

    // --- availableRanges (spec decision 4) ---

    @Test fun `short history only offers week month and all`() {
        val result = computeWeightTrend(readingsOverDays(7), today)
        assertEquals(setOf(TrendRange.WEEK, TrendRange.MONTH, TrendRange.ALL), result.availableRanges)
    }

    @Test fun `history spanning exactly 90 days does not yet offer 3 months`() {
        val readings = listOf(aBodyWeightEntry(date = today.minusDays(90).toString(), kg = 80.0)) + readingsOverDays(7)
        val ranges = computeWeightTrend(readings, today).availableRanges
        assertEquals(setOf(TrendRange.WEEK, TrendRange.MONTH, TrendRange.ALL), ranges)
    }

    @Test fun `history spanning 91 days offers 3 months but not 6`() {
        val readings = listOf(aBodyWeightEntry(date = today.minusDays(91).toString(), kg = 80.0)) + readingsOverDays(7)
        val ranges = computeWeightTrend(readings, today).availableRanges
        assertTrue(TrendRange.THREE_MONTHS in ranges)
        assertTrue(TrendRange.SIX_MONTHS !in ranges)
    }

    @Test fun `history spanning 181 days offers 6 months but not a year`() {
        val readings = listOf(aBodyWeightEntry(date = today.minusDays(181).toString(), kg = 80.0)) + readingsOverDays(7)
        val ranges = computeWeightTrend(readings, today).availableRanges
        assertTrue(TrendRange.SIX_MONTHS in ranges)
        assertTrue(TrendRange.YEAR !in ranges)
    }

    @Test fun `history spanning 366 days offers a year`() {
        val readings = listOf(aBodyWeightEntry(date = today.minusDays(366).toString(), kg = 80.0)) + readingsOverDays(7)
        assertTrue(TrendRange.YEAR in computeWeightTrend(readings, today).availableRanges)
    }

    // --- filterToRange (spec decision 6) ---

    @Test fun `filterToRange excludes points before the window`() {
        val points = (0..10).map {
            WeightTrendPoint(date = today.minusDays(it.toLong()), smoothedKg = 80.0, rawKg = 80.0)
        }
        val filtered = filterToRange(points, TrendRange.WEEK, today)
        assertEquals(8, filtered.size) // today-0 .. today-7 inclusive
        assertTrue(filtered.all { !it.date.isBefore(today.minusDays(7)) })
    }

    @Test fun `filterToRange ALL returns every point`() {
        val points = (0..400).map {
            WeightTrendPoint(date = today.minusDays(it.toLong()), smoothedKg = 80.0, rawKg = 80.0)
        }
        assertEquals(points.size, filterToRange(points, TrendRange.ALL, today).size)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail (function doesn't exist yet)**

Run: `./gradlew :app:test --tests "org.branneman.health.dashboard.WeightTrendTest"`
Expected: FAIL with unresolved reference `computeWeightTrend` (compile error).

- [ ] **Step 3: Implement `WeightTrend.kt`**

Create `app/src/main/kotlin/org/branneman/health/dashboard/WeightTrend.kt`:

```kotlin
package org.branneman.health.dashboard

import org.branneman.health.db.entities.BodyWeightEntity
import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class TrendConfidence { NONE, PARTIAL, FULL }

enum class TrendRange(val days: Long?) {
    WEEK(7), MONTH(30), THREE_MONTHS(90), SIX_MONTHS(180), YEAR(365), ALL(null)
}

data class WeightTrendPoint(
    val date: LocalDate,
    val smoothedKg: Double,
    val rawKg: Double,
)

data class WeightTrendData(
    val points: List<WeightTrendPoint>,
    val confidence: TrendConfidence,
    val availableRanges: Set<TrendRange>,
)

// math-model §3.2 gate, evaluated once over the whole history (spec decision 3).
fun computeWeightTrend(readings: List<BodyWeightEntity>, today: LocalDate): WeightTrendData {
    val sorted = readings.sortedBy { it.date }

    if (sorted.size < 3) {
        return WeightTrendData(
            points = emptyList(),
            confidence = TrendConfidence.NONE,
            availableRanges = setOf(TrendRange.WEEK, TrendRange.MONTH, TrendRange.ALL),
        )
    }

    val confidence = if (sorted.size >= 7) TrendConfidence.FULL else TrendConfidence.PARTIAL

    val points = sorted.map { reading ->
        val date = LocalDate.parse(reading.date)
        WeightTrendPoint(
            date = date,
            smoothedKg = smoothed(sorted, date),
            rawKg = reading.kg,
        )
    }

    val earliest = LocalDate.parse(sorted.first().date)
    val spanDays = ChronoUnit.DAYS.between(earliest, today)
    val availableRanges = buildSet {
        add(TrendRange.WEEK)
        add(TrendRange.MONTH)
        add(TrendRange.ALL)
        if (spanDays > 90) add(TrendRange.THREE_MONTHS)
        if (spanDays > 180) add(TrendRange.SIX_MONTHS)
        if (spanDays > 365) add(TrendRange.YEAR)
    }

    return WeightTrendData(points, confidence, availableRanges)
}

// Range buttons crop the already-computed series; they never re-run the gate (spec decision 3/6).
fun filterToRange(points: List<WeightTrendPoint>, range: TrendRange, today: LocalDate): List<WeightTrendPoint> =
    if (range.days == null) points
    else points.filter { !it.date.isBefore(today.minusDays(range.days)) }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:test --tests "org.branneman.health.dashboard.WeightTrendTest"`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/dashboard/WeightTrend.kt \
        app/src/test/kotlin/org/branneman/health/dashboard/WeightTrendTest.kt
git commit -m "feat(app): add weight trend calculation with range availability"
```

---

### Task 3: Wire trend data into `DashboardViewModel`

**Files:**
- Modify: `app/src/main/kotlin/org/branneman/health/dashboard/DashboardViewModel.kt:30-44` (`DashboardUiState`)
- Modify: `app/src/main/kotlin/org/branneman/health/dashboard/DashboardViewModel.kt:178-216` (`computeLocalState`)
- Modify: `app/src/main/kotlin/org/branneman/health/dashboard/DashboardViewModel.kt:245-266` (`logWeight`)

**Interfaces:**
- Consumes: `computeWeightTrend(readings: List<BodyWeightEntity>, today: LocalDate): WeightTrendData` (Task 2),
  `BodyWeightDao.getAllForUser(userId: String): List<BodyWeightEntity>` (existing),
  `UserProfileEntity.goalWeightKg: Double` (existing).
- Produces (consumed by Task 5 — `DashboardScreen`):
  - `DashboardUiState.weightTrend: WeightTrendData?`
  - `DashboardUiState.selectedTrendRange: TrendRange` (default `TrendRange.MONTH`)
  - `DashboardUiState.goalWeightKg: Double?`
  - `DashboardViewModel.selectTrendRange(range: TrendRange)`

No dedicated ViewModel test exists for `DashboardViewModel` in this codebase (it's
Android-`Application`-backed and validated indirectly through `DashboardScreenTest`
with fake state, per existing convention) — this task has no new test file. Task 5's
`DashboardScreenTest` cases exercise the state shape this task produces.

- [ ] **Step 1: Add fields to `DashboardUiState`**

In `DashboardViewModel.kt`, change:

```kotlin
data class DashboardUiState(
    val isLoading: Boolean = true,
    val caloriesIn: Int = 0,
    val caloriesOut: Int = 0,
    val caloriesOutSource: String = "estimate",
    val targetDeficit: Int = 0,
    val caloriesLeft: Int = 0,
    val budgetLabel: String = "left (estimated)",
    val sportTonight: SportTonightEntity? = null,
    val weightKgToday: Double? = null,
    val expectedTodaySport: Int? = null,
    val expectedTodayNonSport: Int? = null,
    val actualBurnedSoFar: Int? = null,
    val weeklyVerdict: WeeklyVerdict? = null,
)
```

to:

```kotlin
data class DashboardUiState(
    val isLoading: Boolean = true,
    val caloriesIn: Int = 0,
    val caloriesOut: Int = 0,
    val caloriesOutSource: String = "estimate",
    val targetDeficit: Int = 0,
    val caloriesLeft: Int = 0,
    val budgetLabel: String = "left (estimated)",
    val sportTonight: SportTonightEntity? = null,
    val weightKgToday: Double? = null,
    val expectedTodaySport: Int? = null,
    val expectedTodayNonSport: Int? = null,
    val actualBurnedSoFar: Int? = null,
    val weeklyVerdict: WeeklyVerdict? = null,
    val weightTrend: WeightTrendData? = null,
    val selectedTrendRange: TrendRange = TrendRange.MONTH,
    val goalWeightKg: Double? = null,
)
```

- [ ] **Step 2: Compute the trend in `computeLocalState`, reusing the verdict's reading list**

Change:

```kotlin
        val verdict = computeWeeklyVerdict(
            readings      = app.db.bodyWeightDao().getAllForUser(userId),
            targetDeficit = profile.targetDeficit,
            today         = LocalDate.parse(today),
        )
```

to:

```kotlin
        val bodyWeightReadings = app.db.bodyWeightDao().getAllForUser(userId)
        val verdict = computeWeeklyVerdict(
            readings      = bodyWeightReadings,
            targetDeficit = profile.targetDeficit,
            today         = LocalDate.parse(today),
        )
        val trend = computeWeightTrend(bodyWeightReadings, LocalDate.parse(today))
```

Then in the `DashboardUiState(...)` construction later in the same function, add the
two new fields:

```kotlin
            weeklyVerdict         = verdict,
```

becomes:

```kotlin
            weeklyVerdict         = verdict,
            weightTrend           = trend,
            goalWeightKg          = profile.goalWeightKg,
```

- [ ] **Step 3: Recompute the trend in `logWeight`, and add `selectTrendRange`**

Change:

```kotlin
    fun logWeight(kg: Double) {
        viewModelScope.launch {
            val stored = tokenStore.tokenFlow.first() ?: return@launch
            val today = effectiveDate().toString()
            app.db.bodyWeightDao().upsert(
                BodyWeightEntity(
                    id         = today,
                    userId     = stored.userId,
                    date       = today,
                    kg         = kg,
                    syncStatus = SyncStatus.PENDING_CREATE,
                )
            )
            val profile = app.db.userProfileDao().get()
            val verdict = profile?.let { p ->
                computeWeeklyVerdict(
                    readings      = app.db.bodyWeightDao().getAllForUser(stored.userId),
                    targetDeficit = p.targetDeficit,
                    today         = LocalDate.parse(today),
                )
            }
            _uiState.update { it.copy(weightKgToday = kg, weeklyVerdict = verdict ?: it.weeklyVerdict) }
        }
    }
}
```

to:

```kotlin
    fun logWeight(kg: Double) {
        viewModelScope.launch {
            val stored = tokenStore.tokenFlow.first() ?: return@launch
            val today = effectiveDate().toString()
            app.db.bodyWeightDao().upsert(
                BodyWeightEntity(
                    id         = today,
                    userId     = stored.userId,
                    date       = today,
                    kg         = kg,
                    syncStatus = SyncStatus.PENDING_CREATE,
                )
            )
            val profile = app.db.userProfileDao().get()
            val readings = app.db.bodyWeightDao().getAllForUser(stored.userId)
            val verdict = profile?.let { p ->
                computeWeeklyVerdict(
                    readings      = readings,
                    targetDeficit = p.targetDeficit,
                    today         = LocalDate.parse(today),
                )
            }
            val trend = computeWeightTrend(readings, LocalDate.parse(today))
            _uiState.update {
                it.copy(
                    weightKgToday = kg,
                    weeklyVerdict = verdict ?: it.weeklyVerdict,
                    weightTrend   = trend,
                )
            }
        }
    }

    fun selectTrendRange(range: TrendRange) {
        _uiState.update { it.copy(selectedTrendRange = range) }
    }
}
```

(Note the closing brace of the class moves from `logWeight` to `selectTrendRange`.)

- [ ] **Step 4: Compile and run the full app test suite**

Run: `./gradlew :app:test`
Expected: BUILD SUCCESSFUL. (No new tests in this task; this confirms the ViewModel
still compiles and nothing downstream broke — `DashboardScreenTest` still passes
since `DashboardContent`/`DashboardScreen` aren't touched yet.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/dashboard/DashboardViewModel.kt
git commit -m "feat(app): compute weight trend alongside weekly verdict"
```

---

### Task 4: `WeightTrendChart` composable

**Files:**
- Create: `app/src/main/kotlin/org/branneman/health/ui/WeightTrendChart.kt`

**Interfaces:**
- Consumes: `WeightTrendData`, `TrendConfidence`, `TrendRange`, `WeightTrendPoint`,
  `filterToRange(...)` (Task 2); `org.branneman.health.util.effectiveDate()` (existing).
- Produces (consumed by Task 5 — `DashboardScreen`):
  ```kotlin
  @Composable
  fun WeightTrendChart(
      trend: WeightTrendData,
      selectedRange: TrendRange,
      goalWeightKg: Double?,
      onSelectRange: (TrendRange) -> Unit,
      today: LocalDate = effectiveDate(),
  )
  ```
  Test tags: `"trend-empty-message"` (empty-state text), `"trend-chart-canvas"`
  (the Canvas), `"trend-range-<LABEL>"` per button (e.g. `"trend-range-1W"`).

This composable's interaction behavior (empty state, which range buttons render,
tapping a button) is exercised in Task 5's `DashboardScreenTest` additions — per the
testing manifesto, Compose UI tests assert behavior, not pixels, so the Canvas
drawing itself has no dedicated test here.

- [ ] **Step 1: Implement the composable**

Create `app/src/main/kotlin/org/branneman/health/ui/WeightTrendChart.kt`:

```kotlin
package org.branneman.health.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.branneman.health.dashboard.TrendConfidence
import org.branneman.health.dashboard.TrendRange
import org.branneman.health.dashboard.WeightTrendData
import org.branneman.health.dashboard.WeightTrendPoint
import org.branneman.health.dashboard.filterToRange
import org.branneman.health.util.effectiveDate
import java.time.LocalDate

private val rangeOrder = listOf(
    TrendRange.WEEK, TrendRange.MONTH, TrendRange.THREE_MONTHS,
    TrendRange.SIX_MONTHS, TrendRange.YEAR, TrendRange.ALL,
)

private val rangeLabels = mapOf(
    TrendRange.WEEK to "1W",
    TrendRange.MONTH to "1M",
    TrendRange.THREE_MONTHS to "3M",
    TrendRange.SIX_MONTHS to "6M",
    TrendRange.YEAR to "1Y",
    TrendRange.ALL to "ALL",
)

@Composable
fun WeightTrendChart(
    trend: WeightTrendData,
    selectedRange: TrendRange,
    goalWeightKg: Double?,
    onSelectRange: (TrendRange) -> Unit,
    today: LocalDate = effectiveDate(),
) {
    if (trend.confidence == TrendConfidence.NONE) {
        Text(
            text = "Log a few more weigh-ins to see your trend.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("trend-empty-message"),
        )
        return
    }

    val visiblePoints = filterToRange(trend.points, selectedRange, today)

    Column(modifier = Modifier.fillMaxWidth()) {
        WeightTrendCanvas(
            points = visiblePoints,
            dashed = trend.confidence == TrendConfidence.PARTIAL,
            goalWeightKg = goalWeightKg,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            rangeOrder.filter { it in trend.availableRanges }.forEach { range ->
                val label = rangeLabels.getValue(range)
                FilterChip(
                    selected = range == selectedRange,
                    onClick = { onSelectRange(range) },
                    label = { Text(label) },
                    modifier = Modifier.testTag("trend-range-$label"),
                )
            }
        }
    }
}

@Composable
private fun WeightTrendCanvas(
    points: List<WeightTrendPoint>,
    dashed: Boolean,
    goalWeightKg: Double?,
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val dotColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    val goalColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .testTag("trend-chart-canvas"),
    ) {
        if (points.isEmpty()) return@Canvas

        // Honest, un-truncated axis: pad slightly past the visible data's own range
        // (math-model §3.3 / dashboard UX chart conventions — never exaggerate movement).
        val allValues = points.flatMap { listOf(it.smoothedKg, it.rawKg) } + listOfNotNull(goalWeightKg)
        val rawMin = allValues.min()
        val rawMax = allValues.max()
        val padding = ((rawMax - rawMin).takeIf { it > 0 } ?: 1.0) * 0.1
        val minKg = rawMin - padding
        val maxKg = rawMax + padding
        val kgRange = (maxKg - minKg).takeIf { it > 0 } ?: 1.0

        fun yFor(kg: Double): Float = (size.height * (1 - (kg - minKg) / kgRange)).toFloat()
        fun xFor(index: Int): Float =
            if (points.size == 1) size.width / 2f
            else size.width * (index.toFloat() / (points.size - 1))

        goalWeightKg?.let { goal ->
            drawLine(
                color = goalColor,
                start = Offset(0f, yFor(goal)),
                end = Offset(size.width, yFor(goal)),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)),
            )
        }

        points.forEachIndexed { i, point ->
            drawCircle(color = dotColor, radius = 4f, center = Offset(xFor(i), yFor(point.rawKg)))
        }

        val path = Path().apply {
            points.forEachIndexed { i, point ->
                val x = xFor(i)
                val y = yFor(point.smoothedKg)
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(
                width = 6f,
                cap = StrokeCap.Round,
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(20f, 12f)) else null,
            ),
        )
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/ui/WeightTrendChart.kt
git commit -m "feat(app): add hand-rolled weight trend chart composable"
```

---

### Task 5: Wire `WeightTrendChart` into `DashboardScreen`

**Files:**
- Modify: `app/src/main/kotlin/org/branneman/health/ui/DashboardScreen.kt:27-79`
- Modify: `app/src/test/kotlin/org/branneman/health/ui/DashboardScreenTest.kt`

**Interfaces:**
- Consumes: `WeightTrendChart(...)` (Task 4), `DashboardUiState.weightTrend` /
  `.selectedTrendRange` / `.goalWeightKg` (Task 3), `DashboardViewModel.selectTrendRange` (Task 3).

- [ ] **Step 1: Write the failing tests**

In `app/src/test/kotlin/org/branneman/health/ui/DashboardScreenTest.kt`, add the
imports and update the `render` helper:

```kotlin
import org.branneman.health.dashboard.TrendConfidence
import org.branneman.health.dashboard.TrendRange
import org.branneman.health.dashboard.WeightTrendData
import org.branneman.health.dashboard.WeightTrendPoint
import java.time.LocalDate
```

Change:

```kotlin
    private fun render(
        state: DashboardUiState = DashboardUiState(),
        onSetSportTonight: (String, String) -> Unit = { _, _ -> },
        onClearSportTonight: () -> Unit = {},
        onLogWeight: (Double) -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                DashboardContent(
                    state = state,
                    onSetSportTonight = onSetSportTonight,
                    onClearSportTonight = onClearSportTonight,
                    onLogWeight = onLogWeight,
                )
            }
        }
    }
```

to:

```kotlin
    private fun render(
        state: DashboardUiState = DashboardUiState(),
        onSetSportTonight: (String, String) -> Unit = { _, _ -> },
        onClearSportTonight: () -> Unit = {},
        onLogWeight: (Double) -> Unit = {},
        onSelectTrendRange: (TrendRange) -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                DashboardContent(
                    state = state,
                    onSetSportTonight = onSetSportTonight,
                    onClearSportTonight = onClearSportTonight,
                    onLogWeight = onLogWeight,
                    onSelectTrendRange = onSelectTrendRange,
                )
            }
        }
    }
```

Add these test cases after the existing "--- Weekly verdict card ---" section (after
the `no verdict hides the weekly zone` test):

```kotlin
    // --- Weight trend chart ---

    private fun fullConfidenceTrend(availableRanges: Set<TrendRange> = setOf(TrendRange.WEEK, TrendRange.MONTH, TrendRange.ALL)) =
        WeightTrendData(
            points = listOf(WeightTrendPoint(date = LocalDate.parse("2026-07-01"), smoothedKg = 80.0, rawKg = 80.0)),
            confidence = TrendConfidence.FULL,
            availableRanges = availableRanges,
        )

    @Test fun `no readings shows trend empty message and no range buttons`() {
        render(state = DashboardUiState(
            isLoading = false,
            weightTrend = WeightTrendData(emptyList(), TrendConfidence.NONE, setOf(TrendRange.WEEK, TrendRange.MONTH, TrendRange.ALL)),
        ))
        compose.onNodeWithTag("trend-empty-message").assertExists()
        compose.onNodeWithTag("trend-range-1W").assertDoesNotExist()
    }

    @Test fun `only available ranges render as buttons`() {
        render(state = DashboardUiState(isLoading = false, weightTrend = fullConfidenceTrend()))
        compose.onNodeWithTag("trend-range-1W").assertExists()
        compose.onNodeWithTag("trend-range-1M").assertExists()
        compose.onNodeWithTag("trend-range-ALL").assertExists()
        compose.onNodeWithTag("trend-range-3M").assertDoesNotExist()
    }

    @Test fun `three months button renders once available`() {
        render(state = DashboardUiState(
            isLoading = false,
            weightTrend = fullConfidenceTrend(setOf(TrendRange.WEEK, TrendRange.MONTH, TrendRange.THREE_MONTHS, TrendRange.ALL)),
        ))
        compose.onNodeWithTag("trend-range-3M").assertExists()
    }

    @Test fun `tapping a range button calls onSelectTrendRange`() {
        var selected: TrendRange? = null
        render(
            state = DashboardUiState(isLoading = false, weightTrend = fullConfidenceTrend()),
            onSelectTrendRange = { selected = it },
        )
        compose.onNodeWithTag("trend-range-1W").performClick()
        assertEquals(TrendRange.WEEK, selected)
    }

    @Test fun `no trend data hides the chart entirely`() {
        render(state = DashboardUiState(isLoading = false, weightTrend = null))
        compose.onNodeWithTag("trend-empty-message").assertDoesNotExist()
        compose.onNodeWithTag("trend-chart-canvas").assertDoesNotExist()
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:test --tests "org.branneman.health.ui.DashboardScreenTest"`
Expected: FAIL — compile error (`onSelectTrendRange` param doesn't exist on
`DashboardContent` yet, `trend-empty-message`/`trend-range-*` tags don't exist).

- [ ] **Step 3: Wire the composable into `DashboardScreen.kt`**

Add imports:

```kotlin
import org.branneman.health.dashboard.TrendRange
```

Change `DashboardScreen`:

```kotlin
@Composable
fun DashboardScreen(viewModel: DashboardViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DashboardContent(
        state = state,
        onSetSportTonight = viewModel::setSportTonight,
        onClearSportTonight = viewModel::clearSportTonight,
        onLogWeight = viewModel::logWeight,
    )
}
```

to:

```kotlin
@Composable
fun DashboardScreen(viewModel: DashboardViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DashboardContent(
        state = state,
        onSetSportTonight = viewModel::setSportTonight,
        onClearSportTonight = viewModel::clearSportTonight,
        onLogWeight = viewModel::logWeight,
        onSelectTrendRange = viewModel::selectTrendRange,
    )
}
```

Change `DashboardContent`'s signature and body:

```kotlin
@Composable
fun DashboardContent(
    state: DashboardUiState,
    onSetSportTonight: (String, String) -> Unit,
    onClearSportTonight: () -> Unit,
    onLogWeight: (Double) -> Unit,
) {
```

to:

```kotlin
@Composable
fun DashboardContent(
    state: DashboardUiState,
    onSetSportTonight: (String, String) -> Unit,
    onClearSportTonight: () -> Unit,
    onLogWeight: (Double) -> Unit,
    onSelectTrendRange: (TrendRange) -> Unit,
) {
```

Change the "This week" block:

```kotlin
        state.weeklyVerdict?.let { verdict ->
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text(
                text = "This week",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            WeeklyVerdictCard(verdict)
        }
    }
}
```

to:

```kotlin
        state.weeklyVerdict?.let { verdict ->
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text(
                text = "This week",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            WeeklyVerdictCard(verdict)
        }
        state.weightTrend?.let { trend ->
            Spacer(Modifier.height(16.dp))
            WeightTrendChart(
                trend = trend,
                selectedRange = state.selectedTrendRange,
                goalWeightKg = state.goalWeightKg,
                onSelectRange = onSelectTrendRange,
            )
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:test --tests "org.branneman.health.ui.DashboardScreenTest"`
Expected: BUILD SUCCESSFUL, all tests pass (existing + new).

- [ ] **Step 5: Run the full app test suite**

Run: `./gradlew :app:test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/ui/DashboardScreen.kt \
        app/src/test/kotlin/org/branneman/health/ui/DashboardScreenTest.kt
git commit -m "feat(app): wire weight trend chart into dashboard"
```

---

### Task 6: Manual verification on device/emulator

Per CLAUDE.md: "the user installs and uses the app after every story" — this story
must be genuinely usable in isolation before it's considered done.

**Files:** none (verification only).

- [ ] **Step 1: Build and install the debug app**

Run: `./gradlew :app:installDebug` (device/emulator connected).

- [ ] **Step 2: Drive the feature**

Open the app, go to the dashboard. Confirm:
- With < 3 weigh-ins logged: the "Log a few more weigh-ins to see your trend."
  message shows, no chart, no range buttons.
- With ≥ 3 weigh-ins: the chart renders with a bold (dashed if 3-6 readings, solid
  if ≥7) trend line, faint raw dots, and a goal-weight reference line.
- Range buttons show only the ones history supports (a fresh account shows just
  `1W 1M ALL`); tapping one changes the visible window without a reload flicker.
- Log a new weigh-in via the existing weight chip and confirm the chart updates
  (new point appears, line recalculates) without navigating away.

- [ ] **Step 3: Update the backlog**

In `docs/feature-backlog.md`, mark story 20 done (`✓` in the first column), matching
the convention used for stories 1-19.

- [ ] **Step 4: Commit**

```bash
git add docs/feature-backlog.md
git commit -m "docs(backlog): mark 20 (Weight trend chart) done"
```
