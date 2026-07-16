# Remove "Set sport tonight" Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the local-only "Set sport tonight" toggle end-to-end (client UI, ViewModel, Room, server bucketing, DTO, docs) and simplify the daily-budget estimate to a single 30-day average, per `docs/specs/remove-sport-tonight.md`.

**Architecture:** This is a removal, not a rebuild. `BudgetComputer.computeDynamic` collapses from two averages (sport/non-sport) to one (`expectedToday`). That ripples through `TodaySummaryDto` (shared), the `/summary/today` endpoint, the Room `dynamic_budget_params` cache, and `DashboardViewModel`/`DashboardScreen`. The `sport_tonight` Room table and its entity/DAO/UI are deleted outright.

**Tech Stack:** Kotlin, Ktor (server), Room (client), Jetpack Compose (client), kotlinx.serialization (shared DTOs), JUnit/Robolectric (app tests), Ktor test + Exposed (server tests).

## Global Constraints

- Run `./gradlew :server:test` after any server-module change; run `./gradlew :app:test` after any app-module change. Confirm `BUILD SUCCESSFUL` before each commit (per `CLAUDE.md`).
- Only commit once, at the very end (Task 7), after all tests pass — the user asked for the spec + plan + all removal changes to land in one commit together, and since `shared`'s `TodaySummaryDto` is consumed by both `server` and `app`, intermediate commits that touch the DTO shape without updating both consumers would leave `main` non-compiling for one of the two modules.
- Conventional commit format: `type(scope): message`. This is a cross-module removal (client + server + shared + docs) with one clear theme, so use topic scope: `fix(dashboard): remove Set sport tonight toggle` (type `fix` because it corrects an anti-pattern, not `feat`).
- Do not touch `docs/ux/0-context-private.md` — it's a private user-research snapshot explaining *why* the feature was originally proposed; it's historical record, not living documentation of current behavior.
- Do not touch `docs/plans/weekly-verdict.md` / `docs/plans/weight-trend-chart.md` — they're frozen point-in-time plan snapshots that happen to show old `DashboardScreen`/`DashboardViewModel` signatures; editing them would misrepresent history.
- Do not touch `docs/feature-backlog.md` — Story 6's row describes the dashboard daily zone as a whole (budget remaining, calories in/out), which mostly remains; it never named the sport-tonight toggle specifically.

---

### Task 1: Server — collapse sport/non-sport split into a single average

**Files:**
- Modify: `server/src/main/kotlin/org/branneman/health/budget/BudgetComputer.kt`
- Modify: `server/src/test/kotlin/org/branneman/health/budget/BudgetComputerTest.kt`

**Interfaces:**
- Produces: `HistoricalDay(date: LocalDate, caloriesOut: Int)` (no `isSportDay`), `DynamicBudgetParams(expectedToday: Int?, actualBurnedSoFar: Int?)`, `BudgetComputer.computeDynamic(history: List<HistoricalDay>, actualBurnedToday: Int?): DynamicBudgetParams`. Task 2 consumes these exact names.

- [ ] **Step 1: Rewrite `BudgetComputer.kt`**

Replace the full file contents with:

```kotlin
package org.branneman.health.budget

import java.time.LocalDate

data class UserProfileInput(
    val heightCm: Int,
    val birthYear: Int,
    val sex: String,
    val activityLevel: String,
    val targetDeficit: Int,
    val goalWeightKg: Double,
)

data class EnergyRow(val date: LocalDate, val totalKcal: Int)

data class HistoricalDay(
    val date: LocalDate,
    val caloriesOut: Int,
)

data class BudgetResult(
    val caloriesIn: Int,
    val caloriesOut: Int,
    val budgetRemaining: Int,
    val targetDeficit: Int,
    val caloriesOutSource: String,
)

data class DynamicBudgetParams(
    val expectedToday: Int?,
    val actualBurnedSoFar: Int?,
)

fun computeBmr(sex: String, weightKg: Double, heightCm: Int, age: Int): Double {
    val base = 10.0 * weightKg + 6.25 * heightCm - 5.0 * age
    return if (sex == "male") base + 5.0 else base - 161.0
}

fun activityMultiplier(level: String): Double = when (level) {
    "sedentary"         -> 1.20
    "lightly_active"    -> 1.375
    "moderately_active" -> 1.55
    else                -> 1.375
}

object BudgetComputer {

    fun compute(
        today: LocalDate,
        profile: UserProfileInput,
        latestWeightKg: Double?,
        energyRows: List<EnergyRow>,
        caloriesIn: Int,
    ): BudgetResult {
        val (caloriesOut, source) = resolveCaloriesOut(today, profile, latestWeightKg, energyRows)
        return BudgetResult(
            caloriesIn        = caloriesIn,
            caloriesOut       = caloriesOut,
            budgetRemaining   = caloriesOut - profile.targetDeficit - caloriesIn,
            targetDeficit     = profile.targetDeficit,
            caloriesOutSource = source,
        )
    }

    fun computeDynamic(
        history: List<HistoricalDay>,
        actualBurnedToday: Int?,
    ): DynamicBudgetParams {
        return DynamicBudgetParams(
            expectedToday     = computeExpected(history),
            actualBurnedSoFar = actualBurnedToday,
        )
    }

    internal fun computeExpected(history: List<HistoricalDay>): Int? {
        if (history.isEmpty()) return null
        return history.sumOf { it.caloriesOut } / history.size
    }

    private fun resolveCaloriesOut(
        today: LocalDate,
        profile: UserProfileInput,
        latestWeightKg: Double?,
        energyRows: List<EnergyRow>,
    ): Pair<Int, String> {
        energyRows.firstOrNull { it.date == today }
            ?.let { return it.totalKcal to "polar_today" }
        energyRows.firstOrNull { it.date == today.minusDays(1) }
            ?.let { return it.totalKcal to "polar_yesterday" }
        val weightKg = latestWeightKg ?: profile.goalWeightKg
        val age = today.year - profile.birthYear
        val tdee = (computeBmr(profile.sex, weightKg, profile.heightCm, age) * activityMultiplier(profile.activityLevel)).toInt()
        return tdee to "estimate"
    }
}
```

- [ ] **Step 2: Update `BudgetComputerTest.kt`'s `computeDynamic` section**

Replace this block (the `day()` helper and the five `computeDynamic` tests, currently right after the `budgetRemaining` tests):

```kotlin
    // --- computeDynamic ---

    private fun day(i: Int, out: Int, isSport: Boolean) =
        HistoricalDay(date = today.minusDays(i.toLong()), caloriesOut = out, isSportDay = isSport)

    @Test fun `no history returns expectedTodaySport and expectedTodayNonSport are null`() {
        val r = BudgetComputer.computeDynamic(emptyList(), actualBurnedToday = null)
        assertNull(r.expectedTodaySport)
        assertNull(r.expectedTodayNonSport)
    }

    @Test fun `expectedTodaySport = avg of sport-day calories-out`() {
        val history = (1..4).map { day(it, out = 2400, isSport = true) }
        val r = BudgetComputer.computeDynamic(history, actualBurnedToday = null)
        assertEquals(2400, r.expectedTodaySport)
    }

    @Test fun `expectedTodayNonSport = avg of non-sport-day calories-out`() {
        val history = (1..4).map { day(it, out = 2400, isSport = false) }
        val r = BudgetComputer.computeDynamic(history, actualBurnedToday = null)
        assertEquals(2400, r.expectedTodayNonSport)
    }

    @Test fun `actualBurnedSoFar is passed through from actualBurnedToday`() {
        val r = BudgetComputer.computeDynamic(emptyList(), actualBurnedToday = 1800)
        assertEquals(1800, r.actualBurnedSoFar)
    }

    @Test fun `non-sport expected is independent of sport history`() {
        val sportDays = (1..10).map { day(it, out = 2400, isSport = true) }
        val r = BudgetComputer.computeDynamic(sportDays, actualBurnedToday = null)
        // 10 sport days → sport expected = 2400; no non-sport days → null
        assertEquals(2400, r.expectedTodaySport)
        assertNull(r.expectedTodayNonSport)
    }
}
```

