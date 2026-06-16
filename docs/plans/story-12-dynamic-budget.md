# Story 12: Dynamic Calorie Budget — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the static `caloriesOut - deficit` budget with a time-of-day-aware dynamic budget learned from 30 days of history, plus a Settings screen for wake/bedtime.

**Architecture:** Server already computes `DynamicBudgetParams` (expected burn, eating fraction, post-workout flag per sport/non-sport bucket) and includes it in `TodaySummaryDto`. The app caches these params in a new Room entity and recomputes `caloriesLeft` every 60 s via a pure client-side formula. Wake/bedtime from `UserProfileEntity` drive the time-decay.

**Tech Stack:** Kotlin, Jetpack Compose, Room (SQLite), Ktor `HttpClient`, Robolectric, Exposed (server-side integration tests), JUnit4, kotlin.test.

---

## File map

### Created
- `server/src/test/kotlin/org/branneman/health/DynamicBudgetIntegrationTest.kt`
- `app/src/main/kotlin/org/branneman/health/db/entities/DynamicBudgetParamsEntity.kt`
- `app/src/main/kotlin/org/branneman/health/db/dao/DynamicBudgetParamsDao.kt`
- `app/src/test/kotlin/org/branneman/health/db/dao/DynamicBudgetParamsDaoTest.kt`

### Modified
- `server/src/test/kotlin/org/branneman/health/budget/BudgetComputerTest.kt` — +9 `computeDynamic` tests
- `docs/testing-manifesto.md` — add UUID registry row #10
- `app/src/main/kotlin/org/branneman/health/HealthApplication.kt` — add `MIGRATION_4_5`
- `app/src/main/kotlin/org/branneman/health/db/HealthDatabase.kt` — bump v4→v5, add entity+dao
- `app/src/test/kotlin/org/branneman/health/TestFactories.kt` — `aUserProfile()` gains `wakeTime`/`bedtime` params
- `app/src/test/kotlin/org/branneman/health/db/dao/UserProfileDaoTest.kt` — +1 round-trip test
- `app/src/main/kotlin/org/branneman/health/dashboard/DashboardViewModel.kt` — new pure function + full ViewModel rewrite
- `app/src/test/kotlin/org/branneman/health/dashboard/DashboardLogicTest.kt` — +9 `computeDynamicCaloriesLeft` tests
- `app/src/main/kotlin/org/branneman/health/ui/DashboardScreen.kt` — `BudgetSection` uses `state.caloriesLeft` + `state.budgetLabel`
- `app/src/test/kotlin/org/branneman/health/ui/DashboardScreenTest.kt` — update field names + add 3 label tests
- `app/src/main/kotlin/org/branneman/health/ui/OnboardingScreen.kt` — `TimeAdjustRow` `private` → `internal`
- `app/src/main/kotlin/org/branneman/health/ui/SettingsViewModel.kt` — add `ScheduleState` + schedule methods
- `app/src/main/kotlin/org/branneman/health/ui/SettingsScreen.kt` — schedule section in `SettingsContent`
- `app/src/test/kotlin/org/branneman/health/ui/SettingsScreenTest.kt` — +4 schedule tests
- `app/src/test/kotlin/org/branneman/health/ui/OnboardingScreenTest.kt` — +3 step 4 tests

---

## Task 1 — BudgetComputerTest: add `computeDynamic` unit tests

**Files:** `server/src/test/kotlin/org/branneman/health/budget/BudgetComputerTest.kt`

The server logic already exists. These tests verify it.

- [ ] **Add imports at top of existing test file** (after the existing imports):

```kotlin
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
```

- [ ] **Append all 9 tests to the class** (before the closing `}`):

```kotlin
// --- computeDynamic ---

private fun day(i: Int, out: Int, isSport: Boolean, caloriesIn: Int? = null) =
    HistoricalDay(date = today.minusDays(i.toLong()), caloriesOut = out, caloriesIn = caloriesIn, isSportDay = isSport)

@Test fun `computeDynamic - no history returns null expected and fraction`() {
    val r = BudgetComputer.computeDynamic(emptyList(), targetDeficit = 300, actualBurnedToday = null)
    assertNull(r.expectedTodaySport)
    assertNull(r.expectedTodayNonSport)
    assertNull(r.eatingFractionSport)
    assertNull(r.eatingFractionNonSport)
    assertFalse(r.postWorkoutModeSport)
    assertFalse(r.postWorkoutModeNonSport)
}

@Test fun `computeDynamic - fewer than 5 logged sport days uses Approach 1`() {
    // 4 sport days, none food-logged → Approach 1: (expected - D) / expected
    val history = (1..4).map { day(it, out = 2400, isSport = true, caloriesIn = null) }
    val r = BudgetComputer.computeDynamic(history, targetDeficit = 300, actualBurnedToday = null)
    assertEquals(2400, r.expectedTodaySport)
    // (2400 - 300) / 2400 = 0.875
    assertEquals(0.875, r.eatingFractionSport!!, 0.001)
}

@Test fun `computeDynamic - 5 logged sport days uses Approach 2`() {
    val history = (1..5).map { day(it, out = 2400, isSport = true, caloriesIn = 2000) }
    val r = BudgetComputer.computeDynamic(history, targetDeficit = 300, actualBurnedToday = null)
    // avg_in / avg_out = 2000 / 2400
    assertEquals(2000.0 / 2400.0, r.eatingFractionSport!!, 0.001)
}

@Test fun `computeDynamic - 10 qualifying sport days uses Approach 3`() {
    // qualifying threshold = 2400 - 300 + 100 = 2200; 2100 qualifies, 2500 does not
    val qualifying    = (1..10).map { day(it,     out = 2400, isSport = true, caloriesIn = 2100) }
    val nonQualifying = (11..12).map { day(it,    out = 2400, isSport = true, caloriesIn = 2500) }
    val r = BudgetComputer.computeDynamic(qualifying + nonQualifying, 300, null)
    // avg of qualifying only → 2100 / 2400
    assertEquals(2100.0 / 2400.0, r.eatingFractionSport!!, 0.001)
}

@Test fun `computeDynamic - sport and non-sport buckets are independent`() {
    val sportDays    = (1..10).map { day(it,     out = 2400, isSport = true,  caloriesIn = 2100) }
    val nonSportDays = (11..15).map { day(it,    out = 2000, isSport = false, caloriesIn = 1700) }
    val r = BudgetComputer.computeDynamic(sportDays + nonSportDays, 300, null)
    // Sport: Approach 3 → 2100/2400
    assertEquals(2100.0 / 2400.0, r.eatingFractionSport!!, 0.001)
    // Non-sport: Approach 2 (5 logged, threshold 2000-300+100=1800 → none qualify from 5) → 1700/2000
    assertEquals(1700.0 / 2000.0, r.eatingFractionNonSport!!, 0.001)
}

@Test fun `computeDynamic - post-workout triggers at exactly 90 percent of expected`() {
    val history = (1..5).map { day(it, out = 2400, isSport = true, caloriesIn = 2000) }
    val r = BudgetComputer.computeDynamic(history, 300, actualBurnedToday = 2160) // 2400 * 0.9
    assertTrue(r.postWorkoutModeSport)
}

@Test fun `computeDynamic - post-workout does not trigger at 89 percent`() {
    val history = (1..5).map { day(it, out = 2400, isSport = true, caloriesIn = 2000) }
    val r = BudgetComputer.computeDynamic(history, 300, actualBurnedToday = 2159)
    assertFalse(r.postWorkoutModeSport)
}

@Test fun `computeDynamic - post-workout stays off when actualBurnedToday is null`() {
    val history = (1..5).map { day(it, out = 2400, isSport = true, caloriesIn = 2000) }
    val r = BudgetComputer.computeDynamic(history, 300, actualBurnedToday = null)
    assertFalse(r.postWorkoutModeSport)
    assertFalse(r.postWorkoutModeNonSport)
}

@Test fun `computeDynamic - non-sport expected is independent of sport history`() {
    val sportDays = (1..10).map { day(it, out = 2400, isSport = true) }
    val r = BudgetComputer.computeDynamic(sportDays, 300, null)
    // 10 sport days → sport expected = 2400; no non-sport days → null
    assertEquals(2400, r.expectedTodaySport)
    assertNull(r.expectedTodayNonSport)
}
```

- [ ] **Run the tests:**

```bash
./gradlew :server:test --tests "org.branneman.health.budget.BudgetComputerTest"
```

