# Weight Logging Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an inline weight chip to the dashboard so the user can log (and edit) today's body weight, stored offline-first in Room and synced to the server.

**Architecture:** Six targeted changes — a new DAO query, a server upsert, a new sync service, a dashboard state extension, a dashboard ViewModel extension, and new dashboard UI. Each change is independently testable. No new Gradle modules or DB migrations.

**Tech Stack:** Kotlin, Ktor (server), Exposed (server ORM), Room (app), Jetpack Compose, Robolectric, Ktor MockEngine, Compose test rule.

---

## File map

| Action | File |
|---|---|
| Modify | `app/src/main/kotlin/org/branneman/health/db/dao/BodyWeightDao.kt` |
| Modify | `app/src/test/kotlin/org/branneman/health/db/dao/BodyWeightDaoTest.kt` |
| Modify | `server/src/main/kotlin/org/branneman/health/Application.kt` |
| Modify | `server/src/test/kotlin/org/branneman/health/BodyWeightIntegrationTest.kt` |
| Modify | `server/src/apiTest/kotlin/org/branneman/health/BodyWeightApiTest.kt` |
| Modify | `app/src/main/kotlin/org/branneman/health/dashboard/DashboardViewModel.kt` |
| Modify | `app/src/test/kotlin/org/branneman/health/dashboard/DashboardLogicTest.kt` |
| Modify | `app/src/main/kotlin/org/branneman/health/sync/SyncWorker.kt` |
| Create | `app/src/main/kotlin/org/branneman/health/sync/BodyWeightSyncService.kt` |
| Create | `app/src/test/kotlin/org/branneman/health/sync/BodyWeightSyncServiceTest.kt` |
| Modify | `app/src/main/kotlin/org/branneman/health/ui/DashboardScreen.kt` |
| Modify | `app/src/test/kotlin/org/branneman/health/ui/DashboardScreenTest.kt` |

---

## Task 1: BodyWeightDao — add getForDate

**Files:**
- Modify: `app/src/main/kotlin/org/branneman/health/db/dao/BodyWeightDao.kt`
- Modify: `app/src/test/kotlin/org/branneman/health/db/dao/BodyWeightDaoTest.kt`

- [ ] **Step 1: Write the failing tests**

Add to the bottom of `BodyWeightDaoTest` (inside the class):

```kotlin
@Test
fun `getForDate returns entry for matching userId and date`() = runTest {
    val userId = uuid()
    val entry = aBodyWeightEntry(id = "2026-06-11", userId = userId, date = "2026-06-11", kg = 83.2)
    dao.upsert(entry)
    val result = dao.getForDate(userId, "2026-06-11")
    assertNotNull(result)
    assertEquals(83.2, result.kg)
}

@Test
fun `getForDate returns null for a different date`() = runTest {
    val userId = uuid()
    dao.upsert(aBodyWeightEntry(id = "2026-06-10", userId = userId, date = "2026-06-10", kg = 83.2))
    assertNull(dao.getForDate(userId, "2026-06-11"))
}

@Test
fun `getForDate returns null for a different userId`() = runTest {
    dao.upsert(aBodyWeightEntry(id = "2026-06-11", userId = uuid(), date = "2026-06-11", kg = 83.2))
    assertNull(dao.getForDate(uuid(), "2026-06-11"))
}
```

Add import at the top of `BodyWeightDaoTest.kt` if not already present:
```kotlin
import kotlin.test.assertNotNull
import kotlin.test.assertNull
```

- [ ] **Step 2: Run the new tests to verify they fail**

```bash
./gradlew :app:test --tests "org.branneman.health.db.dao.BodyWeightDaoTest" 2>&1 | tail -20
```

Expected: compilation error — `getForDate` is not yet defined.

- [ ] **Step 3: Add getForDate to the DAO**

Full file replacement for `BodyWeightDao.kt`:

