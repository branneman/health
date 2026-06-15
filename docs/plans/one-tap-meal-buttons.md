# One-tap meal buttons — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add user-configurable one-tap meal buttons to the log screen — each button has a name and kcal total and logs instantly, like a named quick-add shortcut.

**Architecture:** Extend `meal_template` (Postgres + Room) with `sort_order INT NULL` and `quick_add_kcal INT NULL`. Templates with `sort_order IS NOT NULL` are "pinned" and appear as one-tap buttons on the log screen. A new `MealTemplateSyncService` pushes local changes to `PUT /in/templates` (replace-all pattern, same as shortcuts). A new `MealButtonsScreen` settings sub-page lets the user configure buttons; the log screen shows a "Set up →" link when none are configured.

**Tech Stack:** Kotlin, Ktor (server), Flyway + Exposed (Postgres), Jetpack Compose + Room (app), Robolectric (app component tests), Ktor `testApplication` (server integration tests)

**Spec:** `docs/specs/one-tap-meal-buttons.md`

---

## File map

| Action | File |
|--------|------|
| Create | `server/src/main/resources/db/migration/V9__meal_template_one_tap.sql` |
| Modify | `server/src/main/kotlin/org/branneman/health/data/Tables.kt` |
| Modify | `shared/src/commonMain/kotlin/org/branneman/health/MealTemplateDto.kt` |
| Modify | `server/src/main/kotlin/org/branneman/health/Application.kt` |
| Create | `server/src/test/kotlin/org/branneman/health/MealTemplatesIntegrationTest.kt` |
| Modify | `docs/testing-manifesto.md` |
| Modify | `app/src/main/kotlin/org/branneman/health/db/entities/MealTemplateEntity.kt` |
| Modify | `app/src/main/kotlin/org/branneman/health/db/dao/MealTemplateDao.kt` |
| Modify | `app/src/main/kotlin/org/branneman/health/db/HealthDatabase.kt` |
| Modify | `app/src/main/kotlin/org/branneman/health/HealthApplication.kt` |
| Modify | `app/src/test/kotlin/org/branneman/health/TestFactories.kt` |
| Modify | `app/src/test/kotlin/org/branneman/health/db/dao/MealTemplateDaoTest.kt` |
| Modify | `app/src/main/kotlin/org/branneman/health/network/HealthApiClient.kt` |
| Create | `app/src/main/kotlin/org/branneman/health/sync/MealTemplateSyncService.kt` |
| Create | `app/src/test/kotlin/org/branneman/health/sync/MealTemplateSyncServiceTest.kt` |
| Modify | `app/src/main/kotlin/org/branneman/health/sync/LoginSyncService.kt` |
| Modify | `app/src/main/kotlin/org/branneman/health/sync/SyncWorker.kt` |
| Modify | `app/src/main/kotlin/org/branneman/health/log/LogViewModel.kt` |
| Create | `app/src/main/kotlin/org/branneman/health/ui/MealButtonsViewModel.kt` |
| Modify | `app/src/main/kotlin/org/branneman/health/ui/LogScreen.kt` |
| Create | `app/src/main/kotlin/org/branneman/health/ui/MealButtonsScreen.kt` |
| Modify | `app/src/main/kotlin/org/branneman/health/ui/SettingsScreen.kt` |
| Modify | `app/src/main/kotlin/org/branneman/health/App.kt` |
| Modify | `app/src/test/kotlin/org/branneman/health/ui/LogScreenTest.kt` |
| Create | `app/src/test/kotlin/org/branneman/health/ui/MealButtonsScreenTest.kt` |
| Create | `app/src/test/kotlin/org/branneman/health/ui/SettingsScreenTest.kt` |

---

## Task 1: Flyway migration + Exposed table

**Files:**
- Create: `server/src/main/resources/db/migration/V9__meal_template_one_tap.sql`
- Modify: `server/src/main/kotlin/org/branneman/health/data/Tables.kt`

- [ ] **Step 1: Create the Flyway migration**

```sql
-- V9__meal_template_one_tap.sql
ALTER TABLE meal_template
  ADD COLUMN quick_add_kcal INTEGER,
  ADD COLUMN sort_order INTEGER;

CREATE UNIQUE INDEX meal_template_user_sort_order
  ON meal_template (user_id, sort_order)
  WHERE sort_order IS NOT NULL;
```

- [ ] **Step 2: Add the two columns to the Exposed table object in `Tables.kt`**

Replace the `object MealTemplate` block with:

```kotlin
object MealTemplate : Table("meal_template") {
    val id           = uuid("id")
    val userId       = uuid("user_id")
    val name         = text("name")
    val quickAddKcal = integer("quick_add_kcal").nullable()
    val sortOrder    = integer("sort_order").nullable()
    val createdAt    = timestampWithTimeZone("created_at")
    val updatedAt    = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(id)
}
```

- [ ] **Step 3: Verify the server compiles**

```bash
./gradlew :server:compileKotlin
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add server/src/main/resources/db/migration/V9__meal_template_one_tap.sql \
        server/src/main/kotlin/org/branneman/health/data/Tables.kt
git commit -m "feat(server): V8 migration — add sort_order and quick_add_kcal to meal_template"
```

---

## Task 2: Shared DTO

**Files:**
- Modify: `shared/src/commonMain/kotlin/org/branneman/health/MealTemplateDto.kt`

- [ ] **Step 1: Add the two new fields to `MealTemplateDto`**

Replace the file content with:

```kotlin
package org.branneman.health

import kotlinx.serialization.Serializable

@Serializable
data class MealTemplateItemDto(
    val foodItemId: String,
    val grams: Double,
)

@Serializable
data class MealTemplateDto(
    val id: String,
    val name: String,
    val sortOrder: Int?,
    val quickAddKcal: Int?,
    val items: List<MealTemplateItemDto>,
)
```

- [ ] **Step 2: Verify the shared module compiles**

```bash
./gradlew :shared:compileKotlin
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/org/branneman/health/MealTemplateDto.kt
git commit -m "feat(shared): add sortOrder and quickAddKcal to MealTemplateDto"
```

---

## Task 3: Server — GET reads new fields; add PUT /in/templates

**Files:**
- Modify: `server/src/main/kotlin/org/branneman/health/Application.kt`

- [ ] **Step 1: Update `GET /in/templates` to map the two new columns**

Find the `get("/in/templates")` handler. Replace the `MealTemplateDto(...)` constructor call:

