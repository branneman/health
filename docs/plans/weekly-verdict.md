# Weekly Verdict Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Story 19 (Weekly verdict) — a colored on-track / behind / dropping-fast card on the dashboard, computed client-side from the smoothed weight trend per `docs/specs/weekly-verdict.md`.

**Architecture:** A pure Kotlin verdict function + message formatter in the `app` module (`dashboard/WeeklyVerdict.kt`), fed by a new user-scoped `BodyWeightDao` query, wired into `DashboardViewModel`, rendered as a card in `DashboardScreen` below the daily zone. No server, DTO, or schema changes.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Room, JUnit + Robolectric + Compose test rule.

## Global Constraints

- Conventional commits: `type(scope): message`; scope is `app` for all code tasks here.
- Run `./gradlew :app:test` and confirm `BUILD SUCCESSFUL` before every `git commit`. Never commit on a failed or unrun suite.
- Run `git` directly, never `git -C <path>` (working dir is the repo root).
- No server component in this story — do not touch `server/` or `shared/`.
- Story references in docs use `N (Short-name)` format, e.g. `19 (Weekly verdict)`.
- Spec: `docs/specs/weekly-verdict.md`. Algorithm source of truth: `docs/math-model.md` §3–4.
- Dates in `BodyWeightEntity.date` are ISO strings (`"2026-07-01"`), so lexicographic order equals date order.
- "Today" is always `effectiveDate()` (day rolls over at 04:00), from `org.branneman.health.util.EffectiveDate.kt`.

---

### Task 1: Verdict computation (pure function)

**Files:**
- Create: `app/src/main/kotlin/org/branneman/health/dashboard/WeeklyVerdict.kt`
- Create: `app/src/test/kotlin/org/branneman/health/dashboard/WeeklyVerdictTest.kt`

**Interfaces:**
- Consumes: `BodyWeightEntity` (`org.branneman.health.db.entities`, has `date: String`, `kg: Double`), test factory `aBodyWeightEntry(...)` from `org.branneman.health.TestFactories`.
- Produces: `sealed interface WeeklyVerdict` with members `Green(deltaKg: Double)`, `AmberBehind(deltaKg: Double)`, `AmberFast(deltaKg: Double)`, `GracePeriod`, `NotEnoughData`; and `fun computeWeeklyVerdict(readings: List<BodyWeightEntity>, targetDeficit: Int, today: LocalDate): WeeklyVerdict`. Tasks 2, 4, 5 rely on these exact names.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/org/branneman/health/dashboard/WeeklyVerdictTest.kt`:

```kotlin
package org.branneman.health.dashboard

