# Onboarding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the 3-step first-launch onboarding flow (biometrics → activity level → target deficit) so the app routes new users through profile setup before showing the dashboard.

**Architecture:** The auth state machine gains a `NeedsOnboarding` state, driven reactively by a Room `existsFlow()` query — when the profile is written to Room after step 3, navigation to the dashboard fires automatically. The save is server-first (PUT /profile + POST /body/weight must succeed before Room is written), because onboarding always follows login which already requires network. Save logic lives in a testable `OnboardingRepository`; the ViewModel holds UI state and delegates to it.

**Tech stack:** Kotlin, Ktor (server), Exposed (server DB), Room + Robolectric (app DB tests), Jetpack Compose + Compose UI Test (UI), kotlinx.coroutines.test, Ktor MockEngine (HTTP mocking).

---

## File map

| Action | Path | Responsibility |
|---|---|---|
| Create | `server/src/test/kotlin/org/branneman/health/BodyWeightIntegrationTest.kt` | Integration tests for POST /body/weight |
| Modify | `server/src/main/kotlin/org/branneman/health/Application.kt` | Add POST /body/weight handler |
| Modify | `app/src/main/kotlin/org/branneman/health/db/dao/UserProfileDao.kt` | Add `existsFlow()` |
| Modify | `app/src/test/kotlin/org/branneman/health/db/dao/UserProfileDaoTest.kt` | Test `existsFlow()` |
| Modify | `app/src/main/kotlin/org/branneman/health/auth/AuthRepository.kt` | `NeedsOnboarding` state, `db` required, `flatMapLatest` |
| Modify | `app/src/test/kotlin/org/branneman/health/auth/AuthRepositoryTest.kt` | Add Robolectric, update/add tests |
| Modify | `app/src/main/kotlin/org/branneman/health/auth/AuthViewModel.kt` | Updated `AuthRepository` constructor call |
| Modify | `app/src/main/kotlin/org/branneman/health/network/HealthApiClient.kt` | Add `postBodyWeight()`, update `putProfile()` |
| Modify | `app/src/test/kotlin/org/branneman/health/network/HealthApiClientTest.kt` | Tests for new/updated methods |
| Create | `app/src/main/kotlin/org/branneman/health/onboarding/OnboardingRepository.kt` | Save logic: PUT /profile + POST /body/weight + Room writes |
| Create | `app/src/test/kotlin/org/branneman/health/onboarding/OnboardingRepositoryTest.kt` | Unit tests for save logic |
| Create | `app/src/main/kotlin/org/branneman/health/onboarding/OnboardingViewModel.kt` | UI state, step navigation, delegates to repository |
| Create | `app/src/main/kotlin/org/branneman/health/ui/OnboardingScreen.kt` | 3-step Compose UI |
| Modify | `app/src/test/kotlin/org/branneman/health/ui/OnboardingScreenTest.kt` | Compose UI tests (create new) |
| Modify | `app/src/main/kotlin/org/branneman/health/App.kt` | Add `NeedsOnboarding` branch |

---

## Task 1: POST /body/weight server endpoint

**Files:**
- Create: `server/src/test/kotlin/org/branneman/health/BodyWeightIntegrationTest.kt`
- Modify: `server/src/main/kotlin/org/branneman/health/Application.kt`

- [ ] **Step 1: Write the failing integration tests**

```kotlin
// server/src/test/kotlin/org/branneman/health/BodyWeightIntegrationTest.kt
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
    fun `POST body-weight creates entry and returns 201 with date and kg`() = appTest {
        val token = login()
        val r = client.post("/body/weight") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"date":"2026-06-10","kg":84.0}""")
        }
        assertEquals(HttpStatusCode.Created, r.status)
        val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals("2026-06-10", body["date"]!!.jsonPrimitive.content)
        assertEquals(84.0, body["kg"]!!.jsonPrimitive.content.toDouble())
    }

    @Test
    fun `POST body-weight returns 409 when entry already exists for this date`() = appTest {
        val token = login()
        client.post("/body/weight") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"date":"2026-06-10","kg":84.0}""")
        }
        val r = client.post("/body/weight") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"date":"2026-06-10","kg":84.5}""")
        }
        assertEquals(HttpStatusCode.Conflict, r.status)
    }
}
```

- [ ] **Step 2: Run to confirm tests fail**

```bash
./gradlew :server:test --tests "org.branneman.health.BodyWeightIntegrationTest"
```

Expected: FAIL — `POST /body/weight` route doesn't exist yet.

- [ ] **Step 3: Add the POST /body/weight handler**

In `server/src/main/kotlin/org/branneman/health/Application.kt`, inside the existing `authenticate("api") { route("/body") { ... } }` block, add after the `get("/weight") { ... }` handler:

```kotlin
post("/weight") {
    val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
    val dto = call.receive<WeightEntryDto>()
    val date = java.time.LocalDate.parse(dto.date)

    val exists = transaction {
        BodyWeight.selectAll()
            .where { (BodyWeight.userId eq userId) and (BodyWeight.date eq date) }
            .count() > 0
    }
    if (exists) {
        call.respond(HttpStatusCode.Conflict)
        return@post
    }

    transaction {
        BodyWeight.insert {
            it[BodyWeight.id]        = UUID.randomUUID()
            it[BodyWeight.userId]    = userId
            it[BodyWeight.date]      = date
            it[BodyWeight.kg]        = dto.kg.toBigDecimal()
            it[BodyWeight.createdAt] = OffsetDateTime.now()
        }
    }
    call.respond(HttpStatusCode.Created, dto)
}
```

`WeightEntryDto` is already imported. The response is just `WeightEntryDto(date, kg)` — no id field, consistent with the GET handler and the shared DTO shape.

- [ ] **Step 4: Run tests to confirm they pass**

```bash
./gradlew :server:test --tests "org.branneman.health.BodyWeightIntegrationTest"
```