```kotlin
MealTemplateDto(
    id           = tRow[MealTemplate.id].toString(),
    name         = tRow[MealTemplate.name],
    sortOrder    = tRow[MealTemplate.sortOrder],
    quickAddKcal = tRow[MealTemplate.quickAddKcal],
    items        = items,
)
```

- [ ] **Step 2: Add `PUT /in/templates` immediately after the GET block**

```kotlin
put("/in/templates") {
    val userId   = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
    val incoming = call.receive<List<MealTemplateDto>>()
    val saved = transaction {
        val existingIds = MealTemplate.select(MealTemplate.id)
            .where { MealTemplate.userId eq userId }
            .map { it[MealTemplate.id] }
        if (existingIds.isNotEmpty()) {
            MealTemplateItem.deleteWhere {
                Op.build { templateId inList existingIds }
            }
        }
        MealTemplate.deleteWhere { Op.build { MealTemplate.userId eq userId } }
        incoming.map { dto ->
            val newId = UUID.randomUUID()
            MealTemplate.insert {
                it[MealTemplate.id]           = newId
                it[MealTemplate.userId]       = userId
                it[MealTemplate.name]         = dto.name
                it[MealTemplate.quickAddKcal] = dto.quickAddKcal
                it[MealTemplate.sortOrder]    = dto.sortOrder
                it[MealTemplate.createdAt]    = OffsetDateTime.now()
                it[MealTemplate.updatedAt]    = OffsetDateTime.now()
            }
            dto.copy(id = newId.toString())
        }
    }
    call.respond(HttpStatusCode.OK, saved)
}
```

- [ ] **Step 3: Verify the server compiles**

```bash
./gradlew :server:compileKotlin
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add server/src/main/kotlin/org/branneman/health/Application.kt
git commit -m "feat(server): GET /in/templates returns sortOrder+quickAddKcal; add PUT /in/templates"
```

---

## Task 4: MealTemplatesIntegrationTest + claim UUID slot #9

**Files:**
- Create: `server/src/test/kotlin/org/branneman/health/MealTemplatesIntegrationTest.kt`
- Modify: `docs/testing-manifesto.md`

Requires a running `health_test` Postgres database. See manifesto for connection env vars.

- [ ] **Step 1: Claim UUID slot #9 in the testing manifesto**

In `docs/testing-manifesto.md`, replace the `| 9 | *(free)* |` row with:

```
| 9 | `...000009` | `MealTemplatesIntegrationTest` | `mealtemplates-test@test.local` |
```

- [ ] **Step 2: Write `MealTemplatesIntegrationTest`**

```kotlin
package org.branneman.health

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.branneman.health.auth.Users
import org.branneman.health.data.MealTemplate
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import java.util.UUID
import kotlin.test.*

class MealTemplatesIntegrationTest {

    companion object {
        private val ds = TestDatabase.dataSource
        private val testUserId = UUID.fromString("00000000-0000-0000-0000-000000000009")
        private const val TEST_EMAIL = "mealtemplates-test@test.local"
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

    @BeforeTest fun cleanTemplates() {
        transaction {
            MealTemplate.deleteWhere { Op.build { MealTemplate.userId eq testUserId } }
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

    @Test fun `GET templates requires auth`() = appTest {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/in/templates").status)
    }

    @Test fun `GET templates returns empty list when none saved`() = appTest {
        val token = login()
        val r = client.get("/in/templates") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(0, Json.parseToJsonElement(r.bodyAsText()).jsonArray.size)
    }

    @Test fun `PUT then GET round-trips sortOrder and quickAddKcal`() = appTest {
        val token = login()
        val body = """[{"id":"ignored","name":"Usual breakfast","sortOrder":0,"quickAddKcal":450,"items":[]}]"""

        val put = client.put("/in/templates") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, put.status)

        val get = client.get("/in/templates") { header(HttpHeaders.Authorization, "Bearer $token") }
        val arr = Json.parseToJsonElement(get.bodyAsText()).jsonArray
        assertEquals(1, arr.size)
        assertEquals("Usual breakfast", arr[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(0,   arr[0].jsonObject["sortOrder"]!!.jsonPrimitive.int)
        assertEquals(450, arr[0].jsonObject["quickAddKcal"]!!.jsonPrimitive.int)
    }

    @Test fun `PUT twice replaces first list`() = appTest {
        val token = login()
        client.put("/in/templates") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""[{"id":"x","name":"Breakfast","sortOrder":0,"quickAddKcal":400,"items":[]}]""")
        }
        client.put("/in/templates") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""[{"id":"x","name":"Lunch","sortOrder":0,"quickAddKcal":600,"items":[]}]""")
        }
        val arr = Json.parseToJsonElement(
            client.get("/in/templates") { header(HttpHeaders.Authorization, "Bearer $token") }.bodyAsText()
        ).jsonArray
        assertEquals(1, arr.size)
        assertEquals("Lunch", arr[0].jsonObject["name"]!!.jsonPrimitive.content)
    }
}
```

- [ ] **Step 3: Run the server integration tests**

```bash
./gradlew :server:test --tests "org.branneman.health.MealTemplatesIntegrationTest"
```
Expected: 4 tests pass.

- [ ] **Step 4: Commit**

```bash
git add server/src/test/kotlin/org/branneman/health/MealTemplatesIntegrationTest.kt \
        docs/testing-manifesto.md
git commit -m "test(server): add MealTemplatesIntegrationTest; claim UUID slot 9"
```

---

## Task 5: Room entity + DAO + database migration

**Files:**
- Modify: `app/src/main/kotlin/org/branneman/health/db/entities/MealTemplateEntity.kt`
- Modify: `app/src/main/kotlin/org/branneman/health/db/dao/MealTemplateDao.kt`
- Modify: `app/src/main/kotlin/org/branneman/health/db/HealthDatabase.kt`
- Modify: `app/src/main/kotlin/org/branneman/health/HealthApplication.kt`

- [ ] **Step 1: Add `sortOrder` and `quickAddKcal` to `MealTemplateEntity`**

Replace the file with:

```kotlin
package org.branneman.health.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.branneman.health.db.SyncStatus
import java.util.UUID

@Entity(tableName = "meal_template")
data class MealTemplateEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val name: String,
    val sortOrder: Int? = null,
    val quickAddKcal: Int? = null,
    val syncStatus: SyncStatus = SyncStatus.PENDING_CREATE,
    val updatedAt: Long = System.currentTimeMillis(),
)
```