with:

```kotlin
    // --- computeDynamic ---

    private fun day(i: Int, out: Int) =
        HistoricalDay(date = today.minusDays(i.toLong()), caloriesOut = out)

    @Test fun `no history returns expectedToday is null`() {
        val r = BudgetComputer.computeDynamic(emptyList(), actualBurnedToday = null)
        assertNull(r.expectedToday)
    }

    @Test fun `expectedToday = avg of history calories-out`() {
        val history = listOf(day(1, 2400), day(2, 2000), day(3, 2200), day(4, 2200))
        val r = BudgetComputer.computeDynamic(history, actualBurnedToday = null)
        assertEquals(2200, r.expectedToday)
    }

    @Test fun `actualBurnedSoFar is passed through from actualBurnedToday`() {
        val r = BudgetComputer.computeDynamic(emptyList(), actualBurnedToday = 1800)
        assertEquals(1800, r.actualBurnedSoFar)
    }
}
```

- [ ] **Step 3: Run the server unit tests**

Run: `./gradlew :server:test --tests "org.branneman.health.budget.BudgetComputerTest"`
Expected: `BUILD SUCCESSFUL`, all tests in the class pass.

(Do not commit yet — see Global Constraints.)

---

### Task 2: Server + shared — single `expectedToday` field on the DTO and endpoint

**Files:**
- Modify: `shared/src/commonMain/kotlin/org/branneman/health/TodaySummaryDto.kt`
- Modify: `server/src/main/kotlin/org/branneman/health/Application.kt:830-904` (the `/summary/today` handler)
- Modify: `server/src/test/kotlin/org/branneman/health/DynamicBudgetIntegrationTest.kt`

**Interfaces:**
- Consumes: `BudgetComputer.computeDynamic`, `HistoricalDay` from Task 1.
- Produces: `TodaySummaryDto(date, caloriesIn, caloriesOut, budgetRemaining, targetDeficit, caloriesOutSource, expectedToday: Int? = null, actualBurnedSoFar: Int? = null)`. Task 4 (client) consumes this exact shape.

- [ ] **Step 1: Update `TodaySummaryDto.kt`**

Replace the full file contents with:

```kotlin
package org.branneman.health

import kotlinx.serialization.Serializable

@Serializable
data class TodaySummaryDto(
    val date: String,
    val caloriesIn: Int,
    val caloriesOut: Int,
    val budgetRemaining: Int,
    val targetDeficit: Int,
    val caloriesOutSource: String,
    val expectedToday: Int? = null,
    val actualBurnedSoFar: Int? = null,
)
```

- [ ] **Step 2: Simplify the history-building block in `Application.kt`**

In the `/summary/today` handler, replace:

```kotlin
                    val historyEnergy = DailyEnergy.selectAll()
                        .where {
                            (DailyEnergy.userId eq userId) and
                            (DailyEnergy.date greaterEq historyStart) and
                            (DailyEnergy.date lessEq historyEnd)
                        }
                        .associate { it[DailyEnergy.date] to it[DailyEnergy.totalKcal] }

                    val sportDates = Workout.selectAll()
                        .where {
                            (Workout.userId eq userId) and
                            (Workout.date greaterEq historyStart) and
                            (Workout.date lessEq historyEnd)
                        }
                        .map { it[Workout.date] }
                        .toSet()

                    val history = historyEnergy.map { (date, out) ->
                        HistoricalDay(
                            date        = date,
                            caloriesOut = out,
                            isSportDay  = date in sportDates,
                        )
                    }
```

with:

```kotlin
                    val history = DailyEnergy.selectAll()
                        .where {
                            (DailyEnergy.userId eq userId) and
                            (DailyEnergy.date greaterEq historyStart) and
                            (DailyEnergy.date lessEq historyEnd)
                        }
                        .map { HistoricalDay(date = it[DailyEnergy.date], caloriesOut = it[DailyEnergy.totalKcal]) }
```

- [ ] **Step 3: Update the `TodaySummaryDto` construction in `Application.kt`**

Replace:

```kotlin
                    TodaySummaryDto(
                        date                  = today.toString(),
                        caloriesIn            = budget.caloriesIn,
                        caloriesOut           = budget.caloriesOut,
                        budgetRemaining       = budget.budgetRemaining,
                        targetDeficit         = budget.targetDeficit,
                        caloriesOutSource     = budget.caloriesOutSource,
                        expectedTodaySport    = dynamic.expectedTodaySport,
                        expectedTodayNonSport = dynamic.expectedTodayNonSport,
                        actualBurnedSoFar     = dynamic.actualBurnedSoFar,
                    )
```

with:

```kotlin
                    TodaySummaryDto(
                        date              = today.toString(),
                        caloriesIn        = budget.caloriesIn,
                        caloriesOut       = budget.caloriesOut,
                        budgetRemaining   = budget.budgetRemaining,
                        targetDeficit     = budget.targetDeficit,
                        caloriesOutSource = budget.caloriesOutSource,
                        expectedToday     = dynamic.expectedToday,
                        actualBurnedSoFar = dynamic.actualBurnedSoFar,
                    )
```

`Workout` and its import stay — the table is still used by the unrelated `/workouts` endpoint earlier in the same file.

- [ ] **Step 4: Update `DynamicBudgetIntegrationTest.kt`**

Replace the two dynamic-budget tests:

```kotlin
    @Test fun `no history - dynamic params are null`() = appTest {
        val token = login()
        val today = LocalDate.now().toString()
        val r = client.get("/summary/today?date=$today") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals(JsonNull, body["expectedTodaySport"])
        assertEquals(JsonNull, body["expectedTodayNonSport"])
        assertEquals(JsonNull, body["actualBurnedSoFar"])
        // removed fields must be absent from the JSON entirely
        assertFalse(body.containsKey("eatingFractionSport"))
        assertFalse(body.containsKey("eatingFractionNonSport"))
        assertFalse(body.containsKey("postWorkoutModeSport"))
        assertFalse(body.containsKey("postWorkoutModeNonSport"))
        assertFalse(body.containsKey("wakeTime"))
        assertFalse(body.containsKey("bedtime"))
    }

    @Test fun `sport history - expectedTodaySport is average of sport-day calories-out`() = appTest {
        val token = login()
        val today = LocalDate.now()
        for (i in 1..5) {
            val d = today.minusDays(i.toLong())
            insertEnergy(d, totalKcal = 2400)
            insertWorkout(d)
        }
        val r = client.get("/summary/today?date=$today") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertNotNull(body["expectedTodaySport"])
        assertEquals(2400, body["expectedTodaySport"]!!.jsonPrimitive.content.toInt())
    }
```

with:

```kotlin
    @Test fun `no history - dynamic params are null`() = appTest {
        val token = login()
        val today = LocalDate.now().toString()
        val r = client.get("/summary/today?date=$today") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals(JsonNull, body["expectedToday"])
        assertEquals(JsonNull, body["actualBurnedSoFar"])
        // removed fields must be absent from the JSON entirely
        assertFalse(body.containsKey("expectedTodaySport"))
        assertFalse(body.containsKey("expectedTodayNonSport"))
        assertFalse(body.containsKey("eatingFractionSport"))
        assertFalse(body.containsKey("eatingFractionNonSport"))
        assertFalse(body.containsKey("postWorkoutModeSport"))
        assertFalse(body.containsKey("postWorkoutModeNonSport"))
        assertFalse(body.containsKey("wakeTime"))
        assertFalse(body.containsKey("bedtime"))
    }

    @Test fun `history - expectedToday is average of calories-out`() = appTest {
        val token = login()
        val today = LocalDate.now()
        for (i in 1..5) {
            val d = today.minusDays(i.toLong())
            insertEnergy(d, totalKcal = 2400)
        }
        val r = client.get("/summary/today?date=$today") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertNotNull(body["expectedToday"])
        assertEquals(2400, body["expectedToday"]!!.jsonPrimitive.content.toInt())
    }
```

- [ ] **Step 5: Remove the now-unused `insertWorkout` helper**

Delete this private function from `DynamicBudgetIntegrationTest.kt` (no test calls it anymore):

```kotlin
    private fun insertWorkout(date: LocalDate) = transaction {
        Workout.insert {
            it[id]     = UUID.randomUUID()
            it[userId] = testUserId
            it[Workout.date] = date
            it[type]   = "climbing"
        }
    }
```

Leave the `Workout` import and the `Workout.deleteWhere { userId eq testUserId }` line in `cleanMutableRows()` as-is — the import is still needed for that cleanup call.

- [ ] **Step 6: Run server tests**

Run: `./gradlew :server:test`
Expected: `BUILD SUCCESSFUL`.

(Do not commit yet.)

---

### Task 3: Client Room — drop `sport_tonight`, collapse `dynamic_budget_params`

**Files:**
- Delete: `app/src/main/kotlin/org/branneman/health/db/entities/SportTonightEntity.kt`
- Delete: `app/src/main/kotlin/org/branneman/health/db/dao/SportTonightDao.kt`
- Delete: `app/src/test/kotlin/org/branneman/health/db/dao/SportTonightDaoTest.kt`
- Modify: `app/src/main/kotlin/org/branneman/health/db/entities/DynamicBudgetParamsEntity.kt`
- Modify: `app/src/test/kotlin/org/branneman/health/db/dao/DynamicBudgetParamsDaoTest.kt`
- Modify: `app/src/main/kotlin/org/branneman/health/db/HealthDatabase.kt`
- Modify: `app/src/main/kotlin/org/branneman/health/HealthApplication.kt`

**Interfaces:**
- Produces: `DynamicBudgetParamsEntity(date: String, expectedToday: Int?)`, `HealthDatabase` at `version = 9` with no `sportTonightDao()`. Task 4 consumes this entity shape.

- [ ] **Step 1: Delete the three sport-tonight files**

```bash
rm app/src/main/kotlin/org/branneman/health/db/entities/SportTonightEntity.kt
rm app/src/main/kotlin/org/branneman/health/db/dao/SportTonightDao.kt
rm app/src/test/kotlin/org/branneman/health/db/dao/SportTonightDaoTest.kt
```

- [ ] **Step 2: Collapse `DynamicBudgetParamsEntity.kt`**

Replace the full file contents with:

```kotlin
package org.branneman.health.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "dynamic_budget_params")
data class DynamicBudgetParamsEntity(
    @PrimaryKey val date: String,
    val expectedToday: Int?,
)
```

- [ ] **Step 3: Rewrite `DynamicBudgetParamsDaoTest.kt`**

Replace the full file contents with:

```kotlin
package org.branneman.health.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.branneman.health.db.HealthDatabase
import org.branneman.health.db.entities.DynamicBudgetParamsEntity
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DynamicBudgetParamsDaoTest {

    private lateinit var db: HealthDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HealthDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() { db.close() }

    @Test fun `upsert and getForDate returns entity with all fields`() = runTest {
        val entity = DynamicBudgetParamsEntity(date = "2026-06-15", expectedToday = 2200)
        db.dynamicBudgetParamsDao().upsert(entity)
        val result = db.dynamicBudgetParamsDao().getForDate("2026-06-15")
        assertNotNull(result)
        assertEquals(2200, result.expectedToday)
    }

    @Test fun `getForDate returns null for missing date`() = runTest {
        db.dynamicBudgetParamsDao().upsert(
            DynamicBudgetParamsEntity(date = "2026-06-15", expectedToday = 2200)
        )
        assertNull(db.dynamicBudgetParamsDao().getForDate("2026-06-14"))
    }

    @Test fun `upsert replaces existing row for same date`() = runTest {
        db.dynamicBudgetParamsDao().upsert(
            DynamicBudgetParamsEntity(date = "2026-06-15", expectedToday = 2400)
        )
        db.dynamicBudgetParamsDao().upsert(
            DynamicBudgetParamsEntity(date = "2026-06-15", expectedToday = 2000)
        )
        val result = db.dynamicBudgetParamsDao().getForDate("2026-06-15")
        assertNotNull(result)
        assertEquals(2000, result.expectedToday)
    }

    @Test fun `nullable field stored as null when no history`() = runTest {
        db.dynamicBudgetParamsDao().upsert(
            DynamicBudgetParamsEntity(date = "2026-06-15", expectedToday = null)
        )
        val result = db.dynamicBudgetParamsDao().getForDate("2026-06-15")
        assertNotNull(result)
        assertNull(result.expectedToday)
    }
}
```

- [ ] **Step 4: Update `HealthDatabase.kt`**

Remove `SportTonightEntity::class` from the `entities` list, remove the `abstract fun sportTonightDao(): SportTonightDao` line, and bump `version = 8` to `version = 9`. Full file:

```kotlin
package org.branneman.health.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import org.branneman.health.db.dao.*
import org.branneman.health.db.entities.*

@Database(
    entities = [
        BodyWeightEntity::class,
        DailyEnergyEntity::class,
        WorkoutEntity::class,
        LogEntryEntity::class,
        LogEntryItemEntity::class,
        MealTemplateEntity::class,
        MealTemplateItemEntity::class,
        FoodItemEntity::class,
        ShortcutEntity::class,
        UserProfileEntity::class,
        DynamicBudgetParamsEntity::class,
    ],
    version = 9,
    exportSchema = false,
)
abstract class HealthDatabase : RoomDatabase() {
    abstract fun bodyWeightDao(): BodyWeightDao
    abstract fun dailyEnergyDao(): DailyEnergyDao
    abstract fun workoutDao(): WorkoutDao
    abstract fun logEntryDao(): LogEntryDao
    abstract fun mealTemplateDao(): MealTemplateDao
    abstract fun foodItemDao(): FoodItemDao
    abstract fun shortcutDao(): ShortcutDao
    abstract fun userProfileDao(): UserProfileDao
    abstract fun dynamicBudgetParamsDao(): DynamicBudgetParamsDao

    companion object {
        fun buildInMemory(context: Context): HealthDatabase =
            Room.inMemoryDatabaseBuilder(context, HealthDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
```

- [ ] **Step 5: Add `MIGRATION_8_9` in `HealthApplication.kt`**

