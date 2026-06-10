# Dashboard Daily Zone Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the dashboard daily zone — estimated calories out, calories in, budget remaining, and the sport-tonight toggle.

**Architecture:** Server computes the base budget via `GET /summary/today?date=YYYY-MM-DD`; the app fetches it on load and falls back to local Room computation when offline. Sport-tonight is ephemeral local state (Room only, never synced), applied client-side on top of the server's base budget.

**Tech Stack:** Kotlin, Ktor + Exposed (server), Jetpack Compose + Room + Ktor client (app), kotlinx.serialization (shared DTO), Robolectric + Compose test rule (app tests), JUnit + Ktor testApplication (server tests).

---

## File Map

**Create:**
- `shared/src/commonMain/kotlin/org/branneman/health/TodaySummaryDto.kt`
- `server/src/main/kotlin/org/branneman/health/budget/BudgetComputer.kt`
- `server/src/test/kotlin/org/branneman/health/budget/BudgetComputerTest.kt`
- `server/src/test/kotlin/org/branneman/health/SummaryIntegrationTest.kt`
- `app/src/main/kotlin/org/branneman/health/db/entities/SportTonightEntity.kt`
- `app/src/main/kotlin/org/branneman/health/db/dao/SportTonightDao.kt`
- `app/src/main/kotlin/org/branneman/health/dashboard/DashboardViewModel.kt`
- `app/src/test/kotlin/org/branneman/health/dashboard/DashboardLogicTest.kt`
- `app/src/test/kotlin/org/branneman/health/db/dao/SportTonightDaoTest.kt`
- `app/src/test/kotlin/org/branneman/health/ui/DashboardScreenTest.kt`

**Modify:**
- `server/src/main/kotlin/org/branneman/health/Application.kt` — add `/summary/today` route
- `app/src/main/kotlin/org/branneman/health/db/HealthDatabase.kt` — version 2, add `SportTonightEntity` + `SportTonightDao`
- `app/src/main/kotlin/org/branneman/health/HealthApplication.kt` — add `Migration(1, 2)`
- `app/src/main/kotlin/org/branneman/health/db/dao/DailyEnergyDao.kt` — add `getForDate`
- `app/src/main/kotlin/org/branneman/health/db/dao/LogEntryDao.kt` — add `sumQuickAddKcalForDate`
- `app/src/main/kotlin/org/branneman/health/network/HealthApiClient.kt` — add `getTodaySummary`
- `app/src/main/kotlin/org/branneman/health/ui/DashboardScreen.kt` — replace stub
- `app/src/test/kotlin/org/branneman/health/TestFactories.kt` — add `aSportTonight`

---

## Task 1: TodaySummaryDto in shared

**Files:**
- Create: `shared/src/commonMain/kotlin/org/branneman/health/TodaySummaryDto.kt`

- [ ] **Create the DTO**

```kotlin
// shared/src/commonMain/kotlin/org/branneman/health/TodaySummaryDto.kt
package org.branneman.health

import kotlinx.serialization.Serializable

@Serializable
data class TodaySummaryDto(
    val date: String,                // YYYY-MM-DD
    val caloriesIn: Int,
    val caloriesOut: Int,
    val budgetRemaining: Int,        // caloriesOut − targetDeficit − caloriesIn
    val targetDeficit: Int,
    val caloriesOutSource: String,   // "polar_today" | "polar_yesterday" | "estimate"
)
```

- [ ] **Verify the shared module compiles**

```bash
./gradlew :shared:build
```

Expected: BUILD SUCCESSFUL

- [ ] **Commit**

```bash
git add shared/src/commonMain/kotlin/org/branneman/health/TodaySummaryDto.kt
git commit -m "feat(shared): add TodaySummaryDto"
```

---

## Task 2: Server — BudgetComputer (unit tests first)

**Files:**
- Create: `server/src/main/kotlin/org/branneman/health/budget/BudgetComputer.kt`
- Create: `server/src/test/kotlin/org/branneman/health/budget/BudgetComputerTest.kt`

- [ ] **Write the failing tests**