- [ ] **Step 2: Add `observePinned()` to `MealTemplateDao`**

Add after `observeAll()`:

```kotlin
@Query("SELECT * FROM meal_template WHERE sortOrder IS NOT NULL AND syncStatus != 'PENDING_DELETE' ORDER BY sortOrder ASC")
fun observePinned(): Flow<List<MealTemplateEntity>>
```

- [ ] **Step 3: Bump Room schema version to 4 in `HealthDatabase.kt`**

Change `version = 3` to `version = 4`.

- [ ] **Step 4: Add `MIGRATION_3_4` in `HealthApplication.kt`**

Add after `MIGRATION_1_2`:

```kotlin
private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE meal_template ADD COLUMN sortOrder INTEGER")
        db.execSQL("ALTER TABLE meal_template ADD COLUMN quickAddKcal INTEGER")
    }
}
```

Update the DB builder call to include both migrations:

```kotlin
db = Room.databaseBuilder(this, HealthDatabase::class.java, "health.db")
    .addMigrations(MIGRATION_1_2, MIGRATION_3_4)
    .build()
```

- [ ] **Step 5: Verify the app compiles**

```bash
./gradlew :app:compileDebugKotlin
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/db/entities/MealTemplateEntity.kt \
        app/src/main/kotlin/org/branneman/health/db/dao/MealTemplateDao.kt \
        app/src/main/kotlin/org/branneman/health/db/HealthDatabase.kt \
        app/src/main/kotlin/org/branneman/health/HealthApplication.kt
git commit -m "feat(app): add sortOrder/quickAddKcal to MealTemplateEntity; Room migration 2→3"
```

---

## Task 6: TestFactories update + MealTemplateDaoTest extensions

**Files:**
- Modify: `app/src/test/kotlin/org/branneman/health/TestFactories.kt`
- Modify: `app/src/test/kotlin/org/branneman/health/db/dao/MealTemplateDaoTest.kt`

- [ ] **Step 1: Update `aMealTemplate` factory in `TestFactories.kt`**

Replace the existing `aMealTemplate` function:

```kotlin
fun aMealTemplate(
    id: String = uuid(),
    userId: String = uuid(),
    name: String = "Test Template",
    sortOrder: Int? = null,
    quickAddKcal: Int? = null,
    syncStatus: SyncStatus = SyncStatus.SYNCED,
) = MealTemplateEntity(
    id = id, userId = userId, name = name,
    sortOrder = sortOrder, quickAddKcal = quickAddKcal,
    syncStatus = syncStatus,
)
```

- [ ] **Step 2: Write failing DAO tests for new behaviour**

Add to `MealTemplateDaoTest`:

```kotlin
@Test
fun `observePinned returns only templates with non-null sortOrder ordered ascending`() = runTest {
    val userId = uuid()
    dao.upsert(aMealTemplate(userId = userId, name = "Dinner",    sortOrder = null))
    dao.upsert(aMealTemplate(userId = userId, name = "Lunch",     sortOrder = 1, quickAddKcal = 600))
    dao.upsert(aMealTemplate(userId = userId, name = "Breakfast", sortOrder = 0, quickAddKcal = 450))
    val pinned = dao.observePinned().first()
    assertEquals(2, pinned.size)
    assertEquals("Breakfast", pinned[0].name)
    assertEquals("Lunch",     pinned[1].name)
}

@Test
fun `observePinned excludes PENDING_DELETE templates`() = runTest {
    val id = uuid()
    dao.upsert(aMealTemplate(id = id, userId = uuid(), sortOrder = 0, quickAddKcal = 400))
    dao.updateSyncStatus(id, SyncStatus.PENDING_DELETE)
    assertTrue(dao.observePinned().first().isEmpty())
}

@Test
fun `quickAddKcal and sortOrder round-trip through upsert`() = runTest {
    val id = uuid()
    dao.upsert(aMealTemplate(id = id, userId = uuid(), sortOrder = 0, quickAddKcal = 500))
    val result = dao.observePinned().first()
    assertEquals(500, result[0].quickAddKcal)
    assertEquals(0,   result[0].sortOrder)
}
```

- [ ] **Step 3: Run DAO tests**

```bash
./gradlew :app:testDebugUnitTest --tests "org.branneman.health.db.dao.MealTemplateDaoTest"
```
Expected: 7 tests pass (4 existing + 3 new).

- [ ] **Step 4: Commit**

```bash
git add app/src/test/kotlin/org/branneman/health/TestFactories.kt \
        app/src/test/kotlin/org/branneman/health/db/dao/MealTemplateDaoTest.kt
git commit -m "test(app): extend MealTemplateDaoTest for new fields; update factory"
```

---

## Task 7: HealthApiClient — add putTemplates

**Files:**
- Modify: `app/src/main/kotlin/org/branneman/health/network/HealthApiClient.kt`

- [ ] **Step 1: Add `putTemplates` after `getTemplates`**

```kotlin
suspend fun putTemplates(token: String, templates: List<MealTemplateDto>): List<MealTemplateDto> =
    client.put("$baseUrl/in/templates") {
        header(HttpHeaders.Authorization, "Bearer $token")
        contentType(ContentType.Application.Json)
        setBody(templates)
    }.body()
```

- [ ] **Step 2: Verify the app compiles**

```bash
./gradlew :app:compileDebugKotlin
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/network/HealthApiClient.kt
git commit -m "feat(app): add putTemplates to HealthApiClient"
```

---

## Task 8: MealTemplateSyncService + test

**Files:**
- Create: `app/src/main/kotlin/org/branneman/health/sync/MealTemplateSyncService.kt`
- Create: `app/src/test/kotlin/org/branneman/health/sync/MealTemplateSyncServiceTest.kt`

- [ ] **Step 1: Write the failing tests first**

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
import org.branneman.health.aMealTemplate
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
class MealTemplateSyncServiceTest {

