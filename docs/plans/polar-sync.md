# Polar Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement Polar AccessLink OAuth + hourly cron pull of daily energy and workouts, app-side download sync, Polar status in Settings, and a post-onboarding Connect Polar screen.

**Architecture:** `AuthState.NeedsPolarSetup` (new state) is shown after onboarding completes, driven by a `polar_setup_shown` DataStore flag; `PolarApiClient` is an injectable interface for testability; server stores AES-256-GCM encrypted tokens; Android deep link `branneman-health://polar/connected` signals completion via `HealthApplication.polarCallbackPending` flow.

**Tech Stack:** Ktor server with CIO http client, Exposed ORM, Flyway V7 migration, Jetpack Compose, Room, WorkManager, DataStore, javax.crypto for AES-256-GCM, Ktor MockEngine for tests.

---

## File Map

**Create (server):**
- `server/src/main/resources/db/migration/V7__polar_sync.sql`
- `server/src/main/kotlin/org/branneman/health/polar/TokenCipher.kt`
- `server/src/main/kotlin/org/branneman/health/polar/PolarApiClient.kt`
- `server/src/main/kotlin/org/branneman/health/polar/PolarSyncService.kt`
- `server/src/main/kotlin/org/branneman/health/polar/PolarConnectRoutes.kt`
- `server/src/test/kotlin/org/branneman/health/polar/TokenCipherTest.kt`
- `server/src/test/kotlin/org/branneman/health/polar/PolarApiClientTest.kt`
- `server/src/test/kotlin/org/branneman/health/polar/PolarSyncServiceTest.kt`
- `server/src/test/kotlin/org/branneman/health/PolarIntegrationTest.kt`

**Modify (server):**
- `server/build.gradle.kts` — add ktor-client-cio, ktor-client-mock, ktor-client-content-negotiation to deps
- `server/src/main/kotlin/org/branneman/health/data/Tables.kt` — add PolarAuth, PolarConnectState, polarExerciseId on Workout
- `server/src/main/kotlin/org/branneman/health/Application.kt` — add optional polar params, polar routes, cron, from-filter on /out/energy and /out/workouts
- `server/src/test/kotlin/org/branneman/health/SyncDownloadIntegrationTest.kt` — add from-param filter tests

**Create (shared):**
- `shared/src/commonMain/kotlin/org/branneman/health/PolarStatusDto.kt`

**Create (app):**
- `app/src/main/kotlin/org/branneman/health/polar/PolarPreferences.kt`
- `app/src/main/kotlin/org/branneman/health/polar/ConnectPolarScreen.kt`
- `app/src/main/kotlin/org/branneman/health/polar/ConnectPolarViewModel.kt`
- `app/src/main/kotlin/org/branneman/health/settings/SettingsViewModel.kt`
- `app/src/main/kotlin/org/branneman/health/sync/DailyEnergySyncService.kt`
- `app/src/main/kotlin/org/branneman/health/sync/WorkoutSyncService.kt`
- `app/src/test/kotlin/org/branneman/health/sync/DailyEnergySyncServiceTest.kt`
- `app/src/test/kotlin/org/branneman/health/sync/WorkoutSyncServiceTest.kt`

**Modify (app):**
- `app/src/main/AndroidManifest.xml` — add intent-filter for branneman-health://polar
- `app/src/main/kotlin/org/branneman/health/MainActivity.kt` — onNewIntent + onCreate deep link
- `app/src/main/kotlin/org/branneman/health/HealthApplication.kt` — add polarCallbackPending flow
- `app/src/main/kotlin/org/branneman/health/App.kt` — NeedsPolarSetup branch + deep link handler
- `app/src/main/kotlin/org/branneman/health/auth/AuthRepository.kt` — add NeedsPolarSetup state
- `app/src/main/kotlin/org/branneman/health/auth/AuthViewModel.kt` — add completePolarSetup()
- `app/src/main/kotlin/org/branneman/health/network/HealthApiClient.kt` — add getPolarConnectUrl, getPolarStatus
- `app/src/main/kotlin/org/branneman/health/sync/SyncWorker.kt` — add energy + workout sync
- `app/src/main/kotlin/org/branneman/health/ui/SettingsScreen.kt` — add Polar status section

**Modify (infra):**
- `ansible/templates/env.j2` — add POLAR_* env vars

---

## Task 1: V7 Postgres migration

**Files:**
- Create: `server/src/main/resources/db/migration/V7__polar_sync.sql`

- [ ] **Step 1: Write the migration**

```sql
-- V7__polar_sync.sql

-- Short-lived OAuth CSRF state tokens (one-time use, GC'd by cron)
CREATE TABLE polar_connect_state (
    state      TEXT        PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL
);

-- Polar's hashed exercise ID for idempotent upserts
ALTER TABLE workout ADD COLUMN polar_exercise_id TEXT;
ALTER TABLE workout ADD CONSTRAINT workout_user_polar_id
    UNIQUE (user_id, polar_exercise_id);

-- Unique health user per polar_auth row (allows upsert-by-user)
ALTER TABLE polar_auth
    ADD CONSTRAINT polar_auth_health_user_id_unique
    UNIQUE (health_user_id);
```

- [ ] **Step 2: Apply migration and verify**

```bash
docker compose up -d postgres
./gradlew :server:flywayMigrate     # or let tests run Flyway
psql $TEST_DATABASE_URL -c "\d polar_connect_state"
psql $TEST_DATABASE_URL -c "\d workout" | grep polar_exercise_id
```

Expected: `polar_connect_state` table exists; `workout` has `polar_exercise_id text` column and `workout_user_polar_id` unique constraint.

- [ ] **Step 3: Commit**

```bash
git add server/src/main/resources/db/migration/V7__polar_sync.sql
git commit -m "feat(server): V7 migration — polar_connect_state, workout.polar_exercise_id"
```

---

## Task 2: Update server build.gradle.kts

**Files:**
- Modify: `server/build.gradle.kts`

The `PolarApiClient` uses the Ktor HTTP client (CIO engine) to call Polar's API; tests use MockEngine.

- [ ] **Step 1: Add Ktor client dependencies**

In `server/build.gradle.kts`, inside `dependencies { ... }`, add after the existing `implementation` lines:

```kotlin
    implementation(libs.ktor.clientCore)
    implementation(libs.ktor.clientCio)
    implementation(libs.ktor.clientContentNegotiation)
    implementation(libs.ktor.clientSerializationJson)
    testImplementation(libs.ktor.clientMock)
```

- [ ] **Step 2: Verify compile**

```bash
./gradlew :server:compileKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add server/build.gradle.kts
git commit -m "chore(server): add Ktor client dependencies for Polar API calls"
```

---

## Task 3: Update Tables.kt — add PolarAuth, PolarConnectState, polarExerciseId

**Files:**
- Modify: `server/src/main/kotlin/org/branneman/health/data/Tables.kt`

- [ ] **Step 1: Add table objects**

Add to the END of `Tables.kt`:

```kotlin
object PolarAuth : Table("polar_auth") {
    val userId       = text("user_id")                            // Polar's x_user_id as TEXT
    val accessToken  = text("access_token")                       // AES-256-GCM encrypted
    val createdAt    = timestampWithTimeZone("created_at")
    val healthUserId = uuid("health_user_id").nullable()          // FK to users(id)
    override val primaryKey = PrimaryKey(userId)
}

object PolarConnectState : Table("polar_connect_state") {
    val state     = text("state")
    val userId    = uuid("user_id")
    val expiresAt = timestampWithTimeZone("expires_at")
    override val primaryKey = PrimaryKey(state)
}
```

- [ ] **Step 2: Add polarExerciseId to Workout**

Replace the existing `Workout` object:

```kotlin
object Workout : Table("workout") {
    val id              = uuid("id")
    val userId          = uuid("user_id")
    val date            = date("date")
    val type            = text("type")
    val durationSecs    = integer("duration_secs").nullable()
    val avgHr           = integer("avg_hr").nullable()
    val kcal            = integer("kcal").nullable()
    val polarExerciseId = text("polar_exercise_id").nullable()
    override val primaryKey = PrimaryKey(id)
}
```

- [ ] **Step 3: Verify compile**

```bash
./gradlew :server:compileKotlin
```

- [ ] **Step 4: Commit**

```bash
git add server/src/main/kotlin/org/branneman/health/data/Tables.kt
git commit -m "feat(server): add PolarAuth, PolarConnectState tables and workout.polarExerciseId"
```

---

## Task 4: PolarStatusDto in shared module

**Files:**
- Create: `shared/src/commonMain/kotlin/org/branneman/health/PolarStatusDto.kt`

- [ ] **Step 1: Write the DTO**

```kotlin
package org.branneman.health

import kotlinx.serialization.Serializable

@Serializable
data class PolarStatusDto(val connected: Boolean)
```

- [ ] **Step 2: Verify compile**

```bash
./gradlew :shared:compileKotlinJvm :shared:compileKotlinAndroid
```

- [ ] **Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/org/branneman/health/PolarStatusDto.kt
git commit -m "feat(shared): add PolarStatusDto"
```

---

## Task 5: TokenCipher + test

**Files:**
- Create: `server/src/main/kotlin/org/branneman/health/polar/TokenCipher.kt`
- Create: `server/src/test/kotlin/org/branneman/health/polar/TokenCipherTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// server/src/test/kotlin/org/branneman/health/polar/TokenCipherTest.kt
package org.branneman.health.polar

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFailsWith

class TokenCipherTest {

    private val key32 = ByteArray(32) { it.toByte() }
    private val cipher = TokenCipher(key32)

    @Test
    fun `encrypt then decrypt returns original plaintext`() {
        val plaintext = "polar-access-token-abc123"
        val encrypted = cipher.encrypt(plaintext)
        assertEquals(plaintext, cipher.decrypt(encrypted))
    }

    @Test
    fun `two encryptions of same plaintext produce different ciphertexts`() {
        val plaintext = "some-token"
        assertNotEquals(cipher.encrypt(plaintext), cipher.encrypt(plaintext))
    }

    @Test
    fun `fromBase64 constructs cipher from base64-encoded key`() {
        val base64Key = Base64.getEncoder().encodeToString(key32)
        val cipher2 = TokenCipher.fromBase64(base64Key)
        val plaintext = "test-token"
        assertEquals(plaintext, cipher2.decrypt(cipher2.encrypt(plaintext)))
    }

    @Test
    fun `tampered ciphertext throws on decrypt`() {
        val encrypted = cipher.encrypt("token")
        val bytes = Base64.getDecoder().decode(encrypted)
        bytes[bytes.size - 1] = bytes[bytes.size - 1].xor(0xFF.toByte())
        val tampered = Base64.getEncoder().encodeToString(bytes)
        assertFailsWith<javax.crypto.AEADBadTagException> {
            cipher.decrypt(tampered)
        }
    }

