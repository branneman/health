# Test Suite Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the test suite described in `docs/specs/testing-manifesto.md` — replace fake server endpoint tests with real integration tests, add missing DAO and Compose UI tests, establish the API test suite, and write seed SQL for the two test accounts.

**Architecture:** Server integration tests boot the real `Application.module()` against a local docker-compose Postgres (`health_test` database); a minimal refactor makes the DataSource injectable. App component tests follow the Robolectric pattern already established in `BodyWeightDaoTest`. API tests live in a new `apiTest` source set in the `server` module, call the real VPS using `ktor-client`, and share DTOs from `shared`.

**Tech Stack:** docker-compose + `health_test` DB, Ktor `testApplication`, Robolectric, Compose UI Test (`ui-test-junit4`), `ktor-client` for API tests, BCrypt for test-user setup.

---

## Phase A — Server Integration Tests

Phases are independent. Work Phase B and Phase C in parallel with Phase A if desired.

---

### Task A1: Set up docker-compose test database and TestDatabase helper

The dev docker-compose already runs Postgres. Add a second database (`health_test`) via an init script, and write the `TestDatabase` helper that connects to it and runs Flyway migrations.

**Files:**
- Modify: `docker-compose.yml`
- Create: `docker/init-test-db.sql`
- Modify: `server/src/test/kotlin/org/branneman/health/TestDatabase.kt`

- [ ] **Add the init script mount to docker-compose.yml**

  In the `postgres` service, add the init script volume:
  ```yaml
  volumes:
    - pgdata:/var/lib/postgresql/data
    - ./docker/init-test-db.sql:/docker-entrypoint-initdb.d/01-init-test-db.sql
  ```

- [ ] **Create docker/init-test-db.sql**

  ```sql
  CREATE DATABASE health_test;
  ```

  Note: `docker-entrypoint-initdb.d` only runs on first container start (when `pgdata` volume is empty). To apply it to an existing volume: `docker compose down -v && docker compose up -d`.

- [ ] **Write the TestDatabase helper**

  Create/replace `server/src/test/kotlin/org/branneman/health/TestDatabase.kt`:
  ```kotlin
  package org.branneman.health

  import com.zaxxer.hikari.HikariConfig
  import com.zaxxer.hikari.HikariDataSource
  import org.flywaydb.core.Flyway
  import javax.sql.DataSource

  object TestDatabase {
      val dataSource: DataSource by lazy {
          val ds = HikariDataSource(HikariConfig().apply {
              jdbcUrl  = System.getenv("TEST_DATABASE_URL")      ?: "jdbc:postgresql://localhost:5432/health_test"
              username = System.getenv("TEST_POSTGRES_USER")     ?: "health"
              password = System.getenv("TEST_POSTGRES_PASSWORD") ?: "health"
              driverClassName = "org.postgresql.Driver"
              maximumPoolSize = 5
          })
          Flyway.configure().dataSource(ds).load().migrate()
          ds
      }
  }
  ```

- [ ] **Run the server tests to confirm the helper compiles**

  ```bash
  ./gradlew :server:test
  ```
  Expected: existing tests pass (the helper is not yet used).

- [ ] **Commit**

  ```bash
  git add docker-compose.yml docker/init-test-db.sql server/src/test/kotlin/org/branneman/health/TestDatabase.kt
  git commit -m "test(server): set up health_test DB in docker-compose + TestDatabase helper"
  ```

---

### Task A2: Make Application.module() injectable for tests

The current `module()` reads `DATABASE_URL` from env vars. Add an overload that accepts a `DataSource` so tests can inject the Testcontainers instance without touching env vars.

**Files:**
- Modify: `server/src/main/kotlin/org/branneman/health/Application.kt`

- [ ] **Extract the database wiring into a private helper and add an overloaded entry point**

  In `Application.kt`, change the top of `fun Application.module()` so it looks like this:

  ```kotlin
  fun Application.module() {
      val dbUrl      = System.getenv("DATABASE_URL")      ?: error("DATABASE_URL not set")
      val dbUser     = System.getenv("POSTGRES_USER")     ?: error("POSTGRES_USER not set")
      val dbPassword = System.getenv("POSTGRES_PASSWORD") ?: error("POSTGRES_PASSWORD not set")

      Flyway.configure().dataSource(dbUrl, dbUser, dbPassword).load().migrate()

      val dataSource = HikariDataSource(HikariConfig().apply {
          jdbcUrl         = dbUrl
          username        = dbUser
          password        = dbPassword
          driverClassName = "org.postgresql.Driver"
          maximumPoolSize = 5
      })
      module(dataSource)
  }

  fun Application.module(dataSource: javax.sql.DataSource) {
      Database.connect(dataSource)

      val authService        = AuthService()
      val ipRateLimiter      = RateLimiter(store = DbLoginAttemptsStore())
      val usernameRateLimiter = RateLimiter(store = DbLoginAttemptsStore())

      // ... (move everything from install(XForwardedHeaders) to end of routing here)
  }
  ```

  The production `module()` creates the DataSource and calls `module(dataSource)`. Tests call `module(dataSource)` directly with the Testcontainers DataSource.

- [ ] **Run the server to confirm the production path still works**

  ```bash
  ./gradlew :server:test
  ```
  Expected: all existing tests still pass.

- [ ] **Commit**

  ```bash
  git add server/src/main/kotlin/org/branneman/health/Application.kt
  git commit -m "refactor(server): make Application.module() accept injectable DataSource"
  ```

---

### Task A3: Rewrite ApplicationTest → AuthIntegrationTest

This replaces the fake `ApplicationTest`. The new test boots the real application against the Testcontainers DB and exercises the full auth flow.

**Files:**
- Delete: `server/src/test/kotlin/org/branneman/health/ApplicationTest.kt`
- Create: `server/src/test/kotlin/org/branneman/health/AuthIntegrationTest.kt`

A test user is inserted before the suite with a BCrypt hash (cost 4 for speed in tests).

- [ ] **Delete the old file**

  ```bash
  rm server/src/test/kotlin/org/branneman/health/ApplicationTest.kt
  ```

- [ ] **Write AuthIntegrationTest**

  Create `server/src/test/kotlin/org/branneman/health/AuthIntegrationTest.kt`:
  ```kotlin
  package org.branneman.health

  import io.ktor.client.request.*
  import io.ktor.client.statement.*
  import io.ktor.http.*
  import io.ktor.server.testing.*
  import kotlinx.serialization.json.Json
  import kotlinx.serialization.json.jsonObject
  import kotlinx.serialization.json.jsonPrimitive
  import org.branneman.health.auth.Users
  import org.jetbrains.exposed.sql.Database
  import org.jetbrains.exposed.sql.insert
  import org.jetbrains.exposed.sql.transactions.transaction
  import org.mindrot.jbcrypt.BCrypt
  import java.util.UUID
  import kotlin.test.*

  class AuthIntegrationTest {

      companion object {
          private val ds = TestDatabase.dataSource
          private val testUserId = UUID.fromString("00000000-0000-0000-0000-000000000001")
          private const val TEST_EMAIL = "integration@test.local"
          private const val TEST_PASSWORD = "testpassword"
          private val TEST_HASH = BCrypt.hashpw(TEST_PASSWORD, BCrypt.gensalt(4))

          init {
              Database.connect(ds)
              transaction {
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

      @Test
      fun `GET server-health returns 200 with status ok`() = appTest {
          val r = client.get("/server-health")
          assertEquals(HttpStatusCode.OK, r.status)
          assertTrue(r.bodyAsText().contains("ok"))
      }

      @Test
      fun `POST auth-token returns 401 for wrong password`() = appTest {
          val r = client.post("/auth/token") {
              contentType(ContentType.Application.Json)
              setBody("""{"username":"$TEST_EMAIL","password":"wrong"}""")
          }
          assertEquals(HttpStatusCode.Unauthorized, r.status)
      }

      @Test
      fun `POST auth-token returns 200 and token for correct credentials`() = appTest {
          val r = client.post("/auth/token") {
              contentType(ContentType.Application.Json)
              setBody("""{"username":"$TEST_EMAIL","password":"$TEST_PASSWORD"}""")
          }
          assertEquals(HttpStatusCode.OK, r.status)
          val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
          assertNotNull(body["token"]?.jsonPrimitive?.content)
      }

      @Test
      fun `POST auth-refresh returns 200 with valid token`() = appTest {
          // First login to get a token
          val loginResp = client.post("/auth/token") {
              contentType(ContentType.Application.Json)
              setBody("""{"username":"$TEST_EMAIL","password":"$TEST_PASSWORD"}""")
          }
          val token = Json.parseToJsonElement(loginResp.bodyAsText())
              .jsonObject["token"]!!.jsonPrimitive.content

          val r = client.post("/auth/refresh") {
              header(HttpHeaders.Authorization, "Bearer $token")
          }
          assertEquals(HttpStatusCode.OK, r.status)
      }

      @Test
      fun `POST auth-refresh returns 401 with no token`() = appTest {
          assertEquals(HttpStatusCode.Unauthorized, client.post("/auth/refresh").status)
      }

      @Test
      fun `POST auth-logout returns 204 with valid token`() = appTest {
          val loginResp = client.post("/auth/token") {
              contentType(ContentType.Application.Json)
              setBody("""{"username":"$TEST_EMAIL","password":"$TEST_PASSWORD"}""")
          }
          val token = Json.parseToJsonElement(loginResp.bodyAsText())
              .jsonObject["token"]!!.jsonPrimitive.content

          val r = client.post("/auth/logout") {
              header(HttpHeaders.Authorization, "Bearer $token")
          }
          assertEquals(HttpStatusCode.NoContent, r.status)
      }

      @Test
      fun `protected route returns 401 without token`() = appTest {
          assertEquals(HttpStatusCode.Unauthorized, client.get("/body/weight").status)
      }
  }
  ```