Do **not** modify `MIGRATION_1_2` — it must stay exactly as-is so devices currently on schema version 1 can still step through to 2 (which created `sport_tonight`); `MIGRATION_8_9` drops it later in the chain. Add this new migration object right after `MIGRATION_7_8`:

```kotlin
private val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `sport_tonight`")
        db.execSQL("DROP TABLE IF EXISTS `dynamic_budget_params`")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `dynamic_budget_params` (
                `date` TEXT NOT NULL,
                `expectedToday` INTEGER,
                PRIMARY KEY(`date`)
            )
        """)
    }
}
```

(Recreating `dynamic_budget_params` from scratch instead of migrating its data is safe and matches the existing `MIGRATION_5_6` precedent — it's purely a cache of the server's last-fetched value, refreshed via upsert on the very next dashboard load.)

Then update the `.addMigrations(...)` call in `onCreate()`:

```kotlin
        db = Room.databaseBuilder(this, HealthDatabase::class.java, "health.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
            .build()
```

- [ ] **Step 6: Run app tests**

Run: `./gradlew :app:test --tests "org.branneman.health.db.dao.DynamicBudgetParamsDaoTest"`
Expected: `BUILD SUCCESSFUL`. (Full `:app:test` will still fail at this point — `DashboardViewModel`/`DashboardScreen` haven't been updated yet. That's expected; Tasks 4–5 fix it.)

(Do not commit yet.)

---

### Task 4: Client — simplify `DashboardViewModel`

**Files:**
- Modify: `app/src/main/kotlin/org/branneman/health/dashboard/DashboardViewModel.kt`
- Modify: `app/src/test/kotlin/org/branneman/health/dashboard/DashboardLogicTest.kt`

**Interfaces:**
- Consumes: `DynamicBudgetParamsEntity(date, expectedToday)` from Task 3; `TodaySummaryDto(..., expectedToday, actualBurnedSoFar)` from Task 2.
- Produces: `DashboardUiState` with `expectedToday: Int?` (no `sportTonight`, no `expectedTodaySport`/`expectedTodayNonSport`), `computeCaloriesLeft(...)` unchanged in signature. Task 5 consumes `DashboardUiState` — no `sportTonight` field exists.

- [ ] **Step 1: Rewrite `DashboardViewModel.kt`**

Replace the full file contents with:

```kotlin
package org.branneman.health.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.branneman.health.BuildConfig
import org.branneman.health.HealthApplication
import org.branneman.health.auth.TokenStore
import org.branneman.health.auth.authDataStore
import org.branneman.health.db.SyncStatus
import org.branneman.health.db.entities.BodyWeightEntity
import org.branneman.health.db.entities.DynamicBudgetParamsEntity
import org.branneman.health.network.HealthApiClient
import org.branneman.health.onboarding.activityMultiplier
import org.branneman.health.onboarding.computeBmr
import java.time.LocalDate
import org.branneman.health.util.effectiveDate

data class DashboardUiState(
    val isLoading: Boolean = true,
    val caloriesIn: Int = 0,
    val caloriesOut: Int = 0,
    val caloriesOutSource: String = "estimate",
    val targetDeficit: Int = 0,
    val caloriesLeft: Int = 0,
    val budgetLabel: String = "left (estimated)",
    val weightKgToday: Double? = null,
    val expectedToday: Int? = null,
    val actualBurnedSoFar: Int? = null,
    val weeklyVerdict: WeeklyVerdict? = null,
    val weightTrend: WeightTrendData? = null,
    val selectedTrendRange: TrendRange = TrendRange.MONTH,
    val goalWeightKg: Double? = null,
)

fun isValidWeightInput(input: String): Boolean {
    val normalized = input.replace(',', '.')
    val value = normalized.toDoubleOrNull() ?: return false
    if (value < 20.0 || value > 300.0) return false
    val sepIndex = normalized.indexOf('.')
    if (sepIndex != -1 && normalized.length - sepIndex - 1 > 1) return false
    return true
}

fun computeCaloriesLeft(
    expectedToday: Int,
    targetDeficit: Int,
    actualBurnedToday: Int?,
    caloriesIn: Int,
): Int {
    val caloriesOut = if (actualBurnedToday != null && actualBurnedToday >= expectedToday * 0.9) {
        actualBurnedToday
    } else {
        expectedToday
    }
    return caloriesOut - targetDeficit - caloriesIn
}

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as HealthApplication
    private val tokenStore = TokenStore(application.authDataStore)
    private val apiClient = HealthApiClient(
        baseUrl = BuildConfig.SERVER_BASE_URL,
        client = HttpClient(Android) { install(ContentNegotiation) { json() } },
    )

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { observeLogEntries() }
        viewModelScope.launch { load() }
    }

    private fun refreshCaloriesLeft() {
        val state = _uiState.value
        val expectedToday = state.expectedToday ?: state.caloriesOut

        val caloriesLeft = computeCaloriesLeft(
            expectedToday      = expectedToday,
            targetDeficit      = state.targetDeficit,
            actualBurnedToday  = state.actualBurnedSoFar,
            caloriesIn         = state.caloriesIn,
        )

        val usingFallback = state.expectedToday == null
        val budgetLabel = when {
            caloriesLeft < 0         -> "kcal over"
            state.targetDeficit == 0 -> "left (balance)"
            usingFallback            -> "left (estimated)"
            else                     -> "left"
        }

        _uiState.update { it.copy(caloriesLeft = caloriesLeft, budgetLabel = budgetLabel) }
    }

    private suspend fun observeLogEntries() {
        val stored = tokenStore.tokenFlow.first() ?: return
        val today = effectiveDate().toString()
        app.db.logEntryDao().observeTotalKcalForDate(stored.userId, "$today%").collect { kcal ->
            _uiState.update { it.copy(caloriesIn = kcal) }
            refreshCaloriesLeft()
        }
    }

    private suspend fun load() {
        val stored = tokenStore.tokenFlow.first() ?: return
        val today = effectiveDate().toString()

        val localState = computeLocalState(stored.userId, today)
        _uiState.value = localState
        refreshCaloriesLeft()

        runCatching { apiClient.getTodaySummary(stored.token, today) }
            .onSuccess { dto ->
                app.db.dynamicBudgetParamsDao().upsert(
                    DynamicBudgetParamsEntity(
                        date          = today,
                        expectedToday = dto.expectedToday,
                    )
                )
                _uiState.update { state ->
                    state.copy(
                        isLoading         = false,
                        caloriesOut       = dto.caloriesOut,
                        caloriesOutSource = dto.caloriesOutSource,
                        targetDeficit     = dto.targetDeficit,
                        expectedToday     = dto.expectedToday,
                        actualBurnedSoFar = dto.actualBurnedSoFar,
                    )
                }
                refreshCaloriesLeft()
            }
    }

    private suspend fun computeLocalState(userId: String, today: String): DashboardUiState {
        val profile = app.db.userProfileDao().get()
            ?: return DashboardUiState(isLoading = false)
        val latestWeight = app.db.bodyWeightDao().observeAll().first().firstOrNull()?.kg
        val weightToday  = app.db.bodyWeightDao().getForDate(userId, today)?.kg
        val energy       = app.db.dailyEnergyDao().getForDate(userId, today)
        val caloriesIn   = app.db.logEntryDao().sumQuickAddKcalForDate(userId, "$today%") +
                           app.db.logEntryDao().sumItemKcalForDate(userId, "$today%")
        val params       = app.db.dynamicBudgetParamsDao().getForDate(today)

        val bodyWeightReadings = app.db.bodyWeightDao().getAllForUser(userId)
        val verdict = computeWeeklyVerdict(
            readings      = bodyWeightReadings,
            targetDeficit = profile.targetDeficit,
            today         = LocalDate.parse(today),
        )
        val trend = computeWeightTrend(bodyWeightReadings, LocalDate.parse(today))

        val (caloriesOut, source) = if (energy != null) {
            energy.totalKcal to "polar_today"
        } else {
            val weightKg = latestWeight ?: profile.goalWeightKg
            val age = LocalDate.now().year - profile.birthYear
            val tdee = (computeBmr(profile.sex, weightKg, profile.heightCm, age) * activityMultiplier(profile.activityLevel)).toInt()
            tdee to "estimate"
        }

        return DashboardUiState(
            isLoading         = false,
            caloriesIn        = caloriesIn,
            caloriesOut       = caloriesOut,
            caloriesOutSource = source,
            targetDeficit     = profile.targetDeficit,
            weightKgToday     = weightToday,
            expectedToday     = params?.expectedToday,
            actualBurnedSoFar = energy?.totalKcal,
            weeklyVerdict     = verdict,
            weightTrend       = trend,
            goalWeightKg      = profile.goalWeightKg,
        )
        // caloriesLeft and budgetLabel are set by refreshCaloriesLeft() called after this
    }

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

- [ ] **Step 2: Remove sport-tonight cases from `DashboardLogicTest.kt`**

Remove the import (currently line 5):

```kotlin
import org.branneman.health.db.entities.SportTonightEntity
```

Remove the now-unused `assertNull` import (currently line 10) — nothing else in the file uses it:

```kotlin
import kotlin.test.assertNull
```

Remove this whole block (the `computeSportEstimate` tests, the auto-clear invariant test, and the `adjustedBudget` tests — everything between the class opening brace and the `caloriesIn` filter tests):

```kotlin
    // --- computeSportEstimate ---

    @Test fun `climbing normal 80kg = 600 kcal`() {
        // MET=5.0 × 80kg × (90/60)h = 600
        assertEquals(600, computeSportEstimate("climbing", "normal", 80.0))
    }

    @Test fun `climbing light 80kg = 400 kcal`() {
        // MET=4.0 × 80kg × (75/60)h = 400
        assertEquals(400, computeSportEstimate("climbing", "light", 80.0))
    }

    @Test fun `climbing hard 80kg = 780 kcal`() {
        // MET=6.5 × 80kg × (90/60)h = 780
        assertEquals(780, computeSportEstimate("climbing", "hard", 80.0))
    }

    @Test fun `rowing normal 80kg = 600 kcal`() {
        // MET=7.5 × 80kg × (60/60)h = 600
        assertEquals(600, computeSportEstimate("rowing", "normal", 80.0))
    }

    @Test fun `rowing light 80kg = 420 kcal`() {
        // MET=7.0 × 80kg × (45/60)h = 420
        assertEquals(420, computeSportEstimate("rowing", "light", 80.0))
    }

    @Test fun `rowing hard 80kg = 720 kcal`() {
        // MET=9.0 × 80kg × (60/60)h = 720
        assertEquals(720, computeSportEstimate("rowing", "hard", 80.0))
    }

    @Test fun `other normal 80kg = 550 kcal`() {
        // MET=5.5 × 80kg × (75/60)h = 550
        assertEquals(550, computeSportEstimate("other", "normal", 80.0))
    }

    @Test fun `other light 80kg = 320 kcal`() {
        // MET=4.0 × 80kg × (60/60)h = 320
        assertEquals(320, computeSportEstimate("other", "light", 80.0))
    }

    @Test fun `other hard 80kg = 700 kcal`() {
        // MET=7.0 × 80kg × (75/60)h = 700
        assertEquals(700, computeSportEstimate("other", "hard", 80.0))
    }

    // --- sport-tonight auto-clear invariant ---

    @Test fun `sport tonight entity with yesterday date is treated as inactive`() {
        val entity = SportTonightEntity(
            date = "2026-06-10", activityType = "climbing", intensity = "normal", estimatedKcal = 600
        )
        val today = "2026-06-11"
        assertNull(entity.takeIf { it.date == today })
    }

    // --- adjusted budget ---

    @Test fun `adjustedBudget adds sport estimate to base when active`() {
        assertEquals(2447, 1847 + 600)
    }

    @Test fun `adjustedBudget equals base when no sport tonight`() {
        val sportKcal = 0
        assertEquals(1847, 1847 + sportKcal)
    }

    // --- caloriesIn reactive filter rules ---