import org.branneman.health.aBodyWeightEntry
import org.branneman.health.db.entities.BodyWeightEntity
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WeeklyVerdictTest {

    private val today = LocalDate.parse("2026-07-01")

    /**
     * 8 readings today−14 … today−7 at [oldKg], 7 readings today−6 … today at [newKg].
     * So smoothed(today) = newKg, smoothed(today − 7) = oldKg, actual = newKg − oldKg,
     * and both data gates pass (earliest = today − 14; 15 readings in window).
     */
    private fun twoPlateaus(oldKg: Double, newKg: Double): List<BodyWeightEntity> =
        (7..14).map { aBodyWeightEntry(date = today.minusDays(it.toLong()).toString(), kg = oldKg) } +
        (0..6).map { aBodyWeightEntry(date = today.minusDays(it.toLong()).toString(), kg = newKg) }

    // --- Verdict thresholds (math-model §4.3) ---

    @Test fun `loss at target pace is Green`() {
        val v = computeWeeklyVerdict(twoPlateaus(80.3, 80.0), targetDeficit = 300, today = today)
        assertIs<WeeklyVerdict.Green>(v)
        assertEquals(-0.3, v.deltaKg, 0.0001)
    }

    @Test fun `flat week with a deficit target is AmberBehind`() {
        assertIs<WeeklyVerdict.AmberBehind>(computeWeeklyVerdict(twoPlateaus(80.0, 80.0), 300, today))
    }

    @Test fun `weight gain is AmberBehind`() {
        val v = computeWeeklyVerdict(twoPlateaus(80.0, 80.4), 300, today)
        assertIs<WeeklyVerdict.AmberBehind>(v)
        assertEquals(0.4, v.deltaKg, 0.0001)
    }

    @Test fun `dropping more than half a kilo is AmberFast`() {
        val v = computeWeeklyVerdict(twoPlateaus(80.8, 80.0), 300, today)
        assertIs<WeeklyVerdict.AmberFast>(v)
        assertEquals(-0.8, v.deltaKg, 0.0001)
    }

    @Test fun `exactly minus 0 point 5 is Green because the ceiling is inclusive`() {
        // deficit 1375 → E = 1.25, 0.4E = 0.5: −0.5 sits exactly on both boundaries at once
        assertIs<WeeklyVerdict.Green>(computeWeeklyVerdict(twoPlateaus(80.5, 80.0), 1375, today))
    }

    @Test fun `losing slower than 40 percent of target pace is AmberBehind`() {
        // deficit 1375 → 0.4E = 0.5; a 0.4 kg drop misses the green floor
        assertIs<WeeklyVerdict.AmberBehind>(computeWeeklyVerdict(twoPlateaus(80.4, 80.0), 1375, today))
    }

    @Test fun `ceiling beats green floor on a huge deficit`() {
        // deficit 2750 → 0.4E = 1.0 > 0.5 ceiling: Green is unreachable, fast loss stays AmberFast
        assertIs<WeeklyVerdict.AmberFast>(computeWeeklyVerdict(twoPlateaus(80.7, 80.0), 2750, today))
    }

    @Test fun `zero deficit makes flat Green`() {
        assertIs<WeeklyVerdict.Green>(computeWeeklyVerdict(twoPlateaus(80.0, 80.0), 0, today))
    }

    // --- Gates (math-model §4.4, spec decisions 2) ---

    @Test fun `no readings is GracePeriod`() {
        assertIs<WeeklyVerdict.GracePeriod>(computeWeeklyVerdict(emptyList(), 300, today))
    }

    @Test fun `earliest reading 13 days ago is GracePeriod`() {
        val readings = (0..13).map { aBodyWeightEntry(date = today.minusDays(it.toLong()).toString(), kg = 80.0) }
        assertIs<WeeklyVerdict.GracePeriod>(computeWeeklyVerdict(readings, 300, today))
    }

    @Test fun `earliest reading exactly 14 days ago passes the grace gate`() {
        val readings = (0..14).map { aBodyWeightEntry(date = today.minusDays(it.toLong()).toString(), kg = 80.0) }
        assertIs<WeeklyVerdict.AmberBehind>(computeWeeklyVerdict(readings, 300, today))
    }

    @Test fun `four readings in the last 14 days is NotEnoughData`() {
        val readings = listOf(aBodyWeightEntry(date = today.minusDays(20).toString(), kg = 81.0)) +
            (0..3).map { aBodyWeightEntry(date = today.minusDays(it.toLong()).toString(), kg = 80.0) }
        assertIs<WeeklyVerdict.NotEnoughData>(computeWeeklyVerdict(readings, 300, today))
    }

    @Test fun `five readings in the last 14 days is enough`() {
        val readings = listOf(aBodyWeightEntry(date = today.minusDays(20).toString(), kg = 81.0)) +
            (0..4).map { aBodyWeightEntry(date = today.minusDays(it.toLong()).toString(), kg = 80.0) }
        // smoothed(today−7) = 81.0 (only the day-20 reading), smoothed(today) = 481/6 ≈ 80.1667
        val v = computeWeeklyVerdict(readings, 300, today)
        assertIs<WeeklyVerdict.AmberFast>(v)
        assertEquals(-0.8333, v.deltaKg, 0.001)
    }

    @Test fun `in grace with too few readings GracePeriod wins`() {
        val readings = listOf(
            aBodyWeightEntry(date = today.minusDays(5).toString(), kg = 80.0),
            aBodyWeightEntry(date = today.toString(), kg = 80.0),
        )
        assertIs<WeeklyVerdict.GracePeriod>(computeWeeklyVerdict(readings, 300, today))
    }

    // --- SMA endpoints (math-model §3.1, §4.2) ---

    @Test fun `sparse endpoint averages only the readings that exist`() {
        val readings = listOf(
            aBodyWeightEntry(date = today.minusDays(14).toString(), kg = 82.0),
            aBodyWeightEntry(date = today.minusDays(7).toString(), kg = 81.0),
        ) + (0..4).map { aBodyWeightEntry(date = today.minusDays(it.toLong()).toString(), kg = 80.0) }
        // smoothed(today−7) = mean(82.0, 81.0) = 81.5; smoothed(today) = 563/7 ≈ 80.4286
        val v = computeWeeklyVerdict(readings, 300, today)
        assertIs<WeeklyVerdict.AmberFast>(v)
        assertEquals(-1.0714, v.deltaKg, 0.001)
    }

    @Test fun `smoothing window is capped at 7 readings`() {
        // the 90.0 outlier is the 8th-oldest reading on or before today−7 — must be excluded
        val readings = listOf(aBodyWeightEntry(date = today.minusDays(14).toString(), kg = 90.0)) +
            (7..13).map { aBodyWeightEntry(date = today.minusDays(it.toLong()).toString(), kg = 82.0) } +
            (0..6).map { aBodyWeightEntry(date = today.minusDays(it.toLong()).toString(), kg = 80.0) }
        val v = computeWeeklyVerdict(readings, 300, today)
        assertIs<WeeklyVerdict.AmberFast>(v)
        assertEquals(-2.0, v.deltaKg, 0.0001)
    }

    @Test fun `input order does not matter`() {
        assertIs<WeeklyVerdict.Green>(computeWeeklyVerdict(twoPlateaus(80.3, 80.0).shuffled(), 300, today))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "org.branneman.health.dashboard.WeeklyVerdictTest"`
Expected: compilation FAILURE — `WeeklyVerdict` and `computeWeeklyVerdict` are unresolved.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/kotlin/org/branneman/health/dashboard/WeeklyVerdict.kt`:

```kotlin
package org.branneman.health.dashboard

import org.branneman.health.db.entities.BodyWeightEntity
import java.time.LocalDate

sealed interface WeeklyVerdict {
    data class Green(val deltaKg: Double) : WeeklyVerdict
    data class AmberBehind(val deltaKg: Double) : WeeklyVerdict
    data class AmberFast(val deltaKg: Double) : WeeklyVerdict
    data object GracePeriod : WeeklyVerdict
    data object NotEnoughData : WeeklyVerdict
}

// math-model §4: gates first (grace before data sufficiency), then thresholds.
// The grace gate guarantees a reading exists on or before today−14, so both
// SMA endpoints are always computable once gates pass.
fun computeWeeklyVerdict(
    readings: List<BodyWeightEntity>,
    targetDeficit: Int,
    today: LocalDate,
): WeeklyVerdict {
    val sorted = readings.sortedBy { it.date }
    val earliest = sorted.firstOrNull()?.let { LocalDate.parse(it.date) }
    if (earliest == null || earliest.isAfter(today.minusDays(14))) return WeeklyVerdict.GracePeriod
    val inWindow = sorted.count { LocalDate.parse(it.date) >= today.minusDays(14) }
    if (inWindow < 5) return WeeklyVerdict.NotEnoughData

    val actual = smoothed(sorted, today) - smoothed(sorted, today.minusDays(7))
    val expectedWeekly = targetDeficit * 7 / 7700.0
    return when {
        actual < -0.5                     -> WeeklyVerdict.AmberFast(actual)
        actual <= -(expectedWeekly * 0.4) -> WeeklyVerdict.Green(actual)
        else                              -> WeeklyVerdict.AmberBehind(actual)
    }
}

// Mean of the last ≤7 readings on or before endpoint (math-model §3.1).
private fun smoothed(sortedByDate: List<BodyWeightEntity>, endpoint: LocalDate): Double =
    sortedByDate
        .filter { LocalDate.parse(it.date) <= endpoint }
        .takeLast(7)
        .map { it.kg }
        .average()
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "org.branneman.health.dashboard.WeeklyVerdictTest"`
Expected: BUILD SUCCESSFUL, 17 tests pass.

- [ ] **Step 5: Run the full app tier and commit**

Run: `./gradlew :app:test` — expected: BUILD SUCCESSFUL.

```bash
git add app/src/main/kotlin/org/branneman/health/dashboard/WeeklyVerdict.kt app/src/test/kotlin/org/branneman/health/dashboard/WeeklyVerdictTest.kt
git commit -m "feat(app): add weekly verdict calculation"
```

---

### Task 2: Verdict message formatter

**Files:**
- Modify: `app/src/main/kotlin/org/branneman/health/dashboard/WeeklyVerdict.kt` (append)
- Modify: `app/src/test/kotlin/org/branneman/health/dashboard/WeeklyVerdictTest.kt` (append)

**Interfaces:**
- Consumes: `WeeklyVerdict` from Task 1.
- Produces: `fun verdictMessage(verdict: WeeklyVerdict): String`. Task 4 renders this string.

- [ ] **Step 1: Write the failing tests**

Append inside `WeeklyVerdictTest`:

```kotlin
    // --- Messages (spec §Messages) ---

    @Test fun `green message shows delta and on track`() {
        assertEquals("Down 0.3 kg this week — on track.", verdictMessage(WeeklyVerdict.Green(-0.3)))
    }

    @Test fun `flat green reads Flat not Down 0 kg`() {
        assertEquals("Flat this week — on track.", verdictMessage(WeeklyVerdict.Green(0.0)))
        assertEquals("Flat this week — on track.", verdictMessage(WeeklyVerdict.Green(-0.04)))
    }

    @Test fun `amber behind messages cover flat up and down`() {
        assertEquals("Flat this week — slightly behind.", verdictMessage(WeeklyVerdict.AmberBehind(0.0)))
        assertEquals("Up 0.2 kg this week — slightly behind.", verdictMessage(WeeklyVerdict.AmberBehind(0.2)))
        assertEquals("Down 0.1 kg this week — slightly behind.", verdictMessage(WeeklyVerdict.AmberBehind(-0.1)))
    }

    @Test fun `amber fast message warns about intake`() {
        assertEquals(
            "Down 0.8 kg this week — dropping quickly, watch your intake.",
            verdictMessage(WeeklyVerdict.AmberFast(-0.8)),
        )
    }

    @Test fun `delta rounds to one decimal`() {
        assertEquals("Down 0.9 kg this week — dropping quickly, watch your intake.",
            verdictMessage(WeeklyVerdict.AmberFast(-0.86)))
    }

    @Test fun `gate state messages`() {
        assertEquals("Building baseline — keep logging.", verdictMessage(WeeklyVerdict.GracePeriod))
        assertEquals("Not enough weigh-ins for a reliable verdict this week.", verdictMessage(WeeklyVerdict.NotEnoughData))
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "org.branneman.health.dashboard.WeeklyVerdictTest"`
Expected: compilation FAILURE — `verdictMessage` unresolved.

- [ ] **Step 3: Write the implementation**

Append to `WeeklyVerdict.kt`:

```kotlin
fun verdictMessage(verdict: WeeklyVerdict): String = when (verdict) {
    is WeeklyVerdict.Green       -> "${deltaPhrase(verdict.deltaKg)} — on track."
    is WeeklyVerdict.AmberBehind -> "${deltaPhrase(verdict.deltaKg)} — slightly behind."
    is WeeklyVerdict.AmberFast   -> "${deltaPhrase(verdict.deltaKg)} — dropping quickly, watch your intake."
    WeeklyVerdict.GracePeriod    -> "Building baseline — keep logging."
    WeeklyVerdict.NotEnoughData  -> "Not enough weigh-ins for a reliable verdict this week."
}

// |delta| < 0.05 counts as flat (spec §Messages), so a flat trend never reads "Down 0.0 kg".
private fun deltaPhrase(deltaKg: Double): String = when {
    kotlin.math.abs(deltaKg) < 0.05 -> "Flat this week"
    deltaKg < 0                     -> "Down %.1f kg this week".format(-deltaKg)
    else                            -> "Up %.1f kg this week".format(deltaKg)
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "org.branneman.health.dashboard.WeeklyVerdictTest"`
Expected: BUILD SUCCESSFUL, 23 tests pass.

- [ ] **Step 5: Run the full app tier and commit**

Run: `./gradlew :app:test` — expected: BUILD SUCCESSFUL.

```bash
git add app/src/main/kotlin/org/branneman/health/dashboard/WeeklyVerdict.kt app/src/test/kotlin/org/branneman/health/dashboard/WeeklyVerdictTest.kt
git commit -m "feat(app): add weekly verdict messages"
```

---

### Task 3: User-scoped body weight query

**Files:**
- Modify: `app/src/main/kotlin/org/branneman/health/db/dao/BodyWeightDao.kt`
- Modify: `app/src/test/kotlin/org/branneman/health/db/dao/BodyWeightDaoTest.kt` (append)

**Interfaces:**
- Produces: `suspend fun getAllForUser(userId: String): List<BodyWeightEntity>` on `BodyWeightDao`, ordered by date ascending. Task 5 calls it.

- [ ] **Step 1: Write the failing tests**

Append inside `BodyWeightDaoTest` (the class already has `db`, `dao`, `userId`, and imports for `aBodyWeightEntry`, `uuid`, `runTest`, `assertEquals`, `assertTrue`):

```kotlin
    @Test
    fun `getAllForUser returns readings ordered by date ascending`() = runTest {
        dao.upsert(aBodyWeightEntry(userId = userId, date = "2026-06-03", kg = 81.0))
        dao.upsert(aBodyWeightEntry(userId = userId, date = "2026-06-01", kg = 82.0))
        dao.upsert(aBodyWeightEntry(userId = userId, date = "2026-06-02", kg = 81.5))
        val result = dao.getAllForUser(userId)
        assertEquals(listOf("2026-06-01", "2026-06-02", "2026-06-03"), result.map { it.date })
    }

    @Test
    fun `getAllForUser excludes other users`() = runTest {
        dao.upsert(aBodyWeightEntry(userId = userId, date = "2026-06-01", kg = 82.0))
        dao.upsert(aBodyWeightEntry(userId = uuid(), date = "2026-06-02", kg = 70.0))
        val result = dao.getAllForUser(userId)
        assertEquals(1, result.size)
        assertEquals(82.0, result[0].kg)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "org.branneman.health.db.dao.BodyWeightDaoTest"`
Expected: compilation FAILURE — `getAllForUser` unresolved.

- [ ] **Step 3: Write the implementation**

Add to `BodyWeightDao` (after `observeAll()`):

```kotlin
    @Query("SELECT * FROM body_weight WHERE userId = :userId ORDER BY date ASC")
    suspend fun getAllForUser(userId: String): List<BodyWeightEntity>
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "org.branneman.health.db.dao.BodyWeightDaoTest"`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: Run the full app tier and commit**

Run: `./gradlew :app:test` — expected: BUILD SUCCESSFUL.

```bash
git add app/src/main/kotlin/org/branneman/health/db/dao/BodyWeightDao.kt app/src/test/kotlin/org/branneman/health/db/dao/BodyWeightDaoTest.kt
git commit -m "feat(app): add user-scoped body weight query"
```

---

### Task 4: Verdict card UI

**Files:**
- Modify: `app/src/main/kotlin/org/branneman/health/dashboard/DashboardViewModel.kt` (one field on `DashboardUiState`)
- Modify: `app/src/main/kotlin/org/branneman/health/ui/DashboardScreen.kt`
- Modify: `app/src/test/kotlin/org/branneman/health/ui/DashboardScreenTest.kt` (append)

**Interfaces:**
- Consumes: `WeeklyVerdict`, `verdictMessage` (Tasks 1–2); existing `DashboardContent` / `DashboardUiState`.
- Produces: `DashboardUiState.weeklyVerdict: WeeklyVerdict?` (null = hidden; Task 5 sets it) and a `WeeklyVerdictCard` composable with test tags `verdict-green`, `verdict-amber`, `verdict-neutral`.

- [ ] **Step 1: Add the state field**

In `DashboardViewModel.kt`, add to `DashboardUiState` after `actualBurnedSoFar`:

```kotlin
    val weeklyVerdict: WeeklyVerdict? = null,
```

(No import needed — `WeeklyVerdict` is in the same `org.branneman.health.dashboard` package.)

- [ ] **Step 2: Write the failing tests**

Append inside `DashboardScreenTest` (add imports `org.branneman.health.dashboard.WeeklyVerdict` and `androidx.compose.ui.test.onNodeWithTag` if not already covered by the wildcard `androidx.compose.ui.test.*`):

```kotlin
    // --- Weekly verdict card ---

    @Test fun `green verdict shows message on green card`() {
        render(state = DashboardUiState(isLoading = false, weeklyVerdict = WeeklyVerdict.Green(-0.3)))
        compose.onNodeWithText("Down 0.3 kg this week — on track.").assertExists()
        compose.onNodeWithTag("verdict-green").assertExists()
    }

    @Test fun `amber behind verdict shows message on amber card`() {
        render(state = DashboardUiState(isLoading = false, weeklyVerdict = WeeklyVerdict.AmberBehind(0.0)))
        compose.onNodeWithText("Flat this week — slightly behind.").assertExists()
        compose.onNodeWithTag("verdict-amber").assertExists()
    }

    @Test fun `amber fast verdict shows message on amber card`() {
        render(state = DashboardUiState(isLoading = false, weeklyVerdict = WeeklyVerdict.AmberFast(-0.8)))
        compose.onNodeWithText("Down 0.8 kg this week — dropping quickly, watch your intake.").assertExists()
        compose.onNodeWithTag("verdict-amber").assertExists()
    }

    @Test fun `grace period shows neutral card`() {
        render(state = DashboardUiState(isLoading = false, weeklyVerdict = WeeklyVerdict.GracePeriod))
        compose.onNodeWithText("Building baseline — keep logging.").assertExists()
        compose.onNodeWithTag("verdict-neutral").assertExists()
    }

    @Test fun `not enough data shows neutral card`() {
        render(state = DashboardUiState(isLoading = false, weeklyVerdict = WeeklyVerdict.NotEnoughData))
        compose.onNodeWithText("Not enough weigh-ins for a reliable verdict this week.").assertExists()
        compose.onNodeWithTag("verdict-neutral").assertExists()
    }

    @Test fun `no verdict hides the weekly zone`() {
        render(state = DashboardUiState(isLoading = false, weeklyVerdict = null))
        compose.onNodeWithText("This week").assertDoesNotExist()
        compose.onNodeWithTag("verdict-green").assertDoesNotExist()
        compose.onNodeWithTag("verdict-amber").assertDoesNotExist()
        compose.onNodeWithTag("verdict-neutral").assertDoesNotExist()
    }
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "org.branneman.health.ui.DashboardScreenTest"`
Expected: the six new tests FAIL (message/tag nodes not found); existing tests still pass.

- [ ] **Step 4: Write the implementation**

In `DashboardScreen.kt`, add imports:

```kotlin
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import org.branneman.health.dashboard.WeeklyVerdict
import org.branneman.health.dashboard.verdictMessage
```

At the end of the `Column` in `DashboardContent` (after `SportTonightSection(...)`):

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
```

Add the composable (near `BudgetSection`):

```kotlin
@Composable
private fun WeeklyVerdictCard(verdict: WeeklyVerdict) {
    // Verdict states get a colored surface; gate states stay neutral (spec §UI).
    val (container, content, tag) = when (verdict) {
        is WeeklyVerdict.Green ->
            Triple(Color(0xFFC8E6C9), Color(0xFF1B5E20), "verdict-green")
        is WeeklyVerdict.AmberBehind, is WeeklyVerdict.AmberFast ->
            Triple(Color(0xFFFFE0B2), Color(0xFF7A4F01), "verdict-amber")
        WeeklyVerdict.GracePeriod, WeeklyVerdict.NotEnoughData ->
            Triple(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, "verdict-neutral")
    }
    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag),
    ) {
        Text(
            text = verdictMessage(verdict),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "org.branneman.health.ui.DashboardScreenTest"`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 6: Run the full app tier and commit**

Run: `./gradlew :app:test` — expected: BUILD SUCCESSFUL.

```bash
git add app/src/main/kotlin/org/branneman/health/dashboard/DashboardViewModel.kt app/src/main/kotlin/org/branneman/health/ui/DashboardScreen.kt app/src/test/kotlin/org/branneman/health/ui/DashboardScreenTest.kt
git commit -m "feat(app): add weekly verdict card to dashboard"
```

---

### Task 5: Wire verdict into DashboardViewModel

**Files:**
- Modify: `app/src/main/kotlin/org/branneman/health/dashboard/DashboardViewModel.kt`

**Interfaces:**
- Consumes: `computeWeeklyVerdict` (Task 1), `BodyWeightDao.getAllForUser` (Task 3), `DashboardUiState.weeklyVerdict` (Task 4).
- Produces: nothing new — fills the existing field.

- [ ] **Step 1: Compute the verdict in `computeLocalState`**

In `computeLocalState(userId, today)`, after the `params` line, add:

```kotlin
        val verdict = computeWeeklyVerdict(
            readings      = app.db.bodyWeightDao().getAllForUser(userId),
            targetDeficit = profile.targetDeficit,
            today         = LocalDate.parse(today),
        )
```

and add to the returned `DashboardUiState`:

```kotlin
            weeklyVerdict         = verdict,
```

(The server-refresh `onSuccess` block uses `state.copy(...)`, so the verdict survives the network update unchanged. `LocalDate` is already imported.)

- [ ] **Step 2: Recompute after logging a weight**

A new weigh-in changes the SMA endpoint, so the card must not go stale after the user logs weight. Replace the `_uiState.update { it.copy(weightKgToday = kg) }` line at the end of `logWeight` with:

```kotlin
            val profile = app.db.userProfileDao().get()
            val verdict = profile?.let { p ->
                computeWeeklyVerdict(
                    readings      = app.db.bodyWeightDao().getAllForUser(stored.userId),
                    targetDeficit = p.targetDeficit,
                    today         = LocalDate.parse(today),
                )
            }
            _uiState.update { it.copy(weightKgToday = kg, weeklyVerdict = verdict ?: it.weeklyVerdict) }
```

- [ ] **Step 3: Run the full app tier**

Run: `./gradlew :app:test`
Expected: BUILD SUCCESSFUL — all unit, DAO, and screen tests pass.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/dashboard/DashboardViewModel.kt
git commit -m "feat(app): wire weekly verdict into dashboard view model"
```

---

### Task 6: Mark story done in the backlog

**Files:**
- Modify: `docs/feature-backlog.md:39`

- [ ] **Step 1: Update the backlog row**

Change row 19 from:

```markdown
|   | 19 | **Weekly verdict** — on-track / behind / dropping-fast, driven by smoothed weight trend                                                                                         | The honest weekly signal                                             |                                           |
```

to (✓ in the first column, spec link in the last):

```markdown
| ✓ | 19 | **Weekly verdict** — on-track / behind / dropping-fast, driven by smoothed weight trend                                                                                         | The honest weekly signal                                             | [weekly-verdict](specs/weekly-verdict.md) |
```

- [ ] **Step 2: Commit**

Docs-only change — no test tier applies.

```bash
git add docs/feature-backlog.md
git commit -m "docs(backlog): mark 19 (Weekly verdict) done"
```
