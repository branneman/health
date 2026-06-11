# Quick-add Logging Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement Story 7 end-to-end: Log screen with inline quick-add form, today's entry list with tap-to-delete, reactive Dashboard budget, and offline-first sync.

**Architecture:** Log entries are written to Room first (`PENDING_CREATE`) and synced to the server by `SyncWorker`. `DashboardViewModel` observes `logEntryDao().observeAll()` as a Flow so `caloriesIn` updates instantly without waiting for sync. The server call in the Dashboard only updates `caloriesOut` — it no longer overwrites `caloriesIn`.

**Tech Stack:** Kotlin, Ktor server (Exposed + Postgres), Room (SQLite), Jetpack Compose, WorkManager, Ktor client (MockEngine for tests), Robolectric.

---

## File map

| Action | Path | Responsibility |
|--------|------|----------------|
| Create | `server/src/main/resources/db/migration/V6__meal_type_enum_extend.sql` | Adds `'unknown'` and `'drink'` to the `meal_type` ENUM |
| Create | `shared/src/commonMain/kotlin/org/branneman/health/QuickAddRequestDto.kt` | POST body DTO for quick-add |
| Modify | `app/src/main/kotlin/org/branneman/health/db/dao/LogEntryDao.kt` | Add `deleteById` |
| Modify | `app/src/test/kotlin/org/branneman/health/TestFactories.kt` | Add `aQuickAddEntry` factory |
| Modify | `app/src/test/kotlin/org/branneman/health/db/dao/LogEntryDaoTest.kt` | Tests for `deleteById` and `sumQuickAddKcalForDate` edge cases |
| Modify | `server/src/main/kotlin/org/branneman/health/Application.kt` | Add `POST /in/log/quick-add` and `DELETE /in/log/{id}` |
| Create | `server/src/test/kotlin/org/branneman/health/LogEntryIntegrationTest.kt` | Tier 2a: server integration tests for the new endpoints |
| Modify | `app/src/main/kotlin/org/branneman/health/network/HealthApiClient.kt` | Add `postQuickAdd` and `deleteLogEntry` |
| Modify | `app/src/test/kotlin/org/branneman/health/network/HealthApiClientTest.kt` | Tests for the two new methods |
| Create | `app/src/main/kotlin/org/branneman/health/sync/LogEntrySyncService.kt` | Encapsulates log entry upload/delete sync logic |
| Create | `app/src/test/kotlin/org/branneman/health/sync/LogEntrySyncServiceTest.kt` | Tier 2b: tests for sync service |
| Modify | `app/src/main/kotlin/org/branneman/health/sync/SyncWorker.kt` | Call `LogEntrySyncService` on each run |
| Modify | `app/src/main/kotlin/org/branneman/health/dashboard/DashboardViewModel.kt` | Reactive `caloriesIn` from Room Flow |
| Modify | `app/src/test/kotlin/org/branneman/health/dashboard/DashboardLogicTest.kt` | Tests for reactive caloriesIn exclusion rules |
| Create | `app/src/main/kotlin/org/branneman/health/log/LogViewModel.kt` | ViewModel: add/delete/observe entries |
| Modify | `app/src/main/kotlin/org/branneman/health/ui/LogScreen.kt` | Replace stub with full UI |
| Create | `app/src/test/kotlin/org/branneman/health/ui/LogScreenTest.kt` | Tier 2b: Compose UI tests |
| Create | `server/src/apiTest/kotlin/org/branneman/health/LogEntryApiTest.kt` | Tier 3: API tests against real server |
| Create | `app/src/androidTest/kotlin/org/branneman/health/E2ESmokeTest.kt` | Tier 4: first E2E smoke test |
| Modify | `docs/specs/testing-manifesto.md` | Update "what currently meets the bar" |
| Modify | `docs/specs/feature-backlog.md` | Mark story 7 done |

---

## Task 1: Flyway V6 — extend meal_type ENUM

**Files:**
- Create: `server/src/main/resources/db/migration/V6__meal_type_enum_extend.sql`

- [ ] **Step 1: Create the migration**

```sql
-- V6__meal_type_enum_extend.sql
-- Adds 'unknown' for quick-add entries (no known meal type) and
-- 'drink' for future drink shortcuts (story 10).
ALTER TYPE meal_type ADD VALUE 'unknown';
ALTER TYPE meal_type ADD VALUE 'drink';
```

- [ ] **Step 2: Verify migration runs cleanly**

Start the server locally (or run `./gradlew :server:test`) — Flyway runs on startup and the test DB is migrated automatically by `TestDatabase`. Check for no migration errors.

- [ ] **Step 3: Commit**

```bash
git add server/src/main/resources/db/migration/V6__meal_type_enum_extend.sql
git commit -m "feat(server): add 'unknown' and 'drink' to meal_type ENUM (V6)"
```

---

## Task 2: QuickAddRequestDto in shared

**Files:**
- Create: `shared/src/commonMain/kotlin/org/branneman/health/QuickAddRequestDto.kt`

- [ ] **Step 1: Create the DTO**

```kotlin
package org.branneman.health

import kotlinx.serialization.Serializable

@Serializable
data class QuickAddRequestDto(
    val id: String,
    val quickAddKcal: Int,
    val quickAddLabel: String? = null,
    val loggedAt: String? = null,
)
```

- [ ] **Step 2: Commit**

```bash
git add shared/src/commonMain/kotlin/org/branneman/health/QuickAddRequestDto.kt
git commit -m "feat(shared): add QuickAddRequestDto"
```

---

## Task 3: LogEntryDao.deleteById + extend LogEntryDaoTest

**Files:**
- Modify: `app/src/main/kotlin/org/branneman/health/db/dao/LogEntryDao.kt`
- Modify: `app/src/test/kotlin/org/branneman/health/db/dao/LogEntryDaoTest.kt`

- [ ] **Step 1: Write the failing tests**

Add to `LogEntryDaoTest`:

```kotlin
@Test
fun `deleteById removes the entry`() = runTest {
    val userId = uuid()
    val id = uuid()
    dao.upsert(aLogEntry(id = id, userId = userId))
    assertEquals(1, dao.observeAll().first().size)

    dao.deleteById(id)

    assertTrue(dao.observeAll().first().isEmpty())
}

@Test
fun `sumQuickAddKcalForDate only counts today`() = runTest {
    val userId = uuid()
    dao.upsert(aQuickAddEntry(userId = userId, loggedAt = "2026-06-11T08:00:00Z", quickAddKcal = 400))
    dao.upsert(aQuickAddEntry(userId = userId, loggedAt = "2026-06-10T19:00:00Z", quickAddKcal = 800))

    val sum = dao.sumQuickAddKcalForDate(userId, "2026-06-11%")

    assertEquals(400, sum)
}

@Test
fun `sumQuickAddKcalForDate excludes entries with null quickAddKcal`() = runTest {
    val userId = uuid()
    dao.upsert(aQuickAddEntry(userId = userId, loggedAt = "2026-06-11T08:00:00Z", quickAddKcal = 350))
    dao.upsert(aLogEntry(userId = userId, loggedAt = "2026-06-11T12:00:00Z"))  // null quickAddKcal

    val sum = dao.sumQuickAddKcalForDate(userId, "2026-06-11%")

    assertEquals(350, sum)
}
```

Note: `aQuickAddEntry` is added in Task 4. Add the import `import org.branneman.health.aQuickAddEntry` now; the test will fail to compile until Task 4 is done — that is fine.

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :app:test --tests "org.branneman.health.db.dao.LogEntryDaoTest" 2>&1 | tail -20
```

Expected: compile error (missing `aQuickAddEntry`) or test failure (`deleteById` not defined).

- [ ] **Step 3: Add deleteById to LogEntryDao**

```kotlin
@Query("DELETE FROM log_entry WHERE id = :id")
suspend fun deleteById(id: String)
```

- [ ] **Step 4: Run tests again (still failing — aQuickAddEntry missing)**

After Task 4 is complete, run tests again. Come back here to confirm they pass.

- [ ] **Step 5: Commit (after Task 4 passes)**

```bash
git add app/src/main/kotlin/org/branneman/health/db/dao/LogEntryDao.kt \
        app/src/test/kotlin/org/branneman/health/db/dao/LogEntryDaoTest.kt
git commit -m "feat(app): add deleteById to LogEntryDao; extend DAO tests"
```

---

## Task 4: TestFactories — add aQuickAddEntry

**Files:**
- Modify: `app/src/test/kotlin/org/branneman/health/TestFactories.kt`

- [ ] **Step 1: Add factory function**

```kotlin
fun aQuickAddEntry(
    id: String = uuid(),
    userId: String = uuid(),
    loggedAt: String = "2026-01-01T08:00:00Z",
    quickAddKcal: Int = 350,
    quickAddLabel: String? = null,
    syncStatus: SyncStatus = SyncStatus.PENDING_CREATE,
) = LogEntryEntity(
    id            = id,
    userId        = userId,
    loggedAt      = loggedAt,
    mealType      = "unknown",
    quickAddKcal  = quickAddKcal,
    quickAddLabel = quickAddLabel,
    syncStatus    = syncStatus,
)
```

- [ ] **Step 2: Run LogEntryDaoTest to confirm it now passes**

```bash
./gradlew :app:test --tests "org.branneman.health.db.dao.LogEntryDaoTest" 2>&1 | tail -20
```

Expected: all tests PASS.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/kotlin/org/branneman/health/TestFactories.kt
git commit -m "test(app): add aQuickAddEntry factory to TestFactories"
```

---

## Task 5: Server endpoints + integration tests

**Files:**
- Modify: `server/src/main/kotlin/org/branneman/health/Application.kt`
- Create: `server/src/test/kotlin/org/branneman/health/LogEntryIntegrationTest.kt`

- [ ] **Step 1: Write the failing integration tests**