- [ ] **Run and confirm all new tests pass**

  ```bash
  ./gradlew :server:test --tests "org.branneman.health.AuthIntegrationTest"
  ```
  Expected: all 6 tests pass.

- [ ] **Commit**

  ```bash
  git add server/src/test/kotlin/org/branneman/health/AuthIntegrationTest.kt
  git commit -m "test(server): replace fake ApplicationTest with AuthIntegrationTest (Testcontainers)"
  ```

---

### Task A4: Rewrite MultiUserEndpointTest → ProfileAndShortcutsIntegrationTest

Replaces fake `MultiUserEndpointTest`. Tests the profile and shortcuts endpoints against the real DB, verifying user-ownership isolation.

**Files:**
- Delete: `server/src/test/kotlin/org/branneman/health/MultiUserEndpointTest.kt`
- Create: `server/src/test/kotlin/org/branneman/health/ProfileAndShortcutsIntegrationTest.kt`

- [ ] **Delete the old file**

  ```bash
  rm server/src/test/kotlin/org/branneman/health/MultiUserEndpointTest.kt
  ```

- [ ] **Write ProfileAndShortcutsIntegrationTest**

  Create `server/src/test/kotlin/org/branneman/health/ProfileAndShortcutsIntegrationTest.kt`:
  ```kotlin
  package org.branneman.health

  import io.ktor.client.request.*
  import io.ktor.client.statement.*
  import io.ktor.http.*
  import io.ktor.server.testing.*
  import kotlinx.serialization.json.Json
  import kotlinx.serialization.json.jsonObject
  import kotlinx.serialization.json.jsonPrimitive
  import org.branneman.health.auth.Sessions
  import org.branneman.health.auth.Users
  import org.jetbrains.exposed.sql.Database
  import org.jetbrains.exposed.sql.insert
  import org.jetbrains.exposed.sql.transactions.transaction
  import org.mindrot.jbcrypt.BCrypt
  import java.time.OffsetDateTime
  import java.util.UUID
  import kotlin.test.*

  class ProfileAndShortcutsIntegrationTest {

      companion object {
          private val ds = TestDatabase.dataSource
          private val userAId = UUID.fromString("00000000-0000-0000-0000-000000000002")
          private val userBId = UUID.fromString("00000000-0000-0000-0000-000000000003")
          private const val TOKEN_A = "test-token-user-a"
          private const val TOKEN_B = "test-token-user-b"

          init {
              Database.connect(ds)
              transaction {
                  val hash = BCrypt.hashpw("pw", BCrypt.gensalt(4))
                  Users.insert { it[id] = userAId; it[username] = "userA@test.local"; it[passwordHash] = hash }
                  Users.insert { it[id] = userBId; it[username] = "userB@test.local"; it[passwordHash] = hash }
                  val expiry = OffsetDateTime.now().plusDays(30)
                  Sessions.insert { it[token] = TOKEN_A; it[userId] = userAId; it[expiresAt] = expiry }
                  Sessions.insert { it[token] = TOKEN_B; it[userId] = userBId; it[expiresAt] = expiry }
              }
          }
      }

      private fun appTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
          application { module(ds) }
          block()
      }

      @Test
      fun `GET profile returns 404 when no profile exists`() = appTest {
          val r = client.get("/profile") {
              header(HttpHeaders.Authorization, "Bearer $TOKEN_A")
          }
          assertEquals(HttpStatusCode.NotFound, r.status)
      }

      @Test
      fun `PUT and GET profile round-trips correctly`() = appTest {
          val body = """{"heightCm":177,"birthYear":1986,"sex":"male","goalWeightKg":74.0,"activityLevel":"lightly_active","targetDeficit":300,"phase":"loss","vacationMode":false}"""
          val put = client.put("/profile") {
              header(HttpHeaders.Authorization, "Bearer $TOKEN_A")
              contentType(ContentType.Application.Json)
              setBody(body)
          }
          assertEquals(HttpStatusCode.OK, put.status)

          val get = client.get("/profile") {
              header(HttpHeaders.Authorization, "Bearer $TOKEN_A")
          }
          assertEquals(HttpStatusCode.OK, get.status)
          val parsed = Json.parseToJsonElement(get.bodyAsText()).jsonObject
          assertEquals("177", parsed["heightCm"]?.jsonPrimitive?.content)
      }

      @Test
      fun `GET profile returns 401 without token`() = appTest {
          assertEquals(HttpStatusCode.Unauthorized, client.get("/profile").status)
      }

      @Test
      fun `PUT shortcuts replaces all shortcuts for the user`() = appTest {
          val r = client.put("/shortcuts") {
              header(HttpHeaders.Authorization, "Bearer $TOKEN_A")
              contentType(ContentType.Application.Json)
              setBody("""[{"id":"","emoji":"🍺","label":"Pils","kcal":140,"sortOrder":0}]""")
          }
          assertEquals(HttpStatusCode.OK, r.status)
          assertTrue(r.bodyAsText().contains("Pils"))
      }

      @Test
      fun `GET shortcuts returns only the authenticated user's shortcuts`() = appTest {
          // Seed a shortcut for user B
          client.put("/shortcuts") {
              header(HttpHeaders.Authorization, "Bearer $TOKEN_B")
              contentType(ContentType.Application.Json)
              setBody("""[{"id":"","emoji":"🥗","label":"Salad","kcal":80,"sortOrder":0}]""")
          }

          val r = client.get("/shortcuts") {
              header(HttpHeaders.Authorization, "Bearer $TOKEN_A")
          }
          assertEquals(HttpStatusCode.OK, r.status)
          assertFalse(r.bodyAsText().contains("Salad"), "User A must not see User B's shortcuts")
      }
  }
  ```

- [ ] **Run and confirm all new tests pass**

  ```bash
  ./gradlew :server:test --tests "org.branneman.health.ProfileAndShortcutsIntegrationTest"
  ```

- [ ] **Commit**

  ```bash
  git add server/src/test/kotlin/org/branneman/health/ProfileAndShortcutsIntegrationTest.kt
  git commit -m "test(server): replace fake MultiUserEndpointTest with ProfileAndShortcutsIntegrationTest"
  ```

---

### Task A5: Rewrite SyncDownloadTest → SyncDownloadIntegrationTest

Replaces fake `SyncDownloadTest`. Tests all sync-download endpoints against real DB rows, verifying each endpoint returns only the authenticated user's data.

**Files:**
- Delete: `server/src/test/kotlin/org/branneman/health/SyncDownloadTest.kt`
- Create: `server/src/test/kotlin/org/branneman/health/SyncDownloadIntegrationTest.kt`

- [ ] **Delete the old file**

  ```bash
  rm server/src/test/kotlin/org/branneman/health/SyncDownloadTest.kt
  ```

- [ ] **Write SyncDownloadIntegrationTest**

  Create `server/src/test/kotlin/org/branneman/health/SyncDownloadIntegrationTest.kt`:
  ```kotlin
  package org.branneman.health

  import io.ktor.client.request.*
  import io.ktor.client.statement.*
  import io.ktor.http.*
  import io.ktor.server.testing.*
  import kotlinx.serialization.json.*
  import org.branneman.health.auth.Sessions
  import org.branneman.health.auth.Users
  import org.branneman.health.data.*
  import org.jetbrains.exposed.sql.Database
  import org.jetbrains.exposed.sql.insert
  import org.jetbrains.exposed.sql.transactions.transaction
  import org.mindrot.jbcrypt.BCrypt
  import java.math.BigDecimal
  import java.time.LocalDate
  import java.time.OffsetDateTime
  import java.util.UUID
  import kotlin.test.*

  class SyncDownloadIntegrationTest {

      companion object {
          private val ds = TestDatabase.dataSource
          private val ownerId = UUID.fromString("00000000-0000-0000-0000-000000000004")
          private val otherId = UUID.fromString("00000000-0000-0000-0000-000000000005")
          private const val TOKEN_OWNER = "sync-token-owner"
          private val today = LocalDate.now()

          init {
              Database.connect(ds)
              transaction {
                  val hash = BCrypt.hashpw("pw", BCrypt.gensalt(4))
                  Users.insert { it[id] = ownerId; it[username] = "owner@test.local"; it[passwordHash] = hash }
                  Users.insert { it[id] = otherId; it[username] = "other@test.local"; it[passwordHash] = hash }
                  val expiry = OffsetDateTime.now().plusDays(30)
                  Sessions.insert { it[token] = TOKEN_OWNER; it[userId] = ownerId; it[expiresAt] = expiry }

                  DailyEnergy.insert {
                      it[userId]     = ownerId
                      it[date]       = today
                      it[bmrKcal]    = 1800
                      it[activeKcal] = 400
                      it[totalKcal]  = 2200
                      it[steps]      = 8000
                      it[dataSource] = "polar"
                  }
                  DailyEnergy.insert {
                      it[userId]     = otherId
                      it[date]       = today
                      it[bmrKcal]    = 1700
                      it[activeKcal] = 300
                      it[totalKcal]  = 2000
                      it[steps]      = null
                      it[dataSource] = "polar"
                  }
              }
          }
      }

      private fun appTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
          application { module(ds) }
          block()
      }

      @Test
      fun `GET out-energy returns 401 without token`() = appTest {
          assertEquals(HttpStatusCode.Unauthorized, client.get("/out/energy").status)
      }

      @Test
      fun `GET out-energy returns only the owner's entries`() = appTest {
          val r = client.get("/out/energy") {
              header(HttpHeaders.Authorization, "Bearer $TOKEN_OWNER")
          }
          assertEquals(HttpStatusCode.OK, r.status)
          val arr = Json.parseToJsonElement(r.bodyAsText()).jsonArray
          assertEquals(1, arr.size)
          assertEquals(2200, arr[0].jsonObject["totalKcal"]?.jsonPrimitive?.int)
      }

      @Test
      fun `GET out-workouts returns 401 without token`() = appTest {
          assertEquals(HttpStatusCode.Unauthorized, client.get("/out/workouts").status)
      }

      @Test
      fun `GET out-workouts returns empty list when no workouts exist`() = appTest {
          val r = client.get("/out/workouts") {
              header(HttpHeaders.Authorization, "Bearer $TOKEN_OWNER")
          }
          assertEquals(HttpStatusCode.OK, r.status)
          assertEquals("[]", r.bodyAsText().trim())
      }

      @Test
      fun `GET in-food-items returns 401 without token`() = appTest {
          assertEquals(HttpStatusCode.Unauthorized, client.get("/in/food-items").status)
      }

      @Test
      fun `GET in-templates returns 401 without token`() = appTest {
          assertEquals(HttpStatusCode.Unauthorized, client.get("/in/templates").status)
      }

      @Test
      fun `GET in-log returns 401 without token`() = appTest {
          assertEquals(HttpStatusCode.Unauthorized, client.get("/in/log").status)
      }

      @Test
      fun `GET in-log returns empty list when no log entries exist`() = appTest {
          val r = client.get("/in/log") {
              header(HttpHeaders.Authorization, "Bearer $TOKEN_OWNER")
          }
          assertEquals(HttpStatusCode.OK, r.status)
          assertEquals("[]", r.bodyAsText().trim())
      }
  }
  ```

- [ ] **Run the full server test suite to confirm all integration tests pass**

  ```bash
  ./gradlew :server:test
  ```
  Expected: all tests pass. The old fake tests are gone; the new integration tests replace them.

- [ ] **Commit**

  ```bash
  git add server/src/test/kotlin/org/branneman/health/SyncDownloadIntegrationTest.kt
  git commit -m "test(server): replace fake SyncDownloadTest with SyncDownloadIntegrationTest"
  ```

---

## Phase B — App Component Tests

---

### Task B1: Create TestFactories.kt

A single file of factory helpers so test bodies only declare what matters. All IDs are UUIDs per the project rule (existing `BodyWeightDaoTest` uses `"id-1"` strings — those get fixed here too).

**Files:**
- Create: `app/src/test/kotlin/org/branneman/health/TestFactories.kt`
- Modify: `app/src/test/kotlin/org/branneman/health/db/dao/BodyWeightDaoTest.kt`

- [ ] **Write TestFactories.kt**

  Create `app/src/test/kotlin/org/branneman/health/TestFactories.kt`:
  ```kotlin
  package org.branneman.health

  import org.branneman.health.db.SyncStatus
  import org.branneman.health.db.entities.*
  import java.util.UUID

  fun uuid() = UUID.randomUUID().toString()

  fun aBodyWeightEntry(
      id: String = uuid(),
      userId: String = uuid(),
      date: String = "2026-01-01",
      kg: Double = 80.0,
      syncStatus: SyncStatus = SyncStatus.SYNCED,
  ) = BodyWeightEntity(id = id, userId = userId, date = date, kg = kg, syncStatus = syncStatus)

  fun aDailyEnergy(
      userId: String = uuid(),
      date: String = "2026-01-01",
      bmrKcal: Int = 1800,
      activeKcal: Int = 400,
      totalKcal: Int = 2200,
      steps: Int? = 8000,
      source: String = "polar",
  ) = DailyEnergyEntity(userId = userId, date = date, bmrKcal = bmrKcal, activeKcal = activeKcal,
      totalKcal = totalKcal, steps = steps, source = source)

  fun aFoodItem(
      id: String = uuid(),
      userId: String = uuid(),
      name: String = "Test Food",
      kcalPer100g: Double = 200.0,
      source: String = "manual",
  ) = FoodItemEntity(id = id, userId = userId, barcode = null, name = name,
      kcalPer100g = kcalPer100g, proteinPer100g = null, carbsPer100g = null,
      fatPer100g = null, source = source, syncStatus = SyncStatus.SYNCED)

  fun aShortcut(
      id: String = uuid(),
      userId: String = uuid(),
      emoji: String = "🍎",
      label: String = "Apple",
      kcal: Int = 52,
      sortOrder: Int = 0,
  ) = ShortcutEntity(id = id, userId = userId, emoji = emoji, label = label,
      kcal = kcal, sortOrder = sortOrder, syncStatus = SyncStatus.SYNCED)

  fun aUserProfile(
      userId: String = uuid(),
  ) = UserProfileEntity(userId = userId, heightCm = 177, birthYear = 1986,
      sex = "male", goalWeightKg = 74.0, activityLevel = "lightly_active",
      targetDeficit = 300, phase = "loss", vacationMode = false,
      syncStatus = SyncStatus.SYNCED)

  fun aLogEntry(
      id: String = uuid(),
      userId: String = uuid(),
      loggedAt: String = "2026-01-01T08:00:00Z",
      mealType: String = "breakfast",
  ) = LogEntryEntity(id = id, userId = userId, loggedAt = loggedAt,
      mealType = mealType, quickAddKcal = null, quickAddLabel = null,
      syncStatus = SyncStatus.PENDING_CREATE)

  fun aMealTemplate(
      id: String = uuid(),
      userId: String = uuid(),
      name: String = "Test Template",
  ) = MealTemplateEntity(id = id, userId = userId, name = name, syncStatus = SyncStatus.SYNCED)

  fun aWorkout(
      id: String = uuid(),
      userId: String = uuid(),
      date: String = "2026-01-01",
      type: String = "running",
  ) = WorkoutEntity(id = id, userId = userId, date = date, type = type,
      durationSecs = 1800, avgHr = 145, kcal = 400)
  ```

- [ ] **Update BodyWeightDaoTest to use UUIDs**

  In `BodyWeightDaoTest.kt`, replace the hardcoded string IDs:
  ```kotlin
  private val userId = uuid()
  ```
  And replace `id = "id-1"`, `id = "id-2"` with `id = uuid()` (or use the factory):
  ```kotlin
  // replace:
  val entry = BodyWeightEntity(id = "id-1", userId = userId, date = "2026-06-01", kg = 82.5)
  // with:
  val entry = aBodyWeightEntry(userId = userId, date = "2026-06-01", kg = 82.5)
  ```
  Add the import at the top of the file:
  ```kotlin
  import org.branneman.health.aBodyWeightEntry
  import org.branneman.health.uuid
  ```

- [ ] **Run to confirm BodyWeightDaoTest still passes**

  ```bash
  ./gradlew :app:test --tests "org.branneman.health.db.dao.BodyWeightDaoTest"
  ```

- [ ] **Commit**

  ```bash
  git add app/src/test/kotlin/org/branneman/health/TestFactories.kt \
          app/src/test/kotlin/org/branneman/health/db/dao/BodyWeightDaoTest.kt
  git commit -m "test(app): add TestFactories; fix BodyWeightDaoTest to use UUIDs"
  ```

---

### Task B2: DailyEnergyDaoTest

**Files:**
- Create: `app/src/test/kotlin/org/branneman/health/db/dao/DailyEnergyDaoTest.kt`

- [ ] **Write the test**

  ```kotlin
  package org.branneman.health.db.dao

  import androidx.room.Room
  import androidx.test.core.app.ApplicationProvider
  import kotlinx.coroutines.flow.first
  import kotlinx.coroutines.test.runTest
  import org.branneman.health.aDailyEnergy
  import org.branneman.health.uuid
  import org.branneman.health.db.HealthDatabase
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
  class DailyEnergyDaoTest {

      private lateinit var db: HealthDatabase
      private lateinit var dao: DailyEnergyDao

      @Before fun setUp() {
          db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HealthDatabase::class.java)
              .allowMainThreadQueries().build()
          dao = db.dailyEnergyDao()
      }

      @After fun tearDown() { db.close() }

      @Test
      fun `upsertAll and observeAll`() = runTest {
          val userId = uuid()
          dao.upsertAll(listOf(aDailyEnergy(userId = userId, date = "2026-01-01", totalKcal = 2200)))
          val result = dao.observeAll().first()
          assertEquals(1, result.size)
          assertEquals(2200, result[0].totalKcal)
      }

      @Test
      fun `upsertAll updates existing entry`() = runTest {
          val userId = uuid()
          dao.upsertAll(listOf(aDailyEnergy(userId = userId, date = "2026-01-01", totalKcal = 2200)))
          dao.upsertAll(listOf(aDailyEnergy(userId = userId, date = "2026-01-01", totalKcal = 2400)))
          assertEquals(1, dao.observeAll().first().size)
          assertEquals(2400, dao.observeAll().first()[0].totalKcal)
      }

      @Test
      fun `deleteAllForUser removes only that user's entries`() = runTest {
          val userId = uuid()
          val otherId = uuid()
          dao.upsertAll(listOf(
              aDailyEnergy(userId = userId, date = "2026-01-01"),
              aDailyEnergy(userId = otherId, date = "2026-01-02"),
          ))
          dao.deleteAllForUser(userId)
          val remaining = dao.observeAll().first()
          assertEquals(1, remaining.size)
          assertEquals(otherId, remaining[0].userId)
      }
  }
  ```

- [ ] **Run the test**

  ```bash
  ./gradlew :app:test --tests "org.branneman.health.db.dao.DailyEnergyDaoTest"
  ```
  Expected: 3 tests pass.

- [ ] **Commit**

  ```bash
  git add app/src/test/kotlin/org/branneman/health/db/dao/DailyEnergyDaoTest.kt
  git commit -m "test(app): add DailyEnergyDaoTest"
  ```

---

### Task B3: ShortcutDaoTest

**Files:**
- Create: `app/src/test/kotlin/org/branneman/health/db/dao/ShortcutDaoTest.kt`

- [ ] **Write the test**

  ```kotlin
  package org.branneman.health.db.dao

  import androidx.room.Room
  import androidx.test.core.app.ApplicationProvider
  import kotlinx.coroutines.flow.first
  import kotlinx.coroutines.test.runTest
  import org.branneman.health.aShortcut
  import org.branneman.health.uuid
  import org.branneman.health.db.HealthDatabase
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
  class ShortcutDaoTest {

      private lateinit var db: HealthDatabase
      private lateinit var dao: ShortcutDao

      @Before fun setUp() {
          db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HealthDatabase::class.java)
              .allowMainThreadQueries().build()
          dao = db.shortcutDao()
      }

      @After fun tearDown() { db.close() }

      @Test
      fun `upsertAll and observeAll returns in sort order`() = runTest {
          val userId = uuid()
          dao.upsertAll(listOf(
              aShortcut(userId = userId, label = "B", sortOrder = 1),
              aShortcut(userId = userId, label = "A", sortOrder = 0),
          ))
          val result = dao.observeAll().first()
          assertEquals(2, result.size)
          assertEquals("A", result[0].label)
          assertEquals("B", result[1].label)
      }

      @Test
      fun `deleteAllForUser removes only that user's shortcuts`() = runTest {
          val userId = uuid()
          val otherId = uuid()
          dao.upsertAll(listOf(
              aShortcut(userId = userId, label = "Mine"),
              aShortcut(userId = otherId, label = "Theirs"),
          ))
          dao.deleteAllForUser(userId)
          val remaining = dao.observeAll().first()
          assertEquals(1, remaining.size)
          assertEquals("Theirs", remaining[0].label)
      }
  }
  ```

- [ ] **Run the test**

  ```bash
  ./gradlew :app:test --tests "org.branneman.health.db.dao.ShortcutDaoTest"
  ```

- [ ] **Commit**

  ```bash
  git add app/src/test/kotlin/org/branneman/health/db/dao/ShortcutDaoTest.kt
  git commit -m "test(app): add ShortcutDaoTest"
  ```

---

### Task B4: UserProfileDaoTest

**Files:**
- Create: `app/src/test/kotlin/org/branneman/health/db/dao/UserProfileDaoTest.kt`

- [ ] **Write the test**

  ```kotlin
  package org.branneman.health.db.dao

  import androidx.room.Room
  import androidx.test.core.app.ApplicationProvider
  import kotlinx.coroutines.flow.first
  import kotlinx.coroutines.test.runTest
  import org.branneman.health.aUserProfile
  import org.branneman.health.uuid
  import org.branneman.health.db.HealthDatabase
  import org.junit.After
  import org.junit.Before
  import org.junit.Test
  import org.junit.runner.RunWith
  import org.robolectric.RobolectricTestRunner
  import org.robolectric.annotation.Config
  import kotlin.test.assertEquals
  import kotlin.test.assertNull
  import kotlin.test.assertNotNull

  @RunWith(RobolectricTestRunner::class)
  @Config(sdk = [28])
  class UserProfileDaoTest {

      private lateinit var db: HealthDatabase
      private lateinit var dao: UserProfileDao

      @Before fun setUp() {
          db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HealthDatabase::class.java)
              .allowMainThreadQueries().build()
          dao = db.userProfileDao()
      }

      @After fun tearDown() { db.close() }

      @Test
      fun `observe returns null when empty`() = runTest {
          assertNull(dao.observe().first())
      }

      @Test
      fun `upsert and get returns the profile`() = runTest {
          val userId = uuid()
          dao.upsert(aUserProfile(userId = userId))
          val result = dao.get()
          assertNotNull(result)
          assertEquals(userId, result.userId)
          assertEquals(177, result.heightCm)
      }

      @Test
      fun `upsert updates existing profile`() = runTest {
          val userId = uuid()
          dao.upsert(aUserProfile(userId = userId))
          dao.upsert(aUserProfile(userId = userId).copy(heightCm = 180))
          assertEquals(180, dao.get()?.heightCm)
      }

      @Test
      fun `deleteForUser removes the profile`() = runTest {
          val userId = uuid()
          dao.upsert(aUserProfile(userId = userId))
          dao.deleteForUser(userId)
          assertNull(dao.get())
      }
  }
  ```

- [ ] **Run the test**

  ```bash
  ./gradlew :app:test --tests "org.branneman.health.db.dao.UserProfileDaoTest"
  ```

- [ ] **Commit**

  ```bash
  git add app/src/test/kotlin/org/branneman/health/db/dao/UserProfileDaoTest.kt
  git commit -m "test(app): add UserProfileDaoTest"
  ```

---

### Task B5: LogEntryDaoTest

**Files:**
- Create: `app/src/test/kotlin/org/branneman/health/db/dao/LogEntryDaoTest.kt`

- [ ] **Write the test**

  ```kotlin
  package org.branneman.health.db.dao

  import androidx.room.Room
  import androidx.test.core.app.ApplicationProvider
  import kotlinx.coroutines.flow.first
  import kotlinx.coroutines.test.runTest
  import org.branneman.health.aLogEntry
  import org.branneman.health.uuid
  import org.branneman.health.db.HealthDatabase
  import org.branneman.health.db.SyncStatus
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
  class LogEntryDaoTest {

      private lateinit var db: HealthDatabase
      private lateinit var dao: LogEntryDao

      @Before fun setUp() {
          db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HealthDatabase::class.java)
              .allowMainThreadQueries().build()
          dao = db.logEntryDao()
      }

      @After fun tearDown() { db.close() }

      @Test
      fun `upsert and observeAll returns non-deleted entries`() = runTest {
          val userId = uuid()
          dao.upsert(aLogEntry(userId = userId, mealType = "breakfast"))
          val result = dao.observeAll().first()
          assertEquals(1, result.size)
          assertEquals("breakfast", result[0].mealType)
      }

      @Test
      fun `observeAll excludes PENDING_DELETE entries`() = runTest {
          val userId = uuid()
          val id = uuid()
          dao.upsert(aLogEntry(id = id, userId = userId, mealType = "lunch"))
          dao.updateSyncStatus(id, SyncStatus.PENDING_DELETE)
          assertTrue(dao.observeAll().first().isEmpty())
      }

      @Test
      fun `getByStatus returns only matching entries`() = runTest {
          val userId = uuid()
          dao.upsert(aLogEntry(userId = userId))
          dao.upsert(aLogEntry(userId = userId).also {
              // second entry starts as PENDING_CREATE per entity default
          })
          val pending = dao.getByStatus(SyncStatus.PENDING_CREATE)
          assertEquals(2, pending.size)
      }

      @Test
      fun `deleteAllForUser removes only that user's entries`() = runTest {
          val userId = uuid()
          val otherId = uuid()
          dao.upsert(aLogEntry(userId = userId))
          dao.upsert(aLogEntry(userId = otherId))
          dao.deleteAllForUser(userId)
          assertEquals(1, dao.observeAll().first().size)
      }
  }
  ```

- [ ] **Run the test**

  ```bash
  ./gradlew :app:test --tests "org.branneman.health.db.dao.LogEntryDaoTest"
  ```

- [ ] **Commit**

  ```bash
  git add app/src/test/kotlin/org/branneman/health/db/dao/LogEntryDaoTest.kt
  git commit -m "test(app): add LogEntryDaoTest"
  ```

---

### Task B6: FoodItemDaoTest

**Files:**
- Create: `app/src/test/kotlin/org/branneman/health/db/dao/FoodItemDaoTest.kt`

- [ ] **Write the test**

  ```kotlin
  package org.branneman.health.db.dao

  import androidx.room.Room
  import androidx.test.core.app.ApplicationProvider
  import kotlinx.coroutines.test.runTest
  import org.branneman.health.aFoodItem
  import org.branneman.health.uuid
  import org.branneman.health.db.HealthDatabase
  import org.branneman.health.db.SyncStatus
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
  class FoodItemDaoTest {

      private lateinit var db: HealthDatabase
      private lateinit var dao: FoodItemDao

      @Before fun setUp() {
          db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HealthDatabase::class.java)
              .allowMainThreadQueries().build()
          dao = db.foodItemDao()
      }

      @After fun tearDown() { db.close() }

      @Test
      fun `upsertAll and getByStatus returns SYNCED items`() = runTest {
          val userId = uuid()
          dao.upsertAll(listOf(aFoodItem(userId = userId, name = "Banana")))
          val result = dao.getByStatus(SyncStatus.SYNCED)
          assertEquals(1, result.size)
          assertEquals("Banana", result[0].name)
      }

      @Test
      fun `updateSyncStatus changes the status`() = runTest {
          val userId = uuid()
          val id = uuid()
          dao.upsertAll(listOf(aFoodItem(id = id, userId = userId)))
          dao.updateSyncStatus(id, SyncStatus.PENDING_DELETE)
          assertTrue(dao.getByStatus(SyncStatus.SYNCED).isEmpty())
          assertEquals(1, dao.getByStatus(SyncStatus.PENDING_DELETE).size)
      }

      @Test
      fun `deleteAllForUser removes only that user's items`() = runTest {
          val userId = uuid()
          val otherId = uuid()
          dao.upsertAll(listOf(
              aFoodItem(userId = userId, name = "Mine"),
              aFoodItem(userId = otherId, name = "Theirs"),
          ))
          dao.deleteAllForUser(userId)
          val remaining = dao.getByStatus(SyncStatus.SYNCED)
          assertEquals(1, remaining.size)
          assertEquals("Theirs", remaining[0].name)
      }
  }
  ```

- [ ] **Run the test**

  ```bash
  ./gradlew :app:test --tests "org.branneman.health.db.dao.FoodItemDaoTest"
  ```

- [ ] **Commit**

  ```bash
  git add app/src/test/kotlin/org/branneman/health/db/dao/FoodItemDaoTest.kt
  git commit -m "test(app): add FoodItemDaoTest"
  ```

---

### Task B7: MealTemplateDaoTest

**Files:**
- Create: `app/src/test/kotlin/org/branneman/health/db/dao/MealTemplateDaoTest.kt`

- [ ] **Write the test**

  ```kotlin
  package org.branneman.health.db.dao

  import androidx.room.Room
  import androidx.test.core.app.ApplicationProvider
  import kotlinx.coroutines.flow.first
  import kotlinx.coroutines.test.runTest
  import org.branneman.health.aMealTemplate
  import org.branneman.health.uuid
  import org.branneman.health.db.HealthDatabase
  import org.branneman.health.db.SyncStatus
  import org.branneman.health.db.entities.MealTemplateItemEntity
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
  class MealTemplateDaoTest {

      private lateinit var db: HealthDatabase
      private lateinit var dao: MealTemplateDao

      @Before fun setUp() {
          db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HealthDatabase::class.java)
              .allowMainThreadQueries().build()
          dao = db.mealTemplateDao()
      }

      @After fun tearDown() { db.close() }

      @Test
      fun `upsert and observeAll returns non-deleted templates`() = runTest {
          val userId = uuid()
          dao.upsert(aMealTemplate(userId = userId, name = "Breakfast"))
          assertEquals(1, dao.observeAll().first().size)
          assertEquals("Breakfast", dao.observeAll().first()[0].name)
      }

      @Test
      fun `observeAll excludes PENDING_DELETE templates`() = runTest {
          val userId = uuid()
          val id = uuid()
          dao.upsert(aMealTemplate(id = id, userId = userId))
          dao.updateSyncStatus(id, SyncStatus.PENDING_DELETE)
          assertTrue(dao.observeAll().first().isEmpty())
      }

      @Test
      fun `upsertItem and getItems round-trips`() = runTest {
          val userId = uuid()
          val templateId = uuid()
          val foodItemId = uuid()
          dao.upsert(aMealTemplate(id = templateId, userId = userId))
          dao.upsertItem(MealTemplateItemEntity(templateId = templateId, foodItemId = foodItemId, grams = 150.0))
          val items = dao.getItems(templateId)
          assertEquals(1, items.size)
          assertEquals(150.0, items[0].grams)
      }

      @Test
      fun `deleteAllForUser removes templates and items`() = runTest {
          val userId = uuid()
          val templateId = uuid()
          val foodItemId = uuid()
          dao.upsert(aMealTemplate(id = templateId, userId = userId))
          dao.upsertItem(MealTemplateItemEntity(templateId = templateId, foodItemId = foodItemId, grams = 100.0))
          dao.deleteAllItemsForUser(userId)
          dao.deleteAllForUser(userId)
          assertTrue(dao.observeAll().first().isEmpty())
          assertTrue(dao.getItems(templateId).isEmpty())
      }
  }
  ```

- [ ] **Run the test**

  ```bash
  ./gradlew :app:test --tests "org.branneman.health.db.dao.MealTemplateDaoTest"
  ```

- [ ] **Commit**

  ```bash
  git add app/src/test/kotlin/org/branneman/health/db/dao/MealTemplateDaoTest.kt
  git commit -m "test(app): add MealTemplateDaoTest"
  ```

---

### Task B8: WorkoutDaoTest

**Files:**
- Create: `app/src/test/kotlin/org/branneman/health/db/dao/WorkoutDaoTest.kt`

- [ ] **Write the test**

  ```kotlin
  package org.branneman.health.db.dao

  import androidx.room.Room
  import androidx.test.core.app.ApplicationProvider
  import kotlinx.coroutines.flow.first
  import kotlinx.coroutines.test.runTest
  import org.branneman.health.aWorkout
  import org.branneman.health.uuid
  import org.branneman.health.db.HealthDatabase
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
  class WorkoutDaoTest {

      private lateinit var db: HealthDatabase
      private lateinit var dao: WorkoutDao

      @Before fun setUp() {
          db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HealthDatabase::class.java)
              .allowMainThreadQueries().build()
          dao = db.workoutDao()
      }

      @After fun tearDown() { db.close() }

      @Test
      fun `upsertAll and observeAll returns entries in descending date order`() = runTest {
          val userId = uuid()
          dao.upsertAll(listOf(
              aWorkout(userId = userId, date = "2026-01-01"),
              aWorkout(userId = userId, date = "2026-01-03"),
          ))
          val result = dao.observeAll().first()
          assertEquals(2, result.size)
          assertEquals("2026-01-03", result[0].date)
          assertEquals("2026-01-01", result[1].date)
      }

      @Test
      fun `deleteAllForUser removes only that user's workouts`() = runTest {
          val userId = uuid()
          val otherId = uuid()
          dao.upsertAll(listOf(
              aWorkout(userId = userId, date = "2026-01-01"),
              aWorkout(userId = otherId, date = "2026-01-02"),
          ))
          dao.deleteAllForUser(userId)
          val remaining = dao.observeAll().first()
          assertEquals(1, remaining.size)
          assertEquals(otherId, remaining[0].userId)
      }
  }
  ```

- [ ] **Run the test**

  ```bash
  ./gradlew :app:test --tests "org.branneman.health.db.dao.WorkoutDaoTest"
  ```

- [ ] **Commit**

  ```bash
  git add app/src/test/kotlin/org/branneman/health/db/dao/WorkoutDaoTest.kt
  git commit -m "test(app): add WorkoutDaoTest"
  ```

---

### Task B9: Replace LoginSyncServiceTest placeholder

**Files:**
- Modify: `app/src/test/kotlin/org/branneman/health/sync/LoginSyncServiceTest.kt`

- [ ] **Rewrite the test**

  ```kotlin
  package org.branneman.health.sync

  import androidx.room.Room
  import androidx.test.core.app.ApplicationProvider
  import io.ktor.client.HttpClient
  import io.ktor.client.engine.mock.MockEngine
  import io.ktor.client.engine.mock.respond
  import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
  import io.ktor.http.ContentType
  import io.ktor.http.HttpHeaders
  import io.ktor.http.HttpStatusCode
  import io.ktor.http.headersOf
  import io.ktor.serialization.kotlinx.json.json
  import kotlinx.coroutines.flow.first
  import kotlinx.coroutines.test.runTest
  import org.branneman.health.db.HealthDatabase
  import org.branneman.health.network.HealthApiClient
  import org.junit.After
  import org.junit.Before
  import org.junit.Test
  import org.junit.runner.RunWith
  import org.robolectric.RobolectricTestRunner
  import org.robolectric.annotation.Config
  import kotlin.test.assertEquals
  import kotlin.test.assertFalse
  import kotlin.test.assertTrue

  @RunWith(RobolectricTestRunner::class)
  @Config(sdk = [28])
  class LoginSyncServiceTest {

      private lateinit var db: HealthDatabase

      @Before fun setUp() {
          db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HealthDatabase::class.java)
              .allowMainThreadQueries().build()
      }

      @After fun tearDown() { db.close() }

      private fun apiClient(vararg responses: String): HealthApiClient {
          var call = 0
          val responseList = responses.toList()
          val engine = MockEngine {
              val body = responseList[call.coerceAtMost(responseList.size - 1)]
              call++
              respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
          }
          return HealthApiClient("http://test", HttpClient(engine) {
              install(ContentNegotiation) { json() }
          })
      }

      @Test
      fun `sync with no profile returns false and stores shortcuts`() = runTest {
          val userId = "00000000-0000-0000-0000-000000000001"
          val api = apiClient(
              "null",   // getProfile → null
              """[{"id":"sc-1","emoji":"🍎","label":"Apple","kcal":52,"sortOrder":0}]""",  // getShortcuts
              "[]", "[]", "[]", "[]", "[]", "[]",  // foodItems, templates, bodyWeight, logEntries, energy, workouts
          )
          val service = LoginSyncService(api, db)
          val hasProfile = service.sync("token", userId)

          assertFalse(hasProfile)
          assertEquals(1, db.shortcutDao().observeAll().first().size)
          assertEquals("Apple", db.shortcutDao().observeAll().first()[0].label)
      }

      @Test
      fun `sync with profile returns true`() = runTest {
          val userId = "00000000-0000-0000-0000-000000000002"
          val profileJson = """{"heightCm":177,"birthYear":1986,"sex":"male","goalWeightKg":74.0,"activityLevel":"lightly_active","targetDeficit":300,"phase":"loss","vacationMode":false}"""
          val api = apiClient(
              profileJson,
              "[]", "[]", "[]", "[]", "[]", "[]",
          )
          val service = LoginSyncService(api, db)
          assertTrue(service.sync("token", userId))
      }
  }
  ```

- [ ] **Run the test**

  ```bash
  ./gradlew :app:test --tests "org.branneman.health.sync.LoginSyncServiceTest"
  ```

- [ ] **Commit**

  ```bash
  git add app/src/test/kotlin/org/branneman/health/sync/LoginSyncServiceTest.kt
  git commit -m "test(app): replace LoginSyncServiceTest placeholder with real Robolectric test"
  ```

---

### Task B10: LoginScreenTest (Compose UI)

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/test/kotlin/org/branneman/health/ui/LoginScreenTest.kt`