```

with just:

```kotlin
    // --- caloriesIn reactive filter rules ---
```

- [ ] **Step 3: Run the app logic tests**

Run: `./gradlew :app:test --tests "org.branneman.health.dashboard.DashboardLogicTest"`
Expected: `BUILD SUCCESSFUL`.

(Do not commit yet — `DashboardScreen.kt` still references the now-deleted `sportTonight` state and won't compile. Task 5 fixes it.)

---

### Task 5: Client — remove the toggle UI from `DashboardScreen`

**Files:**
- Modify: `app/src/main/kotlin/org/branneman/health/ui/DashboardScreen.kt`
- Modify: `app/src/test/kotlin/org/branneman/health/ui/DashboardScreenTest.kt`
- Modify: `app/src/test/kotlin/org/branneman/health/TestFactories.kt`

**Interfaces:**
- Consumes: `DashboardUiState` (no `sportTonight` field) from Task 4.
- Produces: `DashboardContent(state, onLogWeight, onSelectTrendRange)` — two callback params instead of four.

- [ ] **Step 1: Rewrite `DashboardScreen.kt`**

Replace the full file contents with:

```kotlin
package org.branneman.health.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.branneman.health.dashboard.DashboardUiState
import org.branneman.health.dashboard.DashboardViewModel
import org.branneman.health.dashboard.TrendRange
import org.branneman.health.dashboard.WeeklyVerdict
import org.branneman.health.dashboard.isValidWeightInput
import org.branneman.health.dashboard.verdictMessage

@Composable
fun DashboardScreen(viewModel: DashboardViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DashboardContent(
        state = state,
        onLogWeight = viewModel::logWeight,
        onSelectTrendRange = viewModel::selectTrendRange,
    )
}

@Composable
fun DashboardContent(
    state: DashboardUiState,
    onLogWeight: (Double) -> Unit,
    onSelectTrendRange: (TrendRange) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(
            text = "Today",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        BudgetSection(state)
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        WeightChipRow(weightKg = state.weightKgToday, onLogWeight = onLogWeight)
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

@Composable
private fun BudgetSection(state: DashboardUiState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = state.caloriesLeft.toString(),
            style = MaterialTheme.typography.displayMedium,
        )
        Text(
            text = state.budgetLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
            modifier = Modifier.fillMaxWidth(),
        ) {
            InOutColumn(value = state.caloriesIn, label = "in")
            InOutColumn(
                value = state.caloriesOut,
                label = if (state.caloriesOutSource == "estimate") "out (est.)" else "out",
            )
        }
    }
}