    @Test
    fun `key must be 32 bytes`() {
        assertFailsWith<IllegalArgumentException> { TokenCipher(ByteArray(16)) }
        assertFailsWith<IllegalArgumentException> { TokenCipher(ByteArray(64)) }
    }
}
```

- [ ] **Step 2: Run test — expect compile failure**

```bash
./gradlew :server:test --tests "org.branneman.health.polar.TokenCipherTest"
```

Expected: compilation error — `TokenCipher` not found.

- [ ] **Step 3: Implement TokenCipher**

```kotlin
// server/src/main/kotlin/org/branneman/health/polar/TokenCipher.kt
package org.branneman.health.polar

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class TokenCipher(private val key: ByteArray) {

    init {
        require(key.size == 32) { "AES-256 key must be exactly 32 bytes, got ${key.size}" }
    }

    fun encrypt(plaintext: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val cipherAndTag = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + cipherAndTag)
    }

    fun decrypt(encoded: String): String {
        val combined = Base64.getDecoder().decode(encoded)
        val iv = combined.copyOfRange(0, 12)
        val cipherAndTag = combined.copyOfRange(12, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(cipherAndTag), Charsets.UTF_8)
    }

    companion object {
        fun fromBase64(base64Key: String): TokenCipher =
            TokenCipher(Base64.getDecoder().decode(base64Key))
    }
}
```

- [ ] **Step 4: Run tests — expect all pass**

```bash
./gradlew :server:test --tests "org.branneman.health.polar.TokenCipherTest"
```

Expected: 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/org/branneman/health/polar/TokenCipher.kt \
        server/src/test/kotlin/org/branneman/health/polar/TokenCipherTest.kt
git commit -m "feat(server): TokenCipher — AES-256-GCM application-layer token encryption"
```

---

## Task 6: PolarApiClient interface + HttpPolarApiClient + test

**Files:**
- Create: `server/src/main/kotlin/org/branneman/health/polar/PolarApiClient.kt`
- Create: `server/src/test/kotlin/org/branneman/health/polar/PolarApiClientTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
// server/src/test/kotlin/org/branneman/health/polar/PolarApiClientTest.kt
package org.branneman.health.polar

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.util.UUID
import kotlin.test.*

class PolarApiClientTest {

    private fun client(handler: MockRequestHandler) = HttpPolarApiClient(
        httpClient = HttpClient(MockEngine(handler)) { install(ContentNegotiation) { json() } },
        clientId = "test-client-id",
        clientSecret = "test-client-secret",
        redirectUri = "https://example.com/polar/callback",
    )

    @Test
    fun `buildAuthorizationUrl contains client_id, redirect_uri and state`() {
        val c = client { _ -> respond("", HttpStatusCode.OK) }
        val url = c.buildAuthorizationUrl("abc123state")
        assertTrue(url.contains("client_id=test-client-id"))
        assertTrue(url.contains("state=abc123state"))
        assertTrue(url.contains("redirect_uri="))
        assertTrue(url.contains("accesslink.read_all"))
    }

    @Test
    fun `exchangeCode sends Basic auth and form body, returns token and xUserId`() = runBlocking {
        val c = client { req ->
            assertEquals("POST", req.method.value)
            val authHeader = req.headers[HttpHeaders.Authorization] ?: ""
            assertTrue(authHeader.startsWith("Basic "))
            respond(
                """{"access_token":"tok123","token_type":"bearer","expires_in":31535999,"x_user_id":99}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val result = c.exchangeCode("auth-code-xyz")
        assertEquals("tok123", result.accessToken)
        assertEquals(99L, result.xUserId)
    }

    @Test
    fun `exchangeCode throws PolarRateLimitException on 429`() = runBlocking {
        val c = client { _ -> respond("", HttpStatusCode.TooManyRequests) }
        assertFailsWith<PolarRateLimitException> { c.exchangeCode("code") }
        Unit
    }

    @Test
    fun `registerUser succeeds on 200`() = runBlocking {
        var body = ""
        val c = client { req ->
            body = req.body.toByteArray().decodeToString()
            respond("", HttpStatusCode.OK)
        }
        c.registerUser("tok", UUID.fromString("00000000-0000-0000-0000-000000000001"))
        assertTrue(body.contains("00000000-0000-0000-0000-000000000001"))
    }

    @Test
    fun `registerUser does not throw on 409 Conflict`() = runBlocking {
        val c = client { _ -> respond("", HttpStatusCode.Conflict) }
        c.registerUser("tok", UUID.randomUUID())  // should not throw
        Unit
    }

    @Test
    fun `getActivities maps startTime to date, calories to totalKcal, activeCalories to activeKcal`() = runBlocking {
        val c = client { _ ->
            respond(
                """{"activities":[{"start_time":"2026-06-10T06:00:00","calories":2100,"active_calories":400,"steps":8500}]}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val result = c.getActivities("tok", LocalDate.of(2026, 6, 10), LocalDate.of(2026, 6, 10))
        assertEquals(1, result.size)
        assertEquals(LocalDate.of(2026, 6, 10), result[0].date)
        assertEquals(2100, result[0].totalKcal)
        assertEquals(400, result[0].activeKcal)
        assertEquals(8500, result[0].steps)
    }

    @Test
    fun `getActivities returns empty list on 204`() = runBlocking {
        val c = client { _ -> respond("", HttpStatusCode.NoContent) }
        assertEquals(emptyList(), c.getActivities("tok", LocalDate.now(), LocalDate.now()))
    }

    @Test
    fun `getActivities throws PolarRateLimitException on 429`() = runBlocking {
        val c = client { _ -> respond("", HttpStatusCode.TooManyRequests) }
        assertFailsWith<PolarRateLimitException> { c.getActivities("tok", LocalDate.now(), LocalDate.now()) }
        Unit
    }

    @Test
    fun `getExercises maps id, sport, ISO-8601 duration to seconds, heart_rate average`() = runBlocking {
        val c = client { _ ->
            respond(
                """{"exercises":[{"id":"2AC312F","start_time":"2026-06-09T18:00:00","sport":"RUNNING","duration":"PT1H5M30S","calories":450,"heart_rate":{"average":142}}]}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val result = c.getExercises("tok")
        assertEquals(1, result.size)
        assertEquals("2AC312F", result[0].polarId)
        assertEquals(LocalDate.of(2026, 6, 9), result[0].date)
        assertEquals("RUNNING", result[0].sport)
        assertEquals(3930, result[0].durationSecs)  // 1h5m30s = 3930s
        assertEquals(450, result[0].kcal)
        assertEquals(142, result[0].avgHr)
    }

    @Test
    fun `getExercises returns empty list on 204`() = runBlocking {
        val c = client { _ -> respond("", HttpStatusCode.NoContent) }
        assertEquals(emptyList(), c.getExercises("tok"))
    }
}
```

- [ ] **Step 2: Run — expect compile failure**

```bash
./gradlew :server:test --tests "org.branneman.health.polar.PolarApiClientTest"
```

- [ ] **Step 3: Implement PolarApiClient**

```kotlin
// server/src/main/kotlin/org/branneman/health/polar/PolarApiClient.kt
package org.branneman.health.polar

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.LocalDate
import java.util.Base64
import java.util.UUID

data class PolarTokenResponse(val accessToken: String, val xUserId: Long)
data class PolarActivity(val date: LocalDate, val totalKcal: Int, val activeKcal: Int, val steps: Int?)
data class PolarExercise(
    val polarId: String, val date: LocalDate, val sport: String,
    val durationSecs: Int?, val kcal: Int?, val avgHr: Int?,
)

class PolarRateLimitException : Exception("Polar API rate limit exceeded")

interface PolarApiClient {
    fun buildAuthorizationUrl(state: String): String
    suspend fun exchangeCode(code: String): PolarTokenResponse
    suspend fun registerUser(accessToken: String, memberIdUuid: UUID)
    suspend fun getActivities(accessToken: String, from: LocalDate, to: LocalDate): List<PolarActivity>
    suspend fun getExercises(accessToken: String): List<PolarExercise>
}

// --- Internal JSON shapes ---

@Serializable
private data class PolarTokenJson(
    @SerialName("access_token") val accessToken: String,
    @SerialName("x_user_id")    val xUserId: Long,
)

@Serializable
private data class PolarActivitiesJson(
    @SerialName("activities") val activities: List<PolarActivityJson> = emptyList(),
)

@Serializable
private data class PolarActivityJson(
    @SerialName("start_time")       val startTime: String,
    @SerialName("calories")         val calories: Int,
    @SerialName("active_calories")  val activeCalories: Int,
    @SerialName("steps")            val steps: Int? = null,
)

@Serializable
private data class PolarExercisesJson(
    @SerialName("exercises") val exercises: List<PolarExerciseJson> = emptyList(),
)

@Serializable
private data class PolarExerciseJson(
    @SerialName("id")         val id: String,
    @SerialName("start_time") val startTime: String,
    @SerialName("sport")      val sport: String,
    @SerialName("duration")   val duration: String? = null,
    @SerialName("calories")   val calories: Int? = null,
    @SerialName("heart_rate") val heartRate: HeartRateJson? = null,
)

@Serializable
private data class HeartRateJson(@SerialName("average") val average: Int? = null)

// --- Production implementation ---

private val lenientJson = Json { ignoreUnknownKeys = true }

class HttpPolarApiClient(
    private val httpClient: HttpClient,
    private val clientId: String,
    private val clientSecret: String,
    private val redirectUri: String,
) : PolarApiClient {

    override fun buildAuthorizationUrl(state: String): String {
        val encodedUri = java.net.URLEncoder.encode(redirectUri, "UTF-8")
        return "https://flow.polar.com/oauth2/authorization" +
               "?response_type=code&client_id=$clientId" +
               "&redirect_uri=$encodedUri&scope=accesslink.read_all&state=$state"
    }

    override suspend fun exchangeCode(code: String): PolarTokenResponse {
        val credentials = Base64.getEncoder().encodeToString("$clientId:$clientSecret".toByteArray())
        val response = httpClient.post("https://polarremote.com/v2/oauth2/token") {
            header(HttpHeaders.Authorization, "Basic $credentials")
            header(HttpHeaders.Accept, "application/json;charset=UTF-8")
            setBody(FormDataContent(Parameters.build {
                append("grant_type", "authorization_code")
                append("code", code)
                append("redirect_uri", redirectUri)
            }))
        }
        if (response.status == HttpStatusCode.TooManyRequests) throw PolarRateLimitException()
        check(response.status.isSuccess()) { "Token exchange failed: ${response.status.value}" }
        val body = lenientJson.decodeFromString<PolarTokenJson>(response.bodyAsText())
        return PolarTokenResponse(body.accessToken, body.xUserId)
    }

    override suspend fun registerUser(accessToken: String, memberIdUuid: UUID) {
        val response = httpClient.post("https://www.polaraccesslink.com/v3/users") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody("""{"member-id":"$memberIdUuid"}""")
        }
        if (response.status != HttpStatusCode.Conflict) {
            check(response.status.isSuccess()) { "Register user failed: ${response.status.value}" }
        }
    }

    override suspend fun getActivities(accessToken: String, from: LocalDate, to: LocalDate): List<PolarActivity> {
        val response = httpClient.get("https://www.polaraccesslink.com/v3/users/activities") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            parameter("from", from.toString())
            parameter("to", to.toString())
        }
        if (response.status == HttpStatusCode.TooManyRequests) throw PolarRateLimitException()
        if (response.status == HttpStatusCode.NoContent) return emptyList()
        check(response.status.isSuccess()) { "getActivities failed: ${response.status.value}" }
        val body = lenientJson.decodeFromString<PolarActivitiesJson>(response.bodyAsText())
        return body.activities.map {
            PolarActivity(
                date       = LocalDate.parse(it.startTime.take(10)),
                totalKcal  = it.calories,
                activeKcal = it.activeCalories,
                steps      = it.steps,
            )
        }
    }

    override suspend fun getExercises(accessToken: String): List<PolarExercise> {
        val response = httpClient.get("https://www.polaraccesslink.com/v3/exercises") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
        }
        if (response.status == HttpStatusCode.TooManyRequests) throw PolarRateLimitException()
        if (response.status == HttpStatusCode.NoContent) return emptyList()
        check(response.status.isSuccess()) { "getExercises failed: ${response.status.value}" }
        val body = lenientJson.decodeFromString<PolarExercisesJson>(response.bodyAsText())
        return body.exercises.map {
            PolarExercise(
                polarId     = it.id,
                date        = LocalDate.parse(it.startTime.take(10)),
                sport       = it.sport,
                durationSecs = it.duration?.let { d -> parseIso8601DurationSecs(d) },
                kcal        = it.calories,
                avgHr       = it.heartRate?.average,
            )
        }
    }

    private fun parseIso8601DurationSecs(d: String): Int =
        runCatching { Duration.parse(d).seconds.toInt() }.getOrDefault(0)
}
```