Expected: **All 9 new tests PASS** (server logic already implemented).

- [ ] **Commit:**

```bash
git add server/src/test/kotlin/org/branneman/health/budget/BudgetComputerTest.kt
git commit -m "test(server): add computeDynamic unit tests to BudgetComputerTest"
```

---

## Task 2 — DynamicBudgetIntegrationTest (server Tier 2a, UUID slot #10)

**Files:** Create `server/src/test/kotlin/org/branneman/health/DynamicBudgetIntegrationTest.kt`

UUID: `00000000-0000-0000-0000-000000000010` | Email: `dynamic-budget-test@test.local`

- [ ] **Create the file:**

```kotlin
package org.branneman.health

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.branneman.health.auth.Users
import org.branneman.health.data.DailyEnergy
import org.branneman.health.data.LogEntry
import org.branneman.health.data.UserProfile
import org.branneman.health.data.Workout
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.mindrot.jbcrypt.BCrypt
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DynamicBudgetIntegrationTest {

    companion object {
        private val ds = TestDatabase.dataSource
        val testUserId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000010")
        private const val TEST_EMAIL = "dynamic-budget-test@test.local"
        private const val TEST_PASSWORD = "testpassword"
        private val TEST_HASH = BCrypt.hashpw(TEST_PASSWORD, BCrypt.gensalt(4))

        init {
            Database.connect(ds)
            transaction {
                Users.deleteWhere { username eq TEST_EMAIL }
                Users.deleteWhere { id eq testUserId }
                Users.insert {
                    it[id]           = testUserId
                    it[username]     = TEST_EMAIL
                    it[passwordHash] = TEST_HASH
                }
            }
        }
    }

    @org.junit.Before fun cleanMutableRows() {
        transaction {
            DailyEnergy.deleteWhere { userId eq testUserId }
            Workout.deleteWhere    { userId eq testUserId }
            LogEntry.deleteWhere   { userId eq testUserId }
            UserProfile.deleteWhere { userId eq testUserId }
            UserProfile.insert {
                it[userId]        = testUserId
                it[heightCm]      = 177
                it[birthYear]     = 1986
                it[sex]           = "male"
                it[goalWeightKg]  = 74.0.toBigDecimal()
                it[activityLevel] = "lightly_active"
                it[targetDeficit] = 300
                it[phase]         = "loss"
                it[vacationMode]  = false
                it[updatedAt]     = OffsetDateTime.now()
                // wakeTime and bedtime use column defaults: 07:00 and 23:00
            }
        }
    }

    private fun appTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(ds) }
        block()
    }

    private suspend fun ApplicationTestBuilder.login(): String {
        val r = client.post("/auth/token") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$TEST_EMAIL","password":"$TEST_PASSWORD"}""")
        }
        return Json.parseToJsonElement(r.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content
    }

    private fun insertEnergy(date: LocalDate, totalKcal: Int) = transaction {
        DailyEnergy.insert {
            it[userId]     = testUserId
            it[DailyEnergy.date]       = date
            it[bmrKcal]    = 1800
            it[activeKcal] = totalKcal - 1800
            it[DailyEnergy.totalKcal]  = totalKcal
            it[dataSource] = "polar"
        }
    }

    private fun insertWorkout(date: LocalDate) = transaction {
        Workout.insert {
            it[id]     = UUID.randomUUID()
            it[userId] = testUserId
            it[Workout.date] = date
            it[type]   = "climbing"
        }
    }

    private fun insertQuickAdd(date: LocalDate, kcal: Int) = transaction {
        exec(
            "INSERT INTO log_entry (id, user_id, logged_at, meal_type, quick_add_kcal, created_at) " +
            "VALUES (gen_random_uuid(), '$testUserId', '${date.atTime(12, 0).atOffset(ZoneOffset.UTC)}', " +
            "'unknown'::meal_type, $kcal, NOW())"
        )
    }

    @Test fun `no history - dynamic params are null`() = appTest {
        val token = login()
        val today = LocalDate.now().toString()
        val r = client.get("/summary/today?date=$today") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals(JsonNull, body["expectedTodaySport"])
        assertEquals(JsonNull, body["eatingFractionSport"])
        assertFalse(body["postWorkoutModeSport"]!!.jsonPrimitive.boolean)
    }

    @Test fun `5 logged sport days - Approach 2 fraction in response`() = appTest {
        val token = login()
        val today = LocalDate.now()
        // Insert 5 sport days with food logs (yesterday through 5 days ago)
        for (i in 1..5) {
            val d = today.minusDays(i.toLong())
            insertEnergy(d, totalKcal = 2400)
            insertWorkout(d)
            insertQuickAdd(d, kcal = 2000)
        }
        val r = client.get("/summary/today?date=$today") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertNotNull(body["expectedTodaySport"])
        assertEquals(2400, body["expectedTodaySport"]!!.jsonPrimitive.int)
        // Approach 2: avg_in / avg_out = 2000 / 2400
        val fraction = body["eatingFractionSport"]!!.jsonPrimitive.double
        assertEquals(2000.0 / 2400.0, fraction, 0.001)
    }

    @Test fun `10 qualifying sport days - Approach 3 fraction in response`() = appTest {
        val token = login()
        val today = LocalDate.now()
        // 10 qualifying days (caloriesIn = 2100 ≤ 2400-300+100=2200) + 2 non-qualifying (caloriesIn = 2500)
        for (i in 1..10) {
            val d = today.minusDays(i.toLong())
            insertEnergy(d, totalKcal = 2400)
            insertWorkout(d)
            insertQuickAdd(d, kcal = 2100)
        }
        for (i in 11..12) {
            val d = today.minusDays(i.toLong())
            insertEnergy(d, totalKcal = 2400)
            insertWorkout(d)
            insertQuickAdd(d, kcal = 2500)
        }
        val r = client.get("/summary/today?date=$today") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        // Approach 3: filters to 10 qualifying days → avg_in=2100, avg_out=2400
        val fraction = body["eatingFractionSport"]!!.jsonPrimitive.double
        assertEquals(2100.0 / 2400.0, fraction, 0.001)
        // Confirm it differs from Approach 2 (2500 non-qualifying days would shift it)
        assertNotEquals(2167.0 / 2400.0, fraction, 0.001)
    }

    @Test fun `post-workout mode triggers when today burn is at least 90 percent of expected`() = appTest {
        val token = login()
        val today = LocalDate.now()
        // Establish sport history (5 logged days, expectedSport = 2400)
        for (i in 1..5) {
            val d = today.minusDays(i.toLong())
            insertEnergy(d, totalKcal = 2400)
            insertWorkout(d)
            insertQuickAdd(d, kcal = 2000)
        }
        // Today's burn = 2160 = 90% of 2400
        insertEnergy(today, totalKcal = 2160)

        val r = client.get("/summary/today?date=$today") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals(2160, body["actualBurnedSoFar"]!!.jsonPrimitive.int)
        assertTrue(body["postWorkoutModeSport"]!!.jsonPrimitive.boolean)
    }

    @Test fun `wakeTime and bedtime from user profile appear in response`() = appTest {
        // Override profile with non-default times
        transaction {
            UserProfile.update({ UserProfile.userId eq testUserId }) {
                it[wakeTime] = LocalTime.of(6, 30)
                it[bedtime]  = LocalTime.of(22, 0)
            }
        }
        val token = login()
        val today = LocalDate.now().toString()
        val r = client.get("/summary/today?date=$today") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals("06:30", body["wakeTime"]!!.jsonPrimitive.content)
        assertEquals("22:00", body["bedtime"]!!.jsonPrimitive.content)
    }
}
```

- [ ] **Run the integration tests:**

```bash
./gradlew :server:test --tests "org.branneman.health.DynamicBudgetIntegrationTest"
```

Expected: all 5 tests **PASS**.

- [ ] **Commit:**

```bash
git add server/src/test/kotlin/org/branneman/health/DynamicBudgetIntegrationTest.kt
git commit -m "test(server): add DynamicBudgetIntegrationTest (slot #10)"
```

---

## Task 3 — UUID registry update

**Files:** `docs/testing-manifesto.md`

- [ ] **Add row #10 to the UUID registry table.** Find the line `| 9 | \`...000009\` |` and insert after it:

```
| 10 | `...000010` | `DynamicBudgetIntegrationTest` | `dynamic-budget-test@test.local` |
```

- [ ] **Run all server tests to confirm nothing regressed:**