@Composable
private fun InOutColumn(value: Int, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value.toString(), style = MaterialTheme.typography.titleMedium)
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WeightChipRow(
    weightKg: Double?,
    onLogWeight: (Double) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }

    TextButton(
        onClick = { showDialog = true },
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(
            text = if (weightKg != null) "⚖ ${"%.1f".format(weightKg)} kg" else "⚖ -- kg",
            style = MaterialTheme.typography.bodyMedium,
            color = if (weightKg != null) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showDialog) {
        WeightEntryDialog(
            initialValue = weightKg,
            onSave = { kg ->
                onLogWeight(kg)
                showDialog = false
            },
            onDismiss = { showDialog = false },
        )
    }
}

@Composable
private fun WeightEntryDialog(
    initialValue: Double?,
    onSave: (Double) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf(initialValue?.let { "%.1f".format(it) } ?: "") }
    val isValid = isValidWeightInput(input)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log weight") },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("kg") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { input.replace(',', '.').toDoubleOrNull()?.let { onSave(it) } },
                enabled = isValid,
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

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

- [ ] **Step 2: Update `DashboardScreenTest.kt`**

Remove the import (currently line 12):

```kotlin
import org.branneman.health.db.entities.SportTonightEntity
```

Replace the `render` helper:

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

with:

```kotlin
    private fun render(
        state: DashboardUiState = DashboardUiState(),
        onLogWeight: (Double) -> Unit = {},
        onSelectTrendRange: (TrendRange) -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                DashboardContent(
                    state = state,
                    onLogWeight = onLogWeight,
                    onSelectTrendRange = onSelectTrendRange,
                )
            }
        }
    }
```

Remove these six tests (everything between `` `shows in and out labels` `` and the `// --- Weight chip ---` comment):

```kotlin
    @Test fun `sport tonight inactive shows set button`() {
        render(state = DashboardUiState(isLoading = false, sportTonight = null, caloriesLeft = 1847))
        compose.onNodeWithText("sport tonight", substring = true, ignoreCase = true).assertExists()
    }

    @Test fun `sport tonight active shows activity type`() {
        render(state = DashboardUiState(
            isLoading = false,
            sportTonight = SportTonightEntity(date = "2026-06-11", activityType = "climbing", intensity = "normal", estimatedKcal = 600),
            caloriesLeft = 2447,
        ))
        compose.onNodeWithText("Climbing", substring = true, ignoreCase = true).assertExists()
    }

    @Test fun `sport tonight active shows estimated kcal`() {
        render(state = DashboardUiState(
            isLoading = false,
            sportTonight = SportTonightEntity(date = "2026-06-11", activityType = "climbing", intensity = "normal", estimatedKcal = 600),
            caloriesLeft = 2447,
        ))
        compose.onNodeWithText("600", substring = true).assertExists()
    }

    @Test fun `sport tonight active shows intensity chips`() {
        render(state = DashboardUiState(
            isLoading = false,
            sportTonight = SportTonightEntity(date = "2026-06-11", activityType = "climbing", intensity = "normal", estimatedKcal = 600),
            caloriesLeft = 2447,
        ))
        compose.onNodeWithText("Normal").assertExists()
    }

    @Test fun `tapping intensity chip calls onSetSportTonight`() {
        var called: Pair<String, String>? = null
        render(
            state = DashboardUiState(
                isLoading = false,
                sportTonight = SportTonightEntity(date = "2026-06-11", activityType = "climbing", intensity = "normal", estimatedKcal = 600),
                caloriesLeft = 2447,
            ),
            onSetSportTonight = { a, i -> called = a to i },
        )
        compose.onNodeWithText("Hard").performClick()
        assert(called == "climbing" to "hard")
    }

    @Test fun `tapping clear calls onClearSportTonight`() {
        var cleared = false
        render(
            state = DashboardUiState(
                isLoading = false,
                sportTonight = SportTonightEntity(date = "2026-06-11", activityType = "rowing", intensity = "normal", estimatedKcal = 600),
                caloriesLeft = 2447,
            ),
            onClearSportTonight = { cleared = true },
        )
        compose.onNodeWithText("clear", substring = true, ignoreCase = true).performScrollTo().performClick()
        assert(cleared)
    }

```

- [ ] **Step 3: Remove `aSportTonight` from `TestFactories.kt`**

Delete this function:

```kotlin
fun aSportTonight(
    date: String = "2026-06-10",
    activityType: String = "climbing",
    intensity: String = "normal",
    estimatedKcal: Int = 600,
) = SportTonightEntity(
    date = date,
    activityType = activityType,
    intensity = intensity,
    estimatedKcal = estimatedKcal,
)
```

- [ ] **Step 4: Run the full app test suite**

Run: `./gradlew :app:test`
Expected: `BUILD SUCCESSFUL`.

(Do not commit yet.)

---

### Task 6: Docs

**Files:**
- Modify: `docs/ubiquitous-language.md`
- Modify: `docs/ux/2-scenarios.md`
- Modify: `docs/ux/4-flows.md`
- Modify: `docs/ux/3-features/dashboard.md`
- Modify: `docs/math-model.md`
- Modify: `docs/api-design.md`
- Modify: `docs/domain-model.md`
- Modify: `docs/testing-manifesto.md`

No test for this task — it's documentation. Each step is a self-contained edit.

- [ ] **Step 1: `docs/ubiquitous-language.md`** — remove the glossary entry

Replace:

```markdown
excluded from that calculation.

### Sport tonight

A toggle on the dashboard that adds an estimated exercise expenditure to today's
calories out. Set by the user when planning a session that hasn't happened yet. Cleared
each morning. Replaced silently by Polar's actual figure after the session syncs.

---
```

with:

```markdown
excluded from that calculation.

---
```

- [ ] **Step 2: `docs/ux/2-scenarios.md`** — remove the S05 index row, scenario, and its S02 mention

Replace:

```markdown
| S04 | Log lunch                        | Daily midday   |
| S05 | Set sport tonight                | Morning/midday |
| S06 | Log — from template              | Anytime        |
```

with:

```markdown
| S04 | Log lunch                        | Daily midday   |
| S06 | Log — from template              | Anytime        |
```

Replace:

```markdown
3. Dashboard shows today's state: calories in so far (zero), budget remaining, and the
   sport-tonight toggle (cleared to off each morning).
```

with:

```markdown
3. Dashboard shows today's state: calories in so far (zero) and budget remaining.
```

Replace (removing the whole S05 scenario, including the `---` divider that preceded it, so S04 flows straight into the `---` that already separated S05 from S06):

```markdown
---

## S05 — Set sport tonight

**Trigger:** Morning when planning the day, or mid-afternoon when the climbing plan is confirmed
with friends. Sometimes as late as 30 minutes before leaving.

**Goal:** Bump today's calorie budget to account for a planned evening session, so food
choices during the day reflect actual expenditure.

**Steps:**

1. On the dashboard, user taps the sport-tonight toggle (visible and accessible, not buried).
2. A picker: activity type (`Climbing` / `Rowing` / `Other`) × intensity (`Light` /
   `Normal` / `Hard`), with an estimated kcal burn for each combination.
3. User picks — e.g. "Climbing, Normal — est. 600 kcal".
4. Budget updates immediately and shows the adjustment: "X kcal remaining (includes
   planned climb ~600 kcal)".
5. The active toggle stays visible on the dashboard for the rest of the day.

**Outcome:** Daytime budget reflects planned expenditure. User can eat appropriately
before the session.

**Late or last-minute:** User can set or change the toggle at any point during the day
— including 30 minutes before leaving. Polar's actual post-session figure settles the
real number at day's end.

**Edge cases:**

- Session cancelled: tap the toggle again to clear. Budget reverts.
- Rowing (always last-minute): same flow, user picks "Rowing" when the decision is made.
- Calorie variance: climbing burns 300–900 kcal depending on session intensity. The
  Light/Normal/Hard picker lets the user express this rather than locking in one number.
  Over time, Polar data accumulates and the app can suggest better personal defaults for
  each intensity level.

---
```