- [ ] **Step 4: Run tests — expect all pass**

```bash
./gradlew :server:test --tests "org.branneman.health.polar.PolarApiClientTest"
```

Expected: 10 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/org/branneman/health/polar/PolarApiClient.kt \
        server/src/test/kotlin/org/branneman/health/polar/PolarApiClientTest.kt
git commit -m "feat(server): PolarApiClient interface + HttpPolarApiClient with tests"
```

---

## Task 7: PolarSyncService + test

**Files:**
- Create: `server/src/main/kotlin/org/branneman/health/polar/PolarSyncService.kt`
- Create: `server/src/test/kotlin/org/branneman/health/polar/PolarSyncServiceTest.kt`

- [ ] **Step 1: Write FakePolarApiClient and failing tests**

```kotlin
// server/src/test/kotlin/org/branneman/health/polar/PolarSyncServiceTest.kt
package org.branneman.health.polar

import org.branneman.health.TestDatabase
import org.branneman.health.auth.Users
import org.branneman.health.data.DailyEnergy
import org.branneman.health.data.PolarAuth
import org.branneman.health.data.PolarConnectState
import org.branneman.health.data.Workout
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.*

class FakePolarApiClient(
    private val activities: List<PolarActivity> = emptyList(),
    private val exercises: List<PolarExercise> = emptyList(),
    private val throwRateLimitForToken: String? = null,
) : PolarApiClient {
    override fun buildAuthorizationUrl(state: String) = "https://example.com?state=$state"
    override suspend fun exchangeCode(code: String) = PolarTokenResponse("tok", 1L)
    override suspend fun registerUser(accessToken: String, memberIdUuid: UUID) {}
    override suspend fun getActivities(accessToken: String, from: LocalDate, to: LocalDate): List<PolarActivity> {
        if (accessToken == throwRateLimitForToken) throw PolarRateLimitException()
        return activities
    }
    override suspend fun getExercises(accessToken: String): List<PolarExercise> {
        if (accessToken == throwRateLimitForToken) throw PolarRateLimitException()
        return exercises
    }
}

class PolarSyncServiceTest {
    companion object {
        private val ds = TestDatabase.dataSource
        private val userId = UUID.fromString("00000000-0000-0000-0000-000000000099")
        private val userId2 = UUID.fromString("00000000-0000-0000-0000-000000000098")
        private val testKey = ByteArray(32) { 0x42 }
        private val cipher = TokenCipher(testKey)

        init {
            Database.connect(ds)
            transaction {
                Users.deleteWhere { Users.id eq userId }
                Users.deleteWhere { Users.id eq userId2 }
                Users.insert { it[id] = userId; it[username] = "polar-sync-test@test.local"; it[passwordHash] = BCrypt.hashpw("x", BCrypt.gensalt(4)) }
                Users.insert { it[id] = userId2; it[username] = "polar-sync-test2@test.local"; it[passwordHash] = BCrypt.hashpw("x", BCrypt.gensalt(4)) }
            }
        }
    }

    private fun insertPolarAuth(hUserId: UUID, plainToken: String) {
        transaction {
            PolarAuth.deleteWhere { Op.build { PolarAuth.healthUserId eq hUserId } }
            PolarAuth.insert {
                it[PolarAuth.userId]       = "polar-${hUserId}"
                it[PolarAuth.accessToken]  = cipher.encrypt(plainToken)
                it[PolarAuth.healthUserId] = hUserId
                it[PolarAuth.createdAt]    = OffsetDateTime.now()
            }
        }
    }

    private fun cleanUp(hUserId: UUID) {
        transaction {
            DailyEnergy.deleteWhere { Op.build { DailyEnergy.userId eq hUserId } }
            Workout.deleteWhere { Op.build { Workout.userId eq hUserId } }
            PolarAuth.deleteWhere { Op.build { PolarAuth.healthUserId eq hUserId } }
        }
    }

    @BeforeTest fun setUp() { cleanUp(userId); cleanUp(userId2) }

    private fun service(activities: List<PolarActivity> = emptyList(), exercises: List<PolarExercise> = emptyList(), rateLimitToken: String? = null) =
        PolarSyncService(FakePolarApiClient(activities, exercises, rateLimitToken), ds, cipher)

    @Test
    fun `activities are upserted with correct field mapping`() = kotlinx.coroutines.runBlocking {
        insertPolarAuth(userId, "tok-a")
        val activity = PolarActivity(LocalDate.of(2026, 6, 10), totalKcal = 2100, activeKcal = 400, steps = 8500)
        service(activities = listOf(activity)).syncAll()

        val row = transaction {
            DailyEnergy.selectAll().where { (DailyEnergy.userId eq userId) and (DailyEnergy.date eq LocalDate.of(2026, 6, 10)) }.single()
        }
        assertEquals(2100, row[DailyEnergy.totalKcal])
        assertEquals(400, row[DailyEnergy.activeKcal])
        assertEquals(1700, row[DailyEnergy.bmrKcal])
        assertEquals(8500, row[DailyEnergy.steps])
        assertEquals("polar", row[DailyEnergy.dataSource])
    }

    @Test
    fun `syncing same date twice produces one row`() = kotlinx.coroutines.runBlocking {
        insertPolarAuth(userId, "tok-b")
        val activity = PolarActivity(LocalDate.of(2026, 6, 11), 2000, 350, null)
        service(listOf(activity)).syncAll()
        service(listOf(activity)).syncAll()
        val count = transaction { DailyEnergy.selectAll().where { DailyEnergy.userId eq userId }.count() }
        assertEquals(1L, count)
    }

    @Test
    fun `updated total for same date overwrites`() = kotlinx.coroutines.runBlocking {
        insertPolarAuth(userId, "tok-c")
        service(listOf(PolarActivity(LocalDate.of(2026, 6, 12), 1800, 300, null))).syncAll()
        service(listOf(PolarActivity(LocalDate.of(2026, 6, 12), 2200, 500, null))).syncAll()
        val row = transaction {
            DailyEnergy.selectAll().where { (DailyEnergy.userId eq userId) and (DailyEnergy.date eq LocalDate.of(2026, 6, 12)) }.single()
        }
        assertEquals(2200, row[DailyEnergy.totalKcal])
    }

    @Test
    fun `same exercise fetched twice produces one workout row`() = kotlinx.coroutines.runBlocking {
        insertPolarAuth(userId, "tok-d")
        val exercise = PolarExercise("EX001", LocalDate.of(2026, 6, 9), "RUNNING", 3600, 450, 145)
        service(exercises = listOf(exercise)).syncAll()
        service(exercises = listOf(exercise)).syncAll()
        val count = transaction { Workout.selectAll().where { Workout.userId eq userId }.count() }
        assertEquals(1L, count)
    }

    @Test
    fun `rate limit on one user skips that user but processes others`() = kotlinx.coroutines.runBlocking {
        insertPolarAuth(userId, "rate-limited-token")
        insertPolarAuth(userId2, "normal-token")
        val activity = PolarActivity(LocalDate.of(2026, 6, 13), 2000, 400, null)
        service(activities = listOf(activity), rateLimitToken = "rate-limited-token").syncAll()

        val user1Count = transaction { DailyEnergy.selectAll().where { DailyEnergy.userId eq userId }.count() }
        val user2Count = transaction { DailyEnergy.selectAll().where { DailyEnergy.userId eq userId2 }.count() }
        assertEquals(0L, user1Count)
        assertEquals(1L, user2Count)
    }

    @Test
    fun `expired polar_connect_state rows are GC-ed`() = kotlinx.coroutines.runBlocking {
        transaction {
            PolarConnectState.insert {
                it[state] = "expired-state-001"
                it[PolarConnectState.userId] = userId
                it[expiresAt] = OffsetDateTime.now().minusHours(1)
            }
        }
        service().syncAll()
        val count = transaction {
            PolarConnectState.selectAll().where { PolarConnectState.state eq "expired-state-001" }.count()
        }
        assertEquals(0L, count)
    }
}
```

- [ ] **Step 2: Run — expect compile failure**

```bash
./gradlew :server:test --tests "org.branneman.health.polar.PolarSyncServiceTest"
```

- [ ] **Step 3: Implement PolarSyncService**

```kotlin
// server/src/main/kotlin/org/branneman/health/polar/PolarSyncService.kt
package org.branneman.health.polar

import org.branneman.health.data.DailyEnergy
import org.branneman.health.data.PolarAuth
import org.branneman.health.data.PolarConnectState
import org.branneman.health.data.Workout
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

private data class PolarAuthRow(
    val healthUserId: UUID,
    val polarUserId: String,
    val encryptedToken: String,
)