```kotlin
package org.branneman.health.db.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.branneman.health.db.SyncStatus
import org.branneman.health.db.entities.BodyWeightEntity

@Dao
interface BodyWeightDao {
    @Query("SELECT * FROM body_weight ORDER BY date DESC")
    fun observeAll(): Flow<List<BodyWeightEntity>>

    @Query("SELECT * FROM body_weight WHERE userId = :userId AND date = :date LIMIT 1")
    suspend fun getForDate(userId: String, date: String): BodyWeightEntity?

    @Query("SELECT * FROM body_weight WHERE syncStatus = :status")
    suspend fun getByStatus(status: SyncStatus): List<BodyWeightEntity>

    @Upsert
    suspend fun upsert(entity: BodyWeightEntity)

    @Upsert
    suspend fun upsertAll(entities: List<BodyWeightEntity>)

    @Query("DELETE FROM body_weight WHERE userId = :userId")
    suspend fun deleteAllForUser(userId: String)

    @Query("UPDATE body_weight SET syncStatus = :status WHERE id = :id")
    suspend fun updateSyncStatus(id: String, status: SyncStatus)

    @Query("DELETE FROM body_weight WHERE id = :id")
    suspend fun deleteById(id: String)
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
./gradlew :app:test --tests "org.branneman.health.db.dao.BodyWeightDaoTest" 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/db/dao/BodyWeightDao.kt \
        app/src/test/kotlin/org/branneman/health/db/dao/BodyWeightDaoTest.kt
git commit -m "feat(app): add getForDate query to BodyWeightDao"
```

---

## Task 2: Server — upsert semantics for POST /body/weight

**Files:**
- Modify: `server/src/test/kotlin/org/branneman/health/BodyWeightIntegrationTest.kt`
- Modify: `server/src/main/kotlin/org/branneman/health/Application.kt`
- Modify: `server/src/apiTest/kotlin/org/branneman/health/BodyWeightApiTest.kt`

- [ ] **Step 1: Update the server integration tests**

Replace the full content of `BodyWeightIntegrationTest.kt`:

```kotlin
package org.branneman.health

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.branneman.health.auth.Users
import org.branneman.health.data.BodyWeight
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Before
import org.mindrot.jbcrypt.BCrypt
import java.util.UUID
import kotlin.test.*

class BodyWeightIntegrationTest {

    companion object {
        private val ds = TestDatabase.dataSource
        private val testUserId = UUID.fromString("00000000-0000-0000-0000-000000000005")
        private const val TEST_EMAIL = "bodyweight-test@test.local"
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

    @Before fun cleanWeightRows() {
        transaction {
            BodyWeight.deleteWhere { userId eq testUserId }
        }
    }

    @Test
    fun `POST body-weight returns 401 without token`() = appTest {
        val r = client.post("/body/weight") {
            contentType(ContentType.Application.Json)
            setBody("""{"date":"2026-06-10","kg":84.0}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    @Test
    fun `POST body-weight creates entry and returns 200 with date and kg`() = appTest {
        val token = login()
        val r = client.post("/body/weight") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"date":"2026-06-10","kg":84.0}""")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals("2026-06-10", body["date"]!!.jsonPrimitive.content)
        assertEquals(84.0, body["kg"]!!.jsonPrimitive.content.toDouble())
    }

    @Test
    fun `POST body-weight twice for same date updates the value`() = appTest {
        val token = login()
        client.post("/body/weight") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"date":"2026-06-10","kg":84.0}""")
        }
        val r2 = client.post("/body/weight") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"date":"2026-06-10","kg":85.5}""")
        }
        assertEquals(HttpStatusCode.OK, r2.status)

        val entries = client.get("/body/weight") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val arr = Json.parseToJsonElement(entries.bodyAsText()).jsonArray
        val entry = arr.first { it.jsonObject["date"]!!.jsonPrimitive.content == "2026-06-10" }
        assertEquals(85.5, entry.jsonObject["kg"]!!.jsonPrimitive.content.toDouble())
    }

    @Test
    fun `GET body-weight returns all entries for user`() = appTest {
        val token = login()
        client.post("/body/weight") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"date":"2026-06-10","kg":84.0}""")
        }
        val r = client.get("/body/weight") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val arr = Json.parseToJsonElement(r.bodyAsText()).jsonArray
        assertTrue(arr.any { it.jsonObject["date"]!!.jsonPrimitive.content == "2026-06-10" })
    }
}
```