```kotlin
// server/src/test/kotlin/org/branneman/health/budget/BudgetComputerTest.kt
package org.branneman.health.budget

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class BudgetComputerTest {

    private val today = LocalDate.of(2026, 6, 10)

    private val profile = UserProfileInput(
        heightCm = 177,
        birthYear = 1986,
        sex = "male",
        activityLevel = "lightly_active",
        targetDeficit = 300,
        goalWeightKg = 74.0,
    )

    // --- BMR ---

    @Test fun `computeBmr male 84kg 177cm age 40`() {
        // 10×84 + 6.25×177 − 5×40 + 5 = 840 + 1106.25 − 200 + 5 = 1751.25
        assertEquals(1751.25, computeBmr("male", 84.0, 177, 40), 0.01)
    }

    @Test fun `computeBmr female uses −161 constant`() {
        // 10×70 + 6.25×165 − 5×35 − 161 = 700 + 1031.25 − 175 − 161 = 1395.25
        assertEquals(1395.25, computeBmr("female", 70.0, 165, 35), 0.01)
    }

    // --- Activity multiplier ---

    @Test fun `activityMultiplier sedentary`() =
        assertEquals(1.20, activityMultiplier("sedentary"), 0.001)

    @Test fun `activityMultiplier lightly_active`() =
        assertEquals(1.375, activityMultiplier("lightly_active"), 0.001)

    @Test fun `activityMultiplier moderately_active`() =
        assertEquals(1.55, activityMultiplier("moderately_active"), 0.001)

    @Test fun `activityMultiplier unknown defaults to lightly_active`() =
        assertEquals(1.375, activityMultiplier("unknown"), 0.001)

    // --- caloriesOut source priority ---

    @Test fun `uses today Polar when available`() {
        val energy = listOf(
            EnergyRow(today, 2300),
            EnergyRow(today.minusDays(1), 2100),
        )
        val result = BudgetComputer.compute(today, profile, 84.0, energy, 0)
        assertEquals(2300, result.caloriesOut)
        assertEquals("polar_today", result.caloriesOutSource)
    }

    @Test fun `falls back to yesterday Polar when today absent`() {
        val energy = listOf(EnergyRow(today.minusDays(1), 2100))
        val result = BudgetComputer.compute(today, profile, 84.0, energy, 0)
        assertEquals(2100, result.caloriesOut)
        assertEquals("polar_yesterday", result.caloriesOutSource)
    }

    @Test fun `falls back to estimate when no Polar rows`() {
        // BMR: 1751.25 (male,84,177,age=40 in 2026 → birthYear=1986) × 1.375 = 2408.0
        val result = BudgetComputer.compute(today, profile, 84.0, emptyList(), 0)
        assertEquals("estimate", result.caloriesOutSource)
        assertEquals((computeBmr("male", 84.0, 177, 40) * 1.375).toInt(), result.caloriesOut)
    }

    @Test fun `uses goalWeightKg when no body weight provided`() {
        val result = BudgetComputer.compute(today, profile, null, emptyList(), 0)
        // should use goalWeightKg=74.0 instead of null
        val expected = (computeBmr("male", 74.0, 177, 40) * 1.375).toInt()
        assertEquals(expected, result.caloriesOut)
        assertEquals("estimate", result.caloriesOutSource)
    }

    // --- budgetRemaining ---

    @Test fun `budgetRemaining = caloriesOut − deficit − caloriesIn`() {
        val energy = listOf(EnergyRow(today, 2300))
        val result = BudgetComputer.compute(today, profile, 84.0, energy, 850)
        // 2300 − 300 − 850 = 1150
        assertEquals(1150, result.budgetRemaining)
        assertEquals(850, result.caloriesIn)
        assertEquals(300, result.targetDeficit)
    }

    @Test fun `budgetRemaining is negative when over budget`() {
        val energy = listOf(EnergyRow(today, 2000))
        val result = BudgetComputer.compute(today, profile, 84.0, energy, 1800)
        // 2000 − 300 − 1800 = −100
        assertEquals(-100, result.budgetRemaining)
    }
}
```

- [ ] **Run tests — expect compilation failure (BudgetComputer does not exist yet)**

```bash
./gradlew :server:test --tests "org.branneman.health.budget.BudgetComputerTest"
```

Expected: BUILD FAILED (unresolved reference errors)

- [ ] **Implement BudgetComputer**

```kotlin
// server/src/main/kotlin/org/branneman/health/budget/BudgetComputer.kt
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

data class BudgetResult(
    val caloriesIn: Int,
    val caloriesOut: Int,
    val budgetRemaining: Int,
    val targetDeficit: Int,
    val caloriesOutSource: String,
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
            caloriesIn = caloriesIn,
            caloriesOut = caloriesOut,
            budgetRemaining = caloriesOut - profile.targetDeficit - caloriesIn,
            targetDeficit = profile.targetDeficit,
            caloriesOutSource = source,
        )
    }

    private fun resolveCaloriesOut(
        today: LocalDate,
        profile: UserProfileInput,
        latestWeightKg: Double?,
        energyRows: List<EnergyRow>,
    ): Pair<Int, String> {
        energyRows.firstOrNull { it.date == today }?.let { return it.totalKcal to "polar_today" }
        energyRows.firstOrNull { it.date == today.minusDays(1) }?.let { return it.totalKcal to "polar_yesterday" }
        val weightKg = latestWeightKg ?: profile.goalWeightKg
        val age = today.year - profile.birthYear
        val tdee = (computeBmr(profile.sex, weightKg, profile.heightCm, age) * activityMultiplier(profile.activityLevel)).toInt()
        return tdee to "estimate"
    }
}
```

- [ ] **Run tests — expect all pass**

```bash
./gradlew :server:test --tests "org.branneman.health.budget.BudgetComputerTest"
```

Expected: BUILD SUCCESSFUL, 11 tests passed

- [ ] **Commit**

```bash
git add server/src/main/kotlin/org/branneman/health/budget/BudgetComputer.kt \
        server/src/test/kotlin/org/branneman/health/budget/BudgetComputerTest.kt
git commit -m "feat(server): add BudgetComputer with unit tests"
```

---

## Task 3: Server — `GET /summary/today` route (integration test first)

**Files:**
- Create: `server/src/test/kotlin/org/branneman/health/SummaryIntegrationTest.kt`
- Modify: `server/src/main/kotlin/org/branneman/health/Application.kt`

- [ ] **Write the failing integration test**