- [ ] **Add Compose UI test dependency**

  In `gradle/libs.versions.toml` under `[libraries]`:
  ```toml
  compose-uiTestJunit4 = { module = "org.jetbrains.compose.ui:ui-test-junit4", version.ref = "composeMultiplatform" }
  compose-uiTestManifest = { module = "org.jetbrains.compose.ui:ui-test-manifest", version.ref = "composeMultiplatform" }
  ```

  In `app/build.gradle.kts` inside `dependencies {}`:
  ```kotlin
  testImplementation(libs.compose.uiTestJunit4)
  debugImplementation(libs.compose.uiTestManifest)
  ```

- [ ] **Write LoginScreenTest**

  ```kotlin
  package org.branneman.health.ui

  import androidx.compose.ui.test.assertIsEnabled
  import androidx.compose.ui.test.assertIsNotEnabled
  import androidx.compose.ui.test.junit4.createComposeRule
  import androidx.compose.ui.test.onNodeWithText
  import androidx.compose.ui.test.performTextInput
  import org.junit.Rule
  import org.junit.Test
  import org.junit.runner.RunWith
  import org.robolectric.RobolectricTestRunner
  import org.robolectric.annotation.Config
  import kotlin.test.assertEquals

  @RunWith(RobolectricTestRunner::class)
  @Config(sdk = [28])
  class LoginScreenTest {

      @get:Rule val compose = createComposeRule()

      private fun render(
          sessionExpired: Boolean = false,
          isLoading: Boolean = false,
          errorMessage: String? = null,
          onSignIn: (String, String) -> Unit = { _, _ -> },
      ) {
          compose.setContent {
              LoginScreen(
                  sessionExpired = sessionExpired,
                  isLoading = isLoading,
                  errorMessage = errorMessage,
                  onSignIn = onSignIn,
              )
          }
      }

      @Test
      fun `sign-in button is disabled when fields are empty`() {
          render()
          compose.onNodeWithText("Sign in").assertIsNotEnabled()
      }

      @Test
      fun `sign-in button is enabled when both fields are filled`() {
          render()
          compose.onNodeWithText("Email").performTextInput("user@example.com")
          compose.onNodeWithText("Password").performTextInput("secret")
          compose.onNodeWithText("Sign in").assertIsEnabled()
      }

      @Test
      fun `sign-in button is disabled while loading`() {
          render(isLoading = true)
          compose.onNodeWithText("Email").performTextInput("user@example.com")
          compose.onNodeWithText("Password").performTextInput("secret")
          compose.onNodeWithText("Sign in").assertIsNotEnabled()
      }

      @Test
      fun `error message is shown when provided`() {
          render(errorMessage = "Invalid credentials")
          compose.onNodeWithText("Invalid credentials").assertExists()
      }

      @Test
      fun `session-expired banner shown when sessionExpired is true`() {
          render(sessionExpired = true)
          compose.onNodeWithText("Session expired", substring = true).assertExists()
      }

      @Test
      fun `onSignIn called with entered credentials`() {
          var capturedEmail = ""
          var capturedPassword = ""
          render(onSignIn = { e, p -> capturedEmail = e; capturedPassword = p })

          compose.onNodeWithText("Email").performTextInput("user@example.com")
          compose.onNodeWithText("Password").performTextInput("secret")
          compose.onNodeWithText("Sign in").performClick()

          assertEquals("user@example.com", capturedEmail)
          assertEquals("secret", capturedPassword)
      }
  }
  ```