```bash
./gradlew :server:test
```

- [ ] **Commit:**

```bash
git add docs/testing-manifesto.md
git commit -m "docs: add UUID registry slot #10 for DynamicBudgetIntegrationTest"
```

---

## Task 4 — DynamicBudgetParamsEntity + DynamicBudgetParamsDao

**Files:**
- Create: `app/src/main/kotlin/org/branneman/health/db/entities/DynamicBudgetParamsEntity.kt`
- Create: `app/src/main/kotlin/org/branneman/health/db/dao/DynamicBudgetParamsDao.kt`

- [ ] **Create the entity:**

```kotlin
package org.branneman.health.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

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

- [ ] **Create the DAO:**

```kotlin
package org.branneman.health.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import org.branneman.health.db.entities.DynamicBudgetParamsEntity

@Dao
interface DynamicBudgetParamsDao {
    @Upsert
    suspend fun upsert(params: DynamicBudgetParamsEntity)

    @Query("SELECT * FROM dynamic_budget_params WHERE date = :date LIMIT 1")
    suspend fun getForDate(date: String): DynamicBudgetParamsEntity?
}
```

- [ ] **Commit** (no tests yet — the DAO is wired and tested in later tasks):

```bash
git add app/src/main/kotlin/org/branneman/health/db/entities/DynamicBudgetParamsEntity.kt \
        app/src/main/kotlin/org/branneman/health/db/dao/DynamicBudgetParamsDao.kt
git commit -m "feat(app): add DynamicBudgetParamsEntity and DynamicBudgetParamsDao"
```

---

## Task 5 — MIGRATION_4_5 and HealthDatabase v5

**Files:**
- `app/src/main/kotlin/org/branneman/health/HealthApplication.kt`
- `app/src/main/kotlin/org/branneman/health/db/HealthDatabase.kt`

- [ ] **Add `MIGRATION_4_5` in `HealthApplication.kt`** (after the existing `MIGRATION_3_4` block):

```kotlin
private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS dynamic_budget_params (" +
            "date TEXT NOT NULL PRIMARY KEY, " +
            "expectedTodaySport INTEGER, " +
            "expectedTodayNonSport INTEGER, " +
            "eatingFractionSport REAL, " +
            "eatingFractionNonSport REAL, " +
            "postWorkoutModeSport INTEGER NOT NULL, " +
            "postWorkoutModeNonSport INTEGER NOT NULL)"
        )
    }
}
```

- [ ] **Add `MIGRATION_4_5` to the builder** in `HealthApplication.onCreate()`:

Change:
```kotlin
.addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
```
To:
```kotlin
.addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
```

- [ ] **Bump `HealthDatabase.kt`** — version 4 → 5, add `DynamicBudgetParamsEntity::class`, add `dynamicBudgetParamsDao()`:

```kotlin
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
        SportTonightEntity::class,
        DynamicBudgetParamsEntity::class,
    ],
    version = 5,
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
    abstract fun sportTonightDao(): SportTonightDao
    abstract fun dynamicBudgetParamsDao(): DynamicBudgetParamsDao
}
```

- [ ] **Verify app compiles:**

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Commit:**

```bash
git add app/src/main/kotlin/org/branneman/health/HealthApplication.kt \
        app/src/main/kotlin/org/branneman/health/db/HealthDatabase.kt
git commit -m "feat(app): add Room MIGRATION_4_5 and bump database version to 5"
```

---

## Task 6 — DynamicBudgetParamsDaoTest

**Files:** Create `app/src/test/kotlin/org/branneman/health/db/dao/DynamicBudgetParamsDaoTest.kt`

- [ ] **Write the failing test file first:**

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
    private lateinit var dao: DynamicBudgetParamsDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HealthDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.dynamicBudgetParamsDao()
    }

    @After fun tearDown() { db.close() }

    @Test fun `getForDate returns null when empty`() = runTest {
        assertNull(dao.getForDate("2026-06-15"))
    }

    @Test fun `upsert and getForDate round-trips all fields`() = runTest {
        val entity = DynamicBudgetParamsEntity(
            date                   = "2026-06-15",
            expectedTodaySport     = 2400,
            expectedTodayNonSport  = 2100,
            eatingFractionSport    = 0.875,
            eatingFractionNonSport = 0.833,
            postWorkoutModeSport   = true,
            postWorkoutModeNonSport = false,
        )
        dao.upsert(entity)
        val result = dao.getForDate("2026-06-15")
        assertNotNull(result)
        assertEquals(2400, result.expectedTodaySport)
        assertEquals(2100, result.expectedTodayNonSport)
        assertEquals(0.875, result.eatingFractionSport!!, 0.001)
        assertEquals(0.833, result.eatingFractionNonSport!!, 0.001)
        assertEquals(true, result.postWorkoutModeSport)
        assertEquals(false, result.postWorkoutModeNonSport)
    }

    @Test fun `upsert overwrites existing row for same date`() = runTest {
        dao.upsert(DynamicBudgetParamsEntity(
            date = "2026-06-15", expectedTodaySport = 2400, expectedTodayNonSport = null,
            eatingFractionSport = null, eatingFractionNonSport = null,
            postWorkoutModeSport = false, postWorkoutModeNonSport = false,
        ))
        dao.upsert(DynamicBudgetParamsEntity(
            date = "2026-06-15", expectedTodaySport = 2500, expectedTodayNonSport = 2000,
            eatingFractionSport = 0.9, eatingFractionNonSport = 0.8,
            postWorkoutModeSport = true, postWorkoutModeNonSport = false,
        ))
        val result = dao.getForDate("2026-06-15")
        assertNotNull(result)
        assertEquals(2500, result.expectedTodaySport)
        assertEquals(0.9, result.eatingFractionSport!!, 0.001)
    }

    @Test fun `getForDate returns null for different date`() = runTest {
        dao.upsert(DynamicBudgetParamsEntity(
            date = "2026-06-15", expectedTodaySport = 2400, expectedTodayNonSport = null,
            eatingFractionSport = null, eatingFractionNonSport = null,
            postWorkoutModeSport = false, postWorkoutModeNonSport = false,
        ))
        assertNull(dao.getForDate("2026-06-16"))
    }

    @Test fun `stores null fractions and nulls round-trip`() = runTest {
        dao.upsert(DynamicBudgetParamsEntity(
            date = "2026-06-15", expectedTodaySport = null, expectedTodayNonSport = null,
            eatingFractionSport = null, eatingFractionNonSport = null,
            postWorkoutModeSport = false, postWorkoutModeNonSport = false,
        ))
        val result = dao.getForDate("2026-06-15")
        assertNotNull(result)
        assertNull(result.expectedTodaySport)
        assertNull(result.eatingFractionSport)
    }
}
```

- [ ] **Run tests:**

```bash
./gradlew :app:test --tests "org.branneman.health.db.dao.DynamicBudgetParamsDaoTest"
```

Expected: all 5 tests **PASS**.

- [ ] **Commit:**

```bash
git add app/src/test/kotlin/org/branneman/health/db/dao/DynamicBudgetParamsDaoTest.kt
git commit -m "test(app): add DynamicBudgetParamsDaoTest"
```

---

## Task 7 — TestFactories.kt: add wakeTime/bedtime to aUserProfile

**Files:** `app/src/test/kotlin/org/branneman/health/TestFactories.kt`

- [ ] **Update `aUserProfile()`** — add `wakeTime` and `bedtime` override parameters:

Change:
```kotlin
fun aUserProfile(
    userId: String = uuid(),
) = UserProfileEntity(
    userId = userId, heightCm = 177, birthYear = 1986, sex = "male",
    goalWeightKg = 74.0, activityLevel = "lightly_active", targetDeficit = 300,
    phase = "loss", vacationMode = false, syncStatus = SyncStatus.SYNCED,
)
```

To:
```kotlin
fun aUserProfile(
    userId: String = uuid(),
    wakeTime: String = "07:00",
    bedtime: String = "23:00",
) = UserProfileEntity(
    userId = userId, heightCm = 177, birthYear = 1986, sex = "male",
    goalWeightKg = 74.0, activityLevel = "lightly_active", targetDeficit = 300,
    phase = "loss", vacationMode = false, wakeTime = wakeTime, bedtime = bedtime,
    syncStatus = SyncStatus.SYNCED,
)
```

- [ ] **Run all app tests to make sure nothing broke:**

```bash
./gradlew :app:test
```

Expected: same number of tests pass as before.