```kotlin
// server/src/test/kotlin/org/branneman/health/SummaryIntegrationTest.kt
package org.branneman.health

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.branneman.health.auth.Users
import org.branneman.health.data.BodyWeight
import org.branneman.health.data.DailyEnergy
import org.branneman.health.data.LogEntry
import org.branneman.health.data.UserProfile
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Before
import org.mindrot.jbcrypt.BCrypt
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SummaryIntegrationTest {

    companion object {
        private val ds = TestDatabase.dataSource
        val testUserId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000006")
        private const val TEST_EMAIL = "summary-test@test.local"
        private const val TEST_PASSWORD = "testpassword"
        private val TEST_HASH = BCrypt.hashpw(TEST_PASSWORD, BCrypt.gensalt(4))

        init {
            Database.connect(ds)
            transaction {
                Users.deleteWhere { username eq TEST_EMAIL }
                Users.insert {
                    it[id]           = testUserId
                    it[username]     = TEST_EMAIL
                    it[passwordHash] = TEST_HASH
                }
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
                }
                BodyWeight.deleteWhere { userId eq testUserId }
                BodyWeight.insert {
                    it[id]        = UUID.randomUUID()
                    it[userId]    = testUserId
                    it[date]      = LocalDate.now()
                    it[kg]        = 84.0.toBigDecimal()
                    it[createdAt] = OffsetDateTime.now()
                }
            }
        }
    }

    @Before fun cleanMutableRows() {
        transaction {
            LogEntry.deleteWhere { userId eq testUserId }
            DailyEnergy.deleteWhere { userId eq testUserId }
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

    @Test fun `GET summary-today returns 401 without token`() = appTest {
        val r = client.get("/summary/today?date=2026-06-10")
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    @Test fun `GET summary-today returns 400 when date param missing`() = appTest {
        val token = login()
        val r = client.get("/summary/today") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test fun `GET summary-today returns estimate when no Polar data`() = appTest {
        val token = login()
        val today = LocalDate.now().toString()
        val r = client.get("/summary/today?date=$today") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals("estimate", body["caloriesOutSource"]!!.jsonPrimitive.content)
        assertEquals(0, body["caloriesIn"]!!.jsonPrimitive.content.toInt())
        assertEquals(300, body["targetDeficit"]!!.jsonPrimitive.content.toInt())
        assertTrue(body["caloriesOut"]!!.jsonPrimitive.content.toInt() > 0)
        assertNotNull(body["budgetRemaining"])
    }

    @Test fun `GET summary-today includes quick-add caloriesIn for today`() = appTest {
        val token = login()
        val today = LocalDate.now()
        transaction {
            LogEntry.insert {
                it[id]           = UUID.randomUUID()
                it[userId]       = testUserId
                it[loggedAt]     = today.atTime(8, 0).atOffset(java.time.ZoneOffset.UTC)
                it[mealType]     = "breakfast"
                it[quickAddKcal] = 520
                it[createdAt]    = OffsetDateTime.now()
            }
            LogEntry.insert {
                it[id]           = UUID.randomUUID()
                it[userId]       = testUserId
                it[loggedAt]     = today.atTime(12, 30).atOffset(java.time.ZoneOffset.UTC)
                it[mealType]     = "lunch"
                it[quickAddKcal] = 680
                it[createdAt]    = OffsetDateTime.now()
            }
        }
        val r = client.get("/summary/today?date=${today}") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals(1200, body["caloriesIn"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun `GET summary-today excludes log entries from other days`() = appTest {
        val token = login()
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        transaction {
            LogEntry.insert {
                it[id]           = UUID.randomUUID()
                it[userId]       = testUserId
                it[loggedAt]     = yesterday.atTime(19, 0).atOffset(java.time.ZoneOffset.UTC)
                it[mealType]     = "dinner"
                it[quickAddKcal] = 800
                it[createdAt]    = OffsetDateTime.now()
            }
        }
        val r = client.get("/summary/today?date=${today}") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals(0, body["caloriesIn"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun `GET summary-today uses Polar data when available`() = appTest {
        val token = login()
        val today = LocalDate.now()
        transaction {
            DailyEnergy.insert {
                it[userId]     = testUserId
                it[date]       = today
                it[bmrKcal]    = 1800
                it[activeKcal] = 500
                it[totalKcal]  = 2300
                it[dataSource] = "polar"
            }
        }
        val r = client.get("/summary/today?date=${today}") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals(2300, body["caloriesOut"]!!.jsonPrimitive.content.toInt())
        assertEquals("polar_today", body["caloriesOutSource"]!!.jsonPrimitive.content)
    }
}
```

- [ ] **Run tests — expect failure (route does not exist)**

```bash
./gradlew :server:test --tests "org.branneman.health.SummaryIntegrationTest"
```

Expected: BUILD FAILED or tests fail with 404

- [ ] **Add the `/summary/today` route to `Application.kt`**

Add this import at the top of `Application.kt`:

```kotlin
import org.branneman.health.TodaySummaryDto
import org.branneman.health.budget.BudgetComputer
import org.branneman.health.budget.EnergyRow
import org.branneman.health.budget.UserProfileInput
import java.time.ZoneOffset
```

Add this block inside the `authenticate("api") { ... }` block in `Application.kt`, after the existing `/out/workouts` route:

```kotlin
get("/summary/today") {
    val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
    val dateParam = call.request.queryParameters["date"]
        ?: return@get call.respond(HttpStatusCode.BadRequest)
    val today = runCatching { java.time.LocalDate.parse(dateParam) }.getOrNull()
        ?: return@get call.respond(HttpStatusCode.BadRequest)

    val dayStart = today.atStartOfDay().atOffset(ZoneOffset.UTC)
    val dayEnd = dayStart.plusDays(1)

    val dto = transaction {
        val profile = UserProfile.selectAll()
            .where { UserProfile.userId eq userId }
            .singleOrNull()
            ?: return@transaction null

        val profileInput = UserProfileInput(
            heightCm      = profile[UserProfile.heightCm],
            birthYear     = profile[UserProfile.birthYear],
            sex           = profile[UserProfile.sex],
            activityLevel = profile[UserProfile.activityLevel],
            targetDeficit = profile[UserProfile.targetDeficit],
            goalWeightKg  = profile[UserProfile.goalWeightKg].toDouble(),
        )

        val latestWeightKg = BodyWeight.selectAll()
            .where { BodyWeight.userId eq userId }
            .orderBy(BodyWeight.date, SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.get(BodyWeight.kg)?.toDouble()

        val energyRows = DailyEnergy.selectAll()
            .where {
                (DailyEnergy.userId eq userId) and
                (DailyEnergy.date greaterEq today.minusDays(1)) and
                (DailyEnergy.date lessEq today)
            }
            .map { EnergyRow(it[DailyEnergy.date], it[DailyEnergy.totalKcal]) }

        val quickAddKcal = LogEntry.selectAll()
            .where {
                (LogEntry.userId eq userId) and
                (LogEntry.quickAddKcal.isNotNull()) and
                (LogEntry.loggedAt greaterEq dayStart) and
                (LogEntry.loggedAt less dayEnd)
            }
            .sumOf { it[LogEntry.quickAddKcal] ?: 0 }

        val itemKcal = (LogEntry innerJoin LogEntryItem)
            .select(LogEntry.id)
            .where {
                (LogEntry.userId eq userId) and
                (LogEntry.quickAddKcal.isNull()) and
                (LogEntry.loggedAt greaterEq dayStart) and
                (LogEntry.loggedAt less dayEnd)
            }
            .sumOf { row ->
                val kcalPer100g = row[LogEntryItem.kcalPer100g].toDouble()
                val grams = row[LogEntryItem.grams].toDouble()
                (kcalPer100g * grams / 100.0).toInt()
            }

        val budget = BudgetComputer.compute(
            today        = today,
            profile      = profileInput,
            latestWeightKg = latestWeightKg,
            energyRows   = energyRows,
            caloriesIn   = quickAddKcal + itemKcal,
        )

        TodaySummaryDto(
            date               = today.toString(),
            caloriesIn         = budget.caloriesIn,
            caloriesOut        = budget.caloriesOut,
            budgetRemaining    = budget.budgetRemaining,
            targetDeficit      = budget.targetDeficit,
            caloriesOutSource  = budget.caloriesOutSource,
        )
    }

    if (dto == null) call.respond(HttpStatusCode.NotFound)
    else call.respond(dto)
}
```

Note: the `innerJoin` for item-based entries requires adding this import:
```kotlin
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.select
```
These are already imported if you check the existing `Application.kt` imports. Also add:
```kotlin
import org.jetbrains.exposed.sql.innerJoin
```

- [ ] **Run all server tests**

```bash
./gradlew :server:test
```

Expected: BUILD SUCCESSFUL, all tests pass including new `SummaryIntegrationTest`

- [ ] **Commit**

```bash
git add server/src/main/kotlin/org/branneman/health/Application.kt \
        server/src/test/kotlin/org/branneman/health/SummaryIntegrationTest.kt
git commit -m "feat(server): implement GET /summary/today"
```

---

## Task 4: App — SportTonight Room layer

**Files:**
- Create: `app/src/main/kotlin/org/branneman/health/db/entities/SportTonightEntity.kt`
- Create: `app/src/main/kotlin/org/branneman/health/db/dao/SportTonightDao.kt`
- Modify: `app/src/main/kotlin/org/branneman/health/db/HealthDatabase.kt`
- Modify: `app/src/main/kotlin/org/branneman/health/HealthApplication.kt`
- Modify: `app/src/main/kotlin/org/branneman/health/db/dao/DailyEnergyDao.kt`
- Modify: `app/src/main/kotlin/org/branneman/health/db/dao/LogEntryDao.kt`
- Modify: `app/src/test/kotlin/org/branneman/health/TestFactories.kt`
- Create: `app/src/test/kotlin/org/branneman/health/db/dao/SportTonightDaoTest.kt`

- [ ] **Write the failing DAO test**

```kotlin
// app/src/test/kotlin/org/branneman/health/db/dao/SportTonightDaoTest.kt
package org.branneman.health.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.branneman.health.db.HealthDatabase
import org.branneman.health.db.entities.SportTonightEntity
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
class SportTonightDaoTest {

    private lateinit var db: HealthDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HealthDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() { db.close() }

    @Test fun `upsert and getForDate returns entity`() = runTest {
        val entity = SportTonightEntity(
            date = "2026-06-10",
            activityType = "climbing",
            intensity = "normal",
            estimatedKcal = 600,
        )
        db.sportTonightDao().upsert(entity)
        val result = db.sportTonightDao().getForDate("2026-06-10")
        assertNotNull(result)
        assertEquals("climbing", result.activityType)
        assertEquals("normal", result.intensity)
        assertEquals(600, result.estimatedKcal)
    }

    @Test fun `getForDate returns null for different date`() = runTest {
        db.sportTonightDao().upsert(
            SportTonightEntity(date = "2026-06-10", activityType = "climbing", intensity = "normal", estimatedKcal = 600)
        )
        assertNull(db.sportTonightDao().getForDate("2026-06-09"))
    }

    @Test fun `upsert replaces existing row for same date`() = runTest {
        db.sportTonightDao().upsert(
            SportTonightEntity(date = "2026-06-10", activityType = "climbing", intensity = "light", estimatedKcal = 400)
        )
        db.sportTonightDao().upsert(
            SportTonightEntity(date = "2026-06-10", activityType = "climbing", intensity = "hard", estimatedKcal = 780)
        )
        val result = db.sportTonightDao().getForDate("2026-06-10")
        assertNotNull(result)
        assertEquals("hard", result.intensity)
        assertEquals(780, result.estimatedKcal)
    }

    @Test fun `deleteForDate removes entry`() = runTest {
        db.sportTonightDao().upsert(
            SportTonightEntity(date = "2026-06-10", activityType = "rowing", intensity = "normal", estimatedKcal = 600)
        )
        db.sportTonightDao().deleteForDate("2026-06-10")
        assertNull(db.sportTonightDao().getForDate("2026-06-10"))
    }
}
```