- [ ] **Run the test**

  ```bash
  ./gradlew :app:test --tests "org.branneman.health.ui.LoginScreenTest"
  ```

- [ ] **Commit**

  ```bash
  git add gradle/libs.versions.toml app/build.gradle.kts \
          app/src/test/kotlin/org/branneman/health/ui/LoginScreenTest.kt
  git commit -m "test(app): add LoginScreenTest with Compose UI test"
  ```

---

## Phase C — API Test Suite

Depends on the two test accounts existing on the production server (Phase D seed SQL). The source set can be configured now; tests are run manually against the real server.

---

### Task C1: Add the apiTest source set to the server module

**Files:**
- Modify: `server/build.gradle.kts`
- Create: `server/src/apiTest/kotlin/org/branneman/health/ApiTestBase.kt`

- [ ] **Add the source set in server/build.gradle.kts**

  ```kotlin
  sourceSets {
      create("apiTest") {
          kotlin.srcDir("src/apiTest/kotlin")
          compileClasspath += sourceSets["main"].output + sourceSets["main"].compileClasspath
          runtimeClasspath += output + compileClasspath
      }
  }

  val apiTestImplementation by configurations.getting

  dependencies {
      // existing deps ...
      apiTestImplementation(libs.ktor.clientCore)
      apiTestImplementation(libs.ktor.clientContentNegotiation)
      apiTestImplementation(libs.ktor.clientSerializationJson)
      apiTestImplementation(libs.ktor.serverTestHost)  // brings in ktor-client-cio
      apiTestImplementation(libs.kotlin.testJunit)
      apiTestImplementation(libs.kotlinx.coroutines.test)
  }

  tasks.register<Test>("apiTest") {
      description = "Run API tests against the live server"
      group = "verification"
      testClassesDirs = sourceSets["apiTest"].output.classesDirs
      classpath = sourceSets["apiTest"].runtimeClasspath
  }
  ```

  Also add `ktor-client-cio` to `libs.versions.toml`:
  ```toml
  ktor-clientCio = { module = "io.ktor:ktor-client-cio-jvm", version.ref = "ktor" }
  ```
  And to the `apiTestImplementation` block:
  ```kotlin
  apiTestImplementation(libs.ktor.clientCio)
  ```