- [ ] **Commit:**

```bash
git add app/src/test/kotlin/org/branneman/health/TestFactories.kt
git commit -m "test(app): add wakeTime and bedtime params to aUserProfile() factory"
```

---

## Task 8 — UserProfileDaoTest: wake/bedtime round-trip

**Files:** `app/src/test/kotlin/org/branneman/health/db/dao/UserProfileDaoTest.kt`

- [ ] **Append one test to `UserProfileDaoTest`** (before the closing `}`):

```kotlin
@Test
fun `upsert and get preserves wakeTime and bedtime`() = runTest {
    val userId = uuid()
    dao.upsert(aUserProfile(userId = userId, wakeTime = "06:30", bedtime = "22:00"))
    val result = dao.get()
    assertNotNull(result)
    assertEquals("06:30", result.wakeTime)
    assertEquals("22:00", result.bedtime)
}
```

- [ ] **Run:**

```bash
./gradlew :app:test --tests "org.branneman.health.db.dao.UserProfileDaoTest"
```

Expected: all tests **PASS** including the new one.

- [ ] **Commit:**

```bash
git add app/src/test/kotlin/org/branneman/health/db/dao/UserProfileDaoTest.kt
git commit -m "test(app): add wakeTime/bedtime round-trip test to UserProfileDaoTest"
```

---

## Task 9 — computeDynamicCaloriesLeft: TDD (tests first, then implementation)

**Files:**
- Modify (tests first): `app/src/test/kotlin/org/branneman/health/dashboard/DashboardLogicTest.kt`
- Modify (implementation): `app/src/main/kotlin/org/branneman/health/dashboard/DashboardViewModel.kt`

### Step 9a: Write the failing tests

- [ ] **Append 9 tests to `DashboardLogicTest`** (before the closing `}`):

```kotlin
// --- computeDynamicCaloriesLeft ---
//
// Base values for all formula tests:
//   wakeTime = "07:00" (420 min)
//   bedtime  = "23:00" (1380 min)
//   totalAwake = 960 min
//   expectedToday = 2400
//   eatingFraction = 0.875  →  eating budget = 2400 × 0.875 = 2100  (D=300 baked in)

@Test fun `formula - post-workout with actual burnedSoFar`() {
    // burnedSoFar=2160 (90% of expected), caloriesIn=1500
    // caloriesLeft = (2160 × 0.875 - 1500).toInt() = 390
    assertEquals(390, computeDynamicCaloriesLeft(
        wakeTimeStr = "07:00", bedtimeStr = "23:00",
        expectedToday = 2400, eatingFraction = 0.875,
        burnedSoFar = 2160, caloriesIn = 1500,
        postWorkoutMode = true, nowMinutes = 720,
    ))
}

@Test fun `formula - post-workout with null burnedSoFar uses expectedToday`() {
    // caloriesLeft = (2400 × 0.875 - 0).toInt() = 2100
    assertEquals(2100, computeDynamicCaloriesLeft(
        wakeTimeStr = "07:00", bedtimeStr = "23:00",
        expectedToday = 2400, eatingFraction = 0.875,
        burnedSoFar = null, caloriesIn = 0,
        postWorkoutMode = true, nowMinutes = 720,
    ))
}

@Test fun `formula - start of day (elapsed 0) full budget available`() {
    // elapsed=0, burnedSoFarEst=0, pastAllowance=0, overshoot=0
    // caloriesLeft = (2400 × 0.875 - 0).toInt() = 2100
    assertEquals(2100, computeDynamicCaloriesLeft(
        wakeTimeStr = "07:00", bedtimeStr = "23:00",
        expectedToday = 2400, eatingFraction = 0.875,
        burnedSoFar = null, caloriesIn = 0,
        postWorkoutMode = false, nowMinutes = 420,
    ))
}

@Test fun `formula - mid-day on pace no penalty`() {
    // nowMinutes=660 (11am), elapsed=240, totalAwake=960
    // burnedSoFarEst = (2400 × 240 / 960).toInt() = 600
    // pastAllowance = 600 × 0.875 = 525.0
    // caloriesIn = 525 (on pace), overshoot = 0
    // remainingBurn = 1800
    // caloriesLeft = (1800 × 0.875 - 0).toInt() = 1575
    assertEquals(1575, computeDynamicCaloriesLeft(
        wakeTimeStr = "07:00", bedtimeStr = "23:00",
        expectedToday = 2400, eatingFraction = 0.875,
        burnedSoFar = null, caloriesIn = 525,
        postWorkoutMode = false, nowMinutes = 660,
    ))
}

@Test fun `formula - mid-day over pace penalised by overshoot`() {
    // caloriesIn = 725 (200 over pastAllowance of 525)
    // overshoot = 200
    // caloriesLeft = (1800 × 0.875 - 200).toInt() = 1375
    assertEquals(1375, computeDynamicCaloriesLeft(
        wakeTimeStr = "07:00", bedtimeStr = "23:00",
        expectedToday = 2400, eatingFraction = 0.875,
        burnedSoFar = null, caloriesIn = 725,
        postWorkoutMode = false, nowMinutes = 660,
    ))
}

@Test fun `formula - end of day on budget returns 0`() {
    // elapsed=960 (bedtime), burnedSoFarEst=2400, remainingBurn=0
    // pastAllowance = 2400 × 0.875 = 2100, caloriesIn=2100, overshoot=0
    // caloriesLeft = 0
    assertEquals(0, computeDynamicCaloriesLeft(
        wakeTimeStr = "07:00", bedtimeStr = "23:00",
        expectedToday = 2400, eatingFraction = 0.875,
        burnedSoFar = null, caloriesIn = 2100,
        postWorkoutMode = false, nowMinutes = 1380,
    ))
}

@Test fun `formula - end of day over budget returns negative`() {
    // caloriesIn=2300, overshoot=200, caloriesLeft = -200
    assertEquals(-200, computeDynamicCaloriesLeft(
        wakeTimeStr = "07:00", bedtimeStr = "23:00",
        expectedToday = 2400, eatingFraction = 0.875,
        burnedSoFar = null, caloriesIn = 2300,
        postWorkoutMode = false, nowMinutes = 1380,
    ))
}

@Test fun `formula - nowMinutes before wakeTime clamps to wakeTime`() {
    // nowMinutes=300 (5am) → clamped to elapsed=0 → same as start of day
    assertEquals(2100, computeDynamicCaloriesLeft(
        wakeTimeStr = "07:00", bedtimeStr = "23:00",
        expectedToday = 2400, eatingFraction = 0.875,
        burnedSoFar = null, caloriesIn = 0,
        postWorkoutMode = false, nowMinutes = 300,
    ))
}

@Test fun `formula - nowMinutes after bedtime clamps to bedtime`() {
    // nowMinutes=1440 (midnight) → clamped to elapsed=960 → same as end of day
    assertEquals(0, computeDynamicCaloriesLeft(
        wakeTimeStr = "07:00", bedtimeStr = "23:00",
        expectedToday = 2400, eatingFraction = 0.875,
        burnedSoFar = null, caloriesIn = 2100,
        postWorkoutMode = false, nowMinutes = 1440,
    ))
}
```