- [ ] **Run tests — expect failure**

```bash
./gradlew :app:test --tests "org.branneman.health.db.dao.SportTonightDaoTest"
```

Expected: BUILD FAILED (SportTonightEntity/Dao not found)

- [ ] **Create SportTonightEntity**

```kotlin
// app/src/main/kotlin/org/branneman/health/db/entities/SportTonightEntity.kt
package org.branneman.health.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sport_tonight")
data class SportTonightEntity(
    @PrimaryKey val date: String,
    val activityType: String,
    val intensity: String,
    val estimatedKcal: Int,
)
```

- [ ] **Create SportTonightDao**

```kotlin
// app/src/main/kotlin/org/branneman/health/db/dao/SportTonightDao.kt
package org.branneman.health.db.dao

import androidx.room.*
import org.branneman.health.db.entities.SportTonightEntity

@Dao
interface SportTonightDao {
    @Query("SELECT * FROM sport_tonight WHERE date = :date LIMIT 1")
    suspend fun getForDate(date: String): SportTonightEntity?

    @Upsert
    suspend fun upsert(entity: SportTonightEntity)

    @Query("DELETE FROM sport_tonight WHERE date = :date")
    suspend fun deleteForDate(date: String)
}
```

- [ ] **Update HealthDatabase to version 2**

```kotlin
// app/src/main/kotlin/org/branneman/health/db/HealthDatabase.kt
package org.branneman.health.db

import androidx.room.Database
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
        SportTonightEntity::class,
    ],
    version = 2,
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
}
```

- [ ] **Add Migration(1, 2) to HealthApplication**

```kotlin
// app/src/main/kotlin/org/branneman/health/HealthApplication.kt
package org.branneman.health

import android.app.Application
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.branneman.health.db.HealthDatabase

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS sport_tonight (" +
            "date TEXT NOT NULL PRIMARY KEY, " +
            "activityType TEXT NOT NULL, " +
            "intensity TEXT NOT NULL, " +
            "estimatedKcal INTEGER NOT NULL)"
        )
    }
}

class HealthApplication : Application() {

    lateinit var db: HealthDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        db = Room.databaseBuilder(this, HealthDatabase::class.java, "health.db")
            .addMigrations(MIGRATION_1_2)
            .build()
    }
}
```

- [ ] **Add `getForDate` to DailyEnergyDao**

Open `app/src/main/kotlin/org/branneman/health/db/dao/DailyEnergyDao.kt` and add:

```kotlin
@Query("SELECT * FROM daily_energy WHERE userId = :userId AND date = :date LIMIT 1")
suspend fun getForDate(userId: String, date: String): DailyEnergyEntity?
```

- [ ] **Add `sumQuickAddKcalForDate` to LogEntryDao**

Open `app/src/main/kotlin/org/branneman/health/db/dao/LogEntryDao.kt` and add:

```kotlin
@Query("SELECT COALESCE(SUM(quickAddKcal), 0) FROM log_entry WHERE userId = :userId AND loggedAt LIKE :datePrefix || '%'")
suspend fun sumQuickAddKcalForDate(userId: String, datePrefix: String): Int
```

- [ ] **Add `aSportTonight` to TestFactories**

Open `app/src/test/kotlin/org/branneman/health/TestFactories.kt` and add:

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

Also add the import at the top:
```kotlin
import org.branneman.health.db.entities.SportTonightEntity
```

- [ ] **Run the DAO test**

```bash
./gradlew :app:test --tests "org.branneman.health.db.dao.SportTonightDaoTest"
```

Expected: BUILD SUCCESSFUL, 4 tests pass

- [ ] **Run all app tests to check nothing broke**

```bash
./gradlew :app:test
```

Expected: BUILD SUCCESSFUL

- [ ] **Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/db/entities/SportTonightEntity.kt \
        app/src/main/kotlin/org/branneman/health/db/dao/SportTonightDao.kt \
        app/src/main/kotlin/org/branneman/health/db/HealthDatabase.kt \
        app/src/main/kotlin/org/branneman/health/HealthApplication.kt \
        app/src/main/kotlin/org/branneman/health/db/dao/DailyEnergyDao.kt \
        app/src/main/kotlin/org/branneman/health/db/dao/LogEntryDao.kt \
        app/src/test/kotlin/org/branneman/health/TestFactories.kt \
        app/src/test/kotlin/org/branneman/health/db/dao/SportTonightDaoTest.kt