Create `server/src/test/kotlin/org/branneman/health/LogEntryIntegrationTest.kt`:

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
import org.branneman.health.data.LogEntry
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogEntryIntegrationTest {

    companion object {
        private val ds = TestDatabase.dataSource
        val testUserId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000004")
        private const val TEST_EMAIL = "logentry-test@test.local"
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

    @Before fun cleanMutableRows() {
        transaction { LogEntry.deleteWhere { userId eq testUserId } }
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

    @Test fun `POST quick-add returns 401 without token`() = appTest {
        val r = client.post("/in/log/quick-add") {
            contentType(ContentType.Application.Json)
            setBody("""{"id":"${UUID.randomUUID()}","quickAddKcal":350}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    @Test fun `POST quick-add creates entry and returns 201`() = appTest {
        val token = login()
        val id = UUID.randomUUID().toString()
        val r = client.post("/in/log/quick-add") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"id":"$id","quickAddKcal":350,"quickAddLabel":"Lunch","loggedAt":"2026-06-11T12:00:00Z"}""")
        }
        assertEquals(HttpStatusCode.Created, r.status)
        val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals(id, body["id"]!!.jsonPrimitive.content)
        assertEquals(350, body["quickAddKcal"]!!.jsonPrimitive.content.toInt())
        assertEquals("Lunch", body["quickAddLabel"]!!.jsonPrimitive.content)
    }

    @Test fun `POST quick-add is idempotent — second POST with same id returns 409`() = appTest {
        val token = login()
        val id = UUID.randomUUID().toString()
        val body = """{"id":"$id","quickAddKcal":350}"""
        client.post("/in/log/quick-add") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(body)
        }
        val r2 = client.post("/in/log/quick-add") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(body)
        }
        assertEquals(HttpStatusCode.Conflict, r2.status)
    }

    @Test fun `GET in-log returns created entry`() = appTest {
        val token = login()
        val id = UUID.randomUUID().toString()
        client.post("/in/log/quick-add") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"id":"$id","quickAddKcal":500,"loggedAt":"2026-06-11T08:00:00Z"}""")
        }
        val r = client.get("/in/log") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, r.status)
        val arr = Json.parseToJsonElement(r.bodyAsText()).jsonArray
        assertTrue(arr.any { it.jsonObject["id"]!!.jsonPrimitive.content == id })
    }

    @Test fun `DELETE in-log removes entry and returns 204`() = appTest {
        val token = login()
        val id = UUID.randomUUID().toString()
        client.post("/in/log/quick-add") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"id":"$id","quickAddKcal":350}""")
        }
        val del = client.delete("/in/log/$id") { bearerAuth(token) }
        assertEquals(HttpStatusCode.NoContent, del.status)

        val r = client.get("/in/log") { bearerAuth(token) }
        val arr = Json.parseToJsonElement(r.bodyAsText()).jsonArray
        assertTrue(arr.none { it.jsonObject["id"]!!.jsonPrimitive.content == id })
    }

    @Test fun `DELETE in-log for another user's entry returns 404`() = appTest {
        val token = login()
        // Insert an entry owned by a different user directly
        val otherId = UUID.randomUUID()
        val entryId = UUID.randomUUID()
        transaction {
            Users.insert {
                it[id]           = otherId
                it[username]     = "other-${System.currentTimeMillis()}@test.local"
                it[passwordHash] = TEST_HASH
            }
            LogEntry.insert {
                it[LogEntry.id]           = entryId
                it[LogEntry.userId]       = otherId
                it[LogEntry.loggedAt]     = java.time.OffsetDateTime.now()
                it[LogEntry.mealType]     = "unknown"
                it[LogEntry.quickAddKcal] = 200
                it[LogEntry.createdAt]    = java.time.OffsetDateTime.now()
            }
        }
        val r = client.delete("/in/log/$entryId") { bearerAuth(token) }
        assertEquals(HttpStatusCode.NotFound, r.status)
        transaction {
            Users.deleteWhere { Users.id eq otherId }
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :server:test --tests "org.branneman.health.LogEntryIntegrationTest" 2>&1 | tail -20
```

Expected: compile error (`@Before` not imported) or 404/405 responses — endpoints don't exist yet.

Fix the missing import: add `import org.junit.Before` at the top of the test file.

- [ ] **Step 3: Implement POST /in/log/quick-add in Application.kt**

Inside the `authenticate("api")` block, after the existing `get("/in/log")` route, add:

```kotlin
post("/in/log/quick-add") {
    val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
    val dto = call.receive<QuickAddRequestDto>()
    val id = runCatching { UUID.fromString(dto.id) }.getOrNull()
        ?: return@post call.respond(HttpStatusCode.BadRequest)
    val loggedAt = dto.loggedAt
        ?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
        ?: OffsetDateTime.now()

    val inserted = transaction {
        val exists = LogEntry.selectAll().where { LogEntry.id eq id }.count() > 0
        if (exists) return@transaction false
        LogEntry.insert {
            it[LogEntry.id]            = id
            it[LogEntry.userId]        = userId
            it[LogEntry.loggedAt]      = loggedAt
            it[LogEntry.mealType]      = "unknown"
            it[LogEntry.quickAddKcal]  = dto.quickAddKcal
            it[LogEntry.quickAddLabel] = dto.quickAddLabel
            it[LogEntry.createdAt]     = OffsetDateTime.now()
        }
        true
    }
    if (!inserted) return@post call.respond(HttpStatusCode.Conflict)

    call.respond(HttpStatusCode.Created, LogEntryDto(
        id            = id.toString(),
        loggedAt      = loggedAt.toString(),
        mealType      = "unknown",
        quickAddKcal  = dto.quickAddKcal,
        quickAddLabel = dto.quickAddLabel,
        items         = emptyList(),
    ))
}
```

Also add the `QuickAddRequestDto` import at the top of `Application.kt`:

```kotlin
import org.branneman.health.QuickAddRequestDto
```

- [ ] **Step 4: Implement DELETE /in/log/{id} in Application.kt**

In the same `authenticate("api") { route("/in/log") { ... } }` block (or directly under `authenticate`), add:

```kotlin
delete("/in/log/{id}") {
    val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
    val entryId = call.parameters["id"]
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: return@delete call.respond(HttpStatusCode.BadRequest)

    val deleted = transaction {
        LogEntry.deleteWhere {
            (LogEntry.id eq entryId) and (LogEntry.userId eq userId)
        } > 0
    }
    if (deleted) call.respond(HttpStatusCode.NoContent)
    else call.respond(HttpStatusCode.NotFound)
}
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
./gradlew :server:test --tests "org.branneman.health.LogEntryIntegrationTest" 2>&1 | tail -20
```

Expected: all 6 tests PASS.

- [ ] **Step 6: Run the full server test suite to check for regressions**

```bash
./gradlew :server:test 2>&1 | tail -20
```

Expected: all tests PASS.

- [ ] **Step 7: Commit**

```bash
git add server/src/main/kotlin/org/branneman/health/Application.kt \
        server/src/test/kotlin/org/branneman/health/LogEntryIntegrationTest.kt
git commit -m "feat(server): add POST /in/log/quick-add and DELETE /in/log/{id}"
```

---

## Task 6: HealthApiClient additions + client tests

**Files:**
- Modify: `app/src/main/kotlin/org/branneman/health/network/HealthApiClient.kt`
- Modify: `app/src/test/kotlin/org/branneman/health/network/HealthApiClientTest.kt`

- [ ] **Step 1: Write the failing tests**

Add to `HealthApiClientTest`:

```kotlin
@Test
fun `postQuickAdd returns LogEntryDto on 201`() = runBlocking {
    val entryId = "00000000-0000-0000-0000-000000000099"
    val client = mockClient { _ ->
        respond(
            """{"id":"$entryId","loggedAt":"2026-06-11T12:00:00Z","mealType":"unknown","quickAddKcal":350,"quickAddLabel":"Lunch","items":[]}""",
            HttpStatusCode.Created,
            headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        )
    }
    val result = HealthApiClient("http://test", client).postQuickAdd(
        "token",
        QuickAddRequestDto(id = entryId, quickAddKcal = 350, quickAddLabel = "Lunch"),
    )
    assertEquals(entryId, result.id)
    assertEquals(350, result.quickAddKcal)
}

@Test
fun `deleteLogEntry completes without error on 204`() = runBlocking {
    val client = HttpClient(MockEngine { respond("", HttpStatusCode.NoContent) }) {
        install(ContentNegotiation) { json() }
    }
    HealthApiClient("http://test", client).deleteLogEntry("token", "some-uuid")
}

@Test
fun `deleteLogEntry completes without error on 404`() = runBlocking {
    val client = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }) {
        install(ContentNegotiation) { json() }
    }
    HealthApiClient("http://test", client).deleteLogEntry("token", "some-uuid")
}

@Test
fun `deleteLogEntry throws on server error`() = runBlocking {
    val client = HttpClient(MockEngine { respond("", HttpStatusCode.InternalServerError) }) {
        install(ContentNegotiation) { json() }
    }
    assertFailsWith<Exception> {
        HealthApiClient("http://test", client).deleteLogEntry("token", "some-uuid")
    }
    Unit
}
```

Add missing imports:
```kotlin
import org.branneman.health.QuickAddRequestDto
import io.ktor.client.request.delete
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :app:test --tests "org.branneman.health.network.HealthApiClientTest" 2>&1 | tail -20
```

Expected: compile error — `postQuickAdd` and `deleteLogEntry` don't exist yet.

- [ ] **Step 3: Add the two methods to HealthApiClient**

```kotlin
suspend fun postQuickAdd(token: String, dto: QuickAddRequestDto): LogEntryDto =
    client.post("$baseUrl/in/log/quick-add") {
        header(HttpHeaders.Authorization, "Bearer $token")
        contentType(ContentType.Application.Json)
        setBody(dto)
    }.body()