class PolarSyncService(
    private val polarClient: PolarApiClient,
    @Suppress("UnusedPrivateMember") private val dataSource: DataSource,
    private val cipher: TokenCipher,
) {
    suspend fun syncAll() {
        transaction {
            PolarConnectState.deleteWhere { PolarConnectState.expiresAt less OffsetDateTime.now() }
        }

        val users = transaction {
            PolarAuth.selectAll()
                .where { PolarAuth.healthUserId.isNotNull() }
                .mapNotNull { row ->
                    val huid = row[PolarAuth.healthUserId] ?: return@mapNotNull null
                    PolarAuthRow(huid, row[PolarAuth.userId], row[PolarAuth.accessToken])
                }
        }

        users.forEach { row ->
            try {
                val token = cipher.decrypt(row.encryptedToken)
                syncUser(row.healthUserId, token)
            } catch (_: PolarRateLimitException) {
                // skip this cycle for this user
            } catch (_: Exception) {
                // log would go here; skip user, do not rethrow
            }
        }
    }

    private suspend fun syncUser(healthUserId: UUID, accessToken: String) {
        val today = LocalDate.now()

        val activities = polarClient.getActivities(accessToken, today.minusDays(2), today)
        transaction {
            activities.forEach { a ->
                DailyEnergy.upsert {
                    it[DailyEnergy.userId]     = healthUserId
                    it[DailyEnergy.date]       = a.date
                    it[DailyEnergy.bmrKcal]    = a.totalKcal - a.activeKcal
                    it[DailyEnergy.activeKcal] = a.activeKcal
                    it[DailyEnergy.totalKcal]  = a.totalKcal
                    it[DailyEnergy.steps]      = a.steps
                    it[DailyEnergy.dataSource] = "polar"
                }
            }
        }

        val exercises = polarClient.getExercises(accessToken)
        transaction {
            exercises.forEach { e ->
                val existing = Workout.selectAll()
                    .where { (Workout.userId eq healthUserId) and (Workout.polarExerciseId eq e.polarId) }
                    .singleOrNull()
                if (existing != null) {
                    Workout.update({ (Workout.userId eq healthUserId) and (Workout.polarExerciseId eq e.polarId) }) {
                        it[Workout.date]         = e.date
                        it[Workout.type]         = e.sport
                        it[Workout.durationSecs] = e.durationSecs
                        it[Workout.avgHr]        = e.avgHr
                        it[Workout.kcal]         = e.kcal
                    }
                } else {
                    Workout.insert {
                        it[Workout.id]              = UUID.randomUUID()
                        it[Workout.userId]          = healthUserId
                        it[Workout.date]            = e.date
                        it[Workout.type]            = e.sport
                        it[Workout.durationSecs]    = e.durationSecs
                        it[Workout.avgHr]           = e.avgHr
                        it[Workout.kcal]            = e.kcal
                        it[Workout.polarExerciseId] = e.polarId
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 4: Run tests — expect all pass**

```bash
./gradlew :server:test --tests "org.branneman.health.polar.PolarSyncServiceTest"
```

Expected: 6 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/org/branneman/health/polar/PolarSyncService.kt \
        server/src/test/kotlin/org/branneman/health/polar/PolarSyncServiceTest.kt
git commit -m "feat(server): PolarSyncService — hourly pull of activities and exercises"
```

---

## Task 8: PolarConnectRoutes + Application.module() wiring + integration test

**Files:**
- Create: `server/src/main/kotlin/org/branneman/health/polar/PolarConnectRoutes.kt`
- Modify: `server/src/main/kotlin/org/branneman/health/Application.kt`
- Create: `server/src/test/kotlin/org/branneman/health/PolarIntegrationTest.kt`

- [ ] **Step 1: Write failing integration tests**

```kotlin
// server/src/test/kotlin/org/branneman/health/PolarIntegrationTest.kt
package org.branneman.health

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.branneman.health.auth.Users
import org.branneman.health.data.PolarAuth
import org.branneman.health.data.PolarConnectState
import org.branneman.health.polar.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID
import kotlin.test.*

class PolarIntegrationTest {
    companion object {
        private val ds = TestDatabase.dataSource
        private val testUserId = UUID.fromString("00000000-0000-0000-0000-000000000009")
        private const val TEST_EMAIL = "polar-test@test.local"
        private const val TEST_PASSWORD = "testpassword"
        private val TEST_HASH = BCrypt.hashpw(TEST_PASSWORD, BCrypt.gensalt(4))
        private val testKey = ByteArray(32) { 0x42 }
        private val testCipher = TokenCipher(testKey)
        private val fakePolar = FakePolarApiClient()

        init {
            Database.connect(ds)
            transaction {
                Users.deleteWhere { Users.id eq testUserId }
                Users.insert { it[id] = testUserId; it[username] = TEST_EMAIL; it[passwordHash] = TEST_HASH }
            }
        }
    }

    private fun appTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(ds, fakePolar, testCipher) }
        block()
    }

    private suspend fun ApplicationTestBuilder.login(): String {
        val r = client.post("/auth/token") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$TEST_EMAIL","password":"$TEST_PASSWORD"}""")
        }
        return Json.parseToJsonElement(r.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content
    }

    @BeforeTest fun cleanUp() {
        transaction {
            PolarAuth.deleteWhere { Op.build { PolarAuth.healthUserId eq testUserId } }
            PolarConnectState.deleteWhere { Op.build { PolarConnectState.userId eq testUserId } }
        }
    }

    @Test
    fun `GET polar-connect-url returns url with state and client_id, requires auth`() = appTest {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/polar/connect-url").status)

        val token = login()
        val r = client.get("/polar/connect-url") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, r.status)
        val url = Json.parseToJsonElement(r.bodyAsText()).jsonObject["url"]!!.jsonPrimitive.content
        assertTrue(url.contains("state="))
        assertTrue(url.contains("client_id="))
    }

    @Test
    fun `GET polar-callback with valid state stores polar_auth row and returns HTML with deep link`() = appTest {
        val token = login()
        client.get("/polar/connect-url") { header(HttpHeaders.Authorization, "Bearer $token") }

        val state = transaction {
            PolarConnectState.selectAll().where { PolarConnectState.userId eq testUserId }.single()[PolarConnectState.state]
        }

        val r = client.get("/polar/callback?code=test-code&state=$state")
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("branneman-health://polar/connected"))

        val connected = transaction {
            PolarAuth.selectAll().where { PolarAuth.healthUserId eq testUserId }.count() > 0
        }
        assertTrue(connected)
    }

    @Test
    fun `GET polar-callback with expired state returns 400, no row written`() = appTest {
        transaction {
            PolarConnectState.insert {
                it[state] = "expired-state-xyz"
                it[PolarConnectState.userId] = testUserId
                it[expiresAt] = OffsetDateTime.now().minusMinutes(1)
            }
        }
        val r = client.get("/polar/callback?code=x&state=expired-state-xyz")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        val connected = transaction { PolarAuth.selectAll().where { PolarAuth.healthUserId eq testUserId }.count() > 0 }
        assertFalse(connected)
    }

    @Test
    fun `GET polar-callback with replayed state returns 400`() = appTest {
        val token = login()
        client.get("/polar/connect-url") { header(HttpHeaders.Authorization, "Bearer $token") }
        val state = transaction {
            PolarConnectState.selectAll().where { PolarConnectState.userId eq testUserId }.single()[PolarConnectState.state]
        }
        client.get("/polar/callback?code=test-code&state=$state")
        val r = client.get("/polar/callback?code=test-code&state=$state")
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test
    fun `GET polar-status returns false before OAuth, true after`() = appTest {
        val token = login()
        var r = client.get("/polar/status") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertFalse(Json.parseToJsonElement(r.bodyAsText()).jsonObject["connected"]!!.jsonPrimitive.boolean)

        client.get("/polar/connect-url") { header(HttpHeaders.Authorization, "Bearer $token") }
        val state = transaction {
            PolarConnectState.selectAll().where { PolarConnectState.userId eq testUserId }.single()[PolarConnectState.state]
        }
        client.get("/polar/callback?code=x&state=$state")

        r = client.get("/polar/status") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertTrue(Json.parseToJsonElement(r.bodyAsText()).jsonObject["connected"]!!.jsonPrimitive.boolean)
    }
}
```

Note: `FakePolarApiClient` is already defined in `PolarSyncServiceTest.kt`. Move it to a shared test fixture — add a new file:

```kotlin
// server/src/test/kotlin/org/branneman/health/polar/FakePolarApiClient.kt
package org.branneman.health.polar

import java.time.LocalDate
import java.util.UUID

class FakePolarApiClient(
    private val activities: List<PolarActivity> = emptyList(),
    private val exercises: List<PolarExercise> = emptyList(),
    private val throwRateLimitForToken: String? = null,
    private val tokenToReturn: String = "fake-polar-token",
    private val xUserIdToReturn: Long = 12345L,
) : PolarApiClient {
    val registeredUsers = mutableListOf<UUID>()
    val exchangedCodes  = mutableListOf<String>()

    override fun buildAuthorizationUrl(state: String) =
        "https://flow.polar.com/oauth2/authorization?client_id=test-client-id&state=$state"

    override suspend fun exchangeCode(code: String): PolarTokenResponse {
        exchangedCodes.add(code)
        return PolarTokenResponse(tokenToReturn, xUserIdToReturn)
    }

    override suspend fun registerUser(accessToken: String, memberIdUuid: UUID) {
        registeredUsers.add(memberIdUuid)
    }

    override suspend fun getActivities(accessToken: String, from: LocalDate, to: LocalDate): List<PolarActivity> {
        if (accessToken == throwRateLimitForToken) throw PolarRateLimitException()
        return activities
    }

    override suspend fun getExercises(accessToken: String): List<PolarExercise> {
        if (accessToken == throwRateLimitForToken) throw PolarRateLimitException()
        return exercises
    }
}
```

Then update `PolarSyncServiceTest.kt` to remove its local `FakePolarApiClient` and import from this file.

- [ ] **Step 2: Run — expect compile failure**

```bash
./gradlew :server:test --tests "org.branneman.health.PolarIntegrationTest"
```

- [ ] **Step 3: Implement PolarConnectRoutes**

```kotlin
// server/src/main/kotlin/org/branneman/health/polar/PolarConnectRoutes.kt
package org.branneman.health.polar

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.branneman.health.PolarStatusDto
import org.branneman.health.data.PolarAuth
import org.branneman.health.data.PolarConnectState
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.util.UUID

fun Route.polarRoutes(polarApiClient: PolarApiClient, cipher: TokenCipher) {
    authenticate("api") {
        get("/polar/connect-url") {
            val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
            val stateBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val state = stateBytes.joinToString("") { "%02x".format(it) }

            transaction {
                PolarConnectState.insert {
                    it[PolarConnectState.state]     = state
                    it[PolarConnectState.userId]    = userId
                    it[PolarConnectState.expiresAt] = OffsetDateTime.now().plusMinutes(15)
                }
            }

            call.respond(mapOf("url" to polarApiClient.buildAuthorizationUrl(state)))
        }

        get("/polar/status") {
            val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
            val connected = transaction {
                PolarAuth.selectAll()
                    .where { PolarAuth.healthUserId eq userId }
                    .count() > 0
            }
            call.respond(PolarStatusDto(connected))
        }
    }

    get("/polar/callback") {
        val code  = call.parameters["code"]
        val state = call.parameters["state"]

        if (code == null || state == null) {
            call.respondText(errorHtml("Missing parameters"), ContentType.Text.Html, HttpStatusCode.BadRequest)
            return@get
        }

        val userId: UUID? = transaction {
            val row = PolarConnectState.selectAll()
                .where { (PolarConnectState.state eq state) and (PolarConnectState.expiresAt greater OffsetDateTime.now()) }
                .singleOrNull()
                ?: return@transaction null
            PolarConnectState.deleteWhere { Op.build { PolarConnectState.state eq state } }
            row[PolarConnectState.userId]
        }

        if (userId == null) {
            call.respondText(errorHtml("Invalid or expired state"), ContentType.Text.Html, HttpStatusCode.BadRequest)
            return@get
        }

        val tokenResponse = runCatching { polarApiClient.exchangeCode(code) }.getOrElse {
            call.respondText(errorHtml("Token exchange failed"), ContentType.Text.Html, HttpStatusCode.InternalServerError)
            return@get
        }

        runCatching { polarApiClient.registerUser(tokenResponse.accessToken, userId) }

        val encryptedToken = cipher.encrypt(tokenResponse.accessToken)
        transaction {
            PolarAuth.deleteWhere { Op.build { PolarAuth.healthUserId eq userId } }
            PolarAuth.insert {
                it[PolarAuth.userId]       = tokenResponse.xUserId.toString()
                it[PolarAuth.accessToken]  = encryptedToken
                it[PolarAuth.healthUserId] = userId
                it[PolarAuth.createdAt]    = OffsetDateTime.now()
            }
        }

        call.respondText(successHtml(), ContentType.Text.Html)
    }
}

private fun successHtml() = """
<!DOCTYPE html>
<html>
<head><meta charset="utf-8"><title>Polar Connected</title></head>
<body>
  <p>Polar connected successfully. Returning to the Health app…</p>
  <a href="branneman-health://polar/connected">Open Health App</a>
  <script>
    setTimeout(function () { window.location = 'branneman-health://polar/connected'; }, 500);
  </script>
</body>
</html>
""".trimIndent()

private fun errorHtml(message: String) = """
<!DOCTYPE html>
<html>
<head><meta charset="utf-8"><title>Error</title></head>
<body><p>An error occurred. Please return to the app and try again.</p></body>
</html>
""".trimIndent()
```

- [ ] **Step 4: Update Application.module() signature and wire polar routes**

Modify `server/src/main/kotlin/org/branneman/health/Application.kt`:

**Change the no-arg `Application.module()` entry point:**

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

    val clientId      = System.getenv("POLAR_CLIENT_ID")             ?: ""
    val clientSecret  = System.getenv("POLAR_CLIENT_SECRET")         ?: ""
    val redirectUri   = System.getenv("POLAR_REDIRECT_URI")          ?: ""
    val encKeyBase64  = System.getenv("POLAR_TOKEN_ENCRYPTION_KEY")

    val polarApiClient: PolarApiClient? = if (clientId.isNotEmpty() && clientSecret.isNotEmpty()) {
        HttpPolarApiClient(
            httpClient    = io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO) {
                install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                    io.ktor.serialization.kotlinx.json.json()
                }
            },
            clientId      = clientId,
            clientSecret  = clientSecret,
            redirectUri   = redirectUri,
        )
    } else null

    val polarCipher: TokenCipher? = encKeyBase64?.let { TokenCipher.fromBase64(it) }

    module(dataSource, polarApiClient, polarCipher)
}
```

**Change the testable overload signature:**

```kotlin
fun Application.module(
    dataSource: javax.sql.DataSource,
    polarApiClient: PolarApiClient? = null,
    polarCipher: TokenCipher? = null,
) {
```

**At the end of the `routing { }` block** (after the last existing `authenticate("api") { ... }` block), add:

```kotlin
        if (polarApiClient != null && polarCipher != null) {
            polarRoutes(polarApiClient, polarCipher)
        }
```

**After the `routing { }` block**, add the hourly cron:

```kotlin
    if (polarApiClient != null && polarCipher != null) {
        val syncService = PolarSyncService(polarApiClient, dataSource, polarCipher)
        launch {
            while (true) {
                kotlinx.coroutines.delay(kotlin.time.Duration.Companion.hours(1))
                runCatching { syncService.syncAll() }
            }
        }
    }
```

Add the required imports to `Application.kt`:
```kotlin
import org.branneman.health.polar.HttpPolarApiClient
import org.branneman.health.polar.PolarApiClient
import org.branneman.health.polar.PolarConnectRoutes
import org.branneman.health.polar.PolarSyncService
import org.branneman.health.polar.TokenCipher
import org.branneman.health.polar.polarRoutes
import kotlinx.coroutines.launch
```

- [ ] **Step 5: Run integration tests — expect all pass**

```bash
./gradlew :server:test --tests "org.branneman.health.PolarIntegrationTest"
```

Expected: 5 tests PASS.

- [ ] **Step 6: Run all server tests — expect no regressions**

```bash
./gradlew :server:test
```

- [ ] **Step 7: Commit**

```bash
git add server/src/main/kotlin/org/branneman/health/polar/PolarConnectRoutes.kt \
        server/src/test/kotlin/org/branneman/health/polar/FakePolarApiClient.kt \
        server/src/test/kotlin/org/branneman/health/PolarIntegrationTest.kt \
        server/src/main/kotlin/org/branneman/health/Application.kt
git commit -m "feat(server): Polar OAuth routes, hourly cron, and integration tests"
```

---

## Task 9: Add `from` date filter to `/out/energy` and `/out/workouts`

**Files:**
- Modify: `server/src/main/kotlin/org/branneman/health/Application.kt`
- Modify: `server/src/test/kotlin/org/branneman/health/SyncDownloadIntegrationTest.kt`

- [ ] **Step 1: Add failing tests to SyncDownloadIntegrationTest**

Add these test methods to `SyncDownloadIntegrationTest`:

```kotlin
@Test
fun `GET out-energy with from filters by date`() = appTest {
    val token = login()
    // seed two energy rows
    transaction {
        DailyEnergy.upsert {
            it[DailyEnergy.userId]     = testUserId
            it[DailyEnergy.date]       = java.time.LocalDate.of(2026, 1, 1)
            it[DailyEnergy.bmrKcal]    = 1600; it[DailyEnergy.activeKcal] = 300
            it[DailyEnergy.totalKcal]  = 1900; it[DailyEnergy.dataSource] = "polar"
        }
        DailyEnergy.upsert {
            it[DailyEnergy.userId]     = testUserId
            it[DailyEnergy.date]       = java.time.LocalDate.of(2026, 6, 1)
            it[DailyEnergy.bmrKcal]    = 1700; it[DailyEnergy.activeKcal] = 400
            it[DailyEnergy.totalKcal]  = 2100; it[DailyEnergy.dataSource] = "polar"
        }
    }
    val r = client.get("/out/energy?from=2026-06-01") {
        header(HttpHeaders.Authorization, "Bearer $token")
    }
    val arr = Json.parseToJsonElement(r.bodyAsText()).jsonArray
    assertEquals(1, arr.size)
    assertEquals("2026-06-01", arr[0].jsonObject["date"]!!.jsonPrimitive.content)

    // clean up
    transaction { DailyEnergy.deleteWhere { Op.build { DailyEnergy.userId eq testUserId } } }
}

@Test
fun `GET out-workouts with from filters by date`() = appTest {
    val token = login()
    transaction {
        Workout.insert {
            it[Workout.id]     = UUID.randomUUID(); it[Workout.userId] = testUserId
            it[Workout.date]   = java.time.LocalDate.of(2026, 1, 1); it[Workout.type] = "CYCLING"
        }
        Workout.insert {
            it[Workout.id]     = UUID.randomUUID(); it[Workout.userId] = testUserId
            it[Workout.date]   = java.time.LocalDate.of(2026, 6, 1); it[Workout.type] = "RUNNING"
        }
    }
    val r = client.get("/out/workouts?from=2026-06-01") {
        header(HttpHeaders.Authorization, "Bearer $token")
    }
    val arr = Json.parseToJsonElement(r.bodyAsText()).jsonArray
    assertEquals(1, arr.size)
    assertEquals("2026-06-01", arr[0].jsonObject["date"]!!.jsonPrimitive.content)

    transaction { Workout.deleteWhere { Op.build { Workout.userId eq testUserId } } }
}
```

Add missing imports: `import org.branneman.health.data.DailyEnergy`, `import org.branneman.health.data.Workout`.

- [ ] **Step 2: Update route handlers in Application.kt**

Replace the `get("/out/energy")` handler:

```kotlin
get("/out/energy") {
    val userId   = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
    val fromStr  = call.request.queryParameters["from"]
    val fromDate = fromStr?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
    val rows = transaction {
        DailyEnergy.selectAll()
            .where {
                if (fromDate != null) {
                    (DailyEnergy.userId eq userId) and (DailyEnergy.date greaterEq fromDate)
                } else {
                    DailyEnergy.userId eq userId
                }
            }
            .orderBy(DailyEnergy.date, SortOrder.DESC)
            .map {
                DailyEnergyDto(
                    date       = it[DailyEnergy.date].toString(),
                    bmrKcal    = it[DailyEnergy.bmrKcal],
                    activeKcal = it[DailyEnergy.activeKcal],
                    totalKcal  = it[DailyEnergy.totalKcal],
                    steps      = it[DailyEnergy.steps],
                    source     = it[DailyEnergy.dataSource],
                )
            }
    }
    call.respond(rows)
}
```

Replace the `get("/out/workouts")` handler:

```kotlin
get("/out/workouts") {
    val userId   = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
    val fromStr  = call.request.queryParameters["from"]
    val fromDate = fromStr?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
    val rows = transaction {
        Workout.selectAll()
            .where {
                if (fromDate != null) {
                    (Workout.userId eq userId) and (Workout.date greaterEq fromDate)
                } else {
                    Workout.userId eq userId
                }
            }
            .orderBy(Workout.date, SortOrder.DESC)
            .map {
                WorkoutDto(
                    id           = it[Workout.id].toString(),
                    date         = it[Workout.date].toString(),
                    type         = it[Workout.type],
                    durationSecs = it[Workout.durationSecs],
                    avgHr        = it[Workout.avgHr],
                    kcal         = it[Workout.kcal],
                )
            }
    }
    call.respond(rows)
}
```

- [ ] **Step 3: Run tests**

```bash
./gradlew :server:test
```

Expected: all tests PASS including the two new filter tests.

- [ ] **Step 4: Commit**

```bash
git add server/src/main/kotlin/org/branneman/health/Application.kt \
        server/src/test/kotlin/org/branneman/health/SyncDownloadIntegrationTest.kt
git commit -m "feat(server): add from-date filter to /out/energy and /out/workouts"
```

---

## Task 10: HealthApiClient additions + test

**Files:**
- Modify: `app/src/main/kotlin/org/branneman/health/network/HealthApiClient.kt`
- Modify: `app/src/test/kotlin/org/branneman/health/network/HealthApiClientTest.kt`

- [ ] **Step 1: Add failing tests to HealthApiClientTest**

Add these tests (in the existing test class):

```kotlin
@Test
fun `getPolarConnectUrl returns url field`() = runBlocking {
    val client = mockClient { _ ->
        respond(
            """{"url":"https://flow.polar.com/oauth2/authorization?state=abc"}""",
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        )
    }
    val url = HealthApiClient("http://test", client).getPolarConnectUrl("token")
    assertEquals("https://flow.polar.com/oauth2/authorization?state=abc", url)
}

@Test
fun `getPolarStatus returns connected = true`() = runBlocking {
    val client = mockClient { _ ->
        respond(
            """{"connected":true}""",
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        )
    }
    val result = HealthApiClient("http://test", client).getPolarStatus("token")
    assertTrue(result.connected)
}
```

- [ ] **Step 2: Add the methods to HealthApiClient.kt**

Add these two methods at the end of the class (before the closing brace) and the private data class above the class:

```kotlin
// (add as private class inside HealthApiClient.kt)
@kotlinx.serialization.Serializable
private data class PolarConnectUrlResponse(val url: String)
```

```kotlin
suspend fun getPolarConnectUrl(token: String): String {
    val response = client.get("$baseUrl/polar/connect-url") {
        header(HttpHeaders.Authorization, "Bearer $token")
    }
    check(response.status.isSuccess()) { "GET /polar/connect-url failed: ${response.status}" }
    return response.body<PolarConnectUrlResponse>().url
}

suspend fun getPolarStatus(token: String): PolarStatusDto =
    client.get("$baseUrl/polar/status") {
        header(HttpHeaders.Authorization, "Bearer $token")
    }.body()
```

Add import at top: `import org.branneman.health.PolarStatusDto`

- [ ] **Step 3: Run tests**

```bash
./gradlew :app:test --tests "org.branneman.health.network.HealthApiClientTest"
```

Expected: 2 new tests PASS, no regressions.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/network/HealthApiClient.kt \
        app/src/test/kotlin/org/branneman/health/network/HealthApiClientTest.kt
git commit -m "feat(app): add getPolarConnectUrl and getPolarStatus to HealthApiClient"
```

---

## Task 11: DailyEnergySyncService + WorkoutSyncService + tests

**Files:**
- Create: `app/src/main/kotlin/org/branneman/health/sync/DailyEnergySyncService.kt`
- Create: `app/src/main/kotlin/org/branneman/health/sync/WorkoutSyncService.kt`
- Create: `app/src/test/kotlin/org/branneman/health/sync/DailyEnergySyncServiceTest.kt`
- Create: `app/src/test/kotlin/org/branneman/health/sync/WorkoutSyncServiceTest.kt`

- [ ] **Step 1: Write DailyEnergySyncServiceTest**

```kotlin
// app/src/test/kotlin/org/branneman/health/sync/DailyEnergySyncServiceTest.kt
package org.branneman.health.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DailyEnergySyncServiceTest {

    private lateinit var db: HealthDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), HealthDatabase::class.java
        ).allowMainThreadQueries().build()
    }
    @After fun tearDown() { db.close() }

    private fun mockApiClient(json: String): HealthApiClient {
        val engine = MockEngine { _ ->
            respond(json, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        return HealthApiClient("http://test", HttpClient(engine) { install(ContentNegotiation) { json() } })
    }

    @Test
    fun `sync downloads energy rows and upserts into Room`() = runTest {
        val api = mockApiClient("""[{"date":"2026-06-12","bmrKcal":1700,"activeKcal":400,"totalKcal":2100,"steps":9000,"source":"polar"}]""")
        DailyEnergySyncService(api, db).sync("token", "00000000-0000-0000-0000-000000000001")
        val rows = db.dailyEnergyDao().getForDate("00000000-0000-0000-0000-000000000001", "2026-06-12")
        assertEquals(2100, rows?.totalKcal)
    }

    @Test
    fun `sync with same date twice produces one row with latest value`() = runTest {
        val api1 = mockApiClient("""[{"date":"2026-06-12","bmrKcal":1600,"activeKcal":300,"totalKcal":1900,"steps":null,"source":"polar"}]""")
        DailyEnergySyncService(api1, db).sync("token", "00000000-0000-0000-0000-000000000001")

        val api2 = mockApiClient("""[{"date":"2026-06-12","bmrKcal":1700,"activeKcal":400,"totalKcal":2100,"steps":8000,"source":"polar"}]""")
        DailyEnergySyncService(api2, db).sync("token", "00000000-0000-0000-0000-000000000001")

        val rows = db.dailyEnergyDao().getForDate("00000000-0000-0000-0000-000000000001", "2026-06-12")
        assertEquals(2100, rows?.totalKcal)
    }

    @Test
    fun `network error leaves Room unchanged`() = runTest {
        val api = HealthApiClient("http://test", HttpClient(MockEngine { error("no network") }) {
            install(ContentNegotiation) { json() }
        })
        DailyEnergySyncService(api, db).sync("token", "00000000-0000-0000-0000-000000000001")
        val rows = db.dailyEnergyDao().getForDate("00000000-0000-0000-0000-000000000001", "2026-06-12")
        assertEquals(null, rows)
    }
}
```

- [ ] **Step 2: Write WorkoutSyncServiceTest**

```kotlin
// app/src/test/kotlin/org/branneman/health/sync/WorkoutSyncServiceTest.kt
package org.branneman.health.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WorkoutSyncServiceTest {

    private lateinit var db: HealthDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), HealthDatabase::class.java
        ).allowMainThreadQueries().build()
    }
    @After fun tearDown() { db.close() }

    private fun mockApiClient(json: String): HealthApiClient {
        val engine = MockEngine { _ ->
            respond(json, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        return HealthApiClient("http://test", HttpClient(engine) { install(ContentNegotiation) { json() } })
    }

    @Test
    fun `sync downloads workouts and upserts into Room`() = runTest {
        val api = mockApiClient("""[{"id":"00000000-0000-0000-0000-000000000001","date":"2026-06-12","type":"RUNNING","durationSecs":3600,"avgHr":145,"kcal":450}]""")
        WorkoutSyncService(api, db).sync("token", "00000000-0000-0000-0000-000000000001")
        assertEquals(1, db.workoutDao().getAll("00000000-0000-0000-0000-000000000001").size)
    }

    @Test
    fun `sync twice with same id produces one workout row`() = runTest {
        val json = """[{"id":"00000000-0000-0000-0000-000000000001","date":"2026-06-12","type":"RUNNING","durationSecs":3600,"avgHr":145,"kcal":450}]"""
        val api = mockApiClient(json)
        WorkoutSyncService(api, db).sync("token", "00000000-0000-0000-0000-000000000001")
        WorkoutSyncService(api, db).sync("token", "00000000-0000-0000-0000-000000000001")
        assertEquals(1, db.workoutDao().getAll("00000000-0000-0000-0000-000000000001").size)
    }

    @Test
    fun `network error leaves Room unchanged`() = runTest {
        val api = HealthApiClient("http://test", HttpClient(MockEngine { error("no network") }) {
            install(ContentNegotiation) { json() }
        })
        WorkoutSyncService(api, db).sync("token", "00000000-0000-0000-0000-000000000001")
        assertEquals(0, db.workoutDao().getAll("00000000-0000-0000-0000-000000000001").size)
    }
}
```

Note: `WorkoutDao.getAll(userId)` may need to be added. Check if it exists — add if missing:
```kotlin
// In WorkoutDao.kt, if getAll is not already present:
@Query("SELECT * FROM workout WHERE userId = :userId ORDER BY date DESC")
suspend fun getAll(userId: String): List<WorkoutEntity>
```

- [ ] **Step 3: Run tests — expect compile failure**

```bash
./gradlew :app:test --tests "org.branneman.health.sync.DailyEnergySyncServiceTest"
./gradlew :app:test --tests "org.branneman.health.sync.WorkoutSyncServiceTest"
```

- [ ] **Step 4: Implement DailyEnergySyncService**

```kotlin
// app/src/main/kotlin/org/branneman/health/sync/DailyEnergySyncService.kt
package org.branneman.health.sync

import org.branneman.health.db.HealthDatabase
import org.branneman.health.db.entities.DailyEnergyEntity
import org.branneman.health.network.HealthApiClient
import java.time.LocalDate

class DailyEnergySyncService(
    private val apiClient: HealthApiClient,
    private val db: HealthDatabase,
) {
    suspend fun sync(token: String, userId: String) {
        val from = LocalDate.now().minusDays(30).toString()
        runCatching { apiClient.getDailyEnergy(token, from) }
            .onSuccess { dtos ->
                db.dailyEnergyDao().upsertAll(dtos.map { dto ->
                    DailyEnergyEntity(
                        userId     = userId,
                        date       = dto.date,
                        bmrKcal    = dto.bmrKcal,
                        activeKcal = dto.activeKcal,
                        totalKcal  = dto.totalKcal,
                        steps      = dto.steps,
                        source     = dto.source,
                    )
                })
            }
    }
}
```

- [ ] **Step 5: Implement WorkoutSyncService**

```kotlin
// app/src/main/kotlin/org/branneman/health/sync/WorkoutSyncService.kt
package org.branneman.health.sync

import org.branneman.health.db.HealthDatabase
import org.branneman.health.db.entities.WorkoutEntity
import org.branneman.health.network.HealthApiClient
import java.time.LocalDate

class WorkoutSyncService(
    private val apiClient: HealthApiClient,
    private val db: HealthDatabase,
) {
    suspend fun sync(token: String, userId: String) {
        val from = LocalDate.now().minusDays(30).toString()
        runCatching { apiClient.getWorkouts(token, from) }
            .onSuccess { dtos ->
                db.workoutDao().upsertAll(dtos.map { dto ->
                    WorkoutEntity(
                        id           = dto.id,
                        userId       = userId,
                        date         = dto.date,
                        type         = dto.type,
                        durationSecs = dto.durationSecs,
                        avgHr        = dto.avgHr,
                        kcal         = dto.kcal,
                    )
                })
            }
    }
}
```

- [ ] **Step 6: Run tests — expect all pass**

```bash
./gradlew :app:test --tests "org.branneman.health.sync.DailyEnergySyncServiceTest"
./gradlew :app:test --tests "org.branneman.health.sync.WorkoutSyncServiceTest"
```

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/sync/DailyEnergySyncService.kt \
        app/src/main/kotlin/org/branneman/health/sync/WorkoutSyncService.kt \
        app/src/test/kotlin/org/branneman/health/sync/DailyEnergySyncServiceTest.kt \
        app/src/test/kotlin/org/branneman/health/sync/WorkoutSyncServiceTest.kt
git commit -m "feat(app): DailyEnergySyncService and WorkoutSyncService with tests"
```

---

## Task 12: SyncWorker — add energy and workout sync

**Files:**
- Modify: `app/src/main/kotlin/org/branneman/health/sync/SyncWorker.kt`

- [ ] **Step 1: Add the sync calls**

In `SyncWorker.doWork()`, after the existing `LogEntrySyncService(apiClient, db).sync(stored.token)` line, add:

```kotlin
        DailyEnergySyncService(apiClient, db).sync(stored.token, stored.userId)
        WorkoutSyncService(apiClient, db).sync(stored.token, stored.userId)
```

Add imports:
```kotlin
import org.branneman.health.sync.DailyEnergySyncService
import org.branneman.health.sync.WorkoutSyncService
```

- [ ] **Step 2: Verify compile**

```bash
./gradlew :app:compileDebugKotlin
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/sync/SyncWorker.kt
git commit -m "feat(app): sync daily energy and workouts in SyncWorker"
```

---

## Task 13: PolarPreferences + NeedsPolarSetup auth state

**Files:**
- Create: `app/src/main/kotlin/org/branneman/health/polar/PolarPreferences.kt`
- Modify: `app/src/main/kotlin/org/branneman/health/auth/AuthRepository.kt`
- Modify: `app/src/main/kotlin/org/branneman/health/auth/AuthViewModel.kt`

- [ ] **Step 1: Create PolarPreferences**

```kotlin
// app/src/main/kotlin/org/branneman/health/polar/PolarPreferences.kt
package org.branneman.health.polar

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.polarDataStore: DataStore<Preferences> by preferencesDataStore(name = "polar")

private val KEY_SETUP_SHOWN = booleanPreferencesKey("polar_setup_shown")

val DataStore<Preferences>.polarSetupShownFlow: Flow<Boolean>
    get() = data.map { it[KEY_SETUP_SHOWN] ?: false }

suspend fun DataStore<Preferences>.savePolarSetupShown() {
    edit { it[KEY_SETUP_SHOWN] = true }
}
```

- [ ] **Step 2: Add NeedsPolarSetup to AuthState and update authState flow**

In `AuthRepository.kt`, add `NeedsPolarSetup` to the sealed class:

```kotlin
sealed class AuthState {
    data object Loading          : AuthState()
    data object LoggedOut        : AuthState()
    data object Expired          : AuthState()
    data object NeedsOnboarding  : AuthState()
    data object NeedsPolarSetup  : AuthState()
    data object LoggedIn         : AuthState()
}
```

Update the `authState` flow. Replace the existing inner `else ->` branch:

```kotlin
else -> db.userProfileDao()
    .existsFlow()
    .flatMapLatest { exists ->
        if (!exists) flowOf(AuthState.NeedsOnboarding)
        else context.polarDataStore.polarSetupShownFlow.map { shown ->
            if (!shown) AuthState.NeedsPolarSetup else AuthState.LoggedIn
        }
    }
```

`AuthRepository` needs `context: Context` to access the DataStore. Update the constructor:

```kotlin
class AuthRepository(
    private val tokenStore: TokenStore,
    private val apiClient: HealthApiClient,
    private val db: HealthDatabase,
    private val context: android.content.Context,       // NEW
    private val loginSyncService: LoginSyncService? = null,
) {
```

Add import: `import org.branneman.health.polar.polarDataStore`, `import org.branneman.health.polar.polarSetupShownFlow`

- [ ] **Step 3: Update AuthRepository construction in AuthViewModel**

In `AuthViewModel.kt`, the `authRepository` lazy initializer passes `application` as the new context:

```kotlin
private val authRepository: AuthRepository by lazy {
    val app = application as HealthApplication
    AuthRepository(
        tokenStore       = tokenStore,
        apiClient        = apiClient,
        db               = app.db,
        context          = application,                 // NEW
        loginSyncService = LoginSyncService(api = apiClient, db = app.db),
    )
}
```

- [ ] **Step 4: Add completePolarSetup() to AuthViewModel**

```kotlin
fun completePolarSetup() {
    viewModelScope.launch {
        getApplication<Application>().polarDataStore.savePolarSetupShown()
    }
}
```

Add import: `import org.branneman.health.polar.polarDataStore`, `import org.branneman.health.polar.savePolarSetupShown`

- [ ] **Step 5: Update AuthRepositoryTest if it constructs AuthRepository**

In `app/src/test/kotlin/org/branneman/health/auth/AuthRepositoryTest.kt`, pass `ApplicationProvider.getApplicationContext()` as the `context` parameter in any `AuthRepository(...)` constructor calls.

- [ ] **Step 6: Verify compile**

```bash
./gradlew :app:compileDebugKotlin
```

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/polar/PolarPreferences.kt \
        app/src/main/kotlin/org/branneman/health/auth/AuthRepository.kt \
        app/src/main/kotlin/org/branneman/health/auth/AuthViewModel.kt \
        app/src/test/kotlin/org/branneman/health/auth/AuthRepositoryTest.kt
git commit -m "feat(app): NeedsPolarSetup auth state driven by polar_setup_shown DataStore flag"
```

---

## Task 14: Android manifest + MainActivity deep link + HealthApplication flow

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/kotlin/org/branneman/health/MainActivity.kt`
- Modify: `app/src/main/kotlin/org/branneman/health/HealthApplication.kt`

- [ ] **Step 1: Add intent filter to AndroidManifest.xml**

Inside the `<activity android:name=".MainActivity">` element, add:

```xml
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="branneman-health" android:host="polar" />
</intent-filter>
```

Also add `android:launchMode="singleTop"` to the `<activity>` tag so `onNewIntent` fires when the app is already running.

- [ ] **Step 2: Add polarCallbackPending to HealthApplication**

```kotlin
// Add to HealthApplication.kt:
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Add inside HealthApplication class:
private val _polarCallbackPending = MutableStateFlow(false)
val polarCallbackPending: StateFlow<Boolean> = _polarCallbackPending.asStateFlow()

fun onPolarCallback() {
    _polarCallbackPending.value = true
}

fun clearPolarCallback() {
    _polarCallbackPending.value = false
}
```

- [ ] **Step 3: Update MainActivity to handle deep link**

```kotlin
// app/src/main/kotlin/org/branneman/health/MainActivity.kt
package org.branneman.health

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent { App() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "branneman-health" && uri.host == "polar") {
            (application as HealthApplication).onPolarCallback()
        }
    }
}
```

- [ ] **Step 4: Verify compile**

```bash
./gradlew :app:compileDebugKotlin
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/AndroidManifest.xml \
        app/src/main/kotlin/org/branneman/health/MainActivity.kt \
        app/src/main/kotlin/org/branneman/health/HealthApplication.kt
git commit -m "feat(app): deep link branneman-health://polar for OAuth callback"
```

---

## Task 15: ConnectPolarScreen + ConnectPolarViewModel + App.kt routing

**Files:**
- Create: `app/src/main/kotlin/org/branneman/health/polar/ConnectPolarScreen.kt`
- Create: `app/src/main/kotlin/org/branneman/health/polar/ConnectPolarViewModel.kt`
- Modify: `app/src/main/kotlin/org/branneman/health/App.kt`

- [ ] **Step 1: Implement ConnectPolarViewModel**

```kotlin
// app/src/main/kotlin/org/branneman/health/polar/ConnectPolarViewModel.kt
package org.branneman.health.polar

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.branneman.health.BuildConfig
import org.branneman.health.auth.TokenStore
import org.branneman.health.auth.authDataStore
import org.branneman.health.network.HealthApiClient

class ConnectPolarViewModel(application: Application) : AndroidViewModel(application) {

    private val tokenStore = TokenStore(application.authDataStore)
    private val apiClient = HealthApiClient(
        baseUrl = BuildConfig.SERVER_BASE_URL,
        client = HttpClient(Android) { install(ContentNegotiation) { json() } },
    )

    fun connectPolar(context: Context) {
        viewModelScope.launch {
            val stored = tokenStore.tokenFlow.first() ?: return@launch
            runCatching { apiClient.getPolarConnectUrl(stored.token) }
                .onSuccess { url ->
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
        }
    }
}
```

- [ ] **Step 2: Implement ConnectPolarScreen**

```kotlin
// app/src/main/kotlin/org/branneman/health/polar/ConnectPolarScreen.kt
package org.branneman.health.polar

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun ConnectPolarScreen(
    onSkip: () -> Unit,
    viewModel: ConnectPolarViewModel = viewModel(),
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Connect Polar", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Connect your Polar watch to replace the BMR estimate with real calories-out data " +
            "from your watch. Your calorie budget will be accurate from the moment your watch syncs.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.weight(1f))
        Button(
            onClick  = { viewModel.connectPolar(context) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Connect Polar")
        }
        TextButton(
            onClick  = onSkip,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Skip for now")
        }
    }
}
```

- [ ] **Step 3: Update App.kt to handle NeedsPolarSetup and deep link**

In `App.kt`, add the `NeedsPolarSetup` branch to the `when (authState)` block, and add a `LaunchedEffect` to consume the deep link callback:

```kotlin
@Composable
fun App() {
    val authViewModel: AuthViewModel = viewModel()
    val authState by authViewModel.authState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val polarCallbackPending by (context.applicationContext as HealthApplication)
        .polarCallbackPending.collectAsStateWithLifecycle()

    LaunchedEffect(polarCallbackPending, authState) {
        if (polarCallbackPending && authState == AuthState.NeedsPolarSetup) {
            authViewModel.completePolarSetup()
            (context.applicationContext as HealthApplication).clearPolarCallback()
        }
    }

    var loginError by remember { mutableStateOf<String?>(null) }
    var isLoggingIn by remember { mutableStateOf(false) }

    MaterialTheme {
        when (authState) {
            AuthState.Loading -> {}

            AuthState.LoggedOut -> LoginScreen(
                sessionExpired = false,
                isLoading      = isLoggingIn,
                errorMessage   = loginError,
                onSignIn       = { username, password ->
                    isLoggingIn = true; loginError = null
                    authViewModel.login(username, password) { error ->
                        isLoggingIn = false
                        loginError = when {
                            error.contains("401") || error.contains("Unauthorized") -> "Wrong credentials"
                            error.contains("UnresolvedAddressException") || error.contains("ConnectException") ->
                                "Check your connection — login requires internet"
                            else -> "Wrong credentials"
                        }
                    }
                }
            )

            AuthState.Expired -> LoginScreen(
                sessionExpired = true,
                isLoading      = isLoggingIn,
                errorMessage   = loginError,
                onSignIn       = { username, password ->
                    isLoggingIn = true; loginError = null
                    authViewModel.login(username, password) { error ->
                        isLoggingIn = false
                        loginError = when {
                            error.contains("401") || error.contains("Unauthorized") -> "Wrong credentials"
                            error.contains("UnresolvedAddressException") || error.contains("ConnectException") ->
                                "Check your connection — login requires internet"
                            else -> "Wrong credentials"
                        }
                    }
                }
            )

            AuthState.NeedsOnboarding -> OnboardingScreen()

            AuthState.NeedsPolarSetup -> ConnectPolarScreen(
                onSkip = { authViewModel.completePolarSetup() }
            )

            AuthState.LoggedIn -> MainNav(authViewModel)
        }
    }
}
```

Add imports:
```kotlin
import org.branneman.health.auth.AuthState
import org.branneman.health.polar.ConnectPolarScreen
import androidx.compose.ui.platform.LocalContext
```

- [ ] **Step 4: Verify compile and check for regressions**

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:test
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/polar/ConnectPolarViewModel.kt \
        app/src/main/kotlin/org/branneman/health/polar/ConnectPolarScreen.kt \
        app/src/main/kotlin/org/branneman/health/App.kt
git commit -m "feat(app): ConnectPolarScreen shown after onboarding via NeedsPolarSetup auth state"
```

---

## Task 16: SettingsViewModel + SettingsScreen Polar status

**Files:**
- Create: `app/src/main/kotlin/org/branneman/health/settings/SettingsViewModel.kt`
- Modify: `app/src/main/kotlin/org/branneman/health/ui/SettingsScreen.kt`

- [ ] **Step 1: Implement SettingsViewModel**

```kotlin
// app/src/main/kotlin/org/branneman/health/settings/SettingsViewModel.kt
package org.branneman.health.settings

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import kotlinx.coroutines.launch
import org.branneman.health.BuildConfig
import org.branneman.health.auth.TokenStore
import org.branneman.health.auth.authDataStore
import org.branneman.health.network.HealthApiClient

enum class PolarStatus { Loading, Connected, NotConnected, Unknown }

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val tokenStore = TokenStore(application.authDataStore)
    private val apiClient = HealthApiClient(
        baseUrl = BuildConfig.SERVER_BASE_URL,
        client  = HttpClient(Android) { install(ContentNegotiation) { json() } },
    )

    private val _polarStatus = MutableStateFlow<PolarStatus>(PolarStatus.Loading)
    val polarStatus: StateFlow<PolarStatus> = _polarStatus.asStateFlow()

    init {
        viewModelScope.launch { refreshPolarStatus() }
    }

    fun recheckPolarStatus() {
        _polarStatus.value = PolarStatus.Loading
        viewModelScope.launch { refreshPolarStatus() }
    }

    private suspend fun refreshPolarStatus() {
        val stored = tokenStore.tokenFlow.first() ?: return
        val result = runCatching { apiClient.getPolarStatus(stored.token) }
        _polarStatus.value = result.fold(
            onSuccess = { dto -> if (dto.connected) PolarStatus.Connected else PolarStatus.NotConnected },
            onFailure = { PolarStatus.Unknown }
        )
    }

    fun connectPolar(context: Context) {
        viewModelScope.launch {
            val stored = tokenStore.tokenFlow.first() ?: return@launch
            runCatching { apiClient.getPolarConnectUrl(stored.token) }
                .onSuccess { url ->
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
        }
    }
}
```

- [ ] **Step 2: Update SettingsScreen**

Replace the full `SettingsScreen.kt` content:

```kotlin
package org.branneman.health.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.branneman.health.BuildConfig
import org.branneman.health.HealthApplication
import org.branneman.health.network.HealthApiClient
import org.branneman.health.settings.PolarStatus
import org.branneman.health.settings.SettingsViewModel
import org.branneman.health.sync.SyncWorker
import org.branneman.health.sync.lastSyncedAtFlow
import org.branneman.health.sync.syncDataStore
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val syncTimestampFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm")

@Composable
fun SettingsScreen(
    onSignOut: () -> Unit,
    settingsViewModel: SettingsViewModel = viewModel(),
) {
    val context = LocalContext.current
    var serverReachable by remember { mutableStateOf<Boolean?>(null) }
    var showSignOutConfirm by remember { mutableStateOf(false) }
    val lastSyncedAt by context.syncDataStore.lastSyncedAtFlow.collectAsState(initial = null)
    val polarStatus by settingsViewModel.polarStatus.collectAsStateWithLifecycle()

    // Re-check polar status when a callback deep link fires (user returned from Polar OAuth)
    val polarCallbackPending by (context.applicationContext as HealthApplication)
        .polarCallbackPending.collectAsStateWithLifecycle()

    LaunchedEffect(polarCallbackPending) {
        if (polarCallbackPending) {
            settingsViewModel.recheckPolarStatus()
            (context.applicationContext as HealthApplication).clearPolarCallback()
        }
    }

    LaunchedEffect(Unit) {
        serverReachable = HealthApiClient().isServerReachable()
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "Server: ${when (serverReachable) { null -> "Checking…"; true -> "Online"; false -> "Offline" }}")
        Spacer(modifier = Modifier.height(8.dp))

        // Polar status
        Text(
            text = "Polar: ${when (polarStatus) {
                PolarStatus.Loading      -> "Checking…"
                PolarStatus.Connected    -> "Connected"
                PolarStatus.NotConnected -> "Not connected"
                PolarStatus.Unknown      -> "Unknown (check connection)"
            }}",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (polarStatus == PolarStatus.NotConnected) {
            TextButton(
                onClick  = { settingsViewModel.connectPolar(context) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Connect Polar") }
        }
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Last synced: ${lastSyncedAt?.let {
                LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault())
                    .format(syncTimestampFormatter)
            } ?: "Never"}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        TextButton(onClick = { SyncWorker.syncNow(context) }, modifier = Modifier.fillMaxWidth()) {
            Text("Sync now")
        }
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text  = "Version: ${BuildConfig.VERSION_NAME}",
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
                TextButton(onClick = { showSignOutConfirm = false; onSignOut() }) { Text("Sign out") }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutConfirm = false }) { Text("Cancel") }
            },
        )
    }
}
```

- [ ] **Step 3: Verify compile and run tests**

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:test
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/settings/SettingsViewModel.kt \
        app/src/main/kotlin/org/branneman/health/ui/SettingsScreen.kt
git commit -m "feat(app): Polar status in Settings with four states and Connect button"
```