- [ ] **Run tests to confirm they FAIL** (function doesn't exist yet):

```bash
./gradlew :app:test --tests "org.branneman.health.dashboard.DashboardLogicTest" 2>&1 | tail -20
```

Expected: compilation error or test failures (unresolved reference `computeDynamicCaloriesLeft`).

### Step 9b: Implement the function

- [ ] **Add `computeDynamicCaloriesLeft` to `DashboardViewModel.kt`** as a top-level function, after `isValidWeightInput`:

```kotlin
fun computeDynamicCaloriesLeft(
    wakeTimeStr: String,
    bedtimeStr: String,
    expectedToday: Int,
    eatingFraction: Double,
    burnedSoFar: Int?,
    caloriesIn: Int,
    postWorkoutMode: Boolean,
    nowMinutes: Int,
): Int {
    fun parseMinutes(s: String): Int {
        val (h, m) = s.split(":").map { it.toInt() }
        return h * 60 + m
    }
    val wakeMinutes = parseMinutes(wakeTimeStr)
    val bedMinutes  = parseMinutes(bedtimeStr)
    val totalAwake  = maxOf(1, bedMinutes - wakeMinutes)
    val elapsed     = (nowMinutes - wakeMinutes).coerceIn(0, totalAwake)

    if (postWorkoutMode) {
        val todayBurn = burnedSoFar ?: expectedToday
        return (todayBurn * eatingFraction - caloriesIn).toInt()
    }

    val burnedSoFarEst = burnedSoFar
        ?: (expectedToday.toDouble() * elapsed / totalAwake).toInt()
    val remainingBurn = expectedToday - burnedSoFarEst
    val pastAllowance = burnedSoFarEst * eatingFraction
    val overshoot     = maxOf(0.0, caloriesIn - pastAllowance)
    return (remainingBurn * eatingFraction - overshoot).toInt()
}
```

- [ ] **Run tests again:**

```bash
./gradlew :app:test --tests "org.branneman.health.dashboard.DashboardLogicTest"
```

Expected: all tests **PASS** (including the 9 new ones).

- [ ] **Commit:**

```bash
git add app/src/main/kotlin/org/branneman/health/dashboard/DashboardViewModel.kt \
        app/src/test/kotlin/org/branneman/health/dashboard/DashboardLogicTest.kt
git commit -m "feat(app): add computeDynamicCaloriesLeft pure function with unit tests"
```

---

## Task 10 — DashboardUiState refactor and DashboardScreenTest updates

**Files:**
- `app/src/main/kotlin/org/branneman/health/dashboard/DashboardViewModel.kt`
- `app/src/test/kotlin/org/branneman/health/ui/DashboardScreenTest.kt`

This task removes `budgetRemaining` and `adjustedBudgetRemaining` and adds the new fields. Do the refactor and test updates together so the build never breaks.

### Step 10a: Refactor DashboardUiState

- [ ] **Replace the `DashboardUiState` data class** in `DashboardViewModel.kt`:

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
    val eatingFractionSport: Double? = null,
    val eatingFractionNonSport: Double? = null,
    val postWorkoutModeSport: Boolean = false,
    val postWorkoutModeNonSport: Boolean = false,
    val wakeTime: String = "07:00",
    val bedtime: String = "23:00",
    val actualBurnedSoFar: Int? = null,
)
```

### Step 10b: Update DashboardScreenTest

- [ ] **Update `DashboardScreenTest.kt`** — all occurrences of `adjustedBudgetRemaining` → `caloriesLeft`, remove all `budgetRemaining` arguments, and add explicit `budgetLabel` where needed:

Replace the **entire file content** with:

```kotlin
package org.branneman.health.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.branneman.health.dashboard.DashboardUiState
import org.branneman.health.db.entities.SportTonightEntity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DashboardScreenTest {

    @get:Rule val compose = createComposeRule()

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

    @Test fun `shows budget remaining as big number`() {
        render(state = DashboardUiState(
            isLoading = false,
            caloriesIn = 0, caloriesOut = 2147, caloriesOutSource = "estimate",
            targetDeficit = 300, caloriesLeft = 1847,
        ))
        compose.onNodeWithText("1847", substring = true).assertExists()
    }

    @Test fun `shows estimated source label`() {
        render(state = DashboardUiState(
            isLoading = false, caloriesOutSource = "estimate",
            caloriesLeft = 1847, budgetLabel = "left (estimated)",
        ))
        compose.onNodeWithText("estimated", substring = true, ignoreCase = true).assertExists()
    }

    @Test fun `shows calories in and out values`() {
        render(state = DashboardUiState(
            isLoading = false,
            caloriesIn = 520, caloriesOut = 2147, caloriesOutSource = "estimate",
            caloriesLeft = 1327,
        ))
        compose.onNodeWithText("520", substring = true).assertExists()
        compose.onNodeWithText("2147", substring = true).assertExists()
    }

    @Test fun `shows in and out labels`() {
        render(state = DashboardUiState(isLoading = false, caloriesLeft = 1847))
        compose.onNodeWithText("in", substring = true, ignoreCase = true).assertExists()
        compose.onNodeWithText("out", substring = true, ignoreCase = true).assertExists()
    }

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

    // --- Weight chip ---

    @Test fun `weight chip shows dashes when not logged today`() {
        render(state = DashboardUiState(isLoading = false, weightKgToday = null))
        compose.onNodeWithText("-- kg", substring = true).assertExists()
    }

    @Test fun `weight chip shows value when logged today`() {
        render(state = DashboardUiState(isLoading = false, weightKgToday = 82.5))
        compose.onNodeWithText("82.5 kg", substring = true).assertExists()
    }

    @Test fun `tapping weight chip opens log weight dialog`() {
        render(state = DashboardUiState(isLoading = false, weightKgToday = null))
        compose.onNodeWithText("-- kg", substring = true).performClick()
        compose.onNodeWithText("Log weight").assertExists()
    }

    @Test fun `tapping logged weight chip also opens dialog`() {
        render(state = DashboardUiState(isLoading = false, weightKgToday = 82.5))
        compose.onNodeWithText("82.5 kg", substring = true).performClick()
        compose.onNodeWithText("Log weight").assertExists()
    }

    @Test fun `save button is disabled with no input`() {
        render(state = DashboardUiState(isLoading = false, weightKgToday = null))
        compose.onNodeWithText("-- kg", substring = true).performClick()
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test fun `save button is enabled for valid weight`() {
        render(state = DashboardUiState(isLoading = false, weightKgToday = null))
        compose.onNodeWithText("-- kg", substring = true).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("82.5")
        compose.onNodeWithText("Save").assertIsEnabled()
    }

    @Test fun `save button is disabled for two decimal places`() {
        render(state = DashboardUiState(isLoading = false, weightKgToday = null))
        compose.onNodeWithText("-- kg", substring = true).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("82.55")
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test fun `tapping save calls onLogWeight with parsed kg`() {
        var logged: Double? = null
        render(
            state = DashboardUiState(isLoading = false, weightKgToday = null),
            onLogWeight = { logged = it },
        )
        compose.onNodeWithText("-- kg", substring = true).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("82.5")
        compose.onNodeWithText("Save").performClick()
        assertEquals(82.5, logged)
    }

    @Test fun `dialog pre-fills with current value when editing`() {
        render(state = DashboardUiState(isLoading = false, weightKgToday = 82.5))
        compose.onNodeWithText("82.5 kg", substring = true).performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("82.5")
    }

    // --- budgetLabel ---

    @Test fun `shows left label when budgetLabel is left`() {
        render(state = DashboardUiState(isLoading = false, caloriesLeft = 1847, budgetLabel = "left"))
        compose.onNodeWithText("left", substring = false, ignoreCase = true).assertExists()
    }

    @Test fun `shows kcal over and negative number when over budget`() {
        render(state = DashboardUiState(isLoading = false, caloriesLeft = -200, budgetLabel = "kcal over"))
        compose.onNodeWithText("-200", substring = true).assertExists()
        compose.onNodeWithText("kcal over", substring = true, ignoreCase = true).assertExists()
    }

    @Test fun `shows left balance label`() {
        render(state = DashboardUiState(isLoading = false, caloriesLeft = 2100, budgetLabel = "left (balance)"))
        compose.onNodeWithText("left (balance)", substring = true, ignoreCase = true).assertExists()
    }
}
```

- [ ] **Run tests — expect compile errors** since `BudgetSection` still references `adjustedBudgetRemaining`:

```bash
./gradlew :app:test --tests "org.branneman.health.ui.DashboardScreenTest" 2>&1 | tail -30
```

Note: errors are expected until Task 12 updates `DashboardScreen.kt`. Continue to Task 11.

---

## Task 11 — DashboardViewModel: full wiring

**Files:** `app/src/main/kotlin/org/branneman/health/dashboard/DashboardViewModel.kt`

- [ ] **Add `kotlinx.coroutines.delay` import** (add to existing imports):

```kotlin
import kotlinx.coroutines.delay
```

- [ ] **Replace the `DashboardViewModel` class body** (keep all the top-level functions above it):

```kotlin
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
        viewModelScope.launch { tickBudget() }
    }

    private fun refreshCaloriesLeft() {
        val state = _uiState.value
        val isSportTonight = state.sportTonight != null

        val expectedToday = if (isSportTonight) {
            state.expectedTodaySport ?: state.caloriesOut
        } else {
            state.expectedTodayNonSport ?: state.caloriesOut
        }

        val eatingFraction = if (isSportTonight) {
            state.eatingFractionSport
                ?: if (expectedToday > 0) maxOf(0.0, (expectedToday - state.targetDeficit).toDouble() / expectedToday) else 0.0
        } else {
            state.eatingFractionNonSport
                ?: if (expectedToday > 0) maxOf(0.0, (expectedToday - state.targetDeficit).toDouble() / expectedToday) else 0.0
        }

        val postWorkoutMode = if (isSportTonight) state.postWorkoutModeSport else state.postWorkoutModeNonSport

        val now = java.time.LocalTime.now()
        val nowMinutes = now.hour * 60 + now.minute

        val caloriesLeft = computeDynamicCaloriesLeft(
            wakeTimeStr     = state.wakeTime,
            bedtimeStr      = state.bedtime,
            expectedToday   = expectedToday,
            eatingFraction  = eatingFraction,
            burnedSoFar     = state.actualBurnedSoFar,
            caloriesIn      = state.caloriesIn,
            postWorkoutMode = postWorkoutMode,
            nowMinutes      = nowMinutes,
        )

        val budgetLabel = when {
            caloriesLeft < 0                      -> "kcal over"
            state.targetDeficit == 0              -> "left (balance)"
            state.caloriesOutSource == "estimate" -> "left (estimated)"
            else                                  -> "left"
        }

        _uiState.update { it.copy(caloriesLeft = caloriesLeft, budgetLabel = budgetLabel) }
    }

    private suspend fun tickBudget() {
        while (true) {
            delay(60_000L)
            refreshCaloriesLeft()
        }
    }

    private suspend fun observeLogEntries() {
        val stored = tokenStore.tokenFlow.first() ?: return
        app.db.logEntryDao().observeAll().collect { entries ->
            val today = LocalDate.now().toString()
            val caloriesIn = entries
                .filter { it.userId == stored.userId && it.loggedAt.startsWith(today) }
                .sumOf { it.quickAddKcal ?: 0 }
            _uiState.update { it.copy(caloriesIn = caloriesIn) }
            refreshCaloriesLeft()
        }
    }

    private suspend fun load() {
        val stored = tokenStore.tokenFlow.first() ?: return
        val today = LocalDate.now().toString()

        val localState = computeLocalState(stored.userId, today)
        _uiState.value = localState
        refreshCaloriesLeft()

        runCatching { apiClient.getTodaySummary(stored.token, today) }
            .onSuccess { dto ->
                app.db.dynamicBudgetParamsDao().upsert(
                    org.branneman.health.db.entities.DynamicBudgetParamsEntity(
                        date                    = today,
                        expectedTodaySport      = dto.expectedTodaySport,
                        expectedTodayNonSport   = dto.expectedTodayNonSport,
                        eatingFractionSport     = dto.eatingFractionSport,
                        eatingFractionNonSport  = dto.eatingFractionNonSport,
                        postWorkoutModeSport    = dto.postWorkoutModeSport,
                        postWorkoutModeNonSport = dto.postWorkoutModeNonSport,
                    )
                )
                _uiState.update { state ->
                    state.copy(
                        isLoading               = false,
                        caloriesOut             = dto.caloriesOut,
                        caloriesOutSource       = dto.caloriesOutSource,
                        targetDeficit           = dto.targetDeficit,
                        expectedTodaySport      = dto.expectedTodaySport,
                        expectedTodayNonSport   = dto.expectedTodayNonSport,
                        eatingFractionSport     = dto.eatingFractionSport,
                        eatingFractionNonSport  = dto.eatingFractionNonSport,
                        postWorkoutModeSport    = dto.postWorkoutModeSport,
                        postWorkoutModeNonSport = dto.postWorkoutModeNonSport,
                        wakeTime                = dto.wakeTime,
                        bedtime                 = dto.bedtime,
                        actualBurnedSoFar       = dto.actualBurnedSoFar,
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
        val caloriesIn   = app.db.logEntryDao().sumQuickAddKcalForDate(userId, "$today%")
        val sport        = app.db.sportTonightDao().getForDate(today)?.takeIf { it.date == today }
        val params       = app.db.dynamicBudgetParamsDao().getForDate(today)

        val (caloriesOut, source) = if (energy != null) {
            energy.totalKcal to "polar_today"
        } else {
            val weightKg = latestWeight ?: profile.goalWeightKg
            val age = LocalDate.now().year - profile.birthYear
            val tdee = (computeBmr(profile.sex, weightKg, profile.heightCm, age) * activityMultiplier(profile.activityLevel)).toInt()
            tdee to "estimate"
        }

        return DashboardUiState(
            isLoading               = false,
            caloriesIn              = caloriesIn,
            caloriesOut             = caloriesOut,
            caloriesOutSource       = source,
            targetDeficit           = profile.targetDeficit,
            sportTonight            = sport,
            weightKgToday           = weightToday,
            expectedTodaySport      = params?.expectedTodaySport,
            expectedTodayNonSport   = params?.expectedTodayNonSport,
            eatingFractionSport     = params?.eatingFractionSport,
            eatingFractionNonSport  = params?.eatingFractionNonSport,
            postWorkoutModeSport    = params?.postWorkoutModeSport ?: false,
            postWorkoutModeNonSport = params?.postWorkoutModeNonSport ?: false,
            wakeTime                = profile.wakeTime,
            bedtime                 = profile.bedtime,
            actualBurnedSoFar       = energy?.totalKcal,
        )
        // caloriesLeft and budgetLabel are set by refreshCaloriesLeft() called after this
    }

    fun setSportTonight(activityType: String, intensity: String) {
        viewModelScope.launch {
            val profile = app.db.userProfileDao().get() ?: return@launch
            val latestWeight = app.db.bodyWeightDao().observeAll().first().firstOrNull()?.kg
                ?: profile.goalWeightKg
            val today = LocalDate.now().toString()
            val estimatedKcal = computeSportEstimate(activityType, intensity, latestWeight)
            val entity = SportTonightEntity(
                date = today, activityType = activityType,
                intensity = intensity, estimatedKcal = estimatedKcal,
            )
            app.db.sportTonightDao().upsert(entity)
            _uiState.update { it.copy(sportTonight = entity) }
            refreshCaloriesLeft()
        }
    }

    fun clearSportTonight() {
        viewModelScope.launch {
            app.db.sportTonightDao().deleteForDate(LocalDate.now().toString())
            _uiState.update { it.copy(sportTonight = null) }
            refreshCaloriesLeft()
        }
    }

    fun logWeight(kg: Double) {
        viewModelScope.launch {
            val stored = tokenStore.tokenFlow.first() ?: return@launch
            val today = LocalDate.now().toString()
            app.db.bodyWeightDao().upsert(
                BodyWeightEntity(
                    id         = today,
                    userId     = stored.userId,
                    date       = today,
                    kg         = kg,
                    syncStatus = SyncStatus.PENDING_CREATE,
                )
            )
            _uiState.update { it.copy(weightKgToday = kg) }
        }
    }
}
```

- [ ] **Verify app compiles:**

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL (even with the test file referencing old `BudgetSection` — that's the screen file, not tested yet).

---

## Task 12 — DashboardScreen: use caloriesLeft and budgetLabel

**Files:** `app/src/main/kotlin/org/branneman/health/ui/DashboardScreen.kt`

- [ ] **Replace the `BudgetSection` composable** (lines 66–94):

```kotlin
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
```

- [ ] **Run all app tests:**

```bash
./gradlew :app:test
```

Expected: all tests **PASS** (including the updated `DashboardScreenTest` and the 3 new label tests).

- [ ] **Commit:**

```bash
git add app/src/main/kotlin/org/branneman/health/dashboard/DashboardViewModel.kt \
        app/src/main/kotlin/org/branneman/health/ui/DashboardScreen.kt \
        app/src/test/kotlin/org/branneman/health/ui/DashboardScreenTest.kt
git commit -m "feat(app): implement dynamic calorie budget in DashboardViewModel and DashboardScreen"
```

---

## Task 13 — Settings schedule section (TimeAdjustRow + ViewModel + Screen)

**Files:**
- `app/src/main/kotlin/org/branneman/health/ui/OnboardingScreen.kt` — `TimeAdjustRow` visibility
- `app/src/main/kotlin/org/branneman/health/ui/SettingsViewModel.kt`
- `app/src/main/kotlin/org/branneman/health/ui/SettingsScreen.kt`

### Step 13a: Make TimeAdjustRow internal

- [ ] **In `OnboardingScreen.kt`**, change `private fun TimeAdjustRow` to `internal fun TimeAdjustRow` (line 306):

```kotlin
internal fun TimeAdjustRow(
    label: String,
    time: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
```

### Step 13b: Add ScheduleState and schedule methods to SettingsViewModel

- [ ] **Replace `SettingsViewModel.kt`** with the following:

```kotlin
package org.branneman.health.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.branneman.health.HealthApplication
import org.branneman.health.UserProfileDto
import org.branneman.health.auth.TokenStore
import org.branneman.health.auth.authDataStore
import org.branneman.health.network.HealthApiClient

enum class PolarStatus { Loading, Connected, NotConnected, Unknown }

data class ScheduleState(
    val wakeTime: String = "07:00",
    val bedtime: String = "23:00",
    val savedWakeTime: String = "07:00",
    val savedBedtime: String = "23:00",
    val isSaving: Boolean = false,
    val saveError: String? = null,
) {
    val changed: Boolean get() = wakeTime != savedWakeTime || bedtime != savedBedtime
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as HealthApplication
    private val tokenStore = TokenStore(application.authDataStore)
    private val apiClient = HealthApiClient()

    private val _polarStatus = MutableStateFlow(PolarStatus.Loading)
    val polarStatus: StateFlow<PolarStatus> = _polarStatus

    private val _scheduleState = MutableStateFlow(ScheduleState())
    val scheduleState: StateFlow<ScheduleState> = _scheduleState

    init {
        recheckPolarStatus()
        loadSchedule()
    }

    private fun loadSchedule() {
        viewModelScope.launch {
            val profile = app.db.userProfileDao().get() ?: return@launch
            _scheduleState.value = ScheduleState(
                wakeTime      = profile.wakeTime,
                bedtime       = profile.bedtime,
                savedWakeTime = profile.wakeTime,
                savedBedtime  = profile.bedtime,
            )
        }
    }

    fun updateWakeTime(deltaMinutes: Int) {
        _scheduleState.update { s -> s.copy(wakeTime = adjustTime(s.wakeTime, deltaMinutes)) }
    }

    fun updateBedtime(deltaMinutes: Int) {
        _scheduleState.update { s -> s.copy(bedtime = adjustTime(s.bedtime, deltaMinutes)) }
    }

    fun saveSchedule() {
        viewModelScope.launch {
            _scheduleState.update { it.copy(isSaving = true, saveError = null) }
            val profile = app.db.userProfileDao().get() ?: run {
                _scheduleState.update { it.copy(isSaving = false, saveError = "Profile not found") }
                return@launch
            }
            val token = tokenStore.tokenFlow.first() ?: run {
                _scheduleState.update { it.copy(isSaving = false, saveError = "Not signed in") }
                return@launch
            }
            val s = _scheduleState.value
            runCatching {
                val updated = profile.copy(wakeTime = s.wakeTime, bedtime = s.bedtime)
                app.db.userProfileDao().upsert(updated)
                apiClient.putProfile(token.token, UserProfileDto(
                    heightCm      = updated.heightCm,
                    birthYear     = updated.birthYear,
                    sex           = updated.sex,
                    goalWeightKg  = updated.goalWeightKg,
                    activityLevel = updated.activityLevel,
                    targetDeficit = updated.targetDeficit,
                    phase         = updated.phase,
                    vacationMode  = updated.vacationMode,
                    wakeTime      = updated.wakeTime,
                    bedtime       = updated.bedtime,
                ))
            }.onSuccess {
                _scheduleState.update { it.copy(isSaving = false, savedWakeTime = s.wakeTime, savedBedtime = s.bedtime) }
            }.onFailure { e ->
                _scheduleState.update { it.copy(isSaving = false, saveError = e.message ?: "Save failed") }
            }
        }
    }

    fun recheckPolarStatus() {
        viewModelScope.launch {
            _polarStatus.value = PolarStatus.Loading
            runCatching {
                val token = tokenStore.tokenFlow.first() ?: run {
                    _polarStatus.value = PolarStatus.Unknown
                    return@launch
                }
                val status = apiClient.getPolarStatus(token.token)
                _polarStatus.value = if (status.connected) PolarStatus.Connected else PolarStatus.NotConnected
            }.onFailure {
                _polarStatus.value = PolarStatus.Unknown
            }
        }
    }

    fun connectPolar(onUrl: (String) -> Unit) {
        viewModelScope.launch {
            runCatching {
                val token = tokenStore.tokenFlow.first() ?: return@launch
                onUrl(apiClient.getPolarConnectUrl(token.token))
            }
        }
    }
}
```

### Step 13c: Add schedule section to SettingsScreen

- [ ] **Add `scheduleState` + callback params to `SettingsContent`** and add collection in `SettingsScreen()`:

In `SettingsScreen.kt`, update `SettingsScreen()` to collect `scheduleState`:

```kotlin
@Composable
fun SettingsScreen(
    onSignOut: () -> Unit,
    onNavigateMealButtons: () -> Unit = {},
    onNavigateDrinkButtons: () -> Unit = {},
) {
    val context = LocalContext.current
    var serverReachable by remember { mutableStateOf<Boolean?>(null) }
    val lastSyncedAt by context.syncDataStore.lastSyncedAtFlow.collectAsState(initial = null)
    val viewModel: SettingsViewModel = viewModel()
    val polarStatus by viewModel.polarStatus.collectAsState()
    val scheduleState by viewModel.scheduleState.collectAsState()
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
        onNavigateMealButtons  = onNavigateMealButtons,
        onNavigateDrinkButtons = onNavigateDrinkButtons,
        onSignOut              = onSignOut,
        serverReachable        = serverReachable,
        lastSyncedAt           = lastSyncedAt,
        polarStatus            = polarStatus,
        onConnectPolar         = if (polarStatus == PolarStatus.NotConnected) {
            {
                viewModel.connectPolar { url ->
                    CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
                }
            }
        } else null,
        onSyncNow              = { SyncWorker.syncNow(context) },
        versionName            = BuildConfig.VERSION_NAME,
        scheduleState          = scheduleState,
        onWakeTimeMinus        = { viewModel.updateWakeTime(-30) },
        onWakeTimePlus         = { viewModel.updateWakeTime(+30) },
        onBedtimeMinus         = { viewModel.updateBedtime(-30) },
        onBedtimePlus          = { viewModel.updateBedtime(+30) },
        onSaveSchedule         = { viewModel.saveSchedule() },
    )
}
```

Update `SettingsContent` signature and body — add the schedule params and section:

```kotlin
@Composable
fun SettingsContent(
    onNavigateMealButtons: () -> Unit,
    onNavigateDrinkButtons: () -> Unit = {},
    onSignOut: () -> Unit,
    serverReachable: Boolean? = null,
    lastSyncedAt: Long? = null,
    polarStatus: PolarStatus = PolarStatus.Unknown,
    onConnectPolar: (() -> Unit)? = null,
    onSyncNow: (() -> Unit)? = null,
    versionName: String = "",
    scheduleState: ScheduleState = ScheduleState(),
    onWakeTimeMinus: () -> Unit = {},
    onWakeTimePlus: () -> Unit = {},
    onBedtimeMinus: () -> Unit = {},
    onBedtimePlus: () -> Unit = {},
    onSaveSchedule: () -> Unit = {},
) {
    var showSignOutConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Server: ${
                when (serverReachable) {
                    null -> "Checking…"
                    true -> "Online"
                    false -> "Offline"
                }
            }"
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Last synced: ${
                lastSyncedAt?.let {
                    LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault())
                        .format(syncTimestampFormatter)
                } ?: "Never"
            }",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Polar: ${when (polarStatus) {
                PolarStatus.Loading     -> "Checking…"
                PolarStatus.Connected   -> "Connected"
                PolarStatus.NotConnected -> "Not connected"
                PolarStatus.Unknown     -> "Unknown"
            }}",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (onConnectPolar != null) {
            TextButton(onClick = onConnectPolar, modifier = Modifier.fillMaxWidth()) {
                Text("Connect Polar")
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        TextButton(onClick = onNavigateMealButtons, modifier = Modifier.fillMaxWidth()) {
            Text("Meal buttons →")
        }
        TextButton(onClick = onNavigateDrinkButtons, modifier = Modifier.fillMaxWidth()) {
            Text("Drink buttons →")
        }
        if (onSyncNow != null) {
            TextButton(onClick = onSyncNow, modifier = Modifier.fillMaxWidth()) {
                Text("Sync now")
            }
        }
        // --- Schedule section ---
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Schedule",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        TimeAdjustRow(
            label   = "Wake time",
            time    = scheduleState.wakeTime,
            onMinus = onWakeTimeMinus,
            onPlus  = onWakeTimePlus,
        )
        TimeAdjustRow(
            label   = "Bedtime",
            time    = scheduleState.bedtime,
            onMinus = onBedtimeMinus,
            onPlus  = onBedtimePlus,
        )
        if (scheduleState.changed) {
            Spacer(modifier = Modifier.height(4.dp))
            androidx.compose.material3.Button(
                onClick  = onSaveSchedule,
                enabled  = !scheduleState.isSaving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (scheduleState.isSaving) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    Text("Save schedule")
                }
            }
        }
        scheduleState.saveError?.let { error ->
            Text(
                text  = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // --- End schedule section ---
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text  = "Version: $versionName",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = { showSignOutConfirm = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Sign out", color = MaterialTheme.colorScheme.error)
        }
    }

    if (showSignOutConfirm) {
        AlertDialog(
            onDismissRequest = { showSignOutConfirm = false },
            title = { Text("Sign out?") },
            text  = { Text("Your data will be removed from this device. It's all saved on the server.") },
            confirmButton = {
                TextButton(onClick = {
                    showSignOutConfirm = false
                    onSignOut()
                }) { Text("Sign out") }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutConfirm = false }) { Text("Cancel") }
            },
        )
    }
}
```

Also add needed imports to `SettingsScreen.kt`:
```kotlin
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
```

- [ ] **Verify compile:**

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Commit:**

```bash
git add app/src/main/kotlin/org/branneman/health/ui/OnboardingScreen.kt \
        app/src/main/kotlin/org/branneman/health/ui/SettingsViewModel.kt \
        app/src/main/kotlin/org/branneman/health/ui/SettingsScreen.kt