    private lateinit var db: HealthDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), HealthDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() { db.close() }

    private fun mockApi(handler: MockRequestHandler) = HealthApiClient(
        "http://test",
        HttpClient(MockEngine(handler)) { install(ContentNegotiation) { json() } }
    )

    private fun jsonResponse(body: String) = respond(
        body, HttpStatusCode.OK,
        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    )

    @Test
    fun `pushPending sends PENDING_CREATE template and marks it SYNCED`() = runTest {
        val userId = "u1"
        val t = aMealTemplate(userId = userId, name = "Usual breakfast",
            sortOrder = 0, quickAddKcal = 450, syncStatus = SyncStatus.PENDING_CREATE)
        db.mealTemplateDao().upsert(t)

        var body = ""
        val api = mockApi { req -> body = req.body.toByteArray().toString(Charsets.UTF_8)
            jsonResponse("""[{"id":"${t.id}","name":"Usual breakfast","sortOrder":0,"quickAddKcal":450,"items":[]}]""")
        }

        MealTemplateSyncService(api, db).pushPending("token", userId)

        assertEquals(1, db.mealTemplateDao().getByStatus(SyncStatus.SYNCED).size)
        assertTrue(body.contains("Usual breakfast"))
    }

    @Test
    fun `pushPending does nothing when no pending templates`() = runTest {
        val userId = "u1"
        db.mealTemplateDao().upsert(aMealTemplate(userId = userId, sortOrder = 0, quickAddKcal = 300,
            syncStatus = SyncStatus.SYNCED))

        var called = false
        val api = mockApi { _ -> called = true; jsonResponse("[]") }

        MealTemplateSyncService(api, db).pushPending("token", userId)

        assertTrue(!called)
    }

    @Test
    fun `pull upserts server templates into Room as SYNCED`() = runTest {
        val userId = "u1"
        val api = mockApi { _ ->
            jsonResponse("""[{"id":"abc","name":"Usual lunch","sortOrder":0,"quickAddKcal":600,"items":[]}]""")
        }

        MealTemplateSyncService(api, db).pull("token", userId)

        val pinned = db.mealTemplateDao().observePinned().first()
        assertEquals(1, pinned.size)
        assertEquals("Usual lunch", pinned[0].name)
        assertEquals(600, pinned[0].quickAddKcal)
        assertEquals(SyncStatus.SYNCED, pinned[0].syncStatus)
    }

    @Test
    fun `pushPending stays PENDING_CREATE on network error`() = runTest {
        val userId = "u1"
        db.mealTemplateDao().upsert(aMealTemplate(userId = userId, sortOrder = 0, quickAddKcal = 300,
            syncStatus = SyncStatus.PENDING_CREATE))

        val api = HealthApiClient("http://test",
            HttpClient(MockEngine { error("network error") }) { install(ContentNegotiation) { json() } })

        runCatching { MealTemplateSyncService(api, db).pushPending("token", userId) }

        assertEquals(1, db.mealTemplateDao().getByStatus(SyncStatus.PENDING_CREATE).size)
    }
}
```

- [ ] **Step 2: Run to confirm failure (class not yet defined)**

```bash
./gradlew :app:testDebugUnitTest --tests "org.branneman.health.sync.MealTemplateSyncServiceTest"
```
Expected: FAIL — `MealTemplateSyncService` not found.

- [ ] **Step 3: Implement `MealTemplateSyncService`**

```kotlin
package org.branneman.health.sync

import org.branneman.health.MealTemplateDto
import org.branneman.health.db.HealthDatabase
import org.branneman.health.db.SyncStatus
import org.branneman.health.db.entities.MealTemplateEntity
import org.branneman.health.network.HealthApiClient

class MealTemplateSyncService(
    private val api: HealthApiClient,
    private val db: HealthDatabase,
) {
    suspend fun pushPending(token: String, userId: String) {
        val pending = db.mealTemplateDao().getByStatus(SyncStatus.PENDING_CREATE) +
                      db.mealTemplateDao().getByStatus(SyncStatus.PENDING_UPDATE)
        if (pending.isEmpty()) return

        val allActive = pending +
                        db.mealTemplateDao().getByStatus(SyncStatus.SYNCED)

        runCatching {
            api.putTemplates(token, allActive.map { it.toDto() })
        }.onSuccess {
            allActive.forEach { db.mealTemplateDao().updateSyncStatus(it.id, SyncStatus.SYNCED) }
        }
    }

    suspend fun pull(token: String, userId: String) {
        val templates = api.getTemplates(token)
        db.mealTemplateDao().upsertAll(templates.map { dto ->
            MealTemplateEntity(
                id           = dto.id,
                userId       = userId,
                name         = dto.name,
                sortOrder    = dto.sortOrder,
                quickAddKcal = dto.quickAddKcal,
                syncStatus   = SyncStatus.SYNCED,
            )
        })
    }

    private fun MealTemplateEntity.toDto() = MealTemplateDto(
        id           = id,
        name         = name,
        sortOrder    = sortOrder,
        quickAddKcal = quickAddKcal,
        items        = emptyList(),
    )
}
```

- [ ] **Step 4: Run tests and confirm they pass**

```bash
./gradlew :app:testDebugUnitTest --tests "org.branneman.health.sync.MealTemplateSyncServiceTest"
```
Expected: 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/sync/MealTemplateSyncService.kt \
        app/src/test/kotlin/org/branneman/health/sync/MealTemplateSyncServiceTest.kt
git commit -m "feat(app): add MealTemplateSyncService with pushPending/pull; add tests"
```

---

## Task 9: LoginSyncService — pass new fields when mapping templates

**Files:**
- Modify: `app/src/main/kotlin/org/branneman/health/sync/LoginSyncService.kt`

- [ ] **Step 1: Update the template mapping block**

Find:
```kotlin
db.mealTemplateDao().upsertAll(templates.map { dto ->
    MealTemplateEntity(
        id = dto.id, userId = userId, name = dto.name,
        syncStatus = SyncStatus.SYNCED
    )
})
```

Replace with:
```kotlin
db.mealTemplateDao().upsertAll(templates.map { dto ->
    MealTemplateEntity(
        id           = dto.id,
        userId       = userId,
        name         = dto.name,
        sortOrder    = dto.sortOrder,
        quickAddKcal = dto.quickAddKcal,
        syncStatus   = SyncStatus.SYNCED,
    )
})
```

- [ ] **Step 2: Run existing LoginSyncService tests**