Expected: all 3 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/org/branneman/health/Application.kt \
        server/src/test/kotlin/org/branneman/health/BodyWeightIntegrationTest.kt
git commit -m "feat: add POST /body/weight server endpoint"
```

---

## Task 2: UserProfileDao.existsFlow()

**Files:**
- Modify: `app/src/main/kotlin/org/branneman/health/db/dao/UserProfileDao.kt`
- Modify: `app/src/test/kotlin/org/branneman/health/db/dao/UserProfileDaoTest.kt`

- [ ] **Step 1: Write the failing tests**

Add to the bottom of `UserProfileDaoTest` (after the existing `deleteForUser` test):

```kotlin
@Test
fun `existsFlow emits false when table is empty`() = runTest {
    assertFalse(dao.existsFlow().first())
}

@Test
fun `existsFlow emits true after upsert`() = runTest {
    dao.upsert(aUserProfile(userId = uuid()))
    assertTrue(dao.existsFlow().first())
}

@Test
fun `existsFlow emits true then false after delete`() = runTest {
    val userId = uuid()
    dao.upsert(aUserProfile(userId = userId))
    assertTrue(dao.existsFlow().first())
    dao.deleteForUser(userId)
    assertFalse(dao.existsFlow().first())
}
```

- [ ] **Step 2: Run to confirm they fail**

```bash
./gradlew :app:testDebugUnitTest --tests "org.branneman.health.db.dao.UserProfileDaoTest"
```

Expected: FAIL — `existsFlow` not defined.

- [ ] **Step 3: Add existsFlow to UserProfileDao**

```kotlin
// app/src/main/kotlin/org/branneman/health/db/dao/UserProfileDao.kt
@Dao
interface UserProfileDao {
    @Query("SELECT * FROM user_profile")
    fun observe(): Flow<UserProfileEntity?>

    @Query("SELECT * FROM user_profile")
    suspend fun get(): UserProfileEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM user_profile LIMIT 1)")
    fun existsFlow(): Flow<Boolean>

    @Upsert
    suspend fun upsert(entity: UserProfileEntity)

    @Query("DELETE FROM user_profile WHERE userId = :userId")
    suspend fun deleteForUser(userId: String)
}
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
./gradlew :app:testDebugUnitTest --tests "org.branneman.health.db.dao.UserProfileDaoTest"
```

Expected: all tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/db/dao/UserProfileDao.kt \
        app/src/test/kotlin/org/branneman/health/db/dao/UserProfileDaoTest.kt
git commit -m "feat: add UserProfileDao.existsFlow()"
```

---

## Task 3: AuthState.NeedsOnboarding + AuthRepository refactor

**Files:**
- Modify: `app/src/main/kotlin/org/branneman/health/auth/AuthRepository.kt`
- Modify: `app/src/test/kotlin/org/branneman/health/auth/AuthRepositoryTest.kt`
- Modify: `app/src/main/kotlin/org/branneman/health/auth/AuthViewModel.kt`

- [ ] **Step 1: Write the failing tests**

Replace the entire contents of `AuthRepositoryTest.kt`:

```kotlin
package org.branneman.health.auth

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.branneman.health.aUserProfile
import org.branneman.health.db.HealthDatabase
import org.branneman.health.network.HealthApiClient
import org.branneman.health.uuid
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AuthRepositoryTest {

    private fun testTokenStore(): TokenStore {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            produceFile = {
                File.createTempFile("test_auth", ".preferences_pb").also { it.deleteOnExit() }
            }
        )
        return TokenStore(dataStore)
    }

    private fun testDb(): HealthDatabase =
        Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HealthDatabase::class.java
        ).allowMainThreadQueries().build()

    private fun apiClient(handler: MockRequestHandler): HealthApiClient {
        val engine = MockEngine(handler)
        return HealthApiClient(
            baseUrl = "http://test",
            client = HttpClient(engine) { install(ContentNegotiation) { json() } }
        )
    }

    @Test
    fun `no token emits LoggedOut`() = runTest {
        val repo = AuthRepository(testTokenStore(), apiClient { respond("", HttpStatusCode.OK) }, testDb())
        assertEquals(AuthState.LoggedOut, repo.authState.first())
    }

    @Test
    fun `expired token emits Expired`() = runTest {
        val store = testTokenStore()
        store.save("old-token", "2020-01-01T00:00:00Z", uuid())
        val repo = AuthRepository(store, apiClient { respond("", HttpStatusCode.OK) }, testDb())
        assertEquals(AuthState.Expired, repo.authState.first())
    }

    @Test
    fun `valid token without profile emits NeedsOnboarding`() = runTest {
        val store = testTokenStore()
        val farFuture = java.time.OffsetDateTime.now().plusDays(30).toString()
        store.save("valid-token", farFuture, uuid())
        val repo = AuthRepository(store, apiClient { respond("", HttpStatusCode.OK) }, testDb())
        assertEquals(AuthState.NeedsOnboarding, repo.authState.first())
    }

    @Test
    fun `valid token with profile emits LoggedIn`() = runTest {
        val store = testTokenStore()
        val farFuture = java.time.OffsetDateTime.now().plusDays(30).toString()
        val userId = uuid()
        store.save("valid-token", farFuture, userId)
        val db = testDb()
        db.userProfileDao().upsert(aUserProfile(userId = userId))
        val repo = AuthRepository(store, apiClient { respond("", HttpStatusCode.OK) }, db)
        assertEquals(AuthState.LoggedIn, repo.authState.first())
    }

    @Test
    fun `token expiring within 7 days triggers refresh and emits LoggedIn when profile exists`() = runTest {
        val store = testTokenStore()
        val soonExpiry = java.time.OffsetDateTime.now().plusDays(3).toString()
        val newExpiry  = java.time.OffsetDateTime.now().plusDays(30).toString()
        val userId = uuid()
        store.save("soon-expiring-token", soonExpiry, userId)
        val db = testDb()
        db.userProfileDao().upsert(aUserProfile(userId = userId))

        val client = apiClient { _ ->
            respond(
                """{"token":"refreshed-token","expiresAt":"$newExpiry","userId":"$userId"}""",
                HttpStatusCode.OK,
                io.ktor.http.headersOf(
                    io.ktor.http.HttpHeaders.ContentType,
                    io.ktor.http.ContentType.Application.Json.toString()
                )
            )
        }
        val repo = AuthRepository(store, client, db)
        repo.proactiveRefreshIfNeeded()

        assertEquals(AuthState.LoggedIn, repo.authState.first())
    }

    @Test
    fun `token expiring within 7 days emits Expired when refresh fails`() = runTest {
        val store = testTokenStore()
        val soonExpiry = java.time.OffsetDateTime.now().plusDays(3).toString()
        store.save("soon-expiring-token", soonExpiry, uuid())

        val repo = AuthRepository(
            store,
            apiClient { respond("", HttpStatusCode.Unauthorized) },
            testDb()
        )
        repo.proactiveRefreshIfNeeded()
        assertEquals(AuthState.Expired, repo.authState.first())
    }
}
```