- [ ] **Write ApiTestBase**

  Create `server/src/apiTest/kotlin/org/branneman/health/ApiTestBase.kt`:
  ```kotlin
  package org.branneman.health

  import io.ktor.client.*
  import io.ktor.client.engine.cio.*
  import io.ktor.client.plugins.contentnegotiation.*
  import io.ktor.serialization.kotlinx.json.*

  abstract class ApiTestBase {
      protected val serverUrl: String =
          System.getenv("API_TEST_SERVER_URL") ?: error("API_TEST_SERVER_URL not set")
      protected val apiEmail: String =
          System.getenv("API_TEST_EMAIL") ?: error("API_TEST_EMAIL not set")
      protected val apiPassword: String =
          System.getenv("API_TEST_PASSWORD") ?: error("API_TEST_PASSWORD not set")

      protected val client = HttpClient(CIO) {
          install(ContentNegotiation) { json() }
          expectSuccess = false
      }
  }
  ```

- [ ] **Verify the source set compiles**

  ```bash
  ./gradlew :server:compileApiTestKotlin
  ```

- [ ] **Commit**

  ```bash
  git add gradle/libs.versions.toml server/build.gradle.kts \
          server/src/apiTest/kotlin/org/branneman/health/ApiTestBase.kt
  git commit -m "test(server): add apiTest source set and ApiTestBase"
  ```