```bash
./gradlew :app:testDebugUnitTest --tests "org.branneman.health.sync.LoginSyncServiceTest"
```
Expected: All existing tests pass.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/sync/LoginSyncService.kt
git commit -m "fix(app): map sortOrder and quickAddKcal when syncing templates on login"
```

---

## Task 10: SyncWorker — wire in MealTemplateSyncService

**Files:**
- Modify: `app/src/main/kotlin/org/branneman/health/sync/SyncWorker.kt`

- [ ] **Step 1: Add `MealTemplateSyncService.pushPending` call in `doWork`**

After the `LogEntrySyncService` line, add:

```kotlin
MealTemplateSyncService(apiClient, db).pushPending(stored.token, stored.userId)
```

The updated block in `doWork` should look like:
```kotlin
BodyWeightSyncService(apiClient, db).sync(stored.token)
LogEntrySyncService(apiClient, db).sync(stored.token)
MealTemplateSyncService(apiClient, db).pushPending(stored.token, stored.userId)
runCatching { apiClient.triggerPolarSync(stored.token) }
DailyEnergySyncService(apiClient, db).sync(stored.token, stored.userId)
WorkoutSyncService(apiClient, db).sync(stored.token, stored.userId)
```

- [ ] **Step 2: Verify the app compiles**

```bash
./gradlew :app:compileDebugKotlin
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/sync/SyncWorker.kt
git commit -m "feat(app): wire MealTemplateSyncService into SyncWorker"
```

---

## Task 11: LogViewModel — add logFromTemplate

**Files:**
- Modify: `app/src/main/kotlin/org/branneman/health/log/LogViewModel.kt`

- [ ] **Step 1: Add the import and the new function**

Add import:
```kotlin
import org.branneman.health.db.entities.MealTemplateEntity
```

Add `logFromTemplate` after `addEntry`:

```kotlin
fun logFromTemplate(template: MealTemplateEntity) {
    val kcal = template.quickAddKcal ?: return
    viewModelScope.launch {
        val userId = tokenStore.tokenFlow.first()?.userId ?: return@launch
        val entity = LogEntryEntity(
            userId        = userId,
            loggedAt      = OffsetDateTime.now().toString(),
            mealType      = "unknown",
            quickAddKcal  = kcal,
            quickAddLabel = template.name,
        )
        db.logEntryDao().upsert(entity)
        _undoPending.value = entity to SyncStatus.PENDING_CREATE
    }
}
```

- [ ] **Step 2: Verify the app compiles**

```bash
./gradlew :app:compileDebugKotlin
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/log/LogViewModel.kt
git commit -m "feat(app): add logFromTemplate to LogViewModel"
```

---

## Task 12: MealButtonsViewModel

**Files:**
- Create: `app/src/main/kotlin/org/branneman/health/ui/MealButtonsViewModel.kt`

- [ ] **Step 1: Create the ViewModel**

```kotlin
package org.branneman.health.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.branneman.health.HealthApplication
import org.branneman.health.auth.TokenStore
import org.branneman.health.auth.authDataStore
import org.branneman.health.db.SyncStatus
import org.branneman.health.db.entities.MealTemplateEntity

class MealButtonsViewModel(application: Application) : AndroidViewModel(application) {

    private val db = (application as HealthApplication).db
    private val tokenStore = TokenStore(application.authDataStore)

    private val _draft = MutableStateFlow<List<MealTemplateEntity>?>(null)
    val draft: StateFlow<List<MealTemplateEntity>?> = _draft

    private var userId: String? = null

    init {
        viewModelScope.launch {
            userId = tokenStore.tokenFlow.first()?.userId
            _draft.value = db.mealTemplateDao().observePinned().first()
        }
    }

    fun addButton(name: String, kcal: Int) {
        val uid = userId ?: return
        val current = _draft.value ?: return
        val nextOrder = (current.maxOfOrNull { it.sortOrder ?: -1 } ?: -1) + 1
        _draft.value = current + MealTemplateEntity(
            userId       = uid,
            name         = name,
            sortOrder    = nextOrder,
            quickAddKcal = kcal,
            syncStatus   = SyncStatus.PENDING_CREATE,
        )
    }

    fun removeButton(index: Int) {
        val current = _draft.value?.toMutableList() ?: return
        current.removeAt(index)
        _draft.value = current.reindexed()
    }

    fun moveUp(index: Int) {
        if (index == 0) return
        val current = _draft.value?.toMutableList() ?: return
        val tmp = current[index - 1]; current[index - 1] = current[index]; current[index] = tmp
        _draft.value = current.reindexed()
    }

    fun moveDown(index: Int) {
        val current = _draft.value?.toMutableList() ?: return
        if (index >= current.size - 1) return
        val tmp = current[index + 1]; current[index + 1] = current[index]; current[index] = tmp
        _draft.value = current.reindexed()
    }

    fun save() {
        val uid = userId ?: return
        viewModelScope.launch {
            val current = _draft.value ?: return@launch
            db.mealTemplateDao().deleteAllItemsForUser(uid)
            db.mealTemplateDao().deleteAllForUser(uid)
            current.forEachIndexed { i, entity ->
                db.mealTemplateDao().upsert(entity.copy(userId = uid, sortOrder = i,
                    syncStatus = SyncStatus.PENDING_CREATE))
            }
        }
    }
}

internal fun List<MealTemplateEntity>.reindexed(): List<MealTemplateEntity> =
    mapIndexed { i, e -> e.copy(sortOrder = i) }
```

- [ ] **Step 2: Write a unit test for `reindexed` (pure logic)**

Create `app/src/test/kotlin/org/branneman/health/ui/MealButtonsViewModelTest.kt`:

```kotlin
package org.branneman.health.ui

import org.branneman.health.aMealTemplate
import org.junit.Test
import kotlin.test.assertEquals

class MealButtonsViewModelTest {

    @Test fun `reindexed assigns sequential sortOrder starting at 0`() {
        val list = listOf(
            aMealTemplate(name = "A", sortOrder = 5),
            aMealTemplate(name = "B", sortOrder = 2),
            aMealTemplate(name = "C", sortOrder = 9),
        )
        val result = list.reindexed()
        assertEquals(0, result[0].sortOrder)
        assertEquals(1, result[1].sortOrder)
        assertEquals(2, result[2].sortOrder)
        assertEquals("A", result[0].name)
    }

    @Test fun `reindexed on empty list returns empty`() {
        assertEquals(emptyList(), emptyList<org.branneman.health.db.entities.MealTemplateEntity>().reindexed())
    }
}
```

- [ ] **Step 3: Run the unit test**

```bash
./gradlew :app:testDebugUnitTest --tests "org.branneman.health.ui.MealButtonsViewModelTest"
```
Expected: 2 tests pass.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/ui/MealButtonsViewModel.kt \
        app/src/test/kotlin/org/branneman/health/ui/MealButtonsViewModelTest.kt
git commit -m "feat(app): add MealButtonsViewModel with draft state and reindexed logic"
```