- [ ] **Step 2: Run to confirm they fail**

```bash
./gradlew :app:testDebugUnitTest --tests "org.branneman.health.auth.AuthRepositoryTest"
```

Expected: FAIL — `AuthState.NeedsOnboarding` doesn't exist; `AuthRepository` constructor doesn't take a required `db`.

- [ ] **Step 3: Update AuthRepository.kt**

Replace the entire file:

```kotlin
package org.branneman.health.auth

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import org.branneman.health.db.HealthDatabase
import org.branneman.health.network.HealthApiClient
import org.branneman.health.sync.LoginSyncService
import java.time.OffsetDateTime

sealed class AuthState {
    data object Loading         : AuthState()
    data object LoggedOut       : AuthState()
    data object Expired         : AuthState()
    data object NeedsOnboarding : AuthState()
    data object LoggedIn        : AuthState()
}

class AuthRepository(
    private val tokenStore: TokenStore,
    private val apiClient: HealthApiClient,
    private val db: HealthDatabase,
    private val loginSyncService: LoginSyncService? = null,
) {
    private val _expiredChannel = Channel<Unit>(Channel.CONFLATED)

    val authState: Flow<AuthState> = merge(
        tokenStore.tokenFlow.flatMapLatest { stored ->
            when {
                stored == null -> flowOf(AuthState.LoggedOut)
                OffsetDateTime.parse(stored.expiresAt) < OffsetDateTime.now() -> flowOf(AuthState.Expired)
                else -> db.userProfileDao()
                    .existsFlow()
                    .map { exists -> if (exists) AuthState.LoggedIn else AuthState.NeedsOnboarding }
            }
        },
        _expiredChannel.receiveAsFlow().map { AuthState.Expired }
    )

    suspend fun proactiveRefreshIfNeeded() {
        val stored = tokenStore.tokenFlow.first() ?: return
        val expiresAt = OffsetDateTime.parse(stored.expiresAt)
        val sevenDaysFromNow = OffsetDateTime.now().plusDays(7)
        if (expiresAt > OffsetDateTime.now() && expiresAt < sevenDaysFromNow) {
            refresh()
        }
    }

    suspend fun refresh(): String? {
        val stored = tokenStore.tokenFlow.first() ?: return null
        return runCatching {
            val response = apiClient.refresh(stored.token)
            tokenStore.save(response.token, response.expiresAt, stored.userId)
            response.token
        }.getOrElse {
            handleExpired()
            null
        }
    }

    fun handleExpired() {
        _expiredChannel.trySend(Unit)
    }

    suspend fun login(username: String, password: String): Result<Boolean> = runCatching {
        val response = apiClient.login(username, password)
        tokenStore.save(response.token, response.expiresAt, response.userId)
        loginSyncService?.sync(response.token, response.userId) ?: false
    }

    suspend fun logout() {
        val stored = tokenStore.tokenFlow.first()
        if (stored != null) runCatching { apiClient.logout(stored.token) }
        stored?.userId?.let { userId ->
            db.bodyWeightDao().deleteAllForUser(userId)
            db.dailyEnergyDao().deleteAllForUser(userId)
            db.workoutDao().deleteAllForUser(userId)
            db.logEntryDao().deleteAllItemsForUser(userId)
            db.logEntryDao().deleteAllForUser(userId)
            db.mealTemplateDao().deleteAllItemsForUser(userId)
            db.mealTemplateDao().deleteAllForUser(userId)
            db.foodItemDao().deleteAllForUser(userId)
            db.shortcutDao().deleteAllForUser(userId)
            db.userProfileDao().deleteForUser(userId)
        }
        tokenStore.clear()
    }
}
```

- [ ] **Step 4: Update AuthViewModel.kt — pass db to AuthRepository**

In `AuthViewModel.kt`, update the `authRepository` initialisation inside `init {}`:

```kotlin
authRepository = AuthRepository(
    tokenStore = tokenStore,
    apiClient = apiClient,
    db = app.db,
    loginSyncService = LoginSyncService(api = apiClient, db = app.db),
)
```

- [ ] **Step 5: Run tests to confirm they pass**

```bash
./gradlew :app:testDebugUnitTest --tests "org.branneman.health.auth.AuthRepositoryTest"
```

Expected: all 6 tests PASS.

- [ ] **Step 6: Run all app tests to check for regressions**

```bash
./gradlew :app:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/auth/AuthRepository.kt \
        app/src/main/kotlin/org/branneman/health/auth/AuthViewModel.kt \
        app/src/test/kotlin/org/branneman/health/auth/AuthRepositoryTest.kt
git commit -m "feat: add AuthState.NeedsOnboarding; auth flow gates on Room profile"
```

---