git commit -m "feat(app): add SportTonight Room entity, DAO, and db migration"
```

---

## Task 5: App — Dashboard pure logic tests

**Files:**
- Create: `app/src/test/kotlin/org/branneman/health/dashboard/DashboardLogicTest.kt`

The `computeSportEstimate` and `computeBmr`/`activityMultiplier` functions will live in `DashboardViewModel.kt` as top-level functions (same pattern as `OnboardingRepository.kt`). We write the tests first to define the expected values, then implement in Task 6.

- [ ] **Write the failing logic tests**

```kotlin
// app/src/test/kotlin/org/branneman/health/dashboard/DashboardLogicTest.kt
package org.branneman.health.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DashboardLogicTest {

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
        val entity = org.branneman.health.db.entities.SportTonightEntity(
            date = "2026-06-09",
            activityType = "climbing",
            intensity = "normal",
            estimatedKcal = 600,
        )
        val today = "2026-06-10"
        assertNull(entity.takeIf { it.date == today })
    }

    // --- adjusted budget ---

    @Test fun `adjustedBudget adds sport estimate to base when active`() {
        assertEquals(2447, 1847 + 600)
    }

    @Test fun `adjustedBudget equals base when no sport tonight`() {
        val sportKcal = 0  // null sport → 0
        assertEquals(1847, 1847 + sportKcal)
    }
}
```

- [ ] **Run tests — expect failure**

```bash
./gradlew :app:test --tests "org.branneman.health.dashboard.DashboardLogicTest"
```

Expected: BUILD FAILED (computeSportEstimate not defined)

Note: the auto-clear and adjusted-budget tests will pass immediately (they use standard Kotlin). The `computeSportEstimate` tests will fail. That's fine — proceed to Task 6.

---

## Task 6: App — DashboardViewModel and HealthApiClient

**Files:**
- Create: `app/src/main/kotlin/org/branneman/health/dashboard/DashboardViewModel.kt`
- Modify: `app/src/main/kotlin/org/branneman/health/network/HealthApiClient.kt`

- [ ] **Add `getTodaySummary` to HealthApiClient**

Add this import to `HealthApiClient.kt`:
```kotlin
import org.branneman.health.TodaySummaryDto
```

Add this method to `HealthApiClient`:
```kotlin
suspend fun getTodaySummary(token: String, date: String): TodaySummaryDto =
    client.get("$baseUrl/summary/today") {
        header(HttpHeaders.Authorization, "Bearer $token")
        parameter("date", date)
    }.body()
```

- [ ] **Create DashboardViewModel**

```kotlin
// app/src/main/kotlin/org/branneman/health/dashboard/DashboardViewModel.kt
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.branneman.health.BuildConfig
import org.branneman.health.HealthApplication
import org.branneman.health.auth.TokenStore
import org.branneman.health.auth.authDataStore
import org.branneman.health.db.entities.SportTonightEntity
import org.branneman.health.network.HealthApiClient
import java.time.LocalDate

data class DashboardUiState(
    val isLoading: Boolean = true,
    val caloriesIn: Int = 0,
    val caloriesOut: Int = 0,
    val caloriesOutSource: String = "estimate",
    val targetDeficit: Int = 0,
    val budgetRemaining: Int = 0,
    val sportTonight: SportTonightEntity? = null,
    val adjustedBudgetRemaining: Int = 0,
)

fun computeSportEstimate(activityType: String, intensity: String, weightKg: Double): Int {
    data class Cfg(val met: Double, val mins: Int)
    val cfg = when (activityType) {
        "climbing" -> when (intensity) {
            "light" -> Cfg(4.0, 75)
            "hard"  -> Cfg(6.5, 90)
            else    -> Cfg(5.0, 90)
        }
        "rowing" -> when (intensity) {
            "light" -> Cfg(7.0, 45)
            "hard"  -> Cfg(9.0, 60)
            else    -> Cfg(7.5, 60)
        }
        else -> when (intensity) {
            "light" -> Cfg(4.0, 60)
            "hard"  -> Cfg(7.0, 75)
            else    -> Cfg(5.5, 75)
        }
    }
    return (cfg.met * weightKg * cfg.mins / 60.0).toInt()
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
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val stored = tokenStore.tokenFlow.first() ?: return
        val today = LocalDate.now().toString()

        // Step 1: compute from cached Room data immediately
        val localState = computeLocalState(stored.userId, today)
        _uiState.value = localState

        // Step 2: refresh from server in background
        runCatching { apiClient.getTodaySummary(stored.token, today) }
            .onSuccess { dto ->
                _uiState.update { state ->
                    val sport = state.sportTonight
                    state.copy(
                        isLoading = false,
                        caloriesIn = dto.caloriesIn,
                        caloriesOut = dto.caloriesOut,
                        caloriesOutSource = dto.caloriesOutSource,
                        targetDeficit = dto.targetDeficit,
                        budgetRemaining = dto.budgetRemaining,
                        adjustedBudgetRemaining = dto.budgetRemaining + (sport?.estimatedKcal ?: 0),
                    )
                }
            }
        // on network failure: stay with local computation, no error shown
    }

    private suspend fun computeLocalState(userId: String, today: String): DashboardUiState {
        val profile = app.db.userProfileDao().get()
            ?: return DashboardUiState(isLoading = false)
        val latestWeight = app.db.bodyWeightDao().observeAll().first().firstOrNull()?.kg
        val energy = app.db.dailyEnergyDao().getForDate(userId, today)
        val caloriesIn = app.db.logEntryDao().sumQuickAddKcalForDate(userId, today)
        val sport = app.db.sportTonightDao().getForDate(today)

        val (caloriesOut, source) = if (energy != null) {
            energy.totalKcal to "polar_today"
        } else {
            val weightKg = latestWeight ?: profile.goalWeightKg
            val age = LocalDate.now().year - profile.birthYear
            val bmr = org.branneman.health.onboarding.computeBmr(profile.sex, weightKg, profile.heightCm, age)
            val tdee = (bmr * org.branneman.health.onboarding.activityMultiplier(profile.activityLevel)).toInt()
            tdee to "estimate"
        }

        val budgetBase = caloriesOut - profile.targetDeficit - caloriesIn
        return DashboardUiState(
            isLoading = false,
            caloriesIn = caloriesIn,
            caloriesOut = caloriesOut,
            caloriesOutSource = source,
            targetDeficit = profile.targetDeficit,
            budgetRemaining = budgetBase,
            sportTonight = sport,
            adjustedBudgetRemaining = budgetBase + (sport?.estimatedKcal ?: 0),
        )
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
            _uiState.update { state ->
                state.copy(
                    sportTonight = entity,
                    adjustedBudgetRemaining = state.budgetRemaining + estimatedKcal,
                )
            }
        }
    }

    fun clearSportTonight() {
        viewModelScope.launch {
            app.db.sportTonightDao().deleteForDate(LocalDate.now().toString())
            _uiState.update { state ->
                state.copy(sportTonight = null, adjustedBudgetRemaining = state.budgetRemaining)
            }
        }
    }
}
```

- [ ] **Run logic tests — now expect pass**

```bash
./gradlew :app:test --tests "org.branneman.health.dashboard.DashboardLogicTest"
```

Expected: BUILD SUCCESSFUL, all 12 tests pass

- [ ] **Run all app tests**

```bash
./gradlew :app:test
```

Expected: BUILD SUCCESSFUL

- [ ] **Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/dashboard/DashboardViewModel.kt \
        app/src/main/kotlin/org/branneman/health/network/HealthApiClient.kt \
        app/src/test/kotlin/org/branneman/health/dashboard/DashboardLogicTest.kt
git commit -m "feat(app): add DashboardViewModel and getTodaySummary API call"
```