- [ ] **Step 2: Run the new tests to verify they fail**

```bash
./gradlew :server:test --tests "org.branneman.health.BodyWeightIntegrationTest" 2>&1 | tail -30
```

Expected: FAIL — `POST body-weight creates entry and returns 200` fails (server returns 201); `POST body-weight twice for same date updates the value` fails (server returns 409).

- [ ] **Step 3: Update Application.kt — change POST /body/weight to upsert**

Find the `post("/weight")` block inside `route("/body")` in `Application.kt`. Replace it entirely:

```kotlin
post("/weight") {
    val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
    val dto = call.receive<WeightEntryDto>()
    val date = java.time.LocalDate.parse(dto.date)

    transaction {
        val existing = BodyWeight.selectAll()
            .where { (BodyWeight.userId eq userId) and (BodyWeight.date eq date) }
            .singleOrNull()
        if (existing != null) {
            BodyWeight.update({ (BodyWeight.userId eq userId) and (BodyWeight.date eq date) }) {
                it[BodyWeight.kg] = dto.kg.toBigDecimal()
            }
        } else {
            BodyWeight.insert {
                it[BodyWeight.id]        = UUID.randomUUID()
                it[BodyWeight.userId]    = userId
                it[BodyWeight.date]      = date
                it[BodyWeight.kg]        = dto.kg.toBigDecimal()
                it[BodyWeight.createdAt] = OffsetDateTime.now()
            }
        }
    }
    call.respond(HttpStatusCode.OK, dto)
}
```

No new imports needed — `BodyWeight.update` is available via the existing `import org.jetbrains.exposed.sql.*`.

- [ ] **Step 4: Run server integration tests**

```bash
./gradlew :server:test --tests "org.branneman.health.BodyWeightIntegrationTest" 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: Update BodyWeightApiTest**

Replace full content of `BodyWeightApiTest.kt`:

```kotlin
package org.branneman.health

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.branneman.health.WeightEntryDto
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BodyWeightApiTest : ApiTestBase() {

    // A fixed past date used as a stable test anchor.
    // POST is idempotent (upsert) — always returns 200 regardless of how many times it runs.
    private val testDate = "2020-01-01"

    @Test
    fun `POST body weight returns 200 and GET includes the entry`() = runTest {
        val token = login()

        val postResp = client.post("$serverUrl/body/weight") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(WeightEntryDto(testDate, 80.0))
        }
        assertEquals(HttpStatusCode.OK, postResp.status)

        val entries = client.get("$serverUrl/body/weight") { bearerAuth(token) }
            .body<List<WeightEntryDto>>()
        assertTrue(
            entries.any { it.date == testDate },
            "Expected entry for $testDate in response",
        )
    }
}
```

- [ ] **Step 6: Commit**

```bash
git add server/src/main/kotlin/org/branneman/health/Application.kt \
        server/src/test/kotlin/org/branneman/health/BodyWeightIntegrationTest.kt \
        server/src/apiTest/kotlin/org/branneman/health/BodyWeightApiTest.kt
git commit -m "feat(server): upsert semantics for POST /body/weight"
```

---

## Task 3: Weight input validation — isValidWeightInput

**Files:**
- Modify: `app/src/test/kotlin/org/branneman/health/dashboard/DashboardLogicTest.kt`
- Modify: `app/src/main/kotlin/org/branneman/health/dashboard/DashboardViewModel.kt`

- [ ] **Step 1: Write the failing tests**

Add to the bottom of `DashboardLogicTest` (inside the class):

```kotlin
// --- isValidWeightInput ---

@Test fun `valid integer weight passes`() {
    assertTrue(isValidWeightInput("85"))
}