## Task 4: HealthApiClient — postBodyWeight + putProfile error handling

**Files:**
- Modify: `app/src/main/kotlin/org/branneman/health/network/HealthApiClient.kt`
- Modify: `app/src/test/kotlin/org/branneman/health/network/HealthApiClientTest.kt`

- [ ] **Step 1: Write the failing tests**

Add to the bottom of `HealthApiClientTest.kt`:

```kotlin
@Test
fun `postBodyWeight returns on 201`() = runBlocking {
    val client = mockClient { _ ->
        respond(
            """{"date":"2026-06-10","kg":84.0}""",
            HttpStatusCode.Created,
            headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        )
    }
    // Should not throw
    client.postBodyWeight("token", WeightEntryDto("2026-06-10", 84.0))
}

@Test
fun `postBodyWeight does not throw on 409 Conflict`() = runBlocking {
    val client = mockClient { _ -> respond("", HttpStatusCode.Conflict) }
    // 409 = weight already exists for this date; treat as success
    client.postBodyWeight("token", WeightEntryDto("2026-06-10", 84.0))
}

@Test
fun `postBodyWeight throws on server error`() = runBlocking {
    val client = mockClient { _ -> respond("", HttpStatusCode.InternalServerError) }
    assertFailsWith<Exception> {
        client.postBodyWeight("token", WeightEntryDto("2026-06-10", 84.0))
    }
}

@Test
fun `putProfile throws on server error`() = runBlocking {
    val client = mockClient { _ -> respond("", HttpStatusCode.InternalServerError) }
    assertFailsWith<Exception> {
        client.putProfile(
            "token",
            UserProfileDto(177, 1986, "male", 74.0, "lightly_active", 300, "loss", false)
        )
    }
}
```

Make sure `WeightEntryDto` is imported: add `import org.branneman.health.WeightEntryDto` alongside the existing DTO imports.

- [ ] **Step 2: Run to confirm they fail**

```bash
./gradlew :app:testDebugUnitTest --tests "org.branneman.health.network.HealthApiClientTest"
```

Expected: FAIL — `postBodyWeight` not defined; `putProfile` doesn't currently throw.

- [ ] **Step 3: Add postBodyWeight and update putProfile**

In `HealthApiClient.kt`, add `postBodyWeight` after `getBodyWeight`, and update `putProfile` to throw on failure:

```kotlin
suspend fun putProfile(token: String, profile: UserProfileDto) {
    val response = client.put("$baseUrl/profile") {
        header(HttpHeaders.Authorization, "Bearer $token")
        contentType(ContentType.Application.Json)
        setBody(profile)
    }
    check(response.status.isSuccess()) { "PUT /profile failed: ${response.status}" }
}

suspend fun postBodyWeight(token: String, dto: WeightEntryDto) {
    val response = client.post("$baseUrl/body/weight") {
        header(HttpHeaders.Authorization, "Bearer $token")
        contentType(ContentType.Application.Json)
        setBody(dto)
    }
    if (!response.status.isSuccess() && response.status != HttpStatusCode.Conflict) {
        throw Exception("POST /body/weight failed: ${response.status}")
    }
}
```

Add the following import if not already present:
```kotlin
import io.ktor.http.isSuccess
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
./gradlew :app:testDebugUnitTest --tests "org.branneman.health.network.HealthApiClientTest"
```

Expected: all tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/network/HealthApiClient.kt \
        app/src/test/kotlin/org/branneman/health/network/HealthApiClientTest.kt