---

## Task 13: LogScreen — meal button row + test extensions

**Files:**
- Modify: `app/src/main/kotlin/org/branneman/health/ui/LogScreen.kt`
- Modify: `app/src/test/kotlin/org/branneman/health/ui/LogScreenTest.kt`

`LogContent` gains `pinnedTemplates` and `onSetUpMealButtons` parameters. The meal button row sits above the quick-add form.

- [ ] **Step 1: Write failing tests first**

Add to `LogScreenTest`:

```kotlin
private fun renderWithTemplates(
    entries: List<LogEntryEntity> = emptyList(),
    pinned: List<MealTemplateEntity> = emptyList(),
    onAdd: (String, String) -> Unit = { _, _ -> },
    onDelete: (LogEntryEntity) -> Unit = {},
    onSetUpMealButtons: () -> Unit = {},
    onLogTemplate: (MealTemplateEntity) -> Unit = {},
) {
    compose.setContent {
        MaterialTheme {
            LogContent(
                entries           = entries,
                pinnedTemplates   = pinned,
                onAdd             = onAdd,
                onDelete          = onDelete,
                onSetUpMealButtons = onSetUpMealButtons,
                onLogTemplate     = onLogTemplate,
            )
        }
    }
}

@Test fun `shows set-up button when no pinned templates`() {
    renderWithTemplates(pinned = emptyList())
    compose.onNodeWithText("Set up meal buttons", substring = true).assertExists()
}

@Test fun `shows template buttons when pinned templates exist`() {
    val template = aMealTemplate(name = "Usual breakfast", sortOrder = 0, quickAddKcal = 450)
    renderWithTemplates(pinned = listOf(template))
    compose.onNodeWithText("Usual breakfast").assertExists()
}

@Test fun `tapping a template button calls onLogTemplate`() {
    var logged: MealTemplateEntity? = null
    val template = aMealTemplate(name = "Usual breakfast", sortOrder = 0, quickAddKcal = 450)
    renderWithTemplates(
        pinned = listOf(template),
        onLogTemplate = { logged = it },
    )
    compose.onNodeWithText("Usual breakfast").performClick()
    assertEquals(template.id, logged?.id)
}

@Test fun `tapping set-up button calls onSetUpMealButtons`() {
    var tapped = false
    renderWithTemplates(pinned = emptyList(), onSetUpMealButtons = { tapped = true })
    compose.onNodeWithText("Set up meal buttons", substring = true).performClick()
    assertTrue(tapped)
}
```

The existing `render` helper tests the old `LogContent` signature; keep it but also add the import `import org.branneman.health.aMealTemplate` and `import org.branneman.health.db.entities.MealTemplateEntity`.

- [ ] **Step 2: Run to confirm new tests fail**

```bash
./gradlew :app:testDebugUnitTest --tests "org.branneman.health.ui.LogScreenTest"
```
Expected: the 4 new tests fail; the 4 original ones may warn about signature mismatch (fix in next step).

- [ ] **Step 3: Update `LogContent` signature and add the meal button row**

Replace `LogContent` in `LogScreen.kt`:

```kotlin
@Composable
fun LogContent(
    entries: List<LogEntryEntity>,
    pinnedTemplates: List<MealTemplateEntity> = emptyList(),
    onAdd: (kcal: String, label: String) -> Unit,
    onDelete: (LogEntryEntity) -> Unit,
    onSetUpMealButtons: () -> Unit = {},
    onLogTemplate: (MealTemplateEntity) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // ... (existing local state vars unchanged) ...

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // --- Meal button row ---
        if (pinnedTemplates.isEmpty()) {
            OutlinedButton(
                onClick  = onSetUpMealButtons,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Set up meal buttons →")
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .fillMaxWidth(),
            ) {
                pinnedTemplates.forEach { template ->
                    Button(onClick = { onLogTemplate(template) }) {
                        Text(template.name)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // --- Quick-add form (unchanged) ---
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            // ... existing kcal + label + Add button ...
        }
        // ... rest of existing content unchanged ...
    }
}
```

Add missing imports:
```kotlin
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.OutlinedButton
import org.branneman.health.db.entities.MealTemplateEntity
```

Also update `LogScreen` (the top-level composable) to collect `pinnedTemplates` from `viewModel` and pass the new parameters:

```kotlin
@Composable
fun LogScreen(
    viewModel: LogViewModel = viewModel(),
    onSetUpMealButtons: () -> Unit = {},
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val pinnedTemplates by viewModel.pinnedTemplates.collectAsStateWithLifecycle()
    // ... snackbar state unchanged ...

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        LogContent(
            entries            = entries,
            pinnedTemplates    = pinnedTemplates,
            onAdd              = { kcal, label ->
                viewModel.addEntry(kcal, label)
                lastAction = LogAction.Added("Logged")
            },
            onDelete           = { entry ->
                viewModel.deleteEntry(entry)
                lastAction = LogAction.Deleted("Deleted")
            },
            onSetUpMealButtons = onSetUpMealButtons,
            onLogTemplate      = { template ->
                viewModel.logFromTemplate(template)
                lastAction = LogAction.Added("Logged")
            },
            modifier           = Modifier.padding(padding),
        )
    }
}
```

Add `pinnedTemplates` to `LogViewModel`:

```kotlin
val pinnedTemplates: StateFlow<List<MealTemplateEntity>> = db.mealTemplateDao().observePinned()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
```

Add import in `LogViewModel.kt`:
```kotlin
import org.branneman.health.db.entities.MealTemplateEntity
```

- [ ] **Step 4: Run all LogScreen tests**

```bash
./gradlew :app:testDebugUnitTest --tests "org.branneman.health.ui.LogScreenTest"
```
Expected: All 8 tests pass (4 original + 4 new).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/ui/LogScreen.kt \
        app/src/main/kotlin/org/branneman/health/log/LogViewModel.kt \
        app/src/test/kotlin/org/branneman/health/ui/LogScreenTest.kt