---

### Task C2: Write AuthApiTest

**Files:**
- Create: `server/src/apiTest/kotlin/org/branneman/health/AuthApiTest.kt`

- [ ] **Write the test**

  ```kotlin
  package org.branneman.health

  import io.ktor.client.request.*
  import io.ktor.client.statement.*
  import io.ktor.http.*
  import kotlinx.coroutines.runBlocking
  import kotlinx.serialization.json.*
  import kotlin.test.*

  class AuthApiTest : ApiTestBase() {

      @Test
      fun `POST auth-token returns 401 for wrong password`() = runBlocking {
          val r = client.post("$serverUrl/auth/token") {
              contentType(ContentType.Application.Json)
              setBody("""{"username":"$apiEmail","password":"definitely-wrong"}""")
          }
          assertEquals(HttpStatusCode.Unauthorized, r.status)
      }

      @Test
      fun `POST auth-token returns 200 and token for correct credentials`() = runBlocking {
          val r = client.post("$serverUrl/auth/token") {
              contentType(ContentType.Application.Json)
              setBody("""{"username":"$apiEmail","password":"$apiPassword"}""")
          }
          assertEquals(HttpStatusCode.OK, r.status)
          val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
          assertNotNull(body["token"]?.jsonPrimitive?.content)
      }

      @Test
      fun `POST auth-refresh returns 200 then POST auth-logout returns 204`() = runBlocking {
          val token = login()

          val refresh = client.post("$serverUrl/auth/refresh") {
              header(HttpHeaders.Authorization, "Bearer $token")
          }
          assertEquals(HttpStatusCode.OK, refresh.status)
          val newToken = Json.parseToJsonElement(refresh.bodyAsText())
              .jsonObject["token"]!!.jsonPrimitive.content

          val logout = client.post("$serverUrl/auth/logout") {
              header(HttpHeaders.Authorization, "Bearer $newToken")
          }
          assertEquals(HttpStatusCode.NoContent, logout.status)
      }

      @Test
      fun `protected endpoint returns 401 after logout`() = runBlocking {
          val token = login()
          client.post("$serverUrl/auth/logout") {
              header(HttpHeaders.Authorization, "Bearer $token")
          }
          val r = client.get("$serverUrl/profile") {
              header(HttpHeaders.Authorization, "Bearer $token")
          }
          assertEquals(HttpStatusCode.Unauthorized, r.status)
      }

      private suspend fun login(): String {
          val r = client.post("$serverUrl/auth/token") {
              contentType(ContentType.Application.Json)
              setBody("""{"username":"$apiEmail","password":"$apiPassword"}""")
          }
          return Json.parseToJsonElement(r.bodyAsText())
              .jsonObject["token"]!!.jsonPrimitive.content
      }
  }
  ```