git commit -m "feat(app): add wake/bedtime schedule section to SettingsScreen"
```

---

## Task 14 — SettingsScreenTest: add schedule tests

**Files:** `app/src/test/kotlin/org/branneman/health/ui/SettingsScreenTest.kt`

- [ ] **Add imports at top of `SettingsScreenTest.kt`:**

```kotlin
import kotlin.test.assertTrue
```

- [ ] **Append 4 tests to `SettingsScreenTest`** (before the closing `}`):

```kotlin
// --- Schedule section ---

@Test fun `schedule section shows Wake time and Bedtime labels`() {
    compose.setContent {
        MaterialTheme {
            SettingsContent(
                onNavigateMealButtons = {},
                onSignOut = {},
                scheduleState = ScheduleState(wakeTime = "07:00", bedtime = "23:00"),
            )
        }
    }
    compose.onNodeWithText("Wake time", substring = true, ignoreCase = true).assertExists()
    compose.onNodeWithText("Bedtime", substring = true, ignoreCase = true).assertExists()
}

@Test fun `tapping wake time plus calls onWakeTimePlus`() {
    var called = false
    compose.setContent {
        MaterialTheme {
            SettingsContent(
                onNavigateMealButtons = {},
                onSignOut = {},
                scheduleState = ScheduleState(wakeTime = "07:00"),
                onWakeTimePlus = { called = true },
            )
        }
    }
    // First "+" button in the Schedule section belongs to Wake time row
    compose.onAllNodesWithText("+")[0].performClick()
    assertTrue(called)
}