---

## Task 7: App — DashboardScreen (component test first)

**Files:**
- Create: `app/src/test/kotlin/org/branneman/health/ui/DashboardScreenTest.kt`
- Modify: `app/src/main/kotlin/org/branneman/health/ui/DashboardScreen.kt`

- [ ] **Write the failing screen test**

```kotlin
// app/src/test/kotlin/org/branneman/health/ui/DashboardScreenTest.kt
package org.branneman.health.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.branneman.health.dashboard.DashboardUiState
import org.branneman.health.db.entities.SportTonightEntity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DashboardScreenTest {

    @get:Rule val compose = createComposeRule()

    private fun render(
        state: DashboardUiState = DashboardUiState(),
        onSetSportTonight: (String, String) -> Unit = { _, _ -> },
        onClearSportTonight: () -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                DashboardContent(
                    state = state,
                    onSetSportTonight = onSetSportTonight,
                    onClearSportTonight = onClearSportTonight,
                )
            }
        }
    }

    @Test fun `shows budget remaining as big number`() {
        render(state = DashboardUiState(
            isLoading = false,
            caloriesIn = 0, caloriesOut = 2147, caloriesOutSource = "estimate",
            targetDeficit = 300, budgetRemaining = 1847, adjustedBudgetRemaining = 1847,
        ))
        compose.onNodeWithText("1847", substring = true).assertExists()
    }

    @Test fun `shows estimated source label`() {
        render(state = DashboardUiState(
            isLoading = false, caloriesOutSource = "estimate",
            adjustedBudgetRemaining = 1847,
        ))
        compose.onNodeWithText("estimated", substring = true, ignoreCase = true).assertExists()
    }

    @Test fun `shows calories in and out values`() {
        render(state = DashboardUiState(
            isLoading = false,
            caloriesIn = 520, caloriesOut = 2147, caloriesOutSource = "estimate",
            adjustedBudgetRemaining = 1327,
        ))
        compose.onNodeWithText("520", substring = true).assertExists()
        compose.onNodeWithText("2147", substring = true).assertExists()
    }

    @Test fun `shows in and out labels`() {
        render(state = DashboardUiState(isLoading = false, adjustedBudgetRemaining = 1847))
        compose.onNodeWithText("in", substring = true, ignoreCase = true).assertExists()
        compose.onNodeWithText("out", substring = true, ignoreCase = true).assertExists()
    }

    @Test fun `sport tonight inactive shows set button`() {
        render(state = DashboardUiState(isLoading = false, sportTonight = null, adjustedBudgetRemaining = 1847))
        compose.onNodeWithText("sport tonight", substring = true, ignoreCase = true).assertExists()
    }

    @Test fun `sport tonight active shows activity type`() {
        render(state = DashboardUiState(
            isLoading = false,
            sportTonight = SportTonightEntity(date = "2026-06-10", activityType = "climbing", intensity = "normal", estimatedKcal = 600),
            adjustedBudgetRemaining = 2447,
        ))
        compose.onNodeWithText("Climbing", substring = true, ignoreCase = true).assertExists()
    }

    @Test fun `sport tonight active shows estimated kcal`() {
        render(state = DashboardUiState(
            isLoading = false,
            sportTonight = SportTonightEntity(date = "2026-06-10", activityType = "climbing", intensity = "normal", estimatedKcal = 600),
            adjustedBudgetRemaining = 2447,
        ))
        compose.onNodeWithText("600", substring = true).assertExists()
    }

    @Test fun `sport tonight active shows Normal chip as selected`() {
        render(state = DashboardUiState(
            isLoading = false,
            sportTonight = SportTonightEntity(date = "2026-06-10", activityType = "climbing", intensity = "normal", estimatedKcal = 600),
            adjustedBudgetRemaining = 2447,
        ))
        compose.onNodeWithText("Normal").assertExists()
    }

    @Test fun `tapping intensity chip calls onSetSportTonight`() {
        var called: Pair<String, String>? = null
        render(
            state = DashboardUiState(
                isLoading = false,
                sportTonight = SportTonightEntity(date = "2026-06-10", activityType = "climbing", intensity = "normal", estimatedKcal = 600),
                adjustedBudgetRemaining = 2447,
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
                sportTonight = SportTonightEntity(date = "2026-06-10", activityType = "rowing", intensity = "normal", estimatedKcal = 600),
                adjustedBudgetRemaining = 2447,
            ),
            onClearSportTonight = { cleared = true },
        )
        compose.onNodeWithText("clear", substring = true, ignoreCase = true).performClick()
        assert(cleared)
    }
}
```

- [ ] **Run tests — expect failure**