git commit -m "feat: add HealthApiClient.postBodyWeight(); putProfile throws on failure"
```

---

## Task 5: OnboardingRepository

**Files:**
- Create: `app/src/main/kotlin/org/branneman/health/onboarding/OnboardingRepository.kt`
- Create: `app/src/test/kotlin/org/branneman/health/onboarding/OnboardingRepositoryTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
// app/src/test/kotlin/org/branneman/health/onboarding/OnboardingRepositoryTest.kt
package org.branneman.health.onboarding

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
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
import org.branneman.health.db.SyncStatus
import org.branneman.health.network.HealthApiClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OnboardingRepositoryTest {

    private lateinit var db: HealthDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HealthDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() { db.close() }

    private fun apiClient(handler: MockRequestHandler): HealthApiClient =
        HealthApiClient(
            baseUrl = "http://test",
            client = HttpClient(MockEngine(handler)) {
                install(ContentNegotiation) { json() }
            }
        )

    private fun profileJson() =
        """{"heightCm":177,"birthYear":1986,"sex":"male","goalWeightKg":74.0,"activityLevel":"lightly_active","targetDeficit":300,"phase":"loss","vacationMode":false}"""

    private fun weightJson() = """{"date":"${LocalDate.now()}","kg":84.0}"""

    @Test
    fun `save writes profile and weight to Room on success`() = runTest {
        val client = apiClient { request ->
            when {
                request.url.encodedPath.endsWith("/profile") -> respond(
                    profileJson(), HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
                else -> respond(
                    weightJson(), HttpStatusCode.Created,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            }
        }
        val repo = OnboardingRepository(client, db)
        val result = repo.save(
            token = "t", userId = "user-1",
            sex = "male", heightCm = 177, currentWeightKg = 84.0,
            goalWeightKg = 74.0, birthYear = 1986,
            activityLevel = "lightly_active", targetDeficit = 300,
        )
        assertTrue(result.isSuccess)
        assertNotNull(db.userProfileDao().get())
        assertEquals(SyncStatus.SYNCED, db.userProfileDao().get()!!.syncStatus)
        val weight = db.bodyWeightDao().observeAll().first().firstOrNull()
        assertNotNull(weight)
        assertEquals(84.0, weight.kg)
        assertEquals(SyncStatus.SYNCED, weight.syncStatus)
        // existsFlow must now emit true so auth state transitions
        assertTrue(db.userProfileDao().existsFlow().first())
    }

    @Test
    fun `save returns failure and does not write to Room when putProfile fails`() = runTest {
        val client = apiClient { respond("", HttpStatusCode.InternalServerError) }
        val repo = OnboardingRepository(client, db)
        val result = repo.save(
            token = "t", userId = "user-1",
            sex = "male", heightCm = 177, currentWeightKg = 84.0,
            goalWeightKg = 74.0, birthYear = 1986,
            activityLevel = "lightly_active", targetDeficit = 300,
        )
        assertTrue(result.isFailure)
        assertNull(db.userProfileDao().get())
        assertTrue(db.bodyWeightDao().observeAll().first().isEmpty())
    }

    @Test
    fun `save returns failure and does not write to Room when postBodyWeight fails`() = runTest {
        var callCount = 0
        val client = apiClient { request ->
            callCount++
            if (callCount == 1) {
                // putProfile succeeds
                respond(
                    profileJson(), HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            } else {
                // postBodyWeight fails
                respond("", HttpStatusCode.InternalServerError)
            }
        }
        val repo = OnboardingRepository(client, db)
        val result = repo.save(
            token = "t", userId = "user-1",
            sex = "male", heightCm = 177, currentWeightKg = 84.0,
            goalWeightKg = 74.0, birthYear = 1986,
            activityLevel = "lightly_active", targetDeficit = 300,
        )
        assertTrue(result.isFailure)
        assertNull(db.userProfileDao().get())
    }

    @Test
    fun `save succeeds when postBodyWeight returns 409 (weight already exists)`() = runTest {
        val client = apiClient { request ->
            when {
                request.url.encodedPath.endsWith("/profile") -> respond(
                    profileJson(), HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
                else -> respond("", HttpStatusCode.Conflict)
            }
        }
        val repo = OnboardingRepository(client, db)
        val result = repo.save(
            token = "t", userId = "user-1",
            sex = "male", heightCm = 177, currentWeightKg = 84.0,
            goalWeightKg = 74.0, birthYear = 1986,
            activityLevel = "lightly_active", targetDeficit = 300,
        )
        assertTrue(result.isSuccess)
        assertNotNull(db.userProfileDao().get())
    }

    // BMR computation tests — pure functions, no infrastructure needed

    @Test
    fun `computeBmr male 84kg 177cm age 39`() {
        val bmr = computeBmr(sex = "male", weightKg = 84.0, heightCm = 177, age = 39)
        // 10×84 + 6.25×177 − 5×39 + 5 = 840 + 1106.25 − 195 + 5 = 1756.25
        assertEquals(1756.25, bmr, 0.01)
    }

    @Test
    fun `computeBmr female uses −161 constant`() {
        val bmr = computeBmr(sex = "female", weightKg = 70.0, heightCm = 165, age = 35)
        // 10×70 + 6.25×165 − 5×35 − 161 = 700 + 1031.25 − 175 − 161 = 1395.25
        assertEquals(1395.25, bmr, 0.01)
    }

    @Test
    fun `activityMultiplier returns correct values`() {
        assertEquals(1.20,   activityMultiplier("sedentary"),         0.001)
        assertEquals(1.375,  activityMultiplier("lightly_active"),    0.001)
        assertEquals(1.55,   activityMultiplier("moderately_active"), 0.001)
        assertEquals(1.375,  activityMultiplier("unknown"),           0.001) // default
    }
}
```

- [ ] **Step 2: Run to confirm they fail**

```bash
./gradlew :app:testDebugUnitTest --tests "org.branneman.health.onboarding.OnboardingRepositoryTest"
```

Expected: FAIL — `OnboardingRepository`, `computeBmr`, `activityMultiplier` don't exist.

- [ ] **Step 3: Implement OnboardingRepository**

```kotlin
// app/src/main/kotlin/org/branneman/health/onboarding/OnboardingRepository.kt
package org.branneman.health.onboarding

import org.branneman.health.UserProfileDto
import org.branneman.health.WeightEntryDto
import org.branneman.health.db.HealthDatabase
import org.branneman.health.db.SyncStatus
import org.branneman.health.db.entities.BodyWeightEntity
import org.branneman.health.db.entities.UserProfileEntity
import org.branneman.health.network.HealthApiClient
import java.time.LocalDate

fun computeBmr(sex: String, weightKg: Double, heightCm: Int, age: Int): Double {
    val base = 10.0 * weightKg + 6.25 * heightCm - 5.0 * age
    return if (sex == "male") base + 5.0 else base - 161.0
}

fun activityMultiplier(level: String): Double = when (level) {
    "sedentary"          -> 1.20
    "lightly_active"     -> 1.375
    "moderately_active"  -> 1.55
    else                 -> 1.375
}

class OnboardingRepository(
    private val apiClient: HealthApiClient,
    private val db: HealthDatabase,
) {
    suspend fun save(
        token: String,
        userId: String,
        sex: String,
        heightCm: Int,
        currentWeightKg: Double,
        goalWeightKg: Double,
        birthYear: Int,
        activityLevel: String,
        targetDeficit: Int,
    ): Result<Unit> = runCatching {
        val profileDto = UserProfileDto(
            heightCm      = heightCm,
            birthYear     = birthYear,
            sex           = sex,
            goalWeightKg  = goalWeightKg,
            activityLevel = activityLevel,
            targetDeficit = targetDeficit,
            phase         = "loss",
            vacationMode  = false,
        )
        apiClient.putProfile(token, profileDto)

        val today = LocalDate.now().toString()
        apiClient.postBodyWeight(token, WeightEntryDto(date = today, kg = currentWeightKg))

        db.userProfileDao().upsert(
            UserProfileEntity(
                userId        = userId,
                heightCm      = heightCm,
                birthYear     = birthYear,
                sex           = sex,
                goalWeightKg  = goalWeightKg,
                activityLevel = activityLevel,
                targetDeficit = targetDeficit,
                phase         = "loss",
                vacationMode  = false,
                syncStatus    = SyncStatus.SYNCED,
            )
        )
        db.bodyWeightDao().upsert(
            BodyWeightEntity(
                id         = today,
                userId     = userId,
                date       = today,
                kg         = currentWeightKg,
                syncStatus = SyncStatus.SYNCED,
            )
        )
    }
}
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
./gradlew :app:testDebugUnitTest --tests "org.branneman.health.onboarding.OnboardingRepositoryTest"
```

Expected: all tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/onboarding/OnboardingRepository.kt \
        app/src/test/kotlin/org/branneman/health/onboarding/OnboardingRepositoryTest.kt
git commit -m "feat: add OnboardingRepository with BMR helpers and save flow"
```

---

## Task 6: OnboardingViewModel

**Files:**
- Create: `app/src/main/kotlin/org/branneman/health/onboarding/OnboardingViewModel.kt`

No separate ViewModel test file — the save logic is fully covered by `OnboardingRepositoryTest`. The ViewModel is a thin state holder with no independently-testable logic beyond what is covered by the repository and UI tests in Task 7.

- [ ] **Step 1: Implement OnboardingViewModel**

```kotlin
// app/src/main/kotlin/org/branneman/health/onboarding/OnboardingViewModel.kt
package org.branneman.health.onboarding

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
import org.branneman.health.network.HealthApiClient
import java.time.LocalDate

data class OnboardingUiState(
    val step: Int = 1,
    val sex: String = "",
    val heightCm: String = "",
    val currentWeightKg: String = "",
    val goalWeightKg: String = "",
    val age: String = "",
    val activityLevel: String = "lightly_active",
    val targetDeficit: Int = 300,
    val isSaving: Boolean = false,
    val saveError: String? = null,
) {
    val step1Valid: Boolean
        get() {
            val current = currentWeightKg.toDoubleOrNull() ?: return false
            val goal    = goalWeightKg.toDoubleOrNull()    ?: return false
            return sex.isNotEmpty()
                && heightCm.toIntOrNull() != null
                && age.toIntOrNull() != null
                && goal <= current
        }

    val estimatedTdeeKcal: Int?
        get() {
            val w = currentWeightKg.toDoubleOrNull() ?: return null
            val h = heightCm.toIntOrNull()           ?: return null
            val a = age.toIntOrNull()                ?: return null
            if (sex.isEmpty()) return null
            return (computeBmr(sex, w, h, a) * activityMultiplier(activityLevel)).toInt()
        }

    val kgPerWeek: Double?
        get() = if (targetDeficit == 0) null else targetDeficit / 7700.0

    val monthsToGoal: Double?
        get() {
            val current = currentWeightKg.toDoubleOrNull() ?: return null
            val goal    = goalWeightKg.toDoubleOrNull()    ?: return null
            if (targetDeficit == 0 || current <= goal) return null
            return (current - goal) * 7700.0 / targetDeficit / 30.0
        }
}

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as HealthApplication
    private val tokenStore = TokenStore(application.authDataStore)
    private val apiClient = HealthApiClient(
        baseUrl = BuildConfig.SERVER_BASE_URL,
        client = HttpClient(Android) { install(ContentNegotiation) { json() } }
    )
    private val repository = OnboardingRepository(apiClient, app.db)

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    fun update(block: OnboardingUiState.() -> OnboardingUiState) =
        _uiState.update(block)

    fun goBack() = _uiState.update { it.copy(step = it.step - 1) }
    fun goNext() = _uiState.update { it.copy(step = it.step + 1) }

    fun save() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, saveError = null) }

            val stored = tokenStore.tokenFlow.first()
            if (stored == null) {
                _uiState.update { it.copy(isSaving = false, saveError = "Not signed in.") }
                return@launch
            }

            val state = _uiState.value
            val result = repository.save(
                token           = stored.token,
                userId          = stored.userId,
                sex             = state.sex,
                heightCm        = state.heightCm.toInt(),
                currentWeightKg = state.currentWeightKg.toDouble(),
                goalWeightKg    = state.goalWeightKg.toDouble(),
                birthYear       = LocalDate.now().year - state.age.toInt(),
                activityLevel   = state.activityLevel,
                targetDeficit   = state.targetDeficit,
            )

            if (result.isFailure) {
                _uiState.update {
                    it.copy(
                        isSaving  = false,
                        saveError = "Couldn't reach the server — check your connection and try again.",
                    )
                }
                return@launch
            }
            // On success: Room existsFlow emits true → authState → LoggedIn → App.kt
            // renders MainNav automatically. No explicit navigation needed here.
            _uiState.update { it.copy(isSaving = false) }
        }
    }
}
```

- [ ] **Step 2: Run all app tests to verify no regressions**

```bash
./gradlew :app:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/onboarding/OnboardingViewModel.kt
git commit -m "feat: add OnboardingViewModel with step state and save delegation"
```

---

## Task 7: OnboardingScreen + App.kt wiring

**Files:**
- Create: `app/src/main/kotlin/org/branneman/health/ui/OnboardingScreen.kt`
- Create: `app/src/test/kotlin/org/branneman/health/ui/OnboardingScreenTest.kt`
- Modify: `app/src/main/kotlin/org/branneman/health/App.kt`

- [ ] **Step 1: Write the failing UI tests**

```kotlin
// app/src/test/kotlin/org/branneman/health/ui/OnboardingScreenTest.kt
package org.branneman.health.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.branneman.health.onboarding.OnboardingUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OnboardingScreenTest {

    @get:Rule val compose = createComposeRule()

    private fun renderStep1(
        state: OnboardingUiState = OnboardingUiState(),
        onUpdate: (OnboardingUiState.() -> OnboardingUiState) -> Unit = {},
        onNext: () -> Unit = {},
    ) {
        compose.setContent {
            OnboardingStep1(state = state, onUpdate = onUpdate, onNext = onNext)
        }
    }

    private fun renderStep2(
        state: OnboardingUiState = OnboardingUiState(
            sex = "male", heightCm = "177", currentWeightKg = "84.0",
            goalWeightKg = "74.0", age = "39",
        ),
        onUpdate: (OnboardingUiState.() -> OnboardingUiState) -> Unit = {},
        onBack: () -> Unit = {},
        onNext: () -> Unit = {},
    ) {
        compose.setContent {
            OnboardingStep2(state = state, onUpdate = onUpdate, onBack = onBack, onNext = onNext)
        }
    }

    private fun renderStep3(
        state: OnboardingUiState = OnboardingUiState(
            sex = "male", heightCm = "177", currentWeightKg = "84.0",
            goalWeightKg = "74.0", age = "39",
        ),
        onUpdate: (OnboardingUiState.() -> OnboardingUiState) -> Unit = {},
        onBack: () -> Unit = {},
        onSave: () -> Unit = {},
    ) {
        compose.setContent {
            OnboardingStep3(state = state, onUpdate = onUpdate, onBack = onBack, onSave = onSave)
        }
    }

    // Step 1

    @Test
    fun `step 1 Continue is disabled when all fields empty`() {
        renderStep1()
        compose.onNodeWithText("Continue").assertIsNotEnabled()
    }

    @Test
    fun `step 1 Continue is disabled when goal weight exceeds current weight`() {
        renderStep1(
            state = OnboardingUiState(
                sex = "male", heightCm = "177",
                currentWeightKg = "74.0", goalWeightKg = "80.0", age = "39"
            )
        )
        compose.onNodeWithText("Continue").assertIsNotEnabled()
    }

    @Test
    fun `step 1 Continue is enabled when all fields valid`() {
        renderStep1(
            state = OnboardingUiState(
                sex = "male", heightCm = "177",
                currentWeightKg = "84.0", goalWeightKg = "74.0", age = "39"
            )
        )
        compose.onNodeWithText("Continue").assertIsEnabled()
    }

    @Test
    fun `step 1 has no Back button`() {
        renderStep1()
        compose.onNodeWithText("Back").assertDoesNotExist()
    }

    // Step 2

    @Test
    fun `step 2 shows estimated output label`() {
        renderStep2()
        compose.onNodeWithText("Estimated output", substring = true).assertExists()
        compose.onNodeWithText("(estimated)", substring = true).assertExists()
    }

    @Test
    fun `step 2 has Back button`() {
        renderStep2()
        compose.onNodeWithText("Back").assertExists()
    }

    // Step 3

    @Test
    fun `step 3 Done is disabled while saving`() {
        renderStep3(state = OnboardingUiState(
            sex = "male", heightCm = "177", currentWeightKg = "84.0",
            goalWeightKg = "74.0", age = "39", isSaving = true,
        ))
        compose.onNodeWithText("Done").assertIsNotEnabled()
    }

    @Test
    fun `step 3 shows muscle loss warning above 500 kcal`() {
        renderStep3(state = OnboardingUiState(
            sex = "male", heightCm = "177", currentWeightKg = "84.0",
            goalWeightKg = "74.0", age = "39", targetDeficit = 550,
        ))
        compose.onNodeWithText("muscle loss", substring = true).assertExists()
    }

    @Test
    fun `step 3 does not show muscle loss warning at 300 kcal`() {
        renderStep3(state = OnboardingUiState(
            sex = "male", heightCm = "177", currentWeightKg = "84.0",
            goalWeightKg = "74.0", age = "39", targetDeficit = 300,
        ))
        compose.onNodeWithText("muscle loss", substring = true).assertDoesNotExist()
    }

    @Test
    fun `step 3 shows maintain weight when deficit is 0`() {
        renderStep3(state = OnboardingUiState(
            sex = "male", heightCm = "177", currentWeightKg = "84.0",
            goalWeightKg = "84.0", age = "39", targetDeficit = 0,
        ))
        compose.onNodeWithText("Maintain weight", substring = true).assertExists()
    }
}
```

- [ ] **Step 2: Run to confirm tests fail**

```bash
./gradlew :app:testDebugUnitTest --tests "org.branneman.health.ui.OnboardingScreenTest"
```

Expected: FAIL — `OnboardingStep1`, `OnboardingStep2`, `OnboardingStep3` don't exist.

- [ ] **Step 3: Implement OnboardingScreen.kt**

```kotlin
// app/src/main/kotlin/org/branneman/health/ui/OnboardingScreen.kt
package org.branneman.health.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.branneman.health.onboarding.OnboardingUiState
import org.branneman.health.onboarding.OnboardingViewModel
import kotlin.math.roundToInt

@Composable
fun OnboardingScreen(viewModel: OnboardingViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    when (state.step) {
        1 -> OnboardingStep1(
            state    = state,
            onUpdate = viewModel::update,
            onNext   = viewModel::goNext,
        )
        2 -> OnboardingStep2(
            state    = state,
            onUpdate = viewModel::update,
            onBack   = viewModel::goBack,
            onNext   = viewModel::goNext,
        )
        3 -> OnboardingStep3(
            state    = state,
            onUpdate = viewModel::update,
            onBack   = viewModel::goBack,
            onSave   = viewModel::save,
        )
    }
}

@Composable
fun OnboardingStep1(
    state: OnboardingUiState,
    onUpdate: (OnboardingUiState.() -> OnboardingUiState) -> Unit,
    onNext: () -> Unit,
) {
    val goalError = state.goalWeightKg.isNotEmpty() && state.currentWeightKg.isNotEmpty() &&
            (state.goalWeightKg.toDoubleOrNull() ?: 0.0) > (state.currentWeightKg.toDoubleOrNull() ?: 0.0)

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Set up your profile  1/3", style = MaterialTheme.typography.headlineSmall)

        // Sex toggle
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("male" to "Male", "female" to "Female").forEach { (value, label) ->
                FilterChip(
                    selected = state.sex == value,
                    onClick  = { onUpdate { copy(sex = value) } },
                    label    = { Text(label) },
                )
            }
        }

        OutlinedTextField(
            value         = state.heightCm,
            onValueChange = { onUpdate { copy(heightCm = it) } },
            label         = { Text("Height (cm)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier      = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value         = state.currentWeightKg,
            onValueChange = { onUpdate { copy(currentWeightKg = it) } },
            label         = { Text("Current weight (kg)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier      = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value         = state.goalWeightKg,
            onValueChange = { onUpdate { copy(goalWeightKg = it) } },
            label         = { Text("Goal weight (kg)") },
            isError       = goalError,
            supportingText = if (goalError) {{ Text("Goal must be ≤ current weight") }} else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier      = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value         = state.age,
            onValueChange = { onUpdate { copy(age = it) } },
            label         = { Text("Age") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier      = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.weight(1f))

        Button(
            onClick  = onNext,
            enabled  = state.step1Valid,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Continue") }
    }
}

@Composable
fun OnboardingStep2(
    state: OnboardingUiState,
    onUpdate: (OnboardingUiState.() -> OnboardingUiState) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    data class ActivityOption(val value: String, val label: String, val subtitle: String)

    val options = listOf(
        ActivityOption("sedentary",          "Mostly sitting",      "Desk job, ≤1 sport/week"),
        ActivityOption("lightly_active",     "Lightly active",      "2–4 sport sessions/week"),
        ActivityOption("moderately_active",  "Moderately active",   "5+ sessions/week"),
    )

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("How active are you?  2/3", style = MaterialTheme.typography.headlineSmall)

        options.forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = state.activityLevel == option.value,
                        onClick  = { onUpdate { copy(activityLevel = option.value) } },
                    )
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = state.activityLevel == option.value,
                    onClick  = { onUpdate { copy(activityLevel = option.value) } },
                )
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    Text(option.label, style = MaterialTheme.typography.bodyLarge)
                    Text(option.subtitle, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        state.estimatedTdeeKcal?.let { tdee ->
            Text(
                "Estimated output: ~$tdee kcal/day (estimated)",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(Modifier.weight(1f))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Back") }
            Button(onClick = onNext, modifier = Modifier.weight(1f)) { Text("Continue") }
        }
    }
}

@Composable
fun OnboardingStep3(
    state: OnboardingUiState,
    onUpdate: (OnboardingUiState.() -> OnboardingUiState) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("How fast?  3/3", style = MaterialTheme.typography.headlineSmall)

        Text("${state.targetDeficit} kcal/day", style = MaterialTheme.typography.titleMedium)

        Slider(
            value         = state.targetDeficit.toFloat(),
            onValueChange = { onUpdate { copy(targetDeficit = it.roundToInt()) } },
            valueRange    = 0f..600f,
            steps         = 23, // 25 kcal steps: (600-0)/25 - 1 = 23 internal steps
            modifier      = Modifier.fillMaxWidth(),
        )

        Text("Recommended range: 250–400 kcal/day", style = MaterialTheme.typography.bodySmall)

        when {
            state.targetDeficit == 0 ->
                Text("Maintain weight — no active deficit")
            else -> {
                state.kgPerWeek?.let { Text("≈ ${"%.2f".format(it)} kg/week") }
                state.monthsToGoal?.let { Text("Goal reached in ~${"%.0f".format(it)} months") }
            }
        }

        if (state.targetDeficit > 500) {
            Text(
                "⚠ Above 500 kcal/day risks muscle loss.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        state.saveError?.let { error ->
            Snackbar(
                action = {
                    TextButton(onClick = { onUpdate { copy(saveError = null) } }) {
                        Text("Dismiss")
                    }
                }
            ) { Text(error) }
        }

        Spacer(Modifier.weight(1f))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Back") }
            Button(
                onClick  = onSave,
                enabled  = !state.isSaving,
                modifier = Modifier.weight(1f),
            ) {
                if (state.isSaving) CircularProgressIndicator(modifier = Modifier.size(18.dp))
                else Text("Done")
            }
        }
    }
}
```

- [ ] **Step 4: Add NeedsOnboarding branch to App.kt**

In `App.kt`, add the `NeedsOnboarding` branch to the `when (authState)` block, just before the `AuthState.LoggedIn` branch:

```kotlin
AuthState.NeedsOnboarding -> OnboardingScreen()
```

Also add the import at the top:

```kotlin
import org.branneman.health.ui.OnboardingScreen
```

The full `when` block in `App()` should look like:

```kotlin
when (authState) {
    AuthState.Loading -> { /* blank while token is checked */ }

    AuthState.LoggedOut -> LoginScreen(
        sessionExpired = false,
        isLoading = isLoggingIn,
        errorMessage = loginError,
        onSignIn = { username, password -> /* … existing code … */ }
    )

    AuthState.Expired -> LoginScreen(
        sessionExpired = true,
        isLoading = isLoggingIn,
        errorMessage = loginError,
        onSignIn = { username, password -> /* … existing code … */ }
    )

    AuthState.NeedsOnboarding -> OnboardingScreen()

    AuthState.LoggedIn -> MainNav(authViewModel)
}
```

- [ ] **Step 5: Run UI tests**

```bash
./gradlew :app:testDebugUnitTest --tests "org.branneman.health.ui.OnboardingScreenTest"
```

Expected: all tests PASS.

- [ ] **Step 6: Run the full test suite**

```bash
./gradlew :server:test :app:testDebugUnitTest
```

Expected: all tests PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/ui/OnboardingScreen.kt \
        app/src/test/kotlin/org/branneman/health/ui/OnboardingScreenTest.kt \
        app/src/main/kotlin/org/branneman/health/App.kt
git commit -m "feat: onboarding screen (steps 1-3) and auth routing"
```