- [ ] **Commit**

  ```bash
  git add server/src/apiTest/kotlin/org/branneman/health/AuthApiTest.kt
  git commit -m "test(server): add AuthApiTest"
  ```

---

### Task C3: Write SyncDownloadApiTest

**Files:**
- Create: `server/src/apiTest/kotlin/org/branneman/health/SyncDownloadApiTest.kt`

- [ ] **Write the test**

  ```kotlin
  package org.branneman.health

  import io.ktor.client.request.*
  import io.ktor.http.*
  import kotlinx.coroutines.runBlocking
  import kotlinx.serialization.json.*
  import kotlin.test.*

  class SyncDownloadApiTest : ApiTestBase() {

      private suspend fun token(): String {
          val r = client.post("$serverUrl/auth/token") {
              contentType(ContentType.Application.Json)
              setBody("""{"username":"$apiEmail","password":"$apiPassword"}""")
          }
          return Json.parseToJsonElement(r.bodyAsText())
              .jsonObject["token"]!!.jsonPrimitive.content
      }

      @Test
      fun `GET out-energy returns 200 with an array`() = runBlocking {
          val r = client.get("$serverUrl/out/energy") {
              header(HttpHeaders.Authorization, "Bearer ${token()}")
          }
          assertEquals(HttpStatusCode.OK, r.status)
          assertIs<JsonArray>(Json.parseToJsonElement(r.bodyAsText()))
      }

      @Test
      fun `GET out-workouts returns 200 with an array`() = runBlocking {
          val r = client.get("$serverUrl/out/workouts") {
              header(HttpHeaders.Authorization, "Bearer ${token()}")
          }
          assertEquals(HttpStatusCode.OK, r.status)
          assertIs<JsonArray>(Json.parseToJsonElement(r.bodyAsText()))
      }

      @Test
      fun `GET in-food-items returns 200 with an array`() = runBlocking {
          val r = client.get("$serverUrl/in/food-items") {
              header(HttpHeaders.Authorization, "Bearer ${token()}")
          }
          assertEquals(HttpStatusCode.OK, r.status)
          assertIs<JsonArray>(Json.parseToJsonElement(r.bodyAsText()))
      }

      @Test
      fun `GET in-templates returns 200 with an array`() = runBlocking {
          val r = client.get("$serverUrl/in/templates") {
              header(HttpHeaders.Authorization, "Bearer ${token()}")
          }
          assertEquals(HttpStatusCode.OK, r.status)
          assertIs<JsonArray>(Json.parseToJsonElement(r.bodyAsText()))
      }

      @Test
      fun `GET in-log returns 200 with an array`() = runBlocking {
          val r = client.get("$serverUrl/in/log") {
              header(HttpHeaders.Authorization, "Bearer ${token()}")
          }
          assertEquals(HttpStatusCode.OK, r.status)
          assertIs<JsonArray>(Json.parseToJsonElement(r.bodyAsText()))
      }
  }
  ```

- [ ] **Commit**

  ```bash
  git add server/src/apiTest/kotlin/org/branneman/health/SyncDownloadApiTest.kt
  git commit -m "test(server): add SyncDownloadApiTest"
  ```

---

## Phase D — Test Account Seed SQL

---

### Task D1: Create the two seed SQL files