@Test fun `valid one-decimal weight passes`() {
    assertTrue(isValidWeightInput("85.5"))
}

@Test fun `explicitly one-decimal zero passes`() {
    assertTrue(isValidWeightInput("85.0"))
}

@Test fun `two decimal places fails`() {
    assertFalse(isValidWeightInput("85.24"))
}

@Test fun `below minimum fails`() {
    assertFalse(isValidWeightInput("19.9"))
}

@Test fun `minimum boundary passes`() {
    assertTrue(isValidWeightInput("20.0"))
}

@Test fun `maximum boundary passes`() {
    assertTrue(isValidWeightInput("300.0"))
}

@Test fun `above maximum fails`() {
    assertFalse(isValidWeightInput("300.1"))
}

@Test fun `non-numeric input fails`() {
    assertFalse(isValidWeightInput("abc"))
}

@Test fun `empty input fails`() {
    assertFalse(isValidWeightInput(""))
}
```

Add imports at the top of `DashboardLogicTest.kt`:
```kotlin
import kotlin.test.assertFalse
import kotlin.test.assertTrue
```

- [ ] **Step 2: Run to verify they fail**

```bash
./gradlew :app:test --tests "org.branneman.health.dashboard.DashboardLogicTest" 2>&1 | tail -20
```

Expected: compilation error — `isValidWeightInput` is not defined.

- [ ] **Step 3: Add isValidWeightInput to DashboardViewModel.kt**

Add this top-level function directly below the `computeSportEstimate` function in `DashboardViewModel.kt` (before the `class DashboardViewModel` declaration):

```kotlin
fun isValidWeightInput(input: String): Boolean {
    val value = input.toDoubleOrNull() ?: return false
    if (value < 20.0 || value > 300.0) return false
    val dotIndex = input.indexOf('.')
    if (dotIndex != -1 && input.length - dotIndex - 1 > 1) return false
    return true
}
```

- [ ] **Step 4: Run to verify they pass**

```bash
./gradlew :app:test --tests "org.branneman.health.dashboard.DashboardLogicTest" 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/dashboard/DashboardViewModel.kt \
        app/src/test/kotlin/org/branneman/health/dashboard/DashboardLogicTest.kt