suspend fun deleteLogEntry(token: String, id: String) {
    val response = client.delete("$baseUrl/in/log/$id") {
        header(HttpHeaders.Authorization, "Bearer $token")
    }
    if (!response.status.isSuccess() && response.status != HttpStatusCode.NotFound) {
        throw Exception("DELETE /in/log/$id failed: ${response.status}")
    }
}
```

Add the import at the top:
```kotlin
import io.ktor.client.request.delete
import org.branneman.health.QuickAddRequestDto
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :app:test --tests "org.branneman.health.network.HealthApiClientTest" 2>&1 | tail -20
```

Expected: all tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/network/HealthApiClient.kt \
        app/src/test/kotlin/org/branneman/health/network/HealthApiClientTest.kt
git commit -m "feat(app): add postQuickAdd and deleteLogEntry to HealthApiClient"
```

---

## Task 7: LogEntrySyncService + extend SyncWorker

**Files:**
- Create: `app/src/main/kotlin/org/branneman/health/sync/LogEntrySyncService.kt`
- Create: `app/src/test/kotlin/org/branneman/health/sync/LogEntrySyncServiceTest.kt`
- Modify: `app/src/main/kotlin/org/branneman/health/sync/SyncWorker.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/org/branneman/health/sync/LogEntrySyncServiceTest.kt`:

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
import org.branneman.health.aQuickAddEntry
import org.branneman.health.uuid
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
class LogEntrySyncServiceTest {

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
    fun `PENDING_CREATE is posted and marked SYNCED on 201`() = runTest {
        val entry = aQuickAddEntry(quickAddKcal = 500)
        db.logEntryDao().upsert(entry)

        val api = mockApiClient { _ ->
            respond(
                """{"id":"${entry.id}","loggedAt":"2026-06-11T12:00:00Z","mealType":"unknown","quickAddKcal":500,"quickAddLabel":null,"items":[]}""",
                HttpStatusCode.Created,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }

        LogEntrySyncService(api, db).sync("token")

        val synced = db.logEntryDao().getByStatus(SyncStatus.SYNCED)
        assertEquals(1, synced.size)
        assertEquals(entry.id, synced[0].id)
    }

    @Test
    fun `PENDING_CREATE stays PENDING_CREATE on network error`() = runTest {
        val entry = aQuickAddEntry(quickAddKcal = 500)
        db.logEntryDao().upsert(entry)

        val api = HealthApiClient("http://test", HttpClient(MockEngine { error("connection refused") }) {
            install(ContentNegotiation) { json() }
        })

        LogEntrySyncService(api, db).sync("token")

        assertEquals(1, db.logEntryDao().getByStatus(SyncStatus.PENDING_CREATE).size)
    }

    @Test
    fun `PENDING_DELETE is deleted from server and hard-deleted from Room on 204`() = runTest {
        val entry = aQuickAddEntry(quickAddKcal = 300, syncStatus = SyncStatus.PENDING_DELETE)
        db.logEntryDao().upsert(entry)

        val api = mockApiClient { _ -> respond("", HttpStatusCode.NoContent) }

        LogEntrySyncService(api, db).sync("token")

        assertTrue(db.logEntryDao().getByStatus(SyncStatus.PENDING_DELETE).isEmpty())
        assertTrue(db.logEntryDao().observeAll().first().isEmpty())
    }

    @Test
    fun `PENDING_DELETE is hard-deleted from Room even when server returns 404`() = runTest {
        val entry = aQuickAddEntry(quickAddKcal = 300, syncStatus = SyncStatus.PENDING_DELETE)
        db.logEntryDao().upsert(entry)

        val api = mockApiClient { _ -> respond("", HttpStatusCode.NotFound) }

        LogEntrySyncService(api, db).sync("token")

        assertTrue(db.logEntryDao().getByStatus(SyncStatus.PENDING_DELETE).isEmpty())
    }

    @Test
    fun `PENDING_DELETE stays on 500 server error`() = runTest {
        val entry = aQuickAddEntry(quickAddKcal = 300, syncStatus = SyncStatus.PENDING_DELETE)
        db.logEntryDao().upsert(entry)

        val api = mockApiClient { _ -> respond("", HttpStatusCode.InternalServerError) }

        LogEntrySyncService(api, db).sync("token")

        assertEquals(1, db.logEntryDao().getByStatus(SyncStatus.PENDING_DELETE).size)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :app:test --tests "org.branneman.health.sync.LogEntrySyncServiceTest" 2>&1 | tail -20
```

Expected: compile error — `LogEntrySyncService` doesn't exist yet.

- [ ] **Step 3: Create LogEntrySyncService**

Create `app/src/main/kotlin/org/branneman/health/sync/LogEntrySyncService.kt`:

```kotlin
package org.branneman.health.sync

import org.branneman.health.QuickAddRequestDto
import org.branneman.health.db.HealthDatabase
import org.branneman.health.db.SyncStatus
import org.branneman.health.network.HealthApiClient

class LogEntrySyncService(
    private val api: HealthApiClient,
    private val db: HealthDatabase,
) {
    suspend fun sync(token: String) {
        db.logEntryDao().getByStatus(SyncStatus.PENDING_CREATE).forEach { entity ->
            val kcal = entity.quickAddKcal ?: return@forEach
            runCatching {
                api.postQuickAdd(
                    token,
                    QuickAddRequestDto(
                        id            = entity.id,
                        quickAddKcal  = kcal,
                        quickAddLabel = entity.quickAddLabel,
                        loggedAt      = entity.loggedAt,
                    )
                )
            }.onSuccess {
                db.logEntryDao().updateSyncStatus(entity.id, SyncStatus.SYNCED)
            }
        }

        db.logEntryDao().getByStatus(SyncStatus.PENDING_DELETE).forEach { entity ->
            runCatching { api.deleteLogEntry(token, entity.id) }
                .onSuccess { db.logEntryDao().deleteById(entity.id) }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :app:test --tests "org.branneman.health.sync.LogEntrySyncServiceTest" 2>&1 | tail -20
```

Expected: all 5 tests PASS.

- [ ] **Step 5: Extend SyncWorker to call LogEntrySyncService**

Replace `SyncWorker.doWork()` body with:

```kotlin
override suspend fun doWork(): Result {
    val app = applicationContext as HealthApplication
    val db = app.db
    val tokenStore = TokenStore(applicationContext.authDataStore)
    val stored = tokenStore.tokenFlow.first() ?: return Result.success()

    val apiClient = HealthApiClient(
        baseUrl = BuildConfig.SERVER_BASE_URL,
        client = HttpClient(Android) { install(ContentNegotiation) { json() } },
    )

    db.bodyWeightDao().getByStatus(SyncStatus.PENDING_DELETE).forEach { entity ->
        db.bodyWeightDao().deleteById(entity.id)
    }

    LogEntrySyncService(apiClient, db).sync(stored.token)

    return Result.success()
}
```

Add the missing imports to `SyncWorker.kt`:

```kotlin
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import org.branneman.health.BuildConfig
import org.branneman.health.network.HealthApiClient
```

- [ ] **Step 6: Build to confirm no compile errors**

```bash
./gradlew :app:assembleDebug 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/sync/LogEntrySyncService.kt \
        app/src/main/kotlin/org/branneman/health/sync/SyncWorker.kt \
        app/src/test/kotlin/org/branneman/health/sync/LogEntrySyncServiceTest.kt
git commit -m "feat(app): add LogEntrySyncService; wire log entry sync in SyncWorker"
```

---

## Task 8: DashboardViewModel — reactive caloriesIn

**Files:**
- Modify: `app/src/main/kotlin/org/branneman/health/dashboard/DashboardViewModel.kt`
- Modify: `app/src/test/kotlin/org/branneman/health/dashboard/DashboardLogicTest.kt`

- [ ] **Step 1: Write the failing tests**

Add to `DashboardLogicTest`:

```kotlin
// --- caloriesIn reactive filter rules ---

@Test fun `caloriesIn sum includes only today's entries`() {
    // Entries with loggedAt starting with today count; yesterday's do not.
    val today = "2026-06-11"
    val entries = listOf(
        LogEntryEntity(id = uuid(), userId = "u1", loggedAt = "${today}T08:00:00Z",
            mealType = "unknown", quickAddKcal = 400, quickAddLabel = null,
            syncStatus = SyncStatus.PENDING_CREATE),
        LogEntryEntity(id = uuid(), userId = "u1", loggedAt = "2026-06-10T19:00:00Z",
            mealType = "unknown", quickAddKcal = 800, quickAddLabel = null,
            syncStatus = SyncStatus.SYNCED),
    )
    val sum = entries
        .filter { it.userId == "u1" && it.loggedAt.startsWith(today) }
        .sumOf { it.quickAddKcal ?: 0 }
    assertEquals(400, sum)
}

@Test fun `caloriesIn sum excludes entries with null quickAddKcal`() {
    val today = "2026-06-11"
    val entries = listOf(
        LogEntryEntity(id = uuid(), userId = "u1", loggedAt = "${today}T08:00:00Z",
            mealType = "unknown", quickAddKcal = 350, quickAddLabel = null,
            syncStatus = SyncStatus.PENDING_CREATE),
        LogEntryEntity(id = uuid(), userId = "u1", loggedAt = "${today}T12:00:00Z",
            mealType = "breakfast", quickAddKcal = null, quickAddLabel = null,
            syncStatus = SyncStatus.SYNCED),
    )
    val sum = entries
        .filter { it.userId == "u1" && it.loggedAt.startsWith(today) }
        .sumOf { it.quickAddKcal ?: 0 }
    assertEquals(350, sum)
}
```

Add missing imports to `DashboardLogicTest.kt`:

```kotlin
import org.branneman.health.db.SyncStatus
import org.branneman.health.db.entities.LogEntryEntity
import org.branneman.health.uuid
```

- [ ] **Step 2: Run tests to verify they pass already** (these are pure logic tests)

```bash
./gradlew :app:test --tests "org.branneman.health.dashboard.DashboardLogicTest" 2>&1 | tail -20
```

Expected: all tests PASS. (The new tests use only pure Kotlin — no ViewModel changes needed to pass them.)

- [ ] **Step 3: Modify DashboardViewModel — add observeLogEntries coroutine**

In `DashboardViewModel`, change the `init` block from:

```kotlin
init {
    viewModelScope.launch { load() }
}
```

To:

```kotlin
init {
    viewModelScope.launch { observeLogEntries() }
    viewModelScope.launch { load() }
}
```

Add the new function after `init`:

```kotlin
private suspend fun observeLogEntries() {
    val stored = tokenStore.tokenFlow.first() ?: return
    val today = LocalDate.now().toString()
    app.db.logEntryDao().observeAll().collect { entries ->
        val caloriesIn = entries
            .filter { it.userId == stored.userId && it.loggedAt.startsWith(today) }
            .sumOf { it.quickAddKcal ?: 0 }
        _uiState.update { state ->
            val budget = state.caloriesOut - state.targetDeficit - caloriesIn
            state.copy(
                caloriesIn              = caloriesIn,
                budgetRemaining         = budget,
                adjustedBudgetRemaining = budget + (state.sportTonight?.estimatedKcal ?: 0),
            )
        }
    }
}
```

- [ ] **Step 4: Modify load() — stop reading caloriesIn from server DTO**

In `load()`, change the `onSuccess` block from:

```kotlin
.onSuccess { dto ->
    _uiState.update { state ->
        val sport = state.sportTonight
        state.copy(
            isLoading               = false,
            caloriesIn              = dto.caloriesIn,
            caloriesOut             = dto.caloriesOut,
            caloriesOutSource       = dto.caloriesOutSource,
            targetDeficit           = dto.targetDeficit,
            budgetRemaining         = dto.budgetRemaining,
            adjustedBudgetRemaining = dto.budgetRemaining + (sport?.estimatedKcal ?: 0),
        )
    }
}
```

To:

```kotlin
.onSuccess { dto ->
    _uiState.update { state ->
        val budget = dto.caloriesOut - dto.targetDeficit - state.caloriesIn
        state.copy(
            isLoading               = false,
            caloriesOut             = dto.caloriesOut,
            caloriesOutSource       = dto.caloriesOutSource,
            targetDeficit           = dto.targetDeficit,
            budgetRemaining         = budget,
            adjustedBudgetRemaining = budget + (state.sportTonight?.estimatedKcal ?: 0),
        )
    }
}
```

- [ ] **Step 5: Build to confirm no compile errors**

```bash
./gradlew :app:assembleDebug 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/dashboard/DashboardViewModel.kt \
        app/src/test/kotlin/org/branneman/health/dashboard/DashboardLogicTest.kt
git commit -m "feat(app): DashboardViewModel observes Room for reactive caloriesIn"
```

---

## Task 9: LogViewModel

**Files:**
- Create: `app/src/main/kotlin/org/branneman/health/log/LogViewModel.kt`

- [ ] **Step 1: Create LogViewModel**

Create `app/src/main/kotlin/org/branneman/health/log/LogViewModel.kt`:

```kotlin
package org.branneman.health.log

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.branneman.health.HealthApplication
import org.branneman.health.auth.TokenStore
import org.branneman.health.auth.authDataStore
import org.branneman.health.db.SyncStatus
import org.branneman.health.db.entities.LogEntryEntity
import java.time.LocalDate
import java.time.OffsetDateTime

class LogViewModel(application: Application) : AndroidViewModel(application) {

    private val db = (application as HealthApplication).db
    private val tokenStore = TokenStore(application.authDataStore)

    // Non-null while within the 4-second undo window. Cleared on undo or timeout.
    private val _undoPending = MutableStateFlow<Pair<LogEntryEntity, SyncStatus>?>(null)

    val entries: StateFlow<List<LogEntryEntity>> = db.logEntryDao().observeAll()
        .map { all ->
            val today = LocalDate.now().toString()
            all.filter { it.loggedAt.startsWith(today) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addEntry(kcalStr: String, label: String) {
        val kcal = kcalStr.trim().toIntOrNull()?.takeIf { it > 0 } ?: return
        viewModelScope.launch {
            val userId = tokenStore.tokenFlow.first()?.userId ?: return@launch
            val entity = LogEntryEntity(
                userId        = userId,
                loggedAt      = OffsetDateTime.now().toString(),
                mealType      = "unknown",
                quickAddKcal  = kcal,
                quickAddLabel = label.trim().ifEmpty { null },
            )
            db.logEntryDao().upsert(entity)
            _undoPending.value = entity to SyncStatus.PENDING_CREATE
        }
    }

    fun undoAdd() {
        viewModelScope.launch {
            _undoPending.value?.let { (entity, _) ->
                db.logEntryDao().deleteById(entity.id)
                _undoPending.value = null
            }
        }
    }

    fun deleteEntry(entry: LogEntryEntity) {
        viewModelScope.launch {
            _undoPending.value = entry to entry.syncStatus
            db.logEntryDao().updateSyncStatus(entry.id, SyncStatus.PENDING_DELETE)
        }
    }

    fun undoDelete() {
        viewModelScope.launch {
            _undoPending.value?.let { (entity, previousStatus) ->
                db.logEntryDao().updateSyncStatus(entity.id, previousStatus)
                _undoPending.value = null
            }
        }
    }
}
```

- [ ] **Step 2: Build to confirm no compile errors**

```bash
./gradlew :app:assembleDebug 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/log/LogViewModel.kt
git commit -m "feat(app): add LogViewModel for log entry add/delete"
```

---

## Task 10: LogScreen + LogScreenTest

**Files:**
- Modify: `app/src/main/kotlin/org/branneman/health/ui/LogScreen.kt`
- Create: `app/src/test/kotlin/org/branneman/health/ui/LogScreenTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/org/branneman/health/ui/LogScreenTest.kt`:

```kotlin
package org.branneman.health.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.branneman.health.aQuickAddEntry
import org.branneman.health.db.entities.LogEntryEntity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LogScreenTest {

    @get:Rule val compose = createComposeRule()

    private fun render(
        entries: List<LogEntryEntity> = emptyList(),
        onAdd: (String, String) -> Unit = { _, _ -> },
        onDelete: (LogEntryEntity) -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                LogContent(entries = entries, onAdd = onAdd, onDelete = onDelete)
            }
        }
    }

    @Test fun `Add button is disabled when kcal field is empty`() {
        render()
        compose.onNodeWithText("Add").assertIsNotEnabled()
    }

    @Test fun `Add button is enabled when positive kcal is entered`() {
        render()
        compose.onNodeWithTag("kcal_input").performTextInput("350")
        compose.onNodeWithText("Add").assertIsEnabled()
    }

    @Test fun `Add button is disabled when kcal is zero`() {
        render()
        compose.onNodeWithTag("kcal_input").performTextInput("0")
        compose.onNodeWithText("Add").assertIsNotEnabled()
    }

    @Test fun `entries appear in list`() {
        val entries = listOf(
            aQuickAddEntry(loggedAt = "2026-06-11T13:00:00Z", quickAddKcal = 560, quickAddLabel = "Lunch"),
        )
        render(entries = entries)
        compose.onNodeWithText("Lunch", substring = true).assertExists()
        compose.onNodeWithText("560", substring = true).assertExists()
    }

    @Test fun `empty state shows nothing-logged message`() {
        render(entries = emptyList())
        compose.onNodeWithText("Nothing logged today.", substring = true).assertExists()
    }

    @Test fun `tapping entry calls onDelete`() {
        val entry = aQuickAddEntry(loggedAt = "2026-06-11T08:00:00Z", quickAddKcal = 430, quickAddLabel = "Breakfast")
        var deleted: LogEntryEntity? = null
        render(entries = listOf(entry), onDelete = { deleted = it })
        compose.onNodeWithText("Breakfast", substring = true).performClick()
        compose.onNodeWithText("Delete").performClick()
        assert(deleted?.id == entry.id)
    }

    @Test fun `tapping Add calls onAdd with kcal and label`() {
        var result: Pair<String, String>? = null
        render(onAdd = { kcal, label -> result = kcal to label })
        compose.onNodeWithTag("kcal_input").performTextInput("350")
        compose.onNodeWithTag("label_input").performTextInput("Pasta")
        compose.onNodeWithText("Add").performClick()
        assert(result == "350" to "Pasta")
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :app:test --tests "org.branneman.health.ui.LogScreenTest" 2>&1 | tail -20
```

Expected: compile error — `LogContent` not defined with the right signature, test tags not present.

- [ ] **Step 3: Replace LogScreen.kt with the full implementation**

```kotlin
package org.branneman.health.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.branneman.health.db.entities.LogEntryEntity
import org.branneman.health.log.LogViewModel
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

@Composable
fun LogScreen(viewModel: LogViewModel = viewModel()) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
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
            entries  = entries,
            onAdd    = { kcal, label ->
                viewModel.addEntry(kcal, label)
                lastAction = LogAction.Added("Logged")
            },
            onDelete = { entry ->
                viewModel.deleteEntry(entry)
                lastAction = LogAction.Deleted("Deleted")
            },
            modifier = Modifier.padding(padding),
        )
    }
}

private sealed interface LogAction {
    val message: String
    data class Added(override val message: String)   : LogAction
    data class Deleted(override val message: String) : LogAction
}

@Composable
fun LogContent(
    entries: List<LogEntryEntity>,
    onAdd: (kcal: String, label: String) -> Unit,
    onDelete: (LogEntryEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    var kcal by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var entryToDelete by remember { mutableStateOf<LogEntryEntity?>(null) }
    val kcalFocusRequester = remember { FocusRequester() }
    val addEnabled = kcal.isNotEmpty() && (kcal.toIntOrNull() ?: 0) > 0

    LaunchedEffect(Unit) { kcalFocusRequester.requestFocus() }

    entryToDelete?.let { entry ->
        DeleteConfirmDialog(
            entry     = entry,
            onConfirm = { onDelete(entry); entryToDelete = null },
            onDismiss = { entryToDelete = null },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value         = kcal,
                onValueChange = { kcal = it.filter(Char::isDigit) },
                label         = { Text("kcal") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction    = ImeAction.Next,
                ),
                singleLine = true,
                modifier   = Modifier.width(90.dp)
                    .focusRequester(kcalFocusRequester)
                    .testTag("kcal_input"),
            )
            OutlinedTextField(
                value         = label,
                onValueChange = { label = it },
                label         = { Text("label (optional)") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (addEnabled) { onAdd(kcal, label); kcal = ""; label = "" }
                }),
                singleLine = true,
                modifier   = Modifier.weight(1f).testTag("label_input"),
            )
            Button(
                onClick  = { onAdd(kcal, label); kcal = ""; label = "" },
                enabled  = addEnabled,
            ) { Text("Add") }
        }

        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(modifier = Modifier.weight(1f))
            Text(
                text     = "Today",
                style    = MaterialTheme.typography.labelSmall,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            HorizontalDivider(modifier = Modifier.weight(1f))
        }

        Spacer(Modifier.height(8.dp))

        if (entries.isEmpty()) {
            Text(
                text     = "Nothing logged today.",
                style    = MaterialTheme.typography.bodyMedium,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        } else {
            val total = entries.sumOf { it.quickAddKcal ?: 0 }
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(entries, key = { it.id }) { entry ->
                    LogEntryRow(entry = entry, onClick = { entryToDelete = entry })
                    HorizontalDivider()
                }
            }
            Text(
                text     = "$total kcal logged today",
                style    = MaterialTheme.typography.bodySmall,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.End).padding(top = 8.dp),
            )
        }
    }
}

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun LogEntryRow(entry: LogEntryEntity, onClick: () -> Unit) {
    val time = remember(entry.loggedAt) {
        runCatching { OffsetDateTime.parse(entry.loggedAt).format(timeFmt) }.getOrDefault("--:--")
    }
    Row(
        modifier              = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = time, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (entry.quickAddLabel != null) {
                Text(text = entry.quickAddLabel, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(
            text  = "${entry.quickAddKcal ?: 0} kcal",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DeleteConfirmDialog(
    entry: LogEntryEntity,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val time = remember(entry.loggedAt) {
        runCatching { OffsetDateTime.parse(entry.loggedAt).format(timeFmt) }.getOrDefault("--:--")
    }
    val title = buildString {
        entry.quickAddLabel?.let { append("$it — ") }
        append("${entry.quickAddKcal ?: 0} kcal — $time")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title            = { Text(title) },
        confirmButton    = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton    = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :app:test --tests "org.branneman.health.ui.LogScreenTest" 2>&1 | tail -20
```

Expected: all 7 tests PASS.

- [ ] **Step 5: Run the full app test suite**

```bash
./gradlew :app:test 2>&1 | tail -20
```

Expected: all tests PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/ui/LogScreen.kt \
        app/src/test/kotlin/org/branneman/health/ui/LogScreenTest.kt
git commit -m "feat(app): implement LogScreen with quick-add form and today's entry list"
```

---

## Task 11: LogEntryApiTest (Tier 3 — real server)

**Files:**
- Create: `server/src/apiTest/kotlin/org/branneman/health/LogEntryApiTest.kt`

Runs against the live production server. Requires `API_TEST_SERVER_URL`, `API_TEST_EMAIL`, `API_TEST_PASSWORD` env vars (or in `.env`).

- [ ] **Step 1: Create LogEntryApiTest**

```kotlin
package org.branneman.health

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.branneman.health.LogEntryDto
import org.junit.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogEntryApiTest : ApiTestBase() {

    @Test
    fun `POST quick-add then GET then DELETE`() = runTest {
        val token = login()
        val id = UUID.randomUUID().toString()

        val postResp = client.post("$serverUrl/in/log/quick-add") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(QuickAddRequestDto(id = id, quickAddKcal = 350, quickAddLabel = "API test entry"))
        }
        assertEquals(HttpStatusCode.Created, postResp.status)

        val entries = client.get("$serverUrl/in/log") { bearerAuth(token) }
            .body<List<LogEntryDto>>()
        assertTrue(entries.any { it.id == id }, "Created entry must appear in GET /in/log")

        val delResp = client.delete("$serverUrl/in/log/$id") { bearerAuth(token) }
        assertEquals(HttpStatusCode.NoContent, delResp.status)

        val entriesAfter = client.get("$serverUrl/in/log") { bearerAuth(token) }
            .body<List<LogEntryDto>>()
        assertTrue(entriesAfter.none { it.id == id }, "Deleted entry must not appear in GET /in/log")
    }
}
```

Add import `import org.branneman.health.QuickAddRequestDto` at the top.

- [ ] **Step 2: Commit**

```bash
git add server/src/apiTest/kotlin/org/branneman/health/LogEntryApiTest.kt
git commit -m "test(server): add LogEntryApiTest (Tier 3)"
```

- [ ] **Step 3: Run when server is deployed** (on demand, not CI)

```bash
./gradlew :server:apiTest 2>&1 | tail -20
```

---

## Task 12: E2E smoke test (Tier 4)

**Files:**
- Create: `app/src/androidTest/kotlin/org/branneman/health/E2ESmokeTest.kt`

Runs on a real device or emulator against the production server. Requires `E2E_EMAIL`, `E2E_PASSWORD` env vars and the `test+e2e@bran.name` account pre-seeded via `local-db-seed/test-e2e-account-seed.sql`.

- [ ] **Step 1: Create the androidTest directory and test file**

```bash
mkdir -p app/src/androidTest/kotlin/org/branneman/health
```

Create `app/src/androidTest/kotlin/org/branneman/health/E2ESmokeTest.kt`:

```kotlin
package org.branneman.health

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class E2ESmokeTest {

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val email    = System.getenv("E2E_EMAIL")    ?: "test+e2e@bran.name"
    private val password = System.getenv("E2E_PASSWORD") ?: error("E2E_PASSWORD not set")

    @After fun cleanup() {
        // Log out so each test starts from the login screen.
        // If the Settings tab is reachable, sign out; otherwise no-op.
        runCatching {
            compose.onNodeWithText("Settings", substring = true).performClick()
            compose.onNodeWithText("Sign out", substring = true, ignoreCase = true).performClick()
        }
    }

    @Test
    fun `login - view dashboard - log entry - budget updates - sign out`() {
        // Login
        compose.onNodeWithText("Email", substring = true, ignoreCase = true)
            .performTextInput(email)
        compose.onNodeWithText("Password", substring = true, ignoreCase = true)
            .performTextInput(password)
        compose.onNodeWithText("Sign in", substring = true, ignoreCase = true)
            .performClick()

        // Wait for Dashboard to appear
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Today", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        // Navigate to Log tab
        compose.onNodeWithText("Log").performClick()

        // Add a quick entry
        compose.onNodeWithTag("kcal_input").performTextInput("123")
        compose.onNodeWithText("Add").performClick()

        // Entry appears in list
        compose.waitUntil(timeoutMillis = 3_000) {
            compose.onAllNodesWithText("123", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        // Navigate back to Dashboard — budget should now include the 123 kcal
        compose.onNodeWithText("Dashboard").performClick()
        compose.waitUntil(timeoutMillis = 3_000) {
            compose.onAllNodesWithText("Today", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        // Clean up: delete the test entry
        compose.onNodeWithText("Log").performClick()
        compose.onNodeWithText("123", substring = true).performClick()
        compose.onNodeWithText("Delete").performClick()
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/androidTest/kotlin/org/branneman/health/E2ESmokeTest.kt
git commit -m "test(app): add E2E smoke test (Tier 4) — login, log entry, dashboard update"
```

- [ ] **Step 3: Run on a connected device/emulator** (on demand)

```bash
./gradlew :app:connectedAndroidTest 2>&1 | tail -30
```

---

## Task 13: Update docs

**Files:**
- Modify: `docs/specs/testing-manifesto.md`
- Modify: `docs/specs/feature-backlog.md`

- [ ] **Step 1: Update testing-manifesto.md — "what currently meets the bar"**

In the **Tier 2a** section, append `LogEntryIntegrationTest` to the list:

```
**What currently meets the bar:** `AuthIntegrationTest`, `ProfileAndShortcutsIntegrationTest`,
`SyncDownloadIntegrationTest`, `BodyWeightIntegrationTest`, `MultiUserIsolationTest`,
`SummaryIntegrationTest`, `LogEntryIntegrationTest`.
```

In the **Tier 2b** section, update the sync services line and screen tests line:

```
**What currently meets the bar:** all ten DAO tests (add `LogEntryDaoTest` counts as extended),
`LoginSyncServiceTest`, `LogEntrySyncServiceTest`, `LoginScreenTest`, `OnboardingScreenTest`,
`DashboardScreenTest`, `LogScreenTest`.

**What is still missing:** UI tests for `SettingsScreen` — to be written alongside story 15.
```

In the **Tier 3** section, append `LogEntryApiTest`:

```
**What currently meets the bar:** `AuthApiTest`, `SyncDownloadApiTest`, `ProfileApiTest`,
`ShortcutsApiTest`, `BodyWeightApiTest`, `LogEntryApiTest`.
```

In the **Tier 4** section, update to reflect the smoke test now exists:

```
**What currently meets the bar:** `E2ESmokeTest` — login → dashboard → log a meal → sign out.
```

Also remove item 1 from **What Needs to Change Now** (write E2E smoke suite) since it's done. Remove item 2 for `LogScreen` since it's also done.

- [ ] **Step 2: Mark story 7 done in feature-backlog.md**

Change the story 7 row from:

```
|   | 7  | **Quick-add logging** — log anything as kcal + optional label, offline-first, budget updates ...
```

To:

```
| ✓ | 7  | **Quick-add logging** — log anything as kcal + optional label, offline-first, budget updates ...
```

Also add the spec link in the Spec column:

```
| [quick-add-logging](quick-add-logging.md) |
```

- [ ] **Step 3: Commit**

```bash
git add docs/specs/testing-manifesto.md docs/specs/feature-backlog.md
git commit -m "docs(meta): mark Story 7 done; update testing manifesto tiers"
```

---

## Task 14: Squash spec + plan into one commit

The spec was written in two commits (`e4bffef` and `66e73d0`) and the plan in one. Squash all three into a single doc commit before this branch is considered done.

- [ ] **Step 1: Interactive rebase to squash the three doc commits**

The two spec commits and the plan commit are `e4bffef`, `66e73d0`, and the plan commit. Squash them by rebasing onto the commit before the first spec commit (`802e331`):

```bash
git rebase -i 802e331
```

In the editor, mark `e4bffef` as `pick` and the next two as `squash` (or `s`). Save.

- [ ] **Step 2: Write the squash commit message**

```
docs(meta): spec and implementation plan for Story 7 — quick-add logging

Spec covers Log screen UI, offline-first architecture, mealType ENUM extension,
caloriesIn ownership split, sync flow, and full test plan across all four tiers.

Plan covers all 13 implementation tasks with file map, TDD steps, and exact code.

Also updates api-design.md (new endpoints, corrected summary DTO), testing-manifesto.md
(UUID slot #4 claimed), and UX docs (remove (est.) label, mealType note).
```

- [ ] **Step 3: Verify git log**

```bash
git log --oneline -5
```

Expected: the three commits are now one, followed by `802e331`.