---

## Task 17: Ansible env template — add Polar env vars

**Files:**
- Modify: `ansible/templates/env.j2`

- [ ] **Step 1: Add Polar variables**

Add to `ansible/templates/env.j2`:

```
POLAR_CLIENT_ID={{ polar_client_id }}
POLAR_CLIENT_SECRET={{ polar_client_secret }}
POLAR_REDIRECT_URI=https://{{ api_domain }}/polar/callback
POLAR_TOKEN_ENCRYPTION_KEY={{ polar_token_encryption_key }}
```

The corresponding Ansible vault variables (`polar_client_id`, `polar_client_secret`, `polar_token_encryption_key`) must be added to `ansible/vars/vault.yml` manually (vault-encrypted).

To generate a `POLAR_TOKEN_ENCRYPTION_KEY`, run once:
```bash
python3 -c "import secrets, base64; print(base64.b64encode(secrets.token_bytes(32)).decode())"
```

Store the output in the vault as `polar_token_encryption_key`.

- [ ] **Step 2: Add to local `.env` for development**

Add to `.env` in repo root (gitignored):
```
POLAR_CLIENT_ID=your_client_id_here
POLAR_CLIENT_SECRET=your_client_secret_here
POLAR_REDIRECT_URI=https://api.health.bran.name/polar/callback
POLAR_TOKEN_ENCRYPTION_KEY=<base64-encoded 32-byte key from command above>
```