git commit -m "feat(app): add isValidWeightInput validation"
```

---

## Task 4: Dashboard state and ViewModel — weightKgToday + logWeight

**Files:**
- Modify: `app/src/main/kotlin/org/branneman/health/dashboard/DashboardViewModel.kt`

This task has no new test file — the DAO layer is tested in Task 1, the UI layer is tested in Task 6. The ViewModel wiring is tested end-to-end through the screen test.

- [ ] **Step 1: Add weightKgToday to DashboardUiState**

In `DashboardViewModel.kt`, update `DashboardUiState` to add the new field:

```kotlin
data class DashboardUiState(
    val isLoading: Boolean = true,
    val caloriesIn: Int = 0,
    val caloriesOut: Int = 0,
    val caloriesOutSource: String = "estimate",
    val targetDeficit: Int = 0,
    val budgetRemaining: Int = 0,
    val sportTonight: SportTonightEntity? = null,
    val adjustedBudgetRemaining: Int = 0,
    val weightKgToday: Double? = null,
)
```

- [ ] **Step 2: Update computeLocalState to read today's weight**

In `computeLocalState`, add a line to read today's weight via the new DAO method. The full updated function:

```kotlin
private suspend fun computeLocalState(userId: String, today: String): DashboardUiState {
    val profile = app.db.userProfileDao().get()
        ?: return DashboardUiState(isLoading = false)
    val latestWeight = app.db.bodyWeightDao().observeAll().first().firstOrNull()?.kg
    val weightToday = app.db.bodyWeightDao().getForDate(userId, today)?.kg
    val energy = app.db.dailyEnergyDao().getForDate(userId, today)
    val caloriesIn = app.db.logEntryDao().sumQuickAddKcalForDate(userId, "$today%")
    val sport = app.db.sportTonightDao().getForDate(today)?.takeIf { it.date == today }

    val (caloriesOut, source) = if (energy != null) {
        energy.totalKcal to "polar_today"
    } else {
        val weightKg = latestWeight ?: profile.goalWeightKg
        val age = LocalDate.now().year - profile.birthYear
        val tdee = (computeBmr(profile.sex, weightKg, profile.heightCm, age) * activityMultiplier(profile.activityLevel)).toInt()
        tdee to "estimate"
    }

    val budgetBase = caloriesOut - profile.targetDeficit - caloriesIn
    return DashboardUiState(
        isLoading               = false,
        caloriesIn              = caloriesIn,
        caloriesOut             = caloriesOut,
        caloriesOutSource       = source,
        targetDeficit           = profile.targetDeficit,
        budgetRemaining         = budgetBase,
        sportTonight            = sport,
        adjustedBudgetRemaining = budgetBase + (sport?.estimatedKcal ?: 0),
        weightKgToday           = weightToday,
    )
}
```

- [ ] **Step 3: Add logWeight function**

Add this function to `DashboardViewModel`, after `clearSportTonight`:

```kotlin
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
```

Add the missing imports at the top of `DashboardViewModel.kt`:

```kotlin
import org.branneman.health.db.SyncStatus
import org.branneman.health.db.entities.BodyWeightEntity
```

- [ ] **Step 4: Run the full app test suite to verify no regressions**

```bash
./gradlew :app:test 2>&1 | tail -30
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/dashboard/DashboardViewModel.kt
git commit -m "feat(app): add weightKgToday to dashboard state and logWeight to ViewModel"
```

---

## Task 5: BodyWeightSyncService

**Files:**
- Create: `app/src/test/kotlin/org/branneman/health/sync/BodyWeightSyncServiceTest.kt`
- Create: `app/src/main/kotlin/org/branneman/health/sync/BodyWeightSyncService.kt`
- Modify: `app/src/main/kotlin/org/branneman/health/sync/SyncWorker.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/org/branneman/health/sync/BodyWeightSyncServiceTest.kt`:

```kotlin
package org.branneman.health.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.branneman.health.aBodyWeightEntry
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BodyWeightSyncServiceTest {

    private lateinit var db: HealthDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), HealthDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() { db.close() }

    private fun mockApiClient(handler: MockRequestHandler): HealthApiClient {
        val engine = MockEngine(handler)
        return HealthApiClient("http://test", HttpClient(engine) {
            install(ContentNegotiation) { json() }
        })
    }

    @Test
    fun `PENDING_CREATE is posted and marked SYNCED on 200`() = runTest {
        val entry = aBodyWeightEntry(
            id = "2026-06-11", date = "2026-06-11", kg = 82.5,
            syncStatus = SyncStatus.PENDING_CREATE,
        )
        db.bodyWeightDao().upsert(entry)

        val api = mockApiClient { _ ->
            respond(
                """{"date":"2026-06-11","kg":82.5}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        BodyWeightSyncService(api, db).sync("token")

        assertEquals(0, db.bodyWeightDao().getByStatus(SyncStatus.PENDING_CREATE).size)
        assertEquals(1, db.bodyWeightDao().getByStatus(SyncStatus.SYNCED).size)
    }

    @Test
    fun `PENDING_CREATE stays PENDING_CREATE on network error`() = runTest {
        val entry = aBodyWeightEntry(
            id = "2026-06-11", date = "2026-06-11", kg = 82.5,
            syncStatus = SyncStatus.PENDING_CREATE,
        )
        db.bodyWeightDao().upsert(entry)

        val api = HealthApiClient(
            "http://test",
            HttpClient(MockEngine { error("connection refused") }) {
                install(ContentNegotiation) { json() }
            },
        )

        BodyWeightSyncService(api, db).sync("token")

        assertEquals(1, db.bodyWeightDao().getByStatus(SyncStatus.PENDING_CREATE).size)
    }

    @Test
    fun `SYNCED entries are not re-uploaded`() = runTest {
        val entry = aBodyWeightEntry(
            id = "2026-06-11", date = "2026-06-11", kg = 82.5,
            syncStatus = SyncStatus.SYNCED,
        )
        db.bodyWeightDao().upsert(entry)

        var callCount = 0
        val api = mockApiClient { _ ->
            callCount++
            respond(
                """{"date":"2026-06-11","kg":82.5}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        BodyWeightSyncService(api, db).sync("token")

        assertEquals(0, callCount)
    }
}
```

- [ ] **Step 2: Run to verify compilation fails**

```bash
./gradlew :app:test --tests "org.branneman.health.sync.BodyWeightSyncServiceTest" 2>&1 | tail -20
```

Expected: compilation error — `BodyWeightSyncService` is not defined.

- [ ] **Step 3: Create BodyWeightSyncService**

Create `app/src/main/kotlin/org/branneman/health/sync/BodyWeightSyncService.kt`:

```kotlin
package org.branneman.health.sync

import org.branneman.health.WeightEntryDto
import org.branneman.health.db.HealthDatabase
import org.branneman.health.db.SyncStatus
import org.branneman.health.network.HealthApiClient

class BodyWeightSyncService(
    private val api: HealthApiClient,
    private val db: HealthDatabase,
) {
    suspend fun sync(token: String) {
        db.bodyWeightDao().getByStatus(SyncStatus.PENDING_CREATE).forEach { entity ->
            runCatching {
                api.postBodyWeight(token, WeightEntryDto(entity.date, entity.kg))
            }.onSuccess {
                db.bodyWeightDao().updateSyncStatus(entity.id, SyncStatus.SYNCED)
            }
        }
    }
}
```

- [ ] **Step 4: Run to verify tests pass**

```bash
./gradlew :app:test --tests "org.branneman.health.sync.BodyWeightSyncServiceTest" 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL, all three tests pass.

- [ ] **Step 5: Wire BodyWeightSyncService into SyncWorker**

In `SyncWorker.kt`, add the call to `BodyWeightSyncService` alongside `LogEntrySyncService`. Replace the full `doWork()` body:

```kotlin
override suspend fun doWork(): Result {
    val app = applicationContext as HealthApplication
    val db = app.db
    val tokenStore = TokenStore(applicationContext.authDataStore)
    val stored = tokenStore.tokenFlow.first() ?: return Result.success()

    val apiClient = HealthApiClient(
        baseUrl = BuildConfig.SERVER_BASE_URL,
        client  = HttpClient(Android) { install(ContentNegotiation) { json() } },
    )

    db.bodyWeightDao().getByStatus(SyncStatus.PENDING_DELETE).forEach { entity ->
        db.bodyWeightDao().deleteById(entity.id)
    }

    BodyWeightSyncService(apiClient, db).sync(stored.token)
    LogEntrySyncService(apiClient, db).sync(stored.token)

    return Result.success()
}
```

- [ ] **Step 6: Run full app test suite**

```bash
./gradlew :app:test 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/sync/BodyWeightSyncService.kt \
        app/src/test/kotlin/org/branneman/health/sync/BodyWeightSyncServiceTest.kt \
        app/src/main/kotlin/org/branneman/health/sync/SyncWorker.kt
git commit -m "feat(app): add BodyWeightSyncService and wire into SyncWorker"
```

---

## Task 6: Dashboard UI — weight chip and dialog

**Files:**
- Modify: `app/src/test/kotlin/org/branneman/health/ui/DashboardScreenTest.kt`
- Modify: `app/src/main/kotlin/org/branneman/health/ui/DashboardScreen.kt`

- [ ] **Step 1: Update DashboardScreenTest with new test cases**

Replace the full content of `DashboardScreenTest.kt`:

```kotlin
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
            targetDeficit = 300, budgetRemaining = 1847, adjustedBudgetRemaining = 1847,
        ))
        compose.onNodeWithText("1847", substring = true).assertExists()
    }

    @Test fun `shows estimated source label`() {
        render(state = DashboardUiState(
            isLoading = false, caloriesOutSource = "estimate", adjustedBudgetRemaining = 1847,
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
            sportTonight = SportTonightEntity(date = "2026-06-11", activityType = "climbing", intensity = "normal", estimatedKcal = 600),
            adjustedBudgetRemaining = 2447,
        ))
        compose.onNodeWithText("Climbing", substring = true, ignoreCase = true).assertExists()
    }

    @Test fun `sport tonight active shows estimated kcal`() {
        render(state = DashboardUiState(
            isLoading = false,
            sportTonight = SportTonightEntity(date = "2026-06-11", activityType = "climbing", intensity = "normal", estimatedKcal = 600),
            adjustedBudgetRemaining = 2447,
        ))
        compose.onNodeWithText("600", substring = true).assertExists()
    }

    @Test fun `sport tonight active shows intensity chips`() {
        render(state = DashboardUiState(
            isLoading = false,
            sportTonight = SportTonightEntity(date = "2026-06-11", activityType = "climbing", intensity = "normal", estimatedKcal = 600),
            adjustedBudgetRemaining = 2447,
        ))
        compose.onNodeWithText("Normal").assertExists()
    }

    @Test fun `tapping intensity chip calls onSetSportTonight`() {
        var called: Pair<String, String>? = null
        render(
            state = DashboardUiState(
                isLoading = false,
                sportTonight = SportTonightEntity(date = "2026-06-11", activityType = "climbing", intensity = "normal", estimatedKcal = 600),
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
                sportTonight = SportTonightEntity(date = "2026-06-11", activityType = "rowing", intensity = "normal", estimatedKcal = 600),
                adjustedBudgetRemaining = 2447,
            ),
            onClearSportTonight = { cleared = true },
        )
        compose.onNodeWithText("clear", substring = true, ignoreCase = true).performClick()
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
}
```

- [ ] **Step 2: Run to verify the new tests fail**

```bash
./gradlew :app:test --tests "org.branneman.health.ui.DashboardScreenTest" 2>&1 | tail -30
```

Expected: compilation error — `DashboardContent` does not accept `onLogWeight`, and the weight chip composables don't exist yet.

- [ ] **Step 3: Update DashboardScreen.kt**

Replace the full content of `DashboardScreen.kt`:

```kotlin
package org.branneman.health.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.branneman.health.dashboard.DashboardUiState
import org.branneman.health.dashboard.DashboardViewModel
import org.branneman.health.dashboard.isValidWeightInput
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
        onLogWeight = viewModel::logWeight,
    )
}

@Composable
fun DashboardContent(
    state: DashboardUiState,
    onSetSportTonight: (String, String) -> Unit,
    onClearSportTonight: () -> Unit,
    onLogWeight: (Double) -> Unit,
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
        WeightChipRow(weightKg = state.weightKgToday, onLogWeight = onLogWeight)
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        SportTonightSection(state, onSetSportTonight, onClearSportTonight)
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
                onClick = { input.toDoubleOrNull()?.let { onSave(it) } },
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
    onSet: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedActivity by remember { mutableStateOf("climbing") }
    var selectedIntensity by remember { mutableStateOf("normal") }
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

- [ ] **Step 4: Run the dashboard screen tests**

```bash
./gradlew :app:test --tests "org.branneman.health.ui.DashboardScreenTest" 2>&1 | tail -30
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: Run the full app and server test suites**

```bash
./gradlew :app:test :server:test 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/ui/DashboardScreen.kt \
        app/src/test/kotlin/org/branneman/health/ui/DashboardScreenTest.kt
git commit -m "feat(app): add weight chip and entry dialog to dashboard"
```

---

## Done

Story 8 is complete. The user can:
- See "⚖ -- kg" on the dashboard if weight hasn't been logged today
- Tap to open a "Log weight" dialog, enter a value (20–300 kg, 1 d.p. max), and save
- See "⚖ 82.5 kg" after logging; tap again to edit
- Work fully offline — the entry syncs to the server the next time `SyncWorker` runs