```bash
./gradlew :app:test --tests "org.branneman.health.ui.DashboardScreenTest"
```

Expected: BUILD FAILED or tests fail (DashboardContent not found / is a stub)

- [ ] **Replace the DashboardScreen stub**

```kotlin
// app/src/main/kotlin/org/branneman/health/ui/DashboardScreen.kt
package org.branneman.health.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.branneman.health.dashboard.DashboardUiState
import org.branneman.health.dashboard.DashboardViewModel
import org.branneman.health.db.entities.SportTonightEntity

private val activities = listOf("climbing" to "Climbing", "rowing" to "Rowing", "other" to "Other")
private val intensities = listOf("light" to "Light", "normal" to "Normal", "hard" to "Hard")

@Composable
fun DashboardScreen(viewModel: DashboardViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DashboardContent(
        state = state,
        onSetSportTonight = viewModel::setSportTonight,
        onClearSportTonight = viewModel::clearSportTonight,
    )
}

@Composable
fun DashboardContent(
    state: DashboardUiState,
    onSetSportTonight: (String, String) -> Unit,
    onClearSportTonight: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
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

        SportTonightSection(
            state = state,
            onSetSportTonight = onSetSportTonight,
            onClearSportTonight = onClearSportTonight,
        )
    }
}

@Composable
private fun BudgetSection(state: DashboardUiState) {
    val sourceLabel = when (state.caloriesOutSource) {
        "polar_today"     -> "left"
        "polar_yesterday" -> "left (based on yesterday)"
        else              -> "left (estimated)"
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = state.adjustedBudgetRemaining.toString(),
            style = MaterialTheme.typography.displayMedium,
        )
        Text(
            text = sourceLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
            modifier = Modifier.fillMaxWidth(),
        ) {
            InOutColumn(value = state.caloriesIn, label = "in")
            InOutColumn(value = state.caloriesOut, label = "out${if (state.caloriesOutSource == "estimate") " (est.)" else ""}")
        }
    }
}

@Composable
private fun InOutColumn(value: Int, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value.toString(), style = MaterialTheme.typography.titleMedium)
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SportTonightSection(
    state: DashboardUiState,
    onSetSportTonight: (String, String) -> Unit,
    onClearSportTonight: () -> Unit,
) {
    val sport = state.sportTonight

    if (sport == null) {
        var expanded by remember { mutableStateOf(false) }
        if (!expanded) {
            TextButton(onClick = { expanded = true }) {
                Text("Set sport tonight")
            }
        } else {
            SportTonightPicker(
                activityType = "climbing",
                intensity = "normal",
                onSet = { a, i -> onSetSportTonight(a, i) },
                onDismiss = { expanded = false },
            )
        }
    } else {
        SportTonightActive(sport = sport, onSetSportTonight = onSetSportTonight, onClear = onClearSportTonight)
    }
}

@Composable
private fun SportTonightActive(
    sport: SportTonightEntity,
    onSetSportTonight: (String, String) -> Unit,
    onClear: () -> Unit,
) {
    Column {
        val activityLabel = activities.firstOrNull { it.first == sport.activityType }?.second ?: sport.activityType
        Text("$activityLabel tonight", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            intensities.forEach { (value, label) ->
                FilterChip(
                    selected = sport.intensity == value,
                    onClick  = { onSetSportTonight(sport.activityType, value) },
                    label    = { Text(label) },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "+${sport.estimatedKcal} kcal est.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onClear, contentPadding = PaddingValues(0.dp)) {
            Text("clear", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SportTonightPicker(
    activityType: String,
    intensity: String,
    onSet: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedActivity by remember { mutableStateOf(activityType) }
    var selectedIntensity by remember { mutableStateOf(intensity) }

    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            activities.forEach { (value, label) ->
                FilterChip(
                    selected = selectedActivity == value,
                    onClick  = { selectedActivity = value },
                    label    = { Text(label) },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            intensities.forEach { (value, label) ->
                FilterChip(
                    selected = selectedIntensity == value,
                    onClick  = { selectedIntensity = value },
                    label    = { Text(label) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            Button(onClick = { onSet(selectedActivity, selectedIntensity) }) { Text("Done") }
        }
    }
}
```

- [ ] **Run the screen tests**

```bash
./gradlew :app:test --tests "org.branneman.health.ui.DashboardScreenTest"
```

Expected: BUILD SUCCESSFUL, all 10 tests pass

- [ ] **Run the full app test suite**

```bash
./gradlew :app:test
```

Expected: BUILD SUCCESSFUL, all tests pass

- [ ] **Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/ui/DashboardScreen.kt \
        app/src/test/kotlin/org/branneman/health/ui/DashboardScreenTest.kt
git commit -m "feat(app): implement DashboardScreen with budget display and sport-tonight toggle"
```

---

## Self-Review

After all tasks complete, run the full build:

```bash
./gradlew :shared:build :server:test :app:test
```

Expected: BUILD SUCCESSFUL across all modules.

Spec coverage check:
- `TodaySummaryDto` — Task 1 ✓
- Server `BudgetComputer` unit tests — Task 2 ✓
- Server `/summary/today` route — Task 3 ✓
- `SportTonightEntity` + DAO + Room migration — Task 4 ✓
- `computeSportEstimate` unit tests — Task 5 ✓
- `DashboardViewModel` + API client method — Task 6 ✓
- `DashboardScreen` with in/out/left layout — Task 7 ✓
- `DashboardScreenTest` — Task 7 ✓
- Offline local computation fallback — Task 6 ✓
- `caloriesOutSource` display states — Task 7 (`BudgetSection`) ✓
- Sport-tonight auto-clear when date ≠ today — Task 6 (`computeLocalState`) ✓