- [ ] **Step 3: Commit**

```bash
git add ansible/templates/env.j2
git commit -m "feat(server): add Polar env vars to Ansible env template"
```

---

## Final verification

- [ ] **Run all server tests**

```bash
./gradlew :server:test
```

Expected: all PASS.

- [ ] **Run all app unit tests**

```bash
./gradlew :app:test
```

Expected: all PASS.

- [ ] **Build the app**

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

---

## Spec coverage self-review

| Spec requirement | Task |
|---|---|
| V7 migration: `polar_connect_state`, `workout.polar_exercise_id` | Task 1 |
| Token encryption AES-256-GCM | Task 5 |
| `PolarApiClient` injectable interface | Task 6 |
| `PolarSyncService` with per-user error isolation | Task 7 |
| `GET /polar/connect-url` CSRF state | Task 8 |
| `GET /polar/callback` one-time state, token exchange, register, upsert | Task 8 |
| `GET /polar/status` DB read | Task 8 |
| Hourly cron in Application.module() | Task 8 |
| `from` filter on `/out/energy` and `/out/workouts` | Task 9 |
| `HealthApiClient.getPolarConnectUrl` + `getPolarStatus` | Task 10 |
| `DailyEnergySyncService` (30-day pull) | Task 11 |
| `WorkoutSyncService` (30-day pull) | Task 11 |
| `SyncWorker` calls both new services | Task 12 |
| `AuthState.NeedsPolarSetup` after profile saved | Task 13 |
| `branneman-health://polar` deep link on MainActivity | Task 14 |
| `ConnectPolarScreen` (Connect + Skip) | Task 15 |
| `App.kt` routes NeedsPolarSetup to ConnectPolarScreen | Task 15 |
| Settings Polar: Loading / Connected / NotConnected / Unknown | Task 16 |
| Connect Polar button in Settings (NotConnected only) | Task 16 |
| Ansible env template with POLAR_* vars | Task 17 |
| Log discipline (no token in logs) | enforced by design: token only in cipher.decrypt() call, no logging in PolarSyncService for token value |
| Token confinement (no token in API responses) | enforced: `/polar/status` returns Boolean, `/polar/connect-url` returns URL, no token field anywhere |
| GC of expired polar_connect_state | Task 7 (PolarSyncService.syncAll step 1) |