git commit -m "feat(app): add one-tap meal button row to LogScreen; collect pinnedTemplates in LogViewModel"
```

---

## Task 14: MealButtonsScreen

**Files:**
- Create: `app/src/main/kotlin/org/branneman/health/ui/MealButtonsScreen.kt`
- Create: `app/src/test/kotlin/org/branneman/health/ui/MealButtonsScreenTest.kt`

- [ ] **Step 1: Write failing tests first**

```kotlin
package org.branneman.health.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.branneman.health.aMealTemplate
import org.branneman.health.db.entities.MealTemplateEntity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MealButtonsScreenTest {

    @get:Rule val compose = createComposeRule()

    private fun render(
        draft: List<MealTemplateEntity> = emptyList(),
        onSave: (List<MealTemplateEntity>) -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                MealButtonsContent(draft = draft, onSave = onSave, onBack = onBack)
            }
        }
    }

    @Test fun `empty state shows add button and disabled save`() {
        render(draft = emptyList())
        compose.onNodeWithText("Add button").assertExists()
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test fun `save is enabled when a row has name and positive kcal`() {
        val template = aMealTemplate(name = "Breakfast", sortOrder = 0, quickAddKcal = 450)
        render(draft = listOf(template))
        compose.onNodeWithText("Save").assertIsEnabled()
    }

    @Test fun `save is disabled when kcal is zero`() {
        val template = aMealTemplate(name = "Breakfast", sortOrder = 0, quickAddKcal = 0)
        render(draft = listOf(template))
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test fun `save is disabled when name is blank`() {
        val template = aMealTemplate(name = "", sortOrder = 0, quickAddKcal = 400)
        render(draft = listOf(template))
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test fun `tapping save calls onSave with current draft`() {
        var saved: List<MealTemplateEntity>? = null
        val template = aMealTemplate(name = "Usual lunch", sortOrder = 0, quickAddKcal = 600)
        render(draft = listOf(template), onSave = { saved = it })
        compose.onNodeWithText("Save").performClick()
        assertEquals(1, saved?.size)
        assertEquals("Usual lunch", saved?.first()?.name)
    }

    @Test fun `tapping delete removes row from displayed list`() {
        val template = aMealTemplate(name = "Usual snack", sortOrder = 0, quickAddKcal = 200)
        var afterDelete: List<MealTemplateEntity>? = null
        // Render with a custom onSave to capture the post-delete state
        render(
            draft  = listOf(template),
            onSave = { afterDelete = it },
        )
        compose.onNodeWithContentDescription("Delete Usual snack").performClick()
        compose.onNodeWithText("Save").performClick()
        assertTrue(afterDelete?.isEmpty() == true)
    }
}
```

- [ ] **Step 2: Run to confirm failure**

```bash
./gradlew :app:testDebugUnitTest --tests "org.branneman.health.ui.MealButtonsScreenTest"
```
Expected: FAIL — `MealButtonsContent` not found.

- [ ] **Step 3: Implement `MealButtonsScreen.kt`**

```kotlin
package org.branneman.health.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.branneman.health.db.entities.MealTemplateEntity

@Composable
fun MealButtonsScreen(
    onBack: () -> Unit,
    viewModel: MealButtonsViewModel = viewModel(),
) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    MealButtonsContent(
        draft  = draft ?: emptyList(),
        onSave = { viewModel.save() },
        onBack = onBack,
    )
}

@Composable
fun MealButtonsContent(
    draft: List<MealTemplateEntity>,
    onSave: (List<MealTemplateEntity>) -> Unit,
    onBack: () -> Unit,
) {
    var rows by remember(draft) {
        mutableStateOf(draft)
    }
    var showAddDialog by remember { mutableStateOf(false) }

    val saveEnabled = rows.isNotEmpty() && rows.all { it.name.isNotBlank() && (it.quickAddKcal ?: 0) > 0 }

    if (showAddDialog) {
        AddButtonDialog(
            onConfirm = { name, kcal ->
                val nextOrder = (rows.maxOfOrNull { it.sortOrder ?: -1 } ?: -1) + 1
                rows = rows + MealTemplateEntity(
                    userId = rows.firstOrNull()?.userId ?: "",
                    name = name, sortOrder = nextOrder, quickAddKcal = kcal,
                )
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Text("Meal buttons", style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f))
            Button(
                onClick  = { onSave(rows) },
                enabled  = saveEnabled,
                modifier = Modifier.padding(start = 8.dp),
            ) { Text("Save") }
        }

        Spacer(Modifier.height(16.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            itemsIndexed(rows, key = { _, r -> r.id }) { index, row ->
                MealButtonRow(
                    row       = row,
                    onUp      = { rows = rows.toMutableList().also {
                        if (index > 0) { val t = it[index-1]; it[index-1] = it[index]; it[index] = t }
                    }.reindexed() },
                    onDown    = { rows = rows.toMutableList().also {
                        if (index < it.size - 1) { val t = it[index+1]; it[index+1] = it[index]; it[index] = t }
                    }.reindexed() },
                    onDelete  = { rows = rows.toMutableList().also { it.removeAt(index) }.reindexed() },
                    onChange  = { updated -> rows = rows.toMutableList().also { it[index] = updated } },
                )
                HorizontalDivider()
            }
        }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick  = { showAddDialog = true },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Add button") }
    }
}

@Composable
private fun MealButtonRow(
    row: MealTemplateEntity,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onDelete: () -> Unit,
    onChange: (MealTemplateEntity) -> Unit,
) {
    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier              = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column {
            IconButton(onClick = onUp,   modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = null)
            }
            IconButton(onClick = onDown, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
            }
        }
        OutlinedTextField(
            value         = row.name,
            onValueChange = { onChange(row.copy(name = it)) },
            label         = { Text("Name") },
            singleLine    = true,
            modifier      = Modifier.weight(1f),
        )
        OutlinedTextField(
            value         = row.quickAddKcal?.toString() ?: "",
            onValueChange = { onChange(row.copy(quickAddKcal = it.filter(Char::isDigit).toIntOrNull())) },
            label         = { Text("kcal") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine    = true,
            modifier      = Modifier.width(72.dp),
        )
        IconButton(
            onClick  = onDelete,
            modifier = Modifier.semantics { contentDescription = "Delete ${row.name}" },
        ) {
            Icon(Icons.Default.Delete, contentDescription = null)
        }
    }
}

@Composable
private fun AddButtonDialog(onConfirm: (String, Int) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var kcal by remember { mutableStateOf("") }
    val confirmEnabled = name.isNotBlank() && (kcal.toIntOrNull() ?: 0) > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title   = { Text("New meal button") },
        text    = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it },
                    label = { Text("Name") }, singleLine = true)
                OutlinedTextField(value = kcal,
                    onValueChange = { kcal = it.filter(Char::isDigit) },
                    label = { Text("kcal") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, kcal.toInt()) }, enabled = confirmEnabled) {
                Text("Add")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
```

- [ ] **Step 4: Run the MealButtonsScreen tests**

```bash
./gradlew :app:testDebugUnitTest --tests "org.branneman.health.ui.MealButtonsScreenTest"
```
Expected: 6 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/ui/MealButtonsScreen.kt \
        app/src/test/kotlin/org/branneman/health/ui/MealButtonsScreenTest.kt
git commit -m "feat(app): add MealButtonsScreen settings sub-page with add/delete/reorder/save"
```

---

## Task 15: SettingsScreen update + SettingsScreenTest

**Files:**
- Modify: `app/src/main/kotlin/org/branneman/health/ui/SettingsScreen.kt`
- Create: `app/src/test/kotlin/org/branneman/health/ui/SettingsScreenTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package org.branneman.health.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SettingsScreenTest {

    @get:Rule val compose = createComposeRule()

    @Test fun `Meal buttons row is present and calls onNavigateMealButtons`() {
        var tapped = false
        compose.setContent {
            MaterialTheme {
                SettingsContent(
                    onNavigateMealButtons = { tapped = true },
                    onSignOut = {},
                )
            }
        }
        compose.onNodeWithText("Meal buttons", substring = true).assertExists()
        compose.onNodeWithText("Meal buttons", substring = true).performClick()
        assertTrue(tapped)
    }
}
```

- [ ] **Step 2: Run to confirm failure**

```bash
./gradlew :app:testDebugUnitTest --tests "org.branneman.health.ui.SettingsScreenTest"
```
Expected: FAIL — `SettingsContent` not found.

- [ ] **Step 3: Extract `SettingsContent` from `SettingsScreen` and add the Meal buttons row**

The current `SettingsScreen` composable mixes UI with side effects (server reachability check, LaunchedEffects). Extract the pure layout into `SettingsContent` so it is testable, and keep `SettingsScreen` as the stateful wrapper.

Add `SettingsContent` composable (pure layout, no ViewModels):

```kotlin
@Composable
fun SettingsContent(
    onNavigateMealButtons: () -> Unit,
    onSignOut: () -> Unit,
    serverReachable: Boolean? = null,
    lastSyncedAt: Long? = null,
    polarStatus: PolarStatus = PolarStatus.Unknown,
    onConnectPolar: (() -> Unit)? = null,
    onSyncNow: (() -> Unit)? = null,
    versionName: String = "",
) {
    // ... existing settings layout ...
    // Add before the Sync now button:
    TextButton(
        onClick  = onNavigateMealButtons,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Meal buttons →") }
    // ... rest unchanged ...
}
```

Update `SettingsScreen` to accept `onNavigateMealButtons` and pass it through:

```kotlin
@Composable
fun SettingsScreen(
    onSignOut: () -> Unit,
    onNavigateMealButtons: () -> Unit = {},
) {
    // ... existing state and LaunchedEffects ...
    SettingsContent(
        onNavigateMealButtons = onNavigateMealButtons,
        onSignOut             = onSignOut,
        serverReachable       = serverReachable,
        lastSyncedAt          = lastSyncedAt,
        polarStatus           = polarStatus,
        onConnectPolar        = if (polarStatus == PolarStatus.NotConnected) {{ viewModel.connectPolar { ... } }} else null,
        onSyncNow             = { SyncWorker.syncNow(context) },
        versionName           = BuildConfig.VERSION_NAME,
    )
}
```

- [ ] **Step 4: Run the Settings test**

```bash
./gradlew :app:testDebugUnitTest --tests "org.branneman.health.ui.SettingsScreenTest"
```
Expected: 1 test passes.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/ui/SettingsScreen.kt \
        app/src/test/kotlin/org/branneman/health/ui/SettingsScreenTest.kt
git commit -m "feat(app): add Meal buttons row to SettingsScreen; extract SettingsContent for testing"
```

---

## Task 16: App.kt — navigation wiring

**Files:**
- Modify: `app/src/main/kotlin/org/branneman/health/App.kt`

Introduce a `SettingsPage` enum in `MainNav` to handle the Settings sub-page. No `NavHost` restructure needed — the current `when (currentTab)` pattern extends naturally.

- [ ] **Step 1: Update `MainNav` in `App.kt`**

```kotlin
private enum class SettingsPage { Main, MealButtons }

@Composable
private fun MainNav(authViewModel: AuthViewModel) {
    var currentTab by remember { mutableStateOf(Tab.Dashboard) }
    var settingsPage by remember { mutableStateOf(SettingsPage.Main) }

    LaunchedEffect(currentTab) {
        if (currentTab != Tab.Settings) settingsPage = SettingsPage.Main
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = currentTab == tab,
                        onClick  = { currentTab = tab },
                        icon     = { Text(tab.emoji) },
                        label    = { Text(tab.label) }
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (currentTab) {
                Tab.Dashboard -> DashboardScreen()
                Tab.Log -> LogScreen(
                    onSetUpMealButtons = {
                        currentTab   = Tab.Settings
                        settingsPage = SettingsPage.MealButtons
                    }
                )
                Tab.Settings -> when (settingsPage) {
                    SettingsPage.Main -> SettingsScreen(
                        onSignOut             = { authViewModel.logout() },
                        onNavigateMealButtons = { settingsPage = SettingsPage.MealButtons },
                    )
                    SettingsPage.MealButtons -> MealButtonsScreen(
                        onBack = { settingsPage = SettingsPage.Main }
                    )
                }
            }
        }
    }
}
```

Add the missing import:
```kotlin
import org.branneman.health.ui.MealButtonsScreen
```

- [ ] **Step 2: Verify the app compiles and run all unit tests**

```bash
./gradlew :app:compileDebugKotlin && ./gradlew :app:testDebugUnitTest
```
Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/App.kt
git commit -m "feat(app): wire MealButtonsScreen into navigation; deep-link from log screen"
```

---

## Done

All story 9 behaviour is now implemented and tested. Run the full test suite one final time before declaring the story complete:

```bash
./gradlew :shared:test :server:test :app:testDebugUnitTest
```

Then update `docs/feature-backlog.md` to mark story 9 complete (add `✓` to the `#9` row).

```bash
git add docs/feature-backlog.md
git commit -m "docs: mark story 9 complete"
```