with (empty — nothing replaces it; the `## S06` header that used to follow now comes right after `## S04`'s content, separated by the single `---` that used to divide S05 and S06).

- [ ] **Step 5: `docs/ux/4-flows.md`** — remove the F03 scenario reference, wireframe lines, and the picker section

Replace:

```markdown
**Scenarios:** S02, S05, S13, S14  
```

with:

```markdown
**Scenarios:** S02, S13, S14  
```

Replace:

```markdown
│  770 kcal remaining          │ ← neutral style; no color
│  (includes planned climb     │
│   ~600 kcal)                 │ ← shown only when sport-tonight is active
│                              │
│  [ 84.0 kg ✓ ] tap to edit  │ ← weight entry; pre-filled from last log
│  [ ⚡ Sport tonight: off ]   │ ← toggle; tap opens picker (below)
```

with:

```markdown
│  770 kcal remaining          │ ← neutral style; no color
│                              │
│  [ 84.0 kg ✓ ] tap to edit  │ ← weight entry; pre-filled from last log
```

Replace:

```markdown
### Sport-tonight picker (bottom sheet)

Opens when the sport-tonight toggle is tapped.

```
┌──────────────────────────────┐
│ Sport tonight                │
├──────────────────────────────┤
│  Activity:  [ Climbing  ▼ ]  │ ← Climbing / Rowing / Other
│                              │
│  Intensity:                  │
│  ○ Light  ●Normal  ○ Hard   │
│                              │
│  Estimate: ~600 kcal         │ ← updates live; shown before confirming
├──────────────────────────────┤
│  [ Cancel ]      [ Set ]     │
└──────────────────────────────┘
```

- After Set: toggle label updates to "⚡ Climbing, Normal — ~600 kcal"
- Tapping the active toggle re-opens the picker (to change or clear)
- A "Clear" option appears inside the picker when already set

### Weight entry interaction
```

with:

```markdown
### Weight entry interaction
```

- [ ] **Step 6: `docs/ux/3-features/dashboard.md`** — remove the toggle bullet

Replace:

```markdown
    - A quick affordance to log weight if not done today.
    - A **sport-tonight toggle**: activity type (`Climbing` / `Rowing` / `Other`) ×
      intensity (`Light` / `Normal` / `Hard`). Tapping it bumps the day's budget by the
      estimated session expenditure and labels the budget: "X kcal remaining (includes
      planned climb ~600 kcal)". Settable or changeable any time during the day; cleared
      each morning. See S05.
```

with:

```markdown
    - A quick affordance to log weight if not done today.
```

- [ ] **Step 7: `docs/math-model.md`** — remove §1.4, and update §2.2/2.3/2.4/2.5/§6/§8

Replace the scope line:

```markdown
**Scope:** Algorithms behind the daily budget, weight trend, weekly verdict, sport
estimates, and insight trigger conditions. Validates that the UX design in `docs/ux/`
```

with:

```markdown
**Scope:** Algorithms behind the daily budget, weight trend, weekly verdict, and insight
trigger conditions. Validates that the UX design in `docs/ux/`
```

Replace (removing §1.4 entirely, keeping the `---` divider that follows it):

```markdown
User selects level during onboarding. The app displays estimated daily expenditure
with a visible "estimated" label until Polar is connected.

### 1.4 MET defaults for sport-tonight estimates

MET (Metabolic Equivalent of Task) measures exercise intensity relative to rest.

```
kcal = MET × body_weight_kg × duration_hrs
```

`body_weight_kg` uses the user's most recently logged body weight. Server computes
this at the time the sport-tonight toggle is set.

**Bouldering** (stop-and-go, rest-heavy — effective METs are lower than continuous
climbing):

| Intensity | MET | Default duration | ≈ kcal (80 kg) |
|-----------|-----|------------------|----------------|
| Light     | 4.0 | 75 min           | ~400           |
| Normal    | 5.0 | 90 min           | ~600           |
| Hard      | 6.5 | 90 min           | ~780           |

**Indoor rowing** (continuous effort):

| Intensity | MET | Default duration | ≈ kcal (80 kg) |
|-----------|-----|------------------|----------------|
| Light     | 7.0 | 45 min           | ~420           |
| Normal    | 7.5 | 60 min           | ~600           |
| Hard      | 9.0 | 60 min           | ~720           |

All duration and kcal values are user-configurable defaults per activity/intensity
combination. Polar's post-session actual always overwrites the estimate — these
numbers only affect the daytime budget preview.

---
```

with:

```markdown
User selects level during onboarding. The app displays estimated daily expenditure
with a visible "estimated" label until Polar is connected.

---
```

Replace:

```markdown
#### Polar history (last 30 calendar days, from `daily_energy` + `workout` tables)

A day is classified as a **sport day** if a `workout` row exists for that date. Otherwise it is a **non-sport day**.

```
expected_today("sport")     = avg(daily_energy.total_kcal on sport days, last 30 calendar days)
expected_today("non-sport") = avg(daily_energy.total_kcal on non-sport days, last 30 calendar days)
```

When no Polar history is available: `expected_today` falls back to BMR × activity multiplier (§1.2/§1.3).

**Today's bucket:** the sport-tonight toggle on the dashboard determines which average to use — not Polar, not day-of-week heuristics. Historical classification uses the `workout` table.
```

with:

```markdown
#### Polar history (last 30 calendar days, from `daily_energy`)

```
expected_today = avg(daily_energy.total_kcal, last 30 calendar days)
```

When no Polar history is available: `expected_today` falls back to BMR × activity multiplier (§1.2/§1.3).
```

Replace:

```markdown
### 2.3 Core formula

Let `bucket` = `"sport"` if sport-tonight toggled, else `"non-sport"`.

```
calories_out_today = actual_burned_today        if actual_burned_today ≥ 0.9 × expected_today(bucket)
                   = expected_today(bucket)      otherwise

calories_left = calories_out_today − D − calories_in_today
```

**While Polar has only a partial daily reading** (actual is null or < 90% of expected): `expected_today(bucket)` is the stable proxy. It avoids budget jumps mid-day when Polar sends a partial cumulative total.
```

with:

```markdown
### 2.3 Core formula

```
calories_out_today = actual_burned_today        if actual_burned_today ≥ 0.9 × expected_today
                   = expected_today              otherwise

calories_left = calories_out_today − D − calories_in_today
```

**While Polar has only a partial daily reading** (actual is null or < 90% of expected): `expected_today` is the stable proxy. It avoids budget jumps mid-day when Polar sends a partial cumulative total.
```

Replace:

```markdown
1. `actual_burned_today` is updated from the new `total_kcal` for today.
2. If `actual_burned_today ≥ 0.9 × expected_today(bucket)`, the formula switches to using `actual_burned_today` directly.
3. `expected_today(bucket)` is **not** recalibrated mid-day — it remains the 30-day historical average.
```

with:

```markdown
1. `actual_burned_today` is updated from the new `total_kcal` for today.
2. If `actual_burned_today ≥ 0.9 × expected_today`, the formula switches to using `actual_burned_today` directly.
3. `expected_today` is **not** recalibrated mid-day — it remains the 30-day historical average.
```

Replace:

```markdown
The formula no longer changes with clock time — only with food log events, Polar syncs, and sport toggle changes. There is no client-side per-minute tick.

- **Server computes** `expected_today` per bucket and exposes it via `/summary/today`.
- **Client stores** these params in Room alongside `actual_burned_today` and `calories_in_today`.
- **Client recalculates** `calories_left` on: food log event, Polar sync, sport toggle change. The widget reads the same Room data offline.
```

with:

```markdown
The formula no longer changes with clock time — only with food log events and Polar syncs. There is no client-side per-minute tick.

- **Server computes** `expected_today` and exposes it via `/summary/today`.
- **Client stores** these params in Room alongside `actual_burned_today` and `calories_in_today`.
- **Client recalculates** `calories_left` on: food log event, Polar sync. The widget reads the same Room data offline.
```

Replace:

```markdown
**Sport estimates are defaults, not measurements.** MET-based estimates for bouldering
are especially rough because session style varies more than duration. Polar's
post-session actual is the reliable number; the estimate exists only to make the
daytime budget useful before the session.

---
```

with:

```markdown
---
```

Replace:

```markdown
- **Polar absolute calibration:** Polar's daily calorie totals are observed to be
  400–600 kcal too high in absolute terms, inflating `expected_today` and thus the
  budget. Revisit after ≥ 30 days of concurrent Polar + food + weight data. Leading
  approach: weight-trend feedback to infer actual TDEE (if weight is flat while eating
  X kcal/day, TDEE ≈ X). Polar's relative sport/non-sport variation is still valid
  even with an absolute offset; only the anchor shifts.
```

with:

```markdown
- **Polar absolute calibration:** Polar's daily calorie totals are observed to be
  400–600 kcal too high in absolute terms, inflating `expected_today` and thus the
  budget. Revisit after ≥ 30 days of concurrent Polar + food + weight data. Leading
  approach: weight-trend feedback to infer actual TDEE (if weight is flat while eating
  X kcal/day, TDEE ≈ X).
```

- [ ] **Step 8: `docs/api-design.md`** — update the `/summary/today` sample and field docs

Replace:

```markdown
{
  "date": "2026-06-03",
  "caloriesIn": 1850,
  "caloriesOut": 2200,
  "budgetRemaining": 50,
  "targetDeficit": 300,
  "caloriesOutSource": "estimate",
  "expectedTodaySport": 2387,
  "expectedTodayNonSport": 2100,
  "actualBurnedSoFar": null
}
```

with:

```markdown
{
  "date": "2026-06-03",
  "caloriesIn": 1850,
  "caloriesOut": 2200,
  "budgetRemaining": 50,
  "targetDeficit": 300,
  "caloriesOutSource": "estimate",
  "expectedToday": 2387,
  "actualBurnedSoFar": null
}
```

Replace:

```markdown
`expectedTodaySport` / `expectedTodayNonSport`: 30-day rolling average of Polar
`total_kcal` for sport and non-sport days respectively. Null if fewer than 1 day of
Polar history exists for that bucket. Used by the client as the stable daily burn
proxy for the budget formula.

`actualBurnedSoFar`: today's Polar `total_kcal` if synced, else null. When this value
reaches ≥ 90% of the active bucket's expected, the client uses it directly instead of
the historical average.
```

with:

```markdown
`expectedToday`: 30-day rolling average of Polar `total_kcal`. Null if no Polar
history exists yet. Used by the client as the stable daily burn proxy for the budget
formula.

`actualBurnedSoFar`: today's Polar `total_kcal` if synced, else null. When this value
reaches ≥ 90% of `expectedToday`, the client uses it directly instead of the
historical average.
```

- [ ] **Step 9: `docs/domain-model.md`** — update the "no math on client" exception note

Replace:

```markdown
budget or verdict logic. **Narrow exception:** `computeCaloriesLeft()` runs on the
client to avoid a per-event server round-trip. The business logic inputs
(`expectedTodaySport/NonSport`, `actualBurnedSoFar`) are server-computed and
server-owned; only the final arithmetic runs locally. See `docs/math-model.md §2.5`.
```

with:

```markdown
budget or verdict logic. **Narrow exception:** `computeCaloriesLeft()` runs on the
client to avoid a per-event server round-trip. The business logic inputs
(`expectedToday`, `actualBurnedSoFar`) are server-computed and server-owned; only the
final arithmetic runs locally. See `docs/math-model.md §2.5`.
```

- [ ] **Step 10: `docs/testing-manifesto.md`** — drop `SportTonightDaoTest` from the DAO test list

Replace:

```markdown
**What currently meets the bar:** all nine DAO tests (`BodyWeightDaoTest`, `DailyEnergyDaoTest`,
`FoodItemDaoTest`, `LogEntryDaoTest`, `MealTemplateDaoTest`, `ShortcutDaoTest`, `UserProfileDaoTest`,
`WorkoutDaoTest`, `SportTonightDaoTest`), `LoginSyncServiceTest`, `LogEntrySyncServiceTest`,
```

with:

```markdown
**What currently meets the bar:** all eight DAO tests (`BodyWeightDaoTest`, `DailyEnergyDaoTest`,
`FoodItemDaoTest`, `LogEntryDaoTest`, `MealTemplateDaoTest`, `ShortcutDaoTest`, `UserProfileDaoTest`,
`WorkoutDaoTest`), `LoginSyncServiceTest`, `LogEntrySyncServiceTest`,
```

---

### Task 7: Final verification and single commit

**Files:** none (verification + commit only)

- [ ] **Step 1: Run the full app test suite**

Run: `./gradlew :app:test`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run the full server test suite**

Run: `./gradlew :server:test`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run API tests against a local server**

Start the server in one terminal: `./gradlew :server:run`
In another terminal: `API_TEST_SERVER_URL=http://localhost:8080 ./gradlew :server:apiTest`
Expected: `BUILD SUCCESSFUL`. Stop the local server afterward.

- [ ] **Step 4: Review the full diff**

Run: `git status` and `git diff --stat`
Confirm the changed/deleted files match: `SportTonightEntity.kt`, `SportTonightDao.kt`, `SportTonightDaoTest.kt` (deleted); `DynamicBudgetParamsEntity.kt`, `DynamicBudgetParamsDaoTest.kt`, `HealthDatabase.kt`, `HealthApplication.kt`, `DashboardViewModel.kt`, `DashboardScreen.kt`, `DashboardLogicTest.kt`, `DashboardScreenTest.kt`, `TestFactories.kt`, `TodaySummaryDto.kt`, `BudgetComputer.kt`, `BudgetComputerTest.kt`, `Application.kt`, `DynamicBudgetIntegrationTest.kt` (modified); the eight doc files from Task 6 (modified); plus the new `docs/specs/remove-sport-tonight.md` and `docs/plans/remove-sport-tonight.md`.

- [ ] **Step 5: Commit everything as one commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
fix(dashboard): remove Set sport tonight toggle

Pre-emptively crediting today's budget for a planned-but-not-yet-done
workout invites over-eating on an estimate that may not pan out. Removes
the local-only toggle, its Room table, and the server's sport/non-sport
day bucketing in favor of a single 30-day expected-calories-out average;
Polar's actual post-workout burn still overrides it once synced.
EOF
)"
```

- [ ] **Step 6: Verify the commit**

Run: `git status` and `git log -1 --stat`
Expected: clean working tree, one new commit containing all of the above files.