@Test fun `Save schedule button hidden when schedule not changed`() {
    compose.setContent {
        MaterialTheme {
            SettingsContent(
                onNavigateMealButtons = {},
                onSignOut = {},
                scheduleState = ScheduleState(
                    wakeTime = "07:00", savedWakeTime = "07:00",
                    bedtime  = "23:00", savedBedtime  = "23:00",
                ),
            )
        }
    }
    compose.onNodeWithText("Save schedule", substring = true, ignoreCase = true).assertDoesNotExist()
}

@Test fun `Save schedule button disabled while saving`() {
    compose.setContent {
        MaterialTheme {
            SettingsContent(
                onNavigateMealButtons = {},
                onSignOut = {},
                scheduleState = ScheduleState(
                    wakeTime = "07:30", savedWakeTime = "07:00",
                    isSaving = true,
                ),
            )
        }
    }
    compose.onNodeWithText("Save schedule", substring = true, ignoreCase = true).assertIsNotEnabled()
}
```

- [ ] **Run:**

```bash
./gradlew :app:test --tests "org.branneman.health.ui.SettingsScreenTest"
```

Expected: all 6 tests **PASS** (2 existing + 4 new).

- [ ] **Commit:**

```bash
git add app/src/test/kotlin/org/branneman/health/ui/SettingsScreenTest.kt
git commit -m "test(app): add schedule section tests to SettingsScreenTest"
```

---

## Task 15 — OnboardingScreenTest: add step 4 tests

**Files:** `app/src/test/kotlin/org/branneman/health/ui/OnboardingScreenTest.kt`

- [ ] **Append step 4 helper + 3 tests to `OnboardingScreenTest`** (before the closing `}`):

```kotlin
private fun renderStep4(
    state: OnboardingUiState = OnboardingUiState(wakeTime = "07:00", bedtime = "23:00"),
    onUpdate: (OnboardingUiState.() -> OnboardingUiState) -> Unit = {},
    onBack: () -> Unit = {},
    onSave: () -> Unit = {},
) {
    compose.setContent {
        OnboardingStep4(state = state, onUpdate = onUpdate, onBack = onBack, onSave = onSave)
    }
}