**Files:**
- Create: `local-db-seed/test-api-account-seed.sql`
- Create: `local-db-seed/test-e2e-account-seed.sql`

These files seed the test accounts on the production server. Apply manually:
```bash
psql $DATABASE_URL < local-db-seed/test-api-account-seed.sql
```

The password hash must be generated with BCrypt cost 12 (production cost). Generate it once with a short Kotlin script or the REPL:
```kotlin
BCrypt.hashpw("the-test-password", BCrypt.gensalt(12))
```

- [ ] **Write test-api-account-seed.sql**

  ```sql
  -- Test account for API tests: test+api@bran.name
  -- User UUID: 00000000-0000-0000-0000-000000000010
  -- Password: set via bcrypt hash below (generate with BCrypt.hashpw at cost 12)
  -- Apply: psql $DATABASE_URL < local-db-seed/test-api-account-seed.sql

  INSERT INTO users (id, username, password_hash) VALUES (
      '00000000-0000-0000-0000-000000000010',
      'test+api@bran.name',
      '$2a$12$REPLACE_WITH_ACTUAL_BCRYPT_HASH'
  ) ON CONFLICT (id) DO UPDATE SET password_hash = EXCLUDED.password_hash;
  ```

  Replace `$2a$12$REPLACE_WITH_ACTUAL_BCRYPT_HASH` with the actual BCrypt hash of the API test password.

- [ ] **Write test-e2e-account-seed.sql**

  ```sql
  -- Test account for E2E smoke tests: test+e2e@bran.name
  -- User UUID: 00000000-0000-0000-0000-000000000020
  -- Password: set via bcrypt hash below (generate with BCrypt.hashpw at cost 12)
  -- Apply: psql $DATABASE_URL < local-db-seed/test-e2e-account-seed.sql
  -- Re-applying resets the account to a known state (ON CONFLICT DO UPDATE / DELETE+INSERT).

  INSERT INTO users (id, username, password_hash) VALUES (
      '00000000-0000-0000-0000-000000000020',
      'test+e2e@bran.name',
      '$2a$12$REPLACE_WITH_ACTUAL_BCRYPT_HASH'
  ) ON CONFLICT (id) DO UPDATE SET password_hash = EXCLUDED.password_hash;

  -- Seed a realistic profile
  INSERT INTO user_profile (user_id, height_cm, birth_year, sex, goal_weight_kg, activity_level, target_deficit, phase, vacation_mode, updated_at)
  VALUES ('00000000-0000-0000-0000-000000000020', 177, 1986, 'male', 74.0, 'lightly_active', 300, 'loss', false, NOW())
  ON CONFLICT (user_id) DO UPDATE SET
      height_cm = EXCLUDED.height_cm, birth_year = EXCLUDED.birth_year,
      goal_weight_kg = EXCLUDED.goal_weight_kg, updated_at = NOW();

  -- Seed shortcuts
  DELETE FROM shortcut WHERE user_id = '00000000-0000-0000-0000-000000000020';
  INSERT INTO shortcut (id, user_id, emoji, label, kcal, sort_order, updated_at) VALUES
      (gen_random_uuid(), '00000000-0000-0000-0000-000000000020', '🍺', 'Test Pils',    140, 0, NOW()),
      (gen_random_uuid(), '00000000-0000-0000-0000-000000000020', '🥗', 'Test Salad',   220, 1, NOW()),
      (gen_random_uuid(), '00000000-0000-0000-0000-000000000020', '🍌', 'Test Banana',   89, 2, NOW());
  ```

- [ ] **Commit**

  ```bash
  git add local-db-seed/test-api-account-seed.sql local-db-seed/test-e2e-account-seed.sql
  git commit -m "test: add seed SQL for test+api and test+e2e accounts"
  ```

- [ ] **Apply both seed files to the production server** (manual step)

  ```bash
  psql $DATABASE_URL < local-db-seed/test-api-account-seed.sql
  psql $DATABASE_URL < local-db-seed/test-e2e-account-seed.sql
  ```

- [ ] **Run the API test suite to confirm it connects**

  ```bash
  API_TEST_SERVER_URL=https://your-vps.eu \
  API_TEST_EMAIL=test+api@bran.name \
  API_TEST_PASSWORD=the-test-password \
  ./gradlew :server:apiTest
  ```
  Expected: all API tests pass against the real server.

---

## Phase E — E2E Smoke Tests

Depends on Phase D (test accounts seeded on the server). Runs on a connected device or emulator.

---

### Task E1: Add E2E credentials to BuildConfig

The test account password must reach the instrumented test without being committed. The app already reads `server.baseUrl` from `local.properties` → `BuildConfig`. Add the E2E credentials the same way.

**Files:**
- Modify: `app/build.gradle.kts`

- [ ] **Add two BuildConfig fields for E2E credentials**

  In `app/build.gradle.kts`, inside `defaultConfig {}`, after the existing `buildConfigField` for `SERVER_BASE_URL`:
  ```kotlin
  buildConfigField(
      "String", "E2E_TEST_EMAIL",
      "\"${localProps.getProperty("e2e.test.email") ?: ""}\""
  )
  buildConfigField(
      "String", "E2E_TEST_PASSWORD",
      "\"${localProps.getProperty("e2e.test.password") ?: ""}\""
  )
  ```

- [ ] **Add the credentials to local.properties** (not committed)

  ```
  e2e.test.email=test+e2e@bran.name
  e2e.test.password=the-e2e-test-password
  ```

- [ ] **Sync and confirm the app builds**

  ```bash
  ./gradlew :app:assembleDebug
  ```

- [ ] **Commit** (only the build.gradle.kts change — local.properties is git-ignored)

  ```bash
  git add app/build.gradle.kts
  git commit -m "build(app): add E2E test credential fields to BuildConfig"
  ```

---

### Task E2: Write the SmokeTest instrumented suite

**Files:**
- Create: `app/src/androidTest/kotlin/org/branneman/health/SmokeTest.kt`

- [ ] **Write SmokeTest**

  ```kotlin
  package org.branneman.health

  import androidx.compose.ui.test.*
  import androidx.compose.ui.test.junit4.createAndroidComposeRule
  import androidx.test.ext.junit.runners.AndroidJUnit4
  import org.junit.After
  import org.junit.Rule
  import org.junit.Test
  import org.junit.runner.RunWith

  /**
   * E2E smoke suite. Runs against the real production server using the
   * test+e2e@bran.name account. Run manually before significant releases — not on
   * every push. Requires a connected device or emulator with network access.
   *
   * Credentials come from BuildConfig (set in local.properties).
   */
  @RunWith(AndroidJUnit4::class)
  class SmokeTest {

      @get:Rule val compose = createAndroidComposeRule<MainActivity>()

      private val email    = BuildConfig.E2E_TEST_EMAIL
      private val password = BuildConfig.E2E_TEST_PASSWORD

      @After
      fun tearDown() {
          // Sign out after each test so the next test starts clean.
          // If a test writes data (future tests), delete it here before signing out.
          try {
              compose.onNodeWithText("Settings").performClick()
              compose.onNodeWithText("Sign Out").performClick()
              compose.onNodeWithText("Sign out").performClick() // confirmation dialog
          } catch (_: AssertionError) {
              // Already signed out or not navigable — acceptable in teardown.
          }
      }

      private fun signIn() {
          compose.onNodeWithText("Email").performTextInput(email)
          compose.onNodeWithText("Password").performTextInput(password)
          compose.onNodeWithText("Sign in").performClick()
          compose.waitUntil(timeoutMillis = 10_000) {
              compose.onAllNodesWithText("Dashboard").fetchSemanticsNodes().isNotEmpty()
          }
      }

      @Test
      fun `sign in shows dashboard`() {
          signIn()
          compose.onNodeWithText("Dashboard").assertExists()
      }

      @Test
      fun `dashboard shows seeded shortcuts`() {
          signIn()
          // The E2E seed data includes "Test Pils", "Test Salad", "Test Banana"
          compose.onNodeWithText("Test Pils", substring = true).assertExists()
      }

      @Test
      fun `navigate to log and back to dashboard`() {
          signIn()
          compose.onNodeWithText("Log").performClick()
          compose.onNodeWithText("Log").assertExists()
          compose.onNodeWithText("Dashboard").performClick()
          compose.onNodeWithText("Dashboard").assertExists()
      }
  }
  ```

- [ ] **Run on a connected device** (manual — requires server connectivity and seeded account)

  ```bash
  ./gradlew :app:connectedDebugAndroidTest \
    --tests "org.branneman.health.SmokeTest"
  ```
  Expected: all 3 smoke tests pass.

- [ ] **Commit**

  ```bash
  git add app/src/androidTest/kotlin/org/branneman/health/SmokeTest.kt
  git commit -m "test(app): add E2E smoke suite (SmokeTest)"
  ```