// Step 4

@Test fun `step 4 shows Wake time and Bedtime labels`() {
    renderStep4()
    compose.onNodeWithText("Wake time", substring = true, ignoreCase = true).assertExists()
    compose.onNodeWithText("Bedtime", substring = true, ignoreCase = true).assertExists()
}

@Test fun `step 4 shows current wake time value`() {
    renderStep4(state = OnboardingUiState(wakeTime = "06:30", bedtime = "22:00"))
    compose.onNodeWithText("06:30", substring = true).assertExists()
}

@Test fun `step 4 tapping plus on Wake time row calls onUpdate`() {
    var updateCalled = false
    renderStep4(onUpdate = { updateCalled = true })
    // First "+" button belongs to Wake time row
    compose.onAllNodesWithText("+")[0].performClick()
    assert(updateCalled)
}
```

- [ ] **Run:**

```bash
./gradlew :app:test --tests "org.branneman.health.ui.OnboardingScreenTest"
```

Expected: all tests **PASS** (existing + 3 new).

- [ ] **Run the full app test suite to confirm nothing regressed:**

```bash
./gradlew :app:test
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Commit:**

```bash
git add app/src/test/kotlin/org/branneman/health/ui/OnboardingScreenTest.kt
git commit -m "test(app): add OnboardingScreen step 4 tests"
```

---

## Final check

- [ ] **Run all tests (server + app):**

```bash
./gradlew test
```

Expected: all tests across both modules **PASS**.

---

## Self-review

**Spec coverage check:**

| Spec requirement | Task |
|---|---|
| `computeDynamic` unit tests (9 cases) | Task 1 |
| Server integration test, UUID slot #10 | Task 2 |
| UUID registry updated | Task 3 |
| `DynamicBudgetParamsEntity` + `DynamicBudgetParamsDao` | Task 4 |
| `MIGRATION_4_5`, `HealthDatabase` v5 | Task 5 |
| `DynamicBudgetParamsDaoTest` | Task 6 |
| `aUserProfile()` gains wakeTime/bedtime | Task 7 |
| `UserProfileDaoTest` wake/bedtime round-trip | Task 8 |
| `computeDynamicCaloriesLeft` pure function + 9 tests | Task 9 |
| `DashboardUiState` refactor, all existing tests updated | Task 10 |
| `DashboardViewModel` full wiring (tick, refresh, load, sport-tonight) | Task 11 |
| `BudgetSection` uses `caloriesLeft` + `budgetLabel` | Task 12 |
| `TimeAdjustRow` → `internal`, `SettingsViewModel` schedule state, `SettingsContent` schedule section | Task 13 |
| `SettingsScreenTest` +4 tests | Task 14 |
| `OnboardingScreenTest` step 4 +3 tests | Task 15 |

**Type consistency check:** `computeDynamicCaloriesLeft` parameters are consistent between Task 9 tests and Task 9 implementation. `DashboardUiState` field names (`caloriesLeft`, `budgetLabel`) used in Task 10 match those referenced in Task 11 and Task 12.

**Placeholder scan:** No TBDs. All code blocks contain complete, runnable code.
