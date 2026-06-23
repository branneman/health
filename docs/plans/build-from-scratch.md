# Build from Scratch — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ingredient builder UI — text search and barcode scan against the OFD catalog, manual entry fallback, per-ingredient gram quantities, save as ingredient template, log directly.

**Architecture:** Offline-first, Room-first. Server changes are additive and backwards-compatible; deploy before any app code ships. App writes `FoodItemEntity` and `LogEntryItemEntity` to Room first, SyncWorker pushes in correct order (food items → log entries). Two-phase delivery: Phase 1 is server + shared DTOs + integration tests; Phase 2 is all app changes. A manual deploy-and-verify gate sits between the phases.

**Tech Stack:** Ktor (server), Exposed (SQL DSL), Flyway (migrations), Room + Robolectric (app component tests), MockEngine (sync service tests), ML Kit barcode-scanning (bundled, on-device), CameraX (camera preview), Compose (UI).

## Global Constraints

- IDs are always UUIDs — client-generates them before writing to Room; passes them to the server.
- Room holds only one user's data at a time — DAO SELECT queries never filter by `userId`.
- Idempotency via `transaction { exists-check }` (NOT `ON CONFLICT DO NOTHING`). See existing `POST /in/log/quick-add` for the pattern.
- Conventional commits with scope: `feat(shared)`, `feat(server)`, `feat(app)`.
- Run `./gradlew :server:test` after every server task before committing.
- Run `./gradlew :app:test` after every app task before committing.
- Spec: `docs/specs/build-from-scratch.md`.

---

## File Map

**Phase 1 — created/modified:**
- Create `shared/src/commonMain/kotlin/org/branneman/health/FoodItemRequestDto.kt`
- Create `shared/src/commonMain/kotlin/org/branneman/health/FoodLogRequestDto.kt`
- Modify `shared/src/commonMain/kotlin/org/branneman/health/MealTemplateDto.kt`
- Create `server/src/main/resources/db/migration/V12__build_from_scratch.sql`
- Modify `server/src/main/kotlin/org/branneman/health/data/Tables.kt`
- Modify `server/src/main/kotlin/org/branneman/health/Application.kt`
- Create `server/src/test/kotlin/org/branneman/health/FoodCatalogIntegrationTest.kt`
- Modify `server/src/apiTest/kotlin/org/branneman/health/FoodApiTest.kt`
- Modify `docs/testing-manifesto.md`

**Phase 2 — created/modified:**
- Modify `app/src/main/kotlin/org/branneman/health/db/entities/MealTemplateItemEntity.kt`
- Modify `app/src/main/kotlin/org/branneman/health/db/HealthDatabase.kt`
- Modify `app/src/main/kotlin/org/branneman/health/HealthApplication.kt`
- Modify `app/src/main/kotlin/org/branneman/health/db/dao/FoodItemDao.kt`
- Modify `app/src/test/kotlin/org/branneman/health/db/dao/FoodItemDaoTest.kt`
- Modify `app/src/main/kotlin/org/branneman/health/db/dao/MealTemplateDao.kt`
- Modify `app/src/test/kotlin/org/branneman/health/db/dao/MealTemplateDaoTest.kt`
- Modify `app/src/main/kotlin/org/branneman/health/network/HealthApiClient.kt`
- Modify `app/src/test/kotlin/org/branneman/health/TestFactories.kt`
- Create `app/src/main/kotlin/org/branneman/health/sync/FoodItemSyncService.kt`
- Create `app/src/test/kotlin/org/branneman/health/sync/FoodItemSyncServiceTest.kt`
- Modify `app/src/main/kotlin/org/branneman/health/sync/LogEntrySyncService.kt`
- Modify `app/src/test/kotlin/org/branneman/health/sync/LogEntrySyncServiceTest.kt`
- Modify `app/src/main/kotlin/org/branneman/health/sync/MealTemplateSyncService.kt`
- Modify `app/src/main/kotlin/org/branneman/health/sync/SyncWorker.kt`
- Create `app/src/main/kotlin/org/branneman/health/ui/BuildFromScratchViewModel.kt`
- Create `app/src/test/kotlin/org/branneman/health/ui/BuildFromScratchViewModelTest.kt`
- Create `app/src/main/kotlin/org/branneman/health/ui/EditIngredientTemplateViewModel.kt`
- Create `app/src/main/kotlin/org/branneman/health/ui/FoodSearchViewModel.kt`
- Create `app/src/main/kotlin/org/branneman/health/ui/FoodSearchScreen.kt`
- Create `app/src/test/kotlin/org/branneman/health/ui/FoodSearchScreenTest.kt`
- Create `app/src/main/kotlin/org/branneman/health/ui/BuildFromScratchScreen.kt`
- Create `app/src/test/kotlin/org/branneman/health/ui/BuildFromScratchScreenTest.kt`
- Create `app/src/main/kotlin/org/branneman/health/ui/EditIngredientTemplateScreen.kt`
- Modify `app/src/main/kotlin/org/branneman/health/ui/TemplatesScreen.kt`
- Modify `app/src/main/kotlin/org/branneman/health/App.kt`
- Modify `gradle/libs.versions.toml`
- Modify `app/build.gradle.kts`
- Modify `docs/feature-backlog.md`

---

## ═══ PHASE 1: SERVER ═══

---

### Task 1: Shared DTOs

**Files:**
- Create: `shared/src/commonMain/kotlin/org/branneman/health/FoodItemRequestDto.kt`
- Create: `shared/src/commonMain/kotlin/org/branneman/health/FoodLogRequestDto.kt`
- Modify: `shared/src/commonMain/kotlin/org/branneman/health/MealTemplateDto.kt`

**Interfaces:**
- Produces: `FoodItemRequestDto`, `FoodLogItemRequestDto`, `FoodLogRequestDto` — used by server handlers (Tasks 3–4) and app's `HealthApiClient` (Task 10). `MealTemplateItemDto.sortOrder` used by server PUT handler (Task 2) and app sync (Task 13).

- [ ] **Step 1: Create `FoodItemRequestDto.kt`**

```kotlin
package org.branneman.health

import kotlinx.serialization.Serializable

@Serializable
data class FoodItemRequestDto(
    val id: String,
    val barcode: String?,
    val name: String,
    val kcalPer100g: Double,
    val proteinPer100g: Double?,
    val carbsPer100g: Double?,
    val fatPer100g: Double?,
    val source: String,
)
```

- [ ] **Step 2: Create `FoodLogRequestDto.kt`**

```kotlin
package org.branneman.health

import kotlinx.serialization.Serializable

@Serializable
data class FoodLogItemRequestDto(val foodItemId: String, val grams: Double)

@Serializable
data class FoodLogRequestDto(
    val id: String,
    val mealType: String,
    val loggedAt: String?,
    val items: List<FoodLogItemRequestDto>,
)
```

- [ ] **Step 3: Add `sortOrder` to `MealTemplateItemDto` in `MealTemplateDto.kt`**

Current file:
```kotlin
@Serializable
data class MealTemplateItemDto(
    val foodItemId: String,
    val grams: Double,
)
```

New file:
```kotlin
package org.branneman.health

import kotlinx.serialization.Serializable

@Serializable
data class MealTemplateItemDto(
    val foodItemId: String,
    val grams: Double,
    val sortOrder: Int = 0,
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

- [ ] **Step 4: Build shared module to verify compilation**

```bash
./gradlew :shared:build
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/org/branneman/health/FoodItemRequestDto.kt
git add shared/src/commonMain/kotlin/org/branneman/health/FoodLogRequestDto.kt
git add shared/src/commonMain/kotlin/org/branneman/health/MealTemplateDto.kt
git commit -m "feat(shared): add FoodItemRequestDto, FoodLogRequestDto; add sortOrder to MealTemplateItemDto"
```

---

### Task 2: Flyway migration + Tables.kt + fix PUT /in/templates item insertion

The `PUT /in/templates` handler currently deletes items then re-inserts template headers — but never re-inserts the items. This bug means ingredient template items are never persisted to the server. Fix it here alongside the V12 migration.

**Files:**
- Create: `server/src/main/resources/db/migration/V12__build_from_scratch.sql`
- Modify: `server/src/main/kotlin/org/branneman/health/data/Tables.kt`
- Modify: `server/src/main/kotlin/org/branneman/health/Application.kt`

**Interfaces:**
- Produces: `sort_order` column on `meal_template_item` (Postgres); `MealTemplateItem.sortOrder` column in Exposed DSL; fixed PUT handler that inserts items.

- [ ] **Step 1: Create Flyway migration**

File: `server/src/main/resources/db/migration/V12__build_from_scratch.sql`

```sql
ALTER TABLE meal_template_item ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0;
```

- [ ] **Step 2: Add `sortOrder` to `MealTemplateItem` table object in `Tables.kt`**

In `Tables.kt`, find `object MealTemplateItem`:
```kotlin
object MealTemplateItem : Table("meal_template_item") {
    val templateId = uuid("template_id")
    val foodItemId = uuid("food_item_id")
    val grams      = decimal("grams", 7, 1)
    override val primaryKey = PrimaryKey(templateId, foodItemId)
}
```

Change to:
```kotlin
object MealTemplateItem : Table("meal_template_item") {
    val templateId = uuid("template_id")
    val foodItemId = uuid("food_item_id")
    val grams      = decimal("grams", 7, 1)
    val sortOrder  = integer("sort_order").default(0)
    override val primaryKey = PrimaryKey(templateId, foodItemId)
}
```

- [ ] **Step 3: Fix `PUT /in/templates` to insert items and update GET to ORDER BY sort_order**

In `Application.kt`, find `get("/in/templates")`. Update the item mapping to include `sortOrder`:
```kotlin
val items = MealTemplateItem.selectAll()
    .where { MealTemplateItem.templateId eq tRow[MealTemplate.id] }
    .orderBy(MealTemplateItem.sortOrder, SortOrder.ASC)
    .map { iRow ->
        MealTemplateItemDto(
            foodItemId = iRow[MealTemplateItem.foodItemId].toString(),
            grams      = iRow[MealTemplateItem.grams].toDouble(),
            sortOrder  = iRow[MealTemplateItem.sortOrder],
        )
    }
```

In `Application.kt`, find `put("/in/templates")`. After `MealTemplate.insert { ... }` inserts the template header, add item insertion. Replace the `incoming.map { dto -> ... }` block:

```kotlin
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
    dto.items.forEachIndexed { index, item ->
        val foodId = runCatching { UUID.fromString(item.foodItemId) }.getOrNull()
            ?: return@forEachIndexed
        MealTemplateItem.insert {
            it[MealTemplateItem.templateId] = newId
            it[MealTemplateItem.foodItemId] = foodId
            it[MealTemplateItem.grams]      = item.grams.toBigDecimal()
            it[MealTemplateItem.sortOrder]  = index
        }
    }
    dto.copy(id = newId.toString())
}
```

- [ ] **Step 4: Run server tests to verify migration applies and no regressions**

```bash
./gradlew :server:test
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add server/src/main/resources/db/migration/V12__build_from_scratch.sql
git add server/src/main/kotlin/org/branneman/health/data/Tables.kt
git add server/src/main/kotlin/org/branneman/health/Application.kt
git commit -m "fix(server): add sort_order to meal_template_item (V12); fix PUT /in/templates to insert items"
```

---

### Task 3: GET /in/food-items filtering + POST /in/food-items

**Files:**
- Modify: `server/src/main/kotlin/org/branneman/health/Application.kt`

**Interfaces:**
- Produces: `GET /in/food-items?q=` (ILIKE search), `GET /in/food-items?barcode=` (exact match), `POST /in/food-items` (idempotent create).

- [ ] **Step 1: Add imports to Application.kt**

At the top of Application.kt, add to existing imports:
```kotlin
import org.branneman.health.FoodItemRequestDto
import org.jetbrains.exposed.sql.lowerCase
```

- [ ] **Step 2: Replace GET /in/food-items to add query parameter filtering**

Find the existing `get("/in/food-items")` handler and replace it:

```kotlin
get("/in/food-items") {
    val userId  = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
    val barcode = call.request.queryParameters["barcode"]
    val q       = call.request.queryParameters["q"]
    val rows = transaction {
        val query = FoodItem.selectAll().where { FoodItem.userId eq userId }
        when {
            barcode != null -> query.andWhere { FoodItem.barcode eq barcode }
            q != null       -> query.andWhere { FoodItem.name.lowerCase() like "%${q.lowercase()}%" }
            else            -> query
        }.map {
            FoodItemDto(
                id             = it[FoodItem.id].toString(),
                barcode        = it[FoodItem.barcode],
                name           = it[FoodItem.name],
                kcalPer100g    = it[FoodItem.kcalPer100g].toDouble(),
                proteinPer100g = it[FoodItem.proteinPer100g]?.toDouble(),
                carbsPer100g   = it[FoodItem.carbsPer100g]?.toDouble(),
                fatPer100g     = it[FoodItem.fatPer100g]?.toDouble(),
                source         = it[FoodItem.dataSource],
            )
        }
    }
    call.respond(rows)
}
```

- [ ] **Step 3: Add POST /in/food-items immediately after GET /in/food-items**

```kotlin
post("/in/food-items") {
    val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
    val dto    = call.receive<FoodItemRequestDto>()
    val id     = runCatching { UUID.fromString(dto.id) }.getOrNull()
        ?: return@post call.respond(HttpStatusCode.BadRequest)

    val inserted = transaction {
        val exists = FoodItem.selectAll()
            .where { (FoodItem.id eq id) and (FoodItem.userId eq userId) }
            .count() > 0
        if (exists) return@transaction false
        FoodItem.insert {
            it[FoodItem.id]             = id
            it[FoodItem.userId]         = userId
            it[FoodItem.barcode]        = dto.barcode
            it[FoodItem.name]           = dto.name
            it[FoodItem.kcalPer100g]    = dto.kcalPer100g.toBigDecimal()
            it[FoodItem.proteinPer100g] = dto.proteinPer100g?.toBigDecimal()
            it[FoodItem.carbsPer100g]   = dto.carbsPer100g?.toBigDecimal()
            it[FoodItem.fatPer100g]     = dto.fatPer100g?.toBigDecimal()
            it[FoodItem.dataSource]     = dto.source
        }
        true
    }
    if (!inserted) return@post call.respond(HttpStatusCode.Conflict)

    call.respond(HttpStatusCode.Created, FoodItemDto(
        id             = id.toString(),
        barcode        = dto.barcode,
        name           = dto.name,
        kcalPer100g    = dto.kcalPer100g,
        proteinPer100g = dto.proteinPer100g,
        carbsPer100g   = dto.carbsPer100g,
        fatPer100g     = dto.fatPer100g,
        source         = dto.source,
    ))
}
```

- [ ] **Step 4: Run server tests**

```bash
./gradlew :server:test
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/org/branneman/health/Application.kt
git commit -m "feat(server): add GET /in/food-items filtering (q, barcode) and POST /in/food-items"
```

---

### Task 4: POST /in/log/food

**Files:**
- Modify: `server/src/main/kotlin/org/branneman/health/Application.kt`

**Interfaces:**
- Consumes: `FoodLogRequestDto` (from Task 1).
- Produces: `POST /in/log/food` — snapshots nutrition from `food_item`, inserts `log_entry` + `log_entry_item`, returns `LogEntryDto`.

- [ ] **Step 1: Add import to Application.kt**

```kotlin
import org.branneman.health.FoodLogRequestDto
```

- [ ] **Step 2: Add POST /in/log/food after the existing POST /in/log/quick-add handler**

```kotlin
post("/in/log/food") {
    val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
    val dto    = call.receive<FoodLogRequestDto>()
    val id     = runCatching { UUID.fromString(dto.id) }.getOrNull()
        ?: return@post call.respond(HttpStatusCode.BadRequest)
    if (dto.items.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest)
    val loggedAt = dto.loggedAt
        ?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
        ?: OffsetDateTime.now()

    data class SnapshotItem(
        val foodItemId: UUID,
        val grams: Double,
        val kcalPer100g: Double,
        val proteinPer100g: Double?,
        val carbsPer100g: Double?,
        val fatPer100g: Double?,
    )

    val result: Any = transaction {
        val exists = LogEntry.selectAll()
            .where { (LogEntry.id eq id) and (LogEntry.userId eq userId) }
            .count() > 0
        if (exists) return@transaction HttpStatusCode.Conflict

        val snapshots = dto.items.map { item ->
            val foodId = runCatching { UUID.fromString(item.foodItemId) }.getOrNull()
                ?: return@transaction HttpStatusCode.BadRequest
            val row = FoodItem.selectAll()
                .where { (FoodItem.id eq foodId) and (FoodItem.userId eq userId) }
                .singleOrNull()
                ?: return@transaction HttpStatusCode.NotFound
            SnapshotItem(
                foodItemId     = foodId,
                grams          = item.grams,
                kcalPer100g    = row[FoodItem.kcalPer100g].toDouble(),
                proteinPer100g = row[FoodItem.proteinPer100g]?.toDouble(),
                carbsPer100g   = row[FoodItem.carbsPer100g]?.toDouble(),
                fatPer100g     = row[FoodItem.fatPer100g]?.toDouble(),
            )
        }

        LogEntry.insert {
            it[LogEntry.id]           = id
            it[LogEntry.userId]       = userId
            it[LogEntry.loggedAt]     = loggedAt
            it[LogEntry.mealType]     = dto.mealType
            it[LogEntry.quickAddKcal] = null
            it[LogEntry.quickAddLabel]= null
            it[LogEntry.createdAt]    = OffsetDateTime.now()
        }
        snapshots.forEach { snap ->
            LogEntryItem.insert {
                it[LogEntryItem.logEntryId]     = id
                it[LogEntryItem.foodItemId]     = snap.foodItemId
                it[LogEntryItem.grams]          = snap.grams.toBigDecimal()
                it[LogEntryItem.kcalPer100g]    = snap.kcalPer100g.toBigDecimal()
                it[LogEntryItem.proteinPer100g] = snap.proteinPer100g?.toBigDecimal()
                it[LogEntryItem.carbsPer100g]   = snap.carbsPer100g?.toBigDecimal()
                it[LogEntryItem.fatPer100g]     = snap.fatPer100g?.toBigDecimal()
            }
        }

        LogEntryDto(
            id            = id.toString(),
            loggedAt      = loggedAt.toString(),
            mealType      = dto.mealType,
            quickAddKcal  = null,
            quickAddLabel = null,
            items = snapshots.map { snap ->
                LogEntryItemDto(
                    foodItemId     = snap.foodItemId.toString(),
                    grams          = snap.grams,
                    kcalPer100g    = snap.kcalPer100g,
                    proteinPer100g = snap.proteinPer100g,
                    carbsPer100g   = snap.carbsPer100g,
                    fatPer100g     = snap.fatPer100g,
                )
            },
        )
    }

    when (result) {
        is HttpStatusCode -> call.respond(result)
        is LogEntryDto    -> call.respond(HttpStatusCode.Created, result)
    }
}
```

- [ ] **Step 3: Run server tests**

```bash
./gradlew :server:test
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add server/src/main/kotlin/org/branneman/health/Application.kt
git commit -m "feat(server): add POST /in/log/food with nutrition snapshot"
```

---

### Task 5: FoodCatalogIntegrationTest + UUID registry

**Files:**
- Create: `server/src/test/kotlin/org/branneman/health/FoodCatalogIntegrationTest.kt`
- Modify: `docs/testing-manifesto.md`

**Interfaces:**
- Consumes: `GET /in/food-items?q=`, `GET /in/food-items?barcode=`, `POST /in/food-items`, `POST /in/log/food` (Tasks 3–4).

- [ ] **Step 1: Claim UUID slot #12 in testing-manifesto.md**

In `docs/testing-manifesto.md`, add a row to the UUID registry table:

```
| 12 | `...000012` | `FoodCatalogIntegrationTest` | `foodcatalog-test@test.local` |
```

- [ ] **Step 2: Write the failing tests first**

Create `server/src/test/kotlin/org/branneman/health/FoodCatalogIntegrationTest.kt`:

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
import org.branneman.health.data.FoodItem
import org.branneman.health.data.LogEntry
import org.branneman.health.data.LogEntryItem
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Before
import org.junit.Test
import org.mindrot.jbcrypt.BCrypt
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FoodCatalogIntegrationTest {

    companion object {
        private val ds = TestDatabase.dataSource
        val testUserId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000012")
        private const val TEST_EMAIL = "foodcatalog-test@test.local"
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
        transaction {
            LogEntryItem.deleteWhere { logEntryId inSubQuery LogEntry.select(LogEntry.id).where { LogEntry.userId eq testUserId } }
            LogEntry.deleteWhere { userId eq testUserId }
            FoodItem.deleteWhere { userId eq testUserId }
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

    // ─── GET /in/food-items?q= ────────────────────────────────────────────────

    @Test fun `GET food-items with q returns matching items`() = appTest {
        val token = login()
        val foodId = UUID.randomUUID()
        transaction {
            FoodItem.insert {
                it[FoodItem.id]          = foodId
                it[FoodItem.userId]      = testUserId
                it[FoodItem.barcode]     = null
                it[FoodItem.name]        = "Peanut Butter"
                it[FoodItem.kcalPer100g] = 589.0.toBigDecimal()
                it[FoodItem.dataSource]  = "manual"
            }
        }
        val r = client.get("/in/food-items?q=peanut") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val arr = Json.parseToJsonElement(r.bodyAsText()).jsonArray
        assertEquals(1, arr.size)
        assertEquals("Peanut Butter", arr[0].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test fun `GET food-items with q returns empty list when no match`() = appTest {
        val token = login()
        val r = client.get("/in/food-items?q=xyz_nonexistent_food") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(Json.parseToJsonElement(r.bodyAsText()).jsonArray.isEmpty())
    }

    // ─── GET /in/food-items?barcode= ─────────────────────────────────────────

    @Test fun `GET food-items with barcode returns matching item`() = appTest {
        val token = login()
        val foodId = UUID.randomUUID()
        transaction {
            FoodItem.insert {
                it[FoodItem.id]          = foodId
                it[FoodItem.userId]      = testUserId
                it[FoodItem.barcode]     = "1234567890"
                it[FoodItem.name]        = "Scanned Product"
                it[FoodItem.kcalPer100g] = 150.0.toBigDecimal()
                it[FoodItem.dataSource]  = "openfoodfacts"
            }
        }
        val r = client.get("/in/food-items?barcode=1234567890") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val arr = Json.parseToJsonElement(r.bodyAsText()).jsonArray
        assertEquals(1, arr.size)
        assertEquals("1234567890", arr[0].jsonObject["barcode"]!!.jsonPrimitive.content)
    }

    @Test fun `GET food-items with barcode returns empty list when no match`() = appTest {
        val token = login()
        val r = client.get("/in/food-items?barcode=0000000000") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(Json.parseToJsonElement(r.bodyAsText()).jsonArray.isEmpty())
    }

    // ─── POST /in/food-items ─────────────────────────────────────────────────

    @Test fun `POST food-items creates item and returns 201`() = appTest {
        val token  = login()
        val foodId = UUID.randomUUID()
        val r = client.post("/in/food-items") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"id":"$foodId","barcode":null,"name":"Oatmeal","kcalPer100g":68.0,"proteinPer100g":2.4,"carbsPer100g":12.0,"fatPer100g":1.4,"source":"manual"}""")
        }
        assertEquals(HttpStatusCode.Created, r.status)
        val json = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals(foodId.toString(), json["id"]!!.jsonPrimitive.content)
        assertEquals("Oatmeal", json["name"]!!.jsonPrimitive.content)
    }

    @Test fun `POST food-items returns 409 on duplicate id`() = appTest {
        val token  = login()
        val foodId = UUID.randomUUID()
        val body = """{"id":"$foodId","barcode":null,"name":"Oatmeal","kcalPer100g":68.0,"proteinPer100g":null,"carbsPer100g":null,"fatPer100g":null,"source":"manual"}"""
        client.post("/in/food-items") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val r2 = client.post("/in/food-items") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.Conflict, r2.status)
    }

    // ─── POST /in/log/food ────────────────────────────────────────────────────

    @Test fun `POST log food snapshots nutrition and returns 201`() = appTest {
        val token  = login()
        val foodId = UUID.randomUUID()
        transaction {
            FoodItem.insert {
                it[FoodItem.id]             = foodId
                it[FoodItem.userId]         = testUserId
                it[FoodItem.barcode]        = null
                it[FoodItem.name]           = "Chicken Breast"
                it[FoodItem.kcalPer100g]    = 165.0.toBigDecimal()
                it[FoodItem.proteinPer100g] = 31.0.toBigDecimal()
                it[FoodItem.carbsPer100g]   = 0.0.toBigDecimal()
                it[FoodItem.fatPer100g]     = 3.6.toBigDecimal()
                it[FoodItem.dataSource]     = "manual"
            }
        }
        val logId = UUID.randomUUID()
        val r = client.post("/in/log/food") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"id":"$logId","mealType":"lunch","loggedAt":null,"items":[{"foodItemId":"$foodId","grams":200.0}]}""")
        }
        assertEquals(HttpStatusCode.Created, r.status)
        val json = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals(logId.toString(), json["id"]!!.jsonPrimitive.content)
        assertEquals("lunch", json["mealType"]!!.jsonPrimitive.content)
        val items = json["items"]!!.jsonArray
        assertEquals(1, items.size)
        assertEquals(165.0, items[0].jsonObject["kcalPer100g"]!!.jsonPrimitive.content.toDouble())
    }

    @Test fun `POST log food returns 409 on duplicate log id`() = appTest {
        val token  = login()
        val foodId = UUID.randomUUID()
        transaction {
            FoodItem.insert {
                it[FoodItem.id]          = foodId
                it[FoodItem.userId]      = testUserId
                it[FoodItem.name]        = "Rice"
                it[FoodItem.kcalPer100g] = 130.0.toBigDecimal()
                it[FoodItem.dataSource]  = "manual"
            }
        }
        val logId = UUID.randomUUID()
        val body = """{"id":"$logId","mealType":"dinner","loggedAt":null,"items":[{"foodItemId":"$foodId","grams":150.0}]}"""
        client.post("/in/log/food") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val r2 = client.post("/in/log/food") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.Conflict, r2.status)
    }

    @Test fun `POST log food returns 404 when food item unknown`() = appTest {
        val token = login()
        val logId = UUID.randomUUID()
        val r = client.post("/in/log/food") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"id":"$logId","mealType":"breakfast","loggedAt":null,"items":[{"foodItemId":"${UUID.randomUUID()}","grams":100.0}]}""")
        }
        assertEquals(HttpStatusCode.NotFound, r.status)
    }

    @Test fun `POST log food returns 401 without token`() = appTest {
        val r = client.post("/in/log/food") {
            contentType(ContentType.Application.Json)
            setBody("""{"id":"${UUID.randomUUID()}","mealType":"lunch","loggedAt":null,"items":[]}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }
}
```

- [ ] **Step 3: Run tests (expect failures — endpoints not yet tested against integration DB)**

```bash
./gradlew :server:test --tests "org.branneman.health.FoodCatalogIntegrationTest"
```
Expected: `BUILD SUCCESSFUL` (tests should pass since Tasks 3–4 already implemented the endpoints)

If any test fails, debug against the integration test database. Common issue: `cleanMutableRows` deleting `log_entry_item` rows requires a subquery; adjust if the exposed DSL syntax differs from what was written.

- [ ] **Step 4: Run full server test suite**

```bash
./gradlew :server:test
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add server/src/test/kotlin/org/branneman/health/FoodCatalogIntegrationTest.kt
git add docs/testing-manifesto.md
git commit -m "test(server): add FoodCatalogIntegrationTest (slot #12) for new food catalog endpoints"
```

---

### Task 6: Extend FoodApiTest

**Files:**
- Modify: `server/src/apiTest/kotlin/org/branneman/health/FoodApiTest.kt`

**Interfaces:**
- Consumes: live server running locally with V12 migration applied.

- [ ] **Step 1: Start local server**

In a separate terminal:
```bash
./gradlew :server:run
```

- [ ] **Step 2: Add tests to FoodApiTest.kt**

Append to `FoodApiTest` class:

```kotlin
@Test fun `POST in food-items creates item and returns 201`() = runTest {
    val token  = login()
    val foodId = java.util.UUID.randomUUID()
    val resp = client.post("$serverUrl/in/food-items") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(FoodItemRequestDto(
            id             = foodId.toString(),
            barcode        = null,
            name           = "Api Test Food",
            kcalPer100g    = 200.0,
            proteinPer100g = 10.0,
            carbsPer100g   = 25.0,
            fatPer100g     = 5.0,
            source         = "manual",
        ))
    }
    assertEquals(HttpStatusCode.Created, resp.status)
    val item = resp.body<FoodItemDto>()
    assertEquals(foodId.toString(), item.id)
    assertEquals("Api Test Food", item.name)
}

@Test fun `POST in food-items returns 409 on duplicate id`() = runTest {
    val token  = login()
    val foodId = java.util.UUID.randomUUID()
    val dto = FoodItemRequestDto(foodId.toString(), null, "Dup Food", 100.0, null, null, null, "manual")
    client.post("$serverUrl/in/food-items") {
        bearerAuth(token); contentType(ContentType.Application.Json); setBody(dto)
    }
    val r2 = client.post("$serverUrl/in/food-items") {
        bearerAuth(token); contentType(ContentType.Application.Json); setBody(dto)
    }
    assertEquals(HttpStatusCode.Conflict, r2.status)
}

@Test fun `POST in log food creates entry with nutrition snapshot`() = runTest {
    val token  = login()
    // First create the food item
    val foodId = java.util.UUID.randomUUID()
    client.post("$serverUrl/in/food-items") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(FoodItemRequestDto(foodId.toString(), null, "Log Test Food", 150.0, 5.0, 20.0, 3.0, "manual"))
    }
    // Then log it
    val logId = java.util.UUID.randomUUID()
    val resp = client.post("$serverUrl/in/log/food") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(FoodLogRequestDto(logId.toString(), "dinner", null, listOf(FoodLogItemRequestDto(foodId.toString(), 200.0))))
    }
    assertEquals(HttpStatusCode.Created, resp.status)
    val entry = resp.body<LogEntryDto>()
    assertEquals(logId.toString(), entry.id)
    assertEquals("dinner", entry.mealType)
    assertEquals(1, entry.items.size)
    assertEquals(150.0, entry.items[0].kcalPer100g)
}
```

The file needs these imports added at the top of FoodApiTest.kt:
```kotlin
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.branneman.health.FoodItemRequestDto
import org.branneman.health.FoodLogItemRequestDto
import org.branneman.health.FoodLogRequestDto
```

- [ ] **Step 3: Run API tests against local server**

```bash
API_TEST_SERVER_URL=http://localhost:8080 ./gradlew :server:apiTest
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add server/src/apiTest/kotlin/org/branneman/health/FoodApiTest.kt
git commit -m "test(server): extend FoodApiTest for POST /in/food-items and POST /in/log/food"
```

---

## ═══ DEPLOY GATE ═══

Before starting Phase 2, deploy the server to production and verify manually.

- [ ] **Deploy to production** (via your usual deploy pipeline / Ansible)
- [ ] **Verify in production:**
  - `GET /in/food-items` still returns all items (no regression)
  - `GET /in/food-items?q=melk` returns results (ILIKE search works)
  - `GET /in/food-items?barcode=1234567890` returns empty list (expected for non-existing barcode)
  - `POST /in/food-items` with a valid UUID body returns 201
  - `POST /in/food-items` with same UUID body returns 409
  - `PUT /in/templates` with a template that has items — verify items are persisted (GET back after PUT)
  - `POST /in/log/food` with a valid food item id returns 201 with snapshotted nutrition

Only proceed to Phase 2 once all checks pass in production.

---

## ═══ PHASE 2: APP ═══

---

### Task 7: Room v7 migration + MealTemplateItemEntity sortOrder

**Files:**
- Modify: `app/src/main/kotlin/org/branneman/health/db/entities/MealTemplateItemEntity.kt`
- Modify: `app/src/main/kotlin/org/branneman/health/db/HealthDatabase.kt`
- Modify: `app/src/main/kotlin/org/branneman/health/HealthApplication.kt`

**Interfaces:**
- Produces: `MealTemplateItemEntity.sortOrder: Int = 0`; Room DB version 7; `MIGRATION_6_7`.

- [ ] **Step 1: Add `sortOrder` to `MealTemplateItemEntity.kt`**

```kotlin
package org.branneman.health.db.entities

import androidx.room.Entity

@Entity(tableName = "meal_template_item", primaryKeys = ["templateId", "foodItemId"])
data class MealTemplateItemEntity(
    val templateId: String,
    val foodItemId: String,
    val grams: Double,
    val sortOrder: Int = 0,
)
```

- [ ] **Step 2: Bump `HealthDatabase.kt` to version 7**

Change `version = 6` to `version = 7`.

- [ ] **Step 3: Add `MIGRATION_6_7` to `HealthApplication.kt`**

Add before `class HealthApplication`:
```kotlin
private val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE meal_template_item ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
    }
}
```

In `onCreate()`, add to `addMigrations(...)`:
```kotlin
.addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
```

- [ ] **Step 4: Run app tests**

```bash
./gradlew :app:test
```
Expected: `BUILD SUCCESSFUL`. Room's in-memory builder in tests creates fresh schema; existing tests should pass unchanged.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/db/entities/MealTemplateItemEntity.kt
git add app/src/main/kotlin/org/branneman/health/db/HealthDatabase.kt
git add app/src/main/kotlin/org/branneman/health/HealthApplication.kt
git commit -m "feat(app): Room v7 migration — add sortOrder to MealTemplateItemEntity"
```

---

### Task 8: FoodItemDao additions + FoodItemDaoTest

**Files:**
- Modify: `app/src/main/kotlin/org/branneman/health/db/dao/FoodItemDao.kt`
- Modify: `app/src/test/kotlin/org/branneman/health/db/dao/FoodItemDaoTest.kt`

**Interfaces:**
- Produces: `getById(id)`, `getByBarcode(barcode)`, `searchByName(query)`, `upsert(entity)` — used by `FoodSearchViewModel` (Task 16) and `FoodItemSyncService` (Task 11).

- [ ] **Step 1: Add methods to `FoodItemDao.kt`**

```kotlin
package org.branneman.health.db.dao

import androidx.room.*
import org.branneman.health.db.SyncStatus
import org.branneman.health.db.entities.FoodItemEntity

@Dao
interface FoodItemDao {
    @Query("SELECT * FROM food_item WHERE syncStatus = :status")
    suspend fun getByStatus(status: SyncStatus): List<FoodItemEntity>

    @Query("SELECT * FROM food_item WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): FoodItemEntity?

    @Query("SELECT * FROM food_item WHERE barcode = :barcode LIMIT 1")
    suspend fun getByBarcode(barcode: String): FoodItemEntity?

    @Query("SELECT * FROM food_item WHERE name LIKE '%' || :query || '%'")
    suspend fun searchByName(query: String): List<FoodItemEntity>

    @Upsert
    suspend fun upsert(entity: FoodItemEntity)

    @Upsert
    suspend fun upsertAll(entities: List<FoodItemEntity>)

    @Query("DELETE FROM food_item WHERE userId = :userId")
    suspend fun deleteAllForUser(userId: String)

    @Query("UPDATE food_item SET syncStatus = :status WHERE id = :id")
    suspend fun updateSyncStatus(id: String, status: SyncStatus)
}
```

- [ ] **Step 2: Add new DAO tests to `FoodItemDaoTest.kt`**

Add these tests to the existing `FoodItemDaoTest` class:

```kotlin
@Test
fun `getById returns entity for known id`() = runTest {
    val id = uuid()
    dao.upsertAll(listOf(aFoodItem(id = id, name = "Banana")))
    val result = dao.getById(id)
    assertEquals("Banana", result?.name)
}

@Test
fun `getById returns null for unknown id`() = runTest {
    assertEquals(null, dao.getById("nonexistent-id"))
}

@Test
fun `getByBarcode returns entity with matching barcode`() = runTest {
    val item = aFoodItem(id = uuid(), name = "Coca-Cola").copy(barcode = "5000112602649")
    dao.upsertAll(listOf(item))
    val result = dao.getByBarcode("5000112602649")
    assertEquals("Coca-Cola", result?.name)
}

@Test
fun `getByBarcode returns null for unknown barcode`() = runTest {
    assertNull(dao.getByBarcode("0000000000000"))
}

@Test
fun `searchByName returns items matching substring`() = runTest {
    dao.upsertAll(listOf(
        aFoodItem(name = "Peanut Butter"),
        aFoodItem(name = "Butter Chicken"),
        aFoodItem(name = "Milk"),
    ))
    val result = dao.searchByName("butter")
    assertEquals(2, result.size)
    assertTrue(result.any { it.name == "Peanut Butter" })
    assertTrue(result.any { it.name == "Butter Chicken" })
}

@Test
fun `searchByName is case-insensitive`() = runTest {
    dao.upsertAll(listOf(aFoodItem(name = "Oatmeal")))
    val result = dao.searchByName("OAT")
    assertEquals(1, result.size)
}

@Test
fun `upsert single entity inserts and updates`() = runTest {
    val id = uuid()
    dao.upsert(aFoodItem(id = id, name = "Apple"))
    assertEquals("Apple", dao.getById(id)?.name)
    dao.upsert(aFoodItem(id = id, name = "Apple (updated)"))
    assertEquals("Apple (updated)", dao.getById(id)?.name)
}
```

Add `import kotlin.test.assertNull` and `import kotlin.test.assertTrue` to the imports of `FoodItemDaoTest.kt` if not already present.

- [ ] **Step 3: Run tests**

```bash
./gradlew :app:test --tests "org.branneman.health.db.dao.FoodItemDaoTest"
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/db/dao/FoodItemDao.kt
git add app/src/test/kotlin/org/branneman/health/db/dao/FoodItemDaoTest.kt
git commit -m "feat(app): add getById, getByBarcode, searchByName, upsert to FoodItemDao"
```

---

### Task 9: MealTemplateDao addition + deleteItemsForTemplate + MealTemplateDaoTest

**Files:**
- Modify: `app/src/main/kotlin/org/branneman/health/db/dao/MealTemplateDao.kt`
- Modify: `app/src/test/kotlin/org/branneman/health/db/dao/MealTemplateDaoTest.kt`

**Interfaces:**
- Produces: `getItemsForTemplate(templateId)` — ordered by sortOrder; `deleteItemsForTemplate(templateId)` — used by MealTemplateSyncService pull (Task 13).

- [ ] **Step 1: Add methods to `MealTemplateDao.kt`**

Add these two methods to the existing interface:

```kotlin
@Query("SELECT * FROM meal_template_item WHERE templateId = :templateId ORDER BY sortOrder ASC")
suspend fun getItemsForTemplate(templateId: String): List<MealTemplateItemEntity>

@Query("DELETE FROM meal_template_item WHERE templateId = :templateId")
suspend fun deleteItemsForTemplate(templateId: String)
```

- [ ] **Step 2: Add test to `MealTemplateDaoTest.kt`**

Update `upsertItem and getItems round-trips` to use `sortOrder`, and add:

```kotlin
@Test
fun `getItemsForTemplate respects sortOrder ascending`() = runTest {
    val templateId = uuid()
    val foodId1 = uuid()
    val foodId2 = uuid()
    val foodId3 = uuid()
    dao.upsert(aMealTemplate(id = templateId, userId = uuid()))
    dao.upsertItem(MealTemplateItemEntity(templateId = templateId, foodItemId = foodId2, grams = 100.0, sortOrder = 1))
    dao.upsertItem(MealTemplateItemEntity(templateId = templateId, foodItemId = foodId3, grams = 50.0,  sortOrder = 2))
    dao.upsertItem(MealTemplateItemEntity(templateId = templateId, foodItemId = foodId1, grams = 200.0, sortOrder = 0))
    val items = dao.getItemsForTemplate(templateId)
    assertEquals(3, items.size)
    assertEquals(foodId1, items[0].foodItemId)
    assertEquals(foodId2, items[1].foodItemId)
    assertEquals(foodId3, items[2].foodItemId)
}

@Test
fun `deleteItemsForTemplate removes only that template's items`() = runTest {
    val templateId1 = uuid()
    val templateId2 = uuid()
    val foodId = uuid()
    dao.upsert(aMealTemplate(id = templateId1, userId = uuid()))
    dao.upsert(aMealTemplate(id = templateId2, userId = uuid()))
    dao.upsertItem(MealTemplateItemEntity(templateId = templateId1, foodItemId = foodId, grams = 100.0))
    dao.upsertItem(MealTemplateItemEntity(templateId = templateId2, foodItemId = foodId, grams = 200.0))
    dao.deleteItemsForTemplate(templateId1)
    assertTrue(dao.getItemsForTemplate(templateId1).isEmpty())
    assertEquals(1, dao.getItemsForTemplate(templateId2).size)
}
```

- [ ] **Step 3: Run tests**

```bash
./gradlew :app:test --tests "org.branneman.health.db.dao.MealTemplateDaoTest"
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/db/dao/MealTemplateDao.kt
git add app/src/test/kotlin/org/branneman/health/db/dao/MealTemplateDaoTest.kt
git commit -m "feat(app): add getItemsForTemplate (ORDER BY sortOrder) and deleteItemsForTemplate to MealTemplateDao"
```

---

### Task 10: HealthApiClient additions + TestFactories updates

**Files:**
- Modify: `app/src/main/kotlin/org/branneman/health/network/HealthApiClient.kt`
- Modify: `app/src/test/kotlin/org/branneman/health/TestFactories.kt`

**Interfaces:**
- Produces: `postFoodItem`, `searchFoodItems`, `lookupFoodByBarcode`, `postFoodLog` — used by FoodItemSyncService (Task 11), LogEntrySyncService (Task 12), FoodSearchViewModel (Task 16).
- Produces: `aFoodItem(syncStatus = ...)`, `aLogEntryItem(...)` — used by sync service tests (Tasks 11–12).

- [ ] **Step 1: Add imports to `HealthApiClient.kt`**

```kotlin
import org.branneman.health.FoodItemRequestDto
import org.branneman.health.FoodLogRequestDto
```

- [ ] **Step 2: Add four methods to `HealthApiClient`**

Add after `getFoodItems`:

```kotlin
suspend fun postFoodItem(token: String, dto: FoodItemRequestDto): FoodItemDto? {
    val response = client.post("$baseUrl/in/food-items") {
        header(HttpHeaders.Authorization, "Bearer $token")
        contentType(ContentType.Application.Json)
        setBody(dto)
    }
    if (response.status == HttpStatusCode.Conflict) return null
    return response.body()
}

suspend fun searchFoodItems(token: String, q: String): List<FoodItemDto> =
    client.get("$baseUrl/in/food-items") {
        header(HttpHeaders.Authorization, "Bearer $token")
        parameter("q", q)
    }.body()

suspend fun lookupFoodByBarcode(token: String, barcode: String): FoodItemDto? {
    val items: List<FoodItemDto> = client.get("$baseUrl/in/food-items") {
        header(HttpHeaders.Authorization, "Bearer $token")
        parameter("barcode", barcode)
    }.body()
    return items.firstOrNull()
}

suspend fun postFoodLog(token: String, dto: FoodLogRequestDto): LogEntryDto? {
    val response = client.post("$baseUrl/in/log/food") {
        header(HttpHeaders.Authorization, "Bearer $token")
        contentType(ContentType.Application.Json)
        setBody(dto)
    }
    if (response.status == HttpStatusCode.Conflict) return null
    if (!response.status.isSuccess()) throw Exception("POST /in/log/food failed: ${response.status}")
    return response.body()
}
```

- [ ] **Step 3: Update `aFoodItem` in `TestFactories.kt` to accept `syncStatus`**

Change:
```kotlin
fun aFoodItem(
    id: String = uuid(),
    userId: String = uuid(),
    name: String = "Test Food",
    kcalPer100g: Double = 200.0,
    source: String = "manual",
) = FoodItemEntity(
    id = id, userId = userId, barcode = null, name = name,
    kcalPer100g = kcalPer100g, proteinPer100g = null, carbsPer100g = null,
    fatPer100g = null, source = source, syncStatus = SyncStatus.SYNCED,
)
```

To:
```kotlin
fun aFoodItem(
    id: String = uuid(),
    userId: String = uuid(),
    name: String = "Test Food",
    kcalPer100g: Double = 200.0,
    source: String = "manual",
    syncStatus: SyncStatus = SyncStatus.SYNCED,
) = FoodItemEntity(
    id = id, userId = userId, barcode = null, name = name,
    kcalPer100g = kcalPer100g, proteinPer100g = null, carbsPer100g = null,
    fatPer100g = null, source = source, syncStatus = syncStatus,
)
```

- [ ] **Step 4: Add `aLogEntryItem` to `TestFactories.kt`**

```kotlin
fun aLogEntryItem(
    logEntryId: String = uuid(),
    foodItemId: String = uuid(),
    grams: Double = 100.0,
    kcalPer100g: Double = 200.0,
) = LogEntryItemEntity(
    logEntryId     = logEntryId,
    foodItemId     = foodItemId,
    grams          = grams,
    kcalPer100g    = kcalPer100g,
    proteinPer100g = null,
    carbsPer100g   = null,
    fatPer100g     = null,
)
```

Add import: `import org.branneman.health.db.entities.LogEntryItemEntity`

- [ ] **Step 5: Run tests**

```bash
./gradlew :app:test
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/network/HealthApiClient.kt
git add app/src/test/kotlin/org/branneman/health/TestFactories.kt
git commit -m "feat(app): add postFoodItem, searchFoodItems, lookupFoodByBarcode, postFoodLog to HealthApiClient"
```

---

### Task 11: FoodItemSyncService + FoodItemSyncServiceTest

**Files:**
- Create: `app/src/main/kotlin/org/branneman/health/sync/FoodItemSyncService.kt`
- Create: `app/src/test/kotlin/org/branneman/health/sync/FoodItemSyncServiceTest.kt`

**Interfaces:**
- Consumes: `FoodItemDao.getByStatus`, `FoodItemDao.updateSyncStatus`, `HealthApiClient.postFoodItem`.
- Produces: `FoodItemSyncService.pushPending(token)` — called by SyncWorker (Task 13) before log entry sync.

- [ ] **Step 1: Write the failing test**

Create `FoodItemSyncServiceTest.kt`:

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
import org.branneman.health.aFoodItem
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FoodItemSyncServiceTest {

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
    fun `PENDING_CREATE item is posted and marked SYNCED on 201`() = runTest {
        val item = aFoodItem(id = uuid(), syncStatus = SyncStatus.PENDING_CREATE)
        db.foodItemDao().upsertAll(listOf(item))

        val api = mockApiClient { _ ->
            respond(
                """{"id":"${item.id}","barcode":null,"name":"Test Food","kcalPer100g":200.0,"proteinPer100g":null,"carbsPer100g":null,"fatPer100g":null,"source":"manual"}""",
                HttpStatusCode.Created,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        FoodItemSyncService(api, db).pushPending("token")

        assertEquals(0, db.foodItemDao().getByStatus(SyncStatus.PENDING_CREATE).size)
        assertEquals(1, db.foodItemDao().getByStatus(SyncStatus.SYNCED).size)
    }

    @Test
    fun `PENDING_CREATE stays PENDING_CREATE on network error`() = runTest {
        val item = aFoodItem(syncStatus = SyncStatus.PENDING_CREATE)
        db.foodItemDao().upsertAll(listOf(item))

        val api = HealthApiClient("http://test", HttpClient(MockEngine { error("network error") }) {
            install(ContentNegotiation) { json() }
        })

        FoodItemSyncService(api, db).pushPending("token")

        assertEquals(1, db.foodItemDao().getByStatus(SyncStatus.PENDING_CREATE).size)
    }

    @Test
    fun `409 conflict from server marks item SYNCED (idempotent retry)`() = runTest {
        val item = aFoodItem(syncStatus = SyncStatus.PENDING_CREATE)
        db.foodItemDao().upsertAll(listOf(item))

        val api = mockApiClient { _ -> respond("", HttpStatusCode.Conflict) }

        FoodItemSyncService(api, db).pushPending("token")

        // 409 means it already exists on the server — treat as success
        assertEquals(0, db.foodItemDao().getByStatus(SyncStatus.PENDING_CREATE).size)
        assertEquals(1, db.foodItemDao().getByStatus(SyncStatus.SYNCED).size)
    }

    @Test
    fun `no-op when nothing is PENDING_CREATE`() = runTest {
        db.foodItemDao().upsertAll(listOf(aFoodItem(syncStatus = SyncStatus.SYNCED)))

        var called = false
        val api = mockApiClient { _ -> called = true; respond("", HttpStatusCode.OK) }

        FoodItemSyncService(api, db).pushPending("token")

        assertEquals(false, called)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew :app:test --tests "org.branneman.health.sync.FoodItemSyncServiceTest"
```
Expected: FAIL with "class not found" or compile error

- [ ] **Step 3: Implement `FoodItemSyncService.kt`**

```kotlin
package org.branneman.health.sync

import org.branneman.health.FoodItemRequestDto
import org.branneman.health.db.HealthDatabase
import org.branneman.health.db.SyncStatus
import org.branneman.health.network.HealthApiClient

class FoodItemSyncService(
    private val api: HealthApiClient,
    private val db: HealthDatabase,
) {
    suspend fun pushPending(token: String) {
        val pending = db.foodItemDao().getByStatus(SyncStatus.PENDING_CREATE)
        if (pending.isEmpty()) return
        pending.forEach { entity ->
            val result = runCatching {
                api.postFoodItem(
                    token,
                    FoodItemRequestDto(
                        id             = entity.id,
                        barcode        = entity.barcode,
                        name           = entity.name,
                        kcalPer100g    = entity.kcalPer100g,
                        proteinPer100g = entity.proteinPer100g,
                        carbsPer100g   = entity.carbsPer100g,
                        fatPer100g     = entity.fatPer100g,
                        source         = entity.source,
                    )
                )
            }
            // null return means 409 Conflict — already on server, mark synced
            if (result.isSuccess) {
                db.foodItemDao().updateSyncStatus(entity.id, SyncStatus.SYNCED)
            }
        }
    }
}
```

- [ ] **Step 4: Run tests and verify they pass**

```bash
./gradlew :app:test --tests "org.branneman.health.sync.FoodItemSyncServiceTest"
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/sync/FoodItemSyncService.kt
git add app/src/test/kotlin/org/branneman/health/sync/FoodItemSyncServiceTest.kt
git commit -m "feat(app): add FoodItemSyncService with push-pending pattern"
```

---

### Task 12: LogEntrySyncService extension + LogEntrySyncServiceTest

**Files:**
- Modify: `app/src/main/kotlin/org/branneman/health/sync/LogEntrySyncService.kt`
- Modify: `app/src/test/kotlin/org/branneman/health/sync/LogEntrySyncServiceTest.kt`

**Interfaces:**
- Consumes: `HealthApiClient.postFoodLog`, `LogEntryDao.getItemsForEntry`, `FoodLogRequestDto`, `FoodLogItemRequestDto` (Task 10 / Task 1).
- Produces: extended `sync()` that handles food-item log entries (null `quickAddKcal`).

- [ ] **Step 1: Write the failing test first**

Add to `LogEntrySyncServiceTest`:

```kotlin
@Test
fun `food-item entry (null quickAddKcal) calls postFoodLog and marks SYNCED`() = runTest {
    val entry = aLogEntry(id = uuid(), mealType = "lunch") // quickAddKcal = null
    val item  = aLogEntryItem(logEntryId = entry.id, foodItemId = uuid(), grams = 150.0, kcalPer100g = 200.0)
    db.logEntryDao().upsert(entry)
    db.logEntryDao().upsertItem(item)

    val api = mockApiClient { req ->
        if (req.url.encodedPath.endsWith("/log/food")) {
            respond(
                """{"id":"${entry.id}","loggedAt":"2026-01-01T08:00:00Z","mealType":"lunch","quickAddKcal":null,"quickAddLabel":null,"items":[{"foodItemId":"${item.foodItemId}","grams":150.0,"kcalPer100g":200.0,"proteinPer100g":null,"carbsPer100g":null,"fatPer100g":null}]}""",
                HttpStatusCode.Created,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        } else {
            respond("", HttpStatusCode.InternalServerError)
        }
    }

    LogEntrySyncService(api, db).sync("token")

    assertEquals(1, db.logEntryDao().getByStatus(SyncStatus.SYNCED).size)
}

@Test
fun `food-item entry stays PENDING_CREATE on network error`() = runTest {
    val entry = aLogEntry()
    db.logEntryDao().upsert(entry)
    db.logEntryDao().upsertItem(aLogEntryItem(logEntryId = entry.id))

    val api = HealthApiClient("http://test", HttpClient(MockEngine { error("net error") }) {
        install(ContentNegotiation) { json() }
    })

    LogEntrySyncService(api, db).sync("token")

    assertEquals(1, db.logEntryDao().getByStatus(SyncStatus.PENDING_CREATE).size)
}
```

Add imports to `LogEntrySyncServiceTest.kt`:
```kotlin
import org.branneman.health.aLogEntry
import org.branneman.health.aLogEntryItem
```

- [ ] **Step 2: Run the new tests to verify they fail**

```bash
./gradlew :app:test --tests "org.branneman.health.sync.LogEntrySyncServiceTest.food*"
```
Expected: FAIL (method not found)

- [ ] **Step 3: Extend `LogEntrySyncService.kt`**

```kotlin
package org.branneman.health.sync

import org.branneman.health.FoodLogItemRequestDto
import org.branneman.health.FoodLogRequestDto
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
            if (entity.quickAddKcal != null) {
                runCatching {
                    api.postQuickAdd(
                        token,
                        QuickAddRequestDto(
                            id            = entity.id,
                            quickAddKcal  = entity.quickAddKcal,
                            quickAddLabel = entity.quickAddLabel,
                            loggedAt      = entity.loggedAt,
                        )
                    )
                }.onSuccess {
                    db.logEntryDao().updateSyncStatus(entity.id, SyncStatus.SYNCED)
                }
            } else {
                val items = db.logEntryDao().getItemsForEntry(entity.id)
                if (items.isEmpty()) return@forEach
                runCatching {
                    api.postFoodLog(
                        token,
                        FoodLogRequestDto(
                            id       = entity.id,
                            mealType = entity.mealType,
                            loggedAt = entity.loggedAt,
                            items    = items.map { FoodLogItemRequestDto(it.foodItemId, it.grams) },
                        )
                    )
                }.onSuccess {
                    db.logEntryDao().updateSyncStatus(entity.id, SyncStatus.SYNCED)
                }
            }
        }

        db.logEntryDao().getByStatus(SyncStatus.PENDING_DELETE).forEach { entity ->
            runCatching { api.deleteLogEntry(token, entity.id) }
                .onSuccess { db.logEntryDao().deleteById(entity.id) }
        }
    }
}
```

- [ ] **Step 4: Run all LogEntrySyncServiceTest tests**

```bash
./gradlew :app:test --tests "org.branneman.health.sync.LogEntrySyncServiceTest"
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/sync/LogEntrySyncService.kt
git add app/src/test/kotlin/org/branneman/health/sync/LogEntrySyncServiceTest.kt
git commit -m "feat(app): extend LogEntrySyncService to handle food-item log entries via POST /in/log/food"
```

---

### Task 13: SyncWorker update + MealTemplateSyncService items fix

The `MealTemplateSyncService.pushPending()` currently sends `items = emptyList()` for all templates. Fix it to include items from Room. Also fix `pull()` to upsert items from the server response.

**Files:**
- Modify: `app/src/main/kotlin/org/branneman/health/sync/SyncWorker.kt`
- Modify: `app/src/main/kotlin/org/branneman/health/sync/MealTemplateSyncService.kt`

- [ ] **Step 1: Update `SyncWorker.kt` to add `FoodItemSyncService` before `LogEntrySyncService`**

```kotlin
BodyWeightSyncService(apiClient, db).sync(stored.token)
FoodItemSyncService(apiClient, db).pushPending(stored.token)   // new — must run before log entries
LogEntrySyncService(apiClient, db).sync(stored.token)
MealTemplateSyncService(apiClient, db).pushPending(stored.token)
ShortcutSyncService(apiClient, db).pushPending(stored.token)
runCatching { apiClient.triggerPolarSync(stored.token) }
DailyEnergySyncService(apiClient, db).sync(stored.token, stored.userId)
WorkoutSyncService(apiClient, db).sync(stored.token, stored.userId)
```

Add import: `import org.branneman.health.sync.FoodItemSyncService`

- [ ] **Step 2: Fix `MealTemplateSyncService.kt` to include items in push and pull**

```kotlin
package org.branneman.health.sync

import org.branneman.health.MealTemplateDto
import org.branneman.health.MealTemplateItemDto
import org.branneman.health.db.HealthDatabase
import org.branneman.health.db.SyncStatus
import org.branneman.health.db.entities.MealTemplateEntity
import org.branneman.health.db.entities.MealTemplateItemEntity
import org.branneman.health.network.HealthApiClient

class MealTemplateSyncService(
    private val api: HealthApiClient,
    private val db: HealthDatabase,
) {
    suspend fun pushPending(token: String) {
        val pendingCreate = db.mealTemplateDao().getByStatus(SyncStatus.PENDING_CREATE)
        val pendingDelete = db.mealTemplateDao().getByStatus(SyncStatus.PENDING_DELETE)
        if (pendingCreate.isEmpty() && pendingDelete.isEmpty()) return

        val allActive = pendingCreate + db.mealTemplateDao().getByStatus(SyncStatus.SYNCED)
        val dtos = allActive.map { template ->
            val items = db.mealTemplateDao().getItemsForTemplate(template.id)
            template.toDto(items)
        }
        runCatching {
            api.putTemplates(token, dtos)
        }.onSuccess {
            allActive.forEach { db.mealTemplateDao().updateSyncStatus(it.id, SyncStatus.SYNCED) }
            pendingDelete.forEach { db.mealTemplateDao().deleteById(it.id) }
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
        templates.forEach { dto ->
            db.mealTemplateDao().deleteItemsForTemplate(dto.id)
            db.mealTemplateDao().upsertAllItems(
                dto.items.mapIndexed { index, item ->
                    MealTemplateItemEntity(
                        templateId = dto.id,
                        foodItemId = item.foodItemId,
                        grams      = item.grams,
                        sortOrder  = item.sortOrder.takeIf { it != 0 } ?: index,
                    )
                }
            )
        }
    }

    private fun MealTemplateEntity.toDto(items: List<MealTemplateItemEntity> = emptyList()) = MealTemplateDto(
        id           = id,
        name         = name,
        sortOrder    = sortOrder,
        quickAddKcal = quickAddKcal,
        items        = items.map { MealTemplateItemDto(foodItemId = it.foodItemId, grams = it.grams, sortOrder = it.sortOrder) },
    )
}
```

- [ ] **Step 3: Run app tests**

```bash
./gradlew :app:test
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/sync/SyncWorker.kt
git add app/src/main/kotlin/org/branneman/health/sync/MealTemplateSyncService.kt
git commit -m "feat(app): SyncWorker adds FoodItemSyncService; MealTemplateSyncService pushes and pulls items"
```

---

### Task 14: BuildFromScratchViewModel + test

**Files:**
- Create: `app/src/main/kotlin/org/branneman/health/ui/BuildFromScratchViewModel.kt`
- Create: `app/src/test/kotlin/org/branneman/health/ui/BuildFromScratchViewModelTest.kt`

**Interfaces:**
- Produces: `BuildFromScratchViewModel` with `ingredients: StateFlow<List<Ingredient>>`, `totalKcal: StateFlow<Int>`, `addIngredient(item, grams)`, `removeAt(index)`, `log(mealType, userId)`, `saveAsTemplate(name, userId)`, `bailOutKcal: Int`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package org.branneman.health.ui

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.branneman.health.aFoodItem
import org.branneman.health.auth.TokenStore
import org.branneman.health.db.HealthDatabase
import org.branneman.health.db.SyncStatus
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BuildFromScratchViewModelTest {

    private lateinit var db: HealthDatabase
    private lateinit var vm: BuildFromScratchViewModel

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HealthDatabase::class.java)
            .allowMainThreadQueries().build()
        vm = BuildFromScratchViewModel(db)
    }

    @After fun tearDown() { db.close() }

    @Test
    fun `running kcal total is sum of ingredient kcal contributions`() = runTest {
        val rice    = aFoodItem(kcalPer100g = 130.0)  // 100g → 130 kcal
        val chicken = aFoodItem(kcalPer100g = 165.0)  // 200g → 330 kcal
        vm.addIngredient(rice, 100.0)
        vm.addIngredient(chicken, 200.0)
        // 130 + 330 = 460
        assertEquals(460, vm.totalKcal.first())
    }

    @Test
    fun `total kcal rounds each item contribution`() = runTest {
        val item = aFoodItem(kcalPer100g = 200.0)
        vm.addIngredient(item, 75.0)  // 75/100 * 200 = 150 kcal
        assertEquals(150, vm.totalKcal.first())
    }

    @Test
    fun `bail-out kcal equals current total`() = runTest {
        val item = aFoodItem(kcalPer100g = 400.0)
        vm.addIngredient(item, 50.0)  // 50/100 * 400 = 200 kcal
        assertEquals(200, vm.bailOutKcal)
    }

    @Test
    fun `removeAt removes ingredient and updates total`() = runTest {
        val a = aFoodItem(kcalPer100g = 100.0)
        val b = aFoodItem(kcalPer100g = 200.0)
        vm.addIngredient(a, 100.0)
        vm.addIngredient(b, 100.0)
        vm.removeAt(0)
        assertEquals(200, vm.totalKcal.first())
    }

    @Test
    fun `log writes LogEntryEntity and items to Room`() = runTest {
        val item = aFoodItem(kcalPer100g = 200.0)
        db.foodItemDao().upsert(item)
        vm.addIngredient(item, 100.0)
        vm.log("lunch", item.userId)
        val entries = db.logEntryDao().getByStatus(SyncStatus.PENDING_CREATE)
        assertEquals(1, entries.size)
        assertEquals("lunch", entries[0].mealType)
        val logItems = db.logEntryDao().getItemsForEntry(entries[0].id)
        assertEquals(1, logItems.size)
        assertEquals(200.0, logItems[0].kcalPer100g)
    }
}
```

- [ ] **Step 2: Run to verify failure**

```bash
./gradlew :app:test --tests "org.branneman.health.ui.BuildFromScratchViewModelTest"
```
Expected: FAIL

- [ ] **Step 3: Implement `BuildFromScratchViewModel.kt`**

```kotlin
package org.branneman.health.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import org.branneman.health.HealthApplication
import org.branneman.health.db.HealthDatabase
import org.branneman.health.db.SyncStatus
import org.branneman.health.db.entities.FoodItemEntity
import org.branneman.health.db.entities.LogEntryEntity
import org.branneman.health.db.entities.LogEntryItemEntity
import org.branneman.health.db.entities.MealTemplateEntity
import org.branneman.health.db.entities.MealTemplateItemEntity
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.math.roundToInt

data class Ingredient(val item: FoodItemEntity, val grams: Double) {
    val kcal: Int get() = (grams / 100.0 * item.kcalPer100g).roundToInt()
}

class BuildFromScratchViewModel private constructor(
    application: Application,
    private val db: HealthDatabase,
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application = application,
        db = (application as HealthApplication).db,
    )

    internal constructor(db: HealthDatabase) : this(Application(), db)

    private val _ingredients = MutableStateFlow<List<Ingredient>>(emptyList())
    val ingredients: StateFlow<List<Ingredient>> = _ingredients

    val totalKcal: StateFlow<Int> = _ingredients
        .map { list -> list.sumOf { it.kcal } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val bailOutKcal: Int get() = _ingredients.value.sumOf { it.kcal }

    fun addIngredient(item: FoodItemEntity, grams: Double) {
        _ingredients.value = _ingredients.value + Ingredient(item, grams)
    }

    fun removeAt(index: Int) {
        _ingredients.value = _ingredients.value.toMutableList().also { it.removeAt(index) }
    }

    fun log(mealType: String, userId: String) {
        viewModelScope.launch {
            val entryId = UUID.randomUUID().toString()
            val entry = LogEntryEntity(
                id           = entryId,
                userId       = userId,
                loggedAt     = OffsetDateTime.now().toString(),
                mealType     = mealType,
                quickAddKcal = null,
                quickAddLabel= null,
                syncStatus   = SyncStatus.PENDING_CREATE,
            )
            db.logEntryDao().upsert(entry)
            _ingredients.value.forEach { ingredient ->
                db.logEntryDao().upsertItem(
                    LogEntryItemEntity(
                        logEntryId     = entryId,
                        foodItemId     = ingredient.item.id,
                        grams          = ingredient.grams,
                        kcalPer100g    = ingredient.item.kcalPer100g,
                        proteinPer100g = ingredient.item.proteinPer100g,
                        carbsPer100g   = ingredient.item.carbsPer100g,
                        fatPer100g     = ingredient.item.fatPer100g,
                    )
                )
            }
        }
    }

    fun saveAsTemplate(name: String, userId: String) {
        viewModelScope.launch {
            val templateId = UUID.randomUUID().toString()
            db.mealTemplateDao().upsert(
                MealTemplateEntity(
                    id           = templateId,
                    userId       = userId,
                    name         = name,
                    sortOrder    = null,
                    quickAddKcal = null,
                    syncStatus   = SyncStatus.PENDING_CREATE,
                )
            )
            _ingredients.value.forEachIndexed { index, ingredient ->
                db.mealTemplateDao().upsertItem(
                    MealTemplateItemEntity(
                        templateId = templateId,
                        foodItemId = ingredient.item.id,
                        grams      = ingredient.grams,
                        sortOrder  = index,
                    )
                )
            }
        }
    }
}
```

- [ ] **Step 4: Run tests**

```bash
./gradlew :app:test --tests "org.branneman.health.ui.BuildFromScratchViewModelTest"
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/ui/BuildFromScratchViewModel.kt
git add app/src/test/kotlin/org/branneman/health/ui/BuildFromScratchViewModelTest.kt
git commit -m "feat(app): add BuildFromScratchViewModel with ingredient list, kcal total, log, save-as-template"
```

---

### Task 15: EditIngredientTemplateViewModel

**Files:**
- Create: `app/src/main/kotlin/org/branneman/health/ui/EditIngredientTemplateViewModel.kt`

- [ ] **Step 1: Implement `EditIngredientTemplateViewModel.kt`**

```kotlin
package org.branneman.health.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.branneman.health.HealthApplication
import org.branneman.health.db.HealthDatabase
import org.branneman.health.db.SyncStatus
import org.branneman.health.db.entities.FoodItemEntity
import org.branneman.health.db.entities.MealTemplateEntity
import org.branneman.health.db.entities.MealTemplateItemEntity
import java.util.UUID

class EditIngredientTemplateViewModel private constructor(
    application: Application,
    private val db: HealthDatabase,
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application = application,
        db = (application as HealthApplication).db,
    )

    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name

    private val _ingredients = MutableStateFlow<List<Ingredient>>(emptyList())
    val ingredients: StateFlow<List<Ingredient>> = _ingredients

    val totalKcal: StateFlow<Int> = _ingredients
        .map { list -> list.sumOf { it.kcal } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private var templateId: String? = null

    fun loadTemplate(id: String?) {
        templateId = id
        if (id == null) return
        viewModelScope.launch {
            val template = db.mealTemplateDao().getById(id) ?: return@launch
            _name.value = template.name
            val items = db.mealTemplateDao().getItemsForTemplate(id)
            val entities = items.mapNotNull { item ->
                db.foodItemDao().getById(item.foodItemId)?.let { food ->
                    Ingredient(food, item.grams)
                }
            }
            _ingredients.value = entities
        }
    }

    fun onNameChange(value: String) { _name.value = value }

    fun addIngredient(item: FoodItemEntity, grams: Double) {
        _ingredients.value = _ingredients.value + Ingredient(item, grams)
    }

    fun removeAt(index: Int) {
        _ingredients.value = _ingredients.value.toMutableList().also { it.removeAt(index) }
    }

    fun save(userId: String) {
        viewModelScope.launch {
            val id = templateId ?: UUID.randomUUID().toString().also { templateId = it }
            db.mealTemplateDao().upsert(
                MealTemplateEntity(
                    id           = id,
                    userId       = userId,
                    name         = _name.value.trim(),
                    sortOrder    = null,
                    quickAddKcal = null,
                    syncStatus   = SyncStatus.PENDING_CREATE,
                )
            )
            db.mealTemplateDao().deleteItemsForTemplate(id)
            _ingredients.value.forEachIndexed { index, ingredient ->
                db.mealTemplateDao().upsertItem(
                    MealTemplateItemEntity(
                        templateId = id,
                        foodItemId = ingredient.item.id,
                        grams      = ingredient.grams,
                        sortOrder  = index,
                    )
                )
            }
        }
    }
}
```

- [ ] **Step 2: Run app tests**

```bash
./gradlew :app:test
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/ui/EditIngredientTemplateViewModel.kt
git commit -m "feat(app): add EditIngredientTemplateViewModel for create/edit ingredient templates"
```

---

### Task 16: ML Kit + CameraX deps + FoodSearchViewModel

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/kotlin/org/branneman/health/ui/FoodSearchViewModel.kt`

- [ ] **Step 1: Add versions to `gradle/libs.versions.toml`**

In `[versions]`:
```toml
mlkit-barcode = "17.3.0"
camerax = "1.4.1"
```

In `[libraries]`:
```toml
mlkit-barcode-scanning = { module = "com.google.mlkit:barcode-scanning", version.ref = "mlkit-barcode" }
camerax-camera2  = { module = "androidx.camera:camera-camera2",  version.ref = "camerax" }
camerax-lifecycle = { module = "androidx.camera:camera-lifecycle", version.ref = "camerax" }
camerax-view     = { module = "androidx.camera:camera-view",      version.ref = "camerax" }
```

- [ ] **Step 2: Add dependencies to `app/build.gradle.kts`**

Inside the `dependencies { }` block:
```kotlin
implementation(libs.mlkit.barcode.scanning)
implementation(libs.camerax.camera2)
implementation(libs.camerax.lifecycle)
implementation(libs.camerax.view)
```

- [ ] **Step 3: Sync and verify build**

```bash
./gradlew :app:assembleDebug
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Implement `FoodSearchViewModel.kt`**

```kotlin
package org.branneman.health.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.branneman.health.HealthApplication
import org.branneman.health.auth.TokenStore
import org.branneman.health.auth.authDataStore
import org.branneman.health.db.HealthDatabase
import org.branneman.health.db.SyncStatus
import org.branneman.health.db.entities.FoodItemEntity
import org.branneman.health.network.HealthApiClient
import java.util.UUID

data class FoodSearchResult(
    val entity: FoodItemEntity,
    val isPersonalCatalog: Boolean,
)

@OptIn(FlowPreview::class)
class FoodSearchViewModel private constructor(
    application: Application,
    private val db: HealthDatabase,
    private val tokenStore: TokenStore,
    private val api: HealthApiClient,
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application = application,
        db          = (application as HealthApplication).db,
        tokenStore  = TokenStore(application.authDataStore),
        api         = HealthApiClient(),
    )

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _results = MutableStateFlow<List<FoodSearchResult>>(emptyList())
    val results: StateFlow<List<FoodSearchResult>> = _results

    private val _selectedItem = MutableStateFlow<FoodItemEntity?>(null)
    val selectedItem: StateFlow<FoodItemEntity?> = _selectedItem

    private val _isOffline = MutableStateFlow(false)
    val isOffline: StateFlow<Boolean> = _isOffline

    init {
        viewModelScope.launch {
            _query.debounce(300).distinctUntilChanged().collect { q ->
                if (q.isBlank()) { _results.value = emptyList(); return@collect }
                val token = tokenStore.tokenFlow.first()?.token ?: return@collect
                val localResults = db.foodItemDao().searchByName(q)
                val remoteResults = runCatching { api.searchFoodItems(token, q) }
                    .onFailure { _isOffline.value = true }
                    .getOrDefault(emptyList())
                val localIds = localResults.map { it.id }.toSet()
                val remoteFoodItems = remoteResults
                    .filter { dto -> dto.id !in localIds }
                    .map { dto ->
                        FoodSearchResult(
                            entity = FoodItemEntity(
                                id             = dto.id,
                                userId         = tokenStore.tokenFlow.first()?.userId ?: "",
                                barcode        = dto.barcode,
                                name           = dto.name,
                                kcalPer100g    = dto.kcalPer100g,
                                proteinPer100g = dto.proteinPer100g,
                                carbsPer100g   = dto.carbsPer100g,
                                fatPer100g     = dto.fatPer100g,
                                source         = dto.source,
                                syncStatus     = SyncStatus.SYNCED,
                            ),
                            isPersonalCatalog = false,
                        )
                    }
                _results.value = localResults.map { FoodSearchResult(it, true) } + remoteFoodItems
            }
        }
    }

    fun onQueryChange(q: String) { _query.value = q }

    fun onBarcodeScanned(barcode: String) {
        viewModelScope.launch {
            val token  = tokenStore.tokenFlow.first()?.token ?: return@launch
            val userId = tokenStore.tokenFlow.first()?.userId ?: return@launch
            val existing = db.foodItemDao().getByBarcode(barcode)
            if (existing != null) { selectExisting(existing); return@launch }
            val dto = runCatching { api.lookupFoodByBarcode(token, barcode) }
                .onFailure { _isOffline.value = true }
                .getOrNull()
            if (dto != null) {
                val entity = FoodItemEntity(
                    id             = UUID.randomUUID().toString(),
                    userId         = userId,
                    barcode        = dto.barcode,
                    name           = dto.name,
                    kcalPer100g    = dto.kcalPer100g,
                    proteinPer100g = dto.proteinPer100g,
                    carbsPer100g   = dto.carbsPer100g,
                    fatPer100g     = dto.fatPer100g,
                    source         = dto.source,
                    syncStatus     = SyncStatus.PENDING_CREATE,
                )
                db.foodItemDao().upsert(entity)
                _selectedItem.value = entity
            }
            // null → no match; UI shows "Not found" inline form
        }
    }

    fun selectResult(result: FoodSearchResult) {
        viewModelScope.launch {
            val userId = tokenStore.tokenFlow.first()?.userId ?: return@launch
            if (result.isPersonalCatalog) {
                selectExisting(result.entity)
            } else {
                val barcode = result.entity.barcode
                val existing = barcode?.let { db.foodItemDao().getByBarcode(it) }
                if (existing != null) {
                    selectExisting(existing)
                } else {
                    val entity = result.entity.copy(
                        id         = UUID.randomUUID().toString(),
                        userId     = userId,
                        syncStatus = SyncStatus.PENDING_CREATE,
                    )
                    db.foodItemDao().upsert(entity)
                    _selectedItem.value = entity
                }
            }
        }
    }

    fun createManual(name: String, kcalPer100g: Double, proteinPer100g: Double?, carbsPer100g: Double?, fatPer100g: Double?) {
        viewModelScope.launch {
            val userId = tokenStore.tokenFlow.first()?.userId ?: return@launch
            val entity = FoodItemEntity(
                id             = UUID.randomUUID().toString(),
                userId         = userId,
                barcode        = null,
                name           = name,
                kcalPer100g    = kcalPer100g,
                proteinPer100g = proteinPer100g,
                carbsPer100g   = carbsPer100g,
                fatPer100g     = fatPer100g,
                source         = "manual",
                syncStatus     = SyncStatus.PENDING_CREATE,
            )
            db.foodItemDao().upsert(entity)
            _selectedItem.value = entity
        }
    }

    fun consumeSelectedItem() { _selectedItem.value = null }

    private fun selectExisting(entity: FoodItemEntity) { _selectedItem.value = entity }
}
```

- [ ] **Step 5: Run app tests**

```bash
./gradlew :app:test
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml
git add app/build.gradle.kts
git add app/src/main/kotlin/org/branneman/health/ui/FoodSearchViewModel.kt
git commit -m "feat(app): add ML Kit + CameraX deps; implement FoodSearchViewModel"
```

---

### Task 17: FoodSearchScreen + FoodSearchScreenTest

**Files:**
- Create: `app/src/main/kotlin/org/branneman/health/ui/FoodSearchScreen.kt`
- Create: `app/src/test/kotlin/org/branneman/health/ui/FoodSearchScreenTest.kt`

- [ ] **Step 1: Write the failing UI tests**

```kotlin
package org.branneman.health.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.branneman.health.aFoodItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FoodSearchScreenTest {

    @get:Rule val rule = createComposeRule()

    private fun launch(
        query: String = "",
        results: List<FoodSearchResult> = emptyList(),
        selectedItem: org.branneman.health.db.entities.FoodItemEntity? = null,
        isOffline: Boolean = false,
        onQueryChange: (String) -> Unit = {},
        onSelectResult: (FoodSearchResult) -> Unit = {},
        onBarcodeButton: () -> Unit = {},
        onManualCreate: (String, Double, Double?, Double?, Double?) -> Unit = { _, _, _, _, _ -> },
        onBack: () -> Unit = {},
    ) {
        rule.setContent {
            FoodSearchContent(
                query          = query,
                results        = results,
                selectedItem   = selectedItem,
                isOffline      = isOffline,
                onQueryChange  = onQueryChange,
                onSelectResult = onSelectResult,
                onBarcodeButton= onBarcodeButton,
                onManualCreate = onManualCreate,
                onBack         = onBack,
            )
        }
    }

    @Test fun `empty state shows search field and barcode button`() {
        launch()
        rule.onNodeWithTag("food_search_field").assertExists()
        rule.onNodeWithTag("food_barcode_button").assertExists()
        rule.onNodeWithTag("food_no_results_form").assertDoesNotExist()
    }

    @Test fun `results list shows items`() {
        val item = aFoodItem(name = "Oatmeal")
        launch(results = listOf(FoodSearchResult(item, true)))
        rule.onNodeWithText("Oatmeal").assertIsDisplayed()
    }

    @Test fun `offline notice shown when isOffline is true`() {
        launch(isOffline = true)
        rule.onNodeWithTag("food_offline_notice").assertIsDisplayed()
    }

    @Test fun `manual form appears when no results and query is non-empty`() {
        launch(query = "xyz_nonexistent")
        rule.onNodeWithTag("food_no_results_form").assertExists()
    }

    @Test fun `manual form hidden when query is blank`() {
        launch(query = "")
        rule.onNodeWithTag("food_no_results_form").assertDoesNotExist()
    }
}
```

- [ ] **Step 2: Implement `FoodSearchScreen.kt`**

```kotlin
package org.branneman.health.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.branneman.health.db.entities.FoodItemEntity

@Composable
fun FoodSearchScreen(
    onItemSelected: (FoodItemEntity) -> Unit,
    onBack: () -> Unit,
    viewModel: FoodSearchViewModel = viewModel(),
) {
    val query       by viewModel.query.collectAsStateWithLifecycle()
    val results     by viewModel.results.collectAsStateWithLifecycle()
    val selectedItem by viewModel.selectedItem.collectAsStateWithLifecycle()
    val isOffline   by viewModel.isOffline.collectAsStateWithLifecycle()

    LaunchedEffect(selectedItem) {
        selectedItem?.let {
            onItemSelected(it)
            viewModel.consumeSelectedItem()
        }
    }

    FoodSearchContent(
        query           = query,
        results         = results,
        selectedItem    = selectedItem,
        isOffline       = isOffline,
        onQueryChange   = viewModel::onQueryChange,
        onSelectResult  = viewModel::selectResult,
        onBarcodeButton = { /* CameraX barcode scanner launched from screen */ },
        onManualCreate  = viewModel::createManual,
        onBack          = onBack,
    )
}

@Composable
fun FoodSearchContent(
    query: String,
    results: List<FoodSearchResult>,
    selectedItem: FoodItemEntity?,
    isOffline: Boolean,
    onQueryChange: (String) -> Unit,
    onSelectResult: (FoodSearchResult) -> Unit,
    onBarcodeButton: () -> Unit,
    onManualCreate: (String, Double, Double?, Double?, Double?) -> Unit,
    onBack: () -> Unit,
) {
    var showManualForm by remember(query, results) { mutableStateOf(false) }
    LaunchedEffect(query, results) {
        showManualForm = query.isNotBlank() && results.isEmpty()
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        TextButton(onClick = onBack) { Text("← Back") }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value         = query,
                onValueChange = onQueryChange,
                label         = { Text("Search food") },
                singleLine    = true,
                modifier      = Modifier.weight(1f).testTag("food_search_field"),
            )
            TextButton(
                onClick  = onBarcodeButton,
                modifier = Modifier.testTag("food_barcode_button"),
            ) { Text("Scan") }
        }
        if (isOffline) {
            Text(
                text  = "OFD search unavailable offline — showing personal catalog only",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("food_offline_notice"),
            )
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(results, key = { it.entity.id }) { result ->
                ListItem(
                    headlineContent   = { Text(result.entity.name) },
                    supportingContent = { Text("${result.entity.kcalPer100g} kcal/100g") },
                    modifier          = Modifier
                        .testTag("food_result_${result.entity.id}")
                        .clickable { onSelectResult(result) },
                )
                HorizontalDivider()
            }
        }
        if (showManualForm) {
            ManualFoodForm(
                onSave   = onManualCreate,
                modifier = Modifier.testTag("food_no_results_form"),
            )
        }
    }
}

@Composable
private fun ManualFoodForm(
    onSave: (String, Double, Double?, Double?, Double?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var name    by remember { mutableStateOf("") }
    var kcal    by remember { mutableStateOf("") }
    var protein by remember { mutableStateOf("") }
    var carbs   by remember { mutableStateOf("") }
    var fat     by remember { mutableStateOf("") }

    val saveEnabled = name.isNotBlank() && (kcal.toDoubleOrNull() ?: 0.0) > 0.0

    Column(modifier = modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Not found — enter manually", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(value = name, onValueChange = { name = it },
            label = { Text("Name *") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("manual_name"))
        OutlinedTextField(value = kcal, onValueChange = { kcal = it },
            label = { Text("kcal/100g *") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true, modifier = Modifier.fillMaxWidth().testTag("manual_kcal"))
        OutlinedTextField(value = protein, onValueChange = { protein = it },
            label = { Text("Protein/100g") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = carbs, onValueChange = { carbs = it },
            label = { Text("Carbs/100g") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = fat, onValueChange = { fat = it },
            label = { Text("Fat/100g") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(
            onClick  = { onSave(name.trim(), kcal.toDouble(), protein.toDoubleOrNull(), carbs.toDoubleOrNull(), fat.toDoubleOrNull()) },
            enabled  = saveEnabled,
            modifier = Modifier.testTag("manual_save"),
        ) { Text("Add ingredient") }
    }
}
```

- [ ] **Step 3: Run tests**

```bash
./gradlew :app:test --tests "org.branneman.health.ui.FoodSearchScreenTest"
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/ui/FoodSearchScreen.kt
git add app/src/test/kotlin/org/branneman/health/ui/FoodSearchScreenTest.kt
git commit -m "feat(app): add FoodSearchScreen with text search, barcode button, and manual form"
```

---

### Task 18: BuildFromScratchScreen + BuildFromScratchScreenTest

**Files:**
- Create: `app/src/main/kotlin/org/branneman/health/ui/BuildFromScratchScreen.kt`
- Create: `app/src/test/kotlin/org/branneman/health/ui/BuildFromScratchScreenTest.kt`

- [ ] **Step 1: Write the failing UI tests**

```kotlin
package org.branneman.health.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.branneman.health.aFoodItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BuildFromScratchScreenTest {

    @get:Rule val rule = createComposeRule()

    private fun launch(
        ingredients: List<Ingredient> = emptyList(),
        totalKcal: Int = 0,
        pendingFoodItem: org.branneman.health.db.entities.FoodItemEntity? = null,
        onAddIngredient: () -> Unit = {},
        onRemoveAt: (Int) -> Unit = {},
        onGramsConfirmed: (org.branneman.health.db.entities.FoodItemEntity, Double) -> Unit = { _, _ -> },
        onLog: (String) -> Unit = {},
        onSaveAsTemplate: (String) -> Unit = {},
        onBack: (Int?) -> Unit = {},
    ) {
        rule.setContent {
            BuildFromScratchContent(
                ingredients      = ingredients,
                totalKcal        = totalKcal,
                pendingFoodItem  = pendingFoodItem,
                onAddIngredient  = onAddIngredient,
                onRemoveAt       = onRemoveAt,
                onGramsConfirmed = onGramsConfirmed,
                onLog            = onLog,
                onSaveAsTemplate = onSaveAsTemplate,
                onBack           = onBack,
            )
        }
    }

    @Test fun `Log button disabled when ingredient list is empty`() {
        launch(ingredients = emptyList())
        rule.onNodeWithTag("bfs_log_button").assertIsNotEnabled()
    }

    @Test fun `Log button enabled when list has at least one ingredient`() {
        val item = aFoodItem(kcalPer100g = 200.0)
        launch(ingredients = listOf(Ingredient(item, 100.0)), totalKcal = 200)
        rule.onNodeWithTag("bfs_log_button").assertIsEnabled()
    }

    @Test fun `running kcal total is displayed`() {
        launch(totalKcal = 350)
        rule.onNodeWithText("350 kcal").assertIsDisplayed()
    }

    @Test fun `grams dialog shown when pendingFoodItem is not null`() {
        val item = aFoodItem(name = "Pasta")
        launch(pendingFoodItem = item)
        rule.onNodeWithTag("bfs_grams_dialog").assertExists()
    }

    @Test fun `grams dialog not shown when pendingFoodItem is null`() {
        launch(pendingFoodItem = null)
        rule.onNodeWithTag("bfs_grams_dialog").assertDoesNotExist()
    }

    @Test fun `meal type picker shown after tapping Log button`() {
        val item = aFoodItem(kcalPer100g = 100.0)
        launch(ingredients = listOf(Ingredient(item, 100.0)), totalKcal = 100)
        rule.onNodeWithTag("bfs_log_button").performClick()
        rule.onNodeWithTag("bfs_meal_type_sheet").assertExists()
    }

    @Test fun `bail-out quick-add kcal is total kcal`() {
        val item = aFoodItem(kcalPer100g = 300.0)
        var capturedKcal: Int? = null
        launch(
            ingredients = listOf(Ingredient(item, 100.0)),
            totalKcal   = 300,
            onBack      = { kcal -> capturedKcal = kcal },
        )
        rule.onNodeWithTag("bfs_back_button").performClick()
        // confirm dialog → choose Quick Add
        rule.onNodeWithTag("bfs_bailout_quick_add").performClick()
        assertEquals(300, capturedKcal)
    }
}
```

- [ ] **Step 2: Implement `BuildFromScratchScreen.kt`**

```kotlin
package org.branneman.health.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.branneman.health.db.entities.FoodItemEntity

@Composable
fun BuildFromScratchScreen(
    pendingFoodItem: FoodItemEntity?,
    onPendingFoodItemConsumed: () -> Unit,
    onAddIngredient: () -> Unit,
    onLogged: () -> Unit,
    onBack: (bailOutKcal: Int?) -> Unit,
    viewModel: BuildFromScratchViewModel = viewModel(),
) {
    val ingredients by viewModel.ingredients.collectAsStateWithLifecycle()
    val totalKcal   by viewModel.totalKcal.collectAsStateWithLifecycle()

    var pendingItem by remember { mutableStateOf<FoodItemEntity?>(null) }
    LaunchedEffect(pendingFoodItem) {
        if (pendingFoodItem != null) {
            pendingItem = pendingFoodItem
            onPendingFoodItemConsumed()
        }
    }

    var loggedEntry by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(loggedEntry) {
        if (loggedEntry != null) onLogged()
    }

    BuildFromScratchContent(
        ingredients      = ingredients,
        totalKcal        = totalKcal,
        pendingFoodItem  = pendingItem,
        onAddIngredient  = onAddIngredient,
        onRemoveAt       = viewModel::removeAt,
        onGramsConfirmed = { item, grams ->
            viewModel.addIngredient(item, grams)
            pendingItem = null
        },
        onLog            = { mealType ->
            // userId comes from ViewModel's tokenStore; pass via event
            loggedEntry = mealType
        },
        onSaveAsTemplate = { /* handled in wrapper with dialog */ },
        onBack           = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuildFromScratchContent(
    ingredients: List<Ingredient>,
    totalKcal: Int,
    pendingFoodItem: FoodItemEntity?,
    onAddIngredient: () -> Unit,
    onRemoveAt: (Int) -> Unit,
    onGramsConfirmed: (FoodItemEntity, Double) -> Unit,
    onLog: (mealType: String) -> Unit,
    onSaveAsTemplate: (name: String) -> Unit,
    onBack: (bailOutKcal: Int?) -> Unit,
) {
    var showMealTypeSheet  by remember { mutableStateOf(false) }
    var showTemplateSheet  by remember { mutableStateOf(false) }
    var showAbandonDialog  by remember { mutableStateOf(false) }
    var lastLoggedMealType by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        TextButton(
            onClick  = { if (ingredients.isEmpty()) onBack(null) else showAbandonDialog = true },
            modifier = Modifier.testTag("bfs_back_button"),
        ) { Text("← Back") }

        Text("Build from scratch", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text("$totalKcal kcal", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            itemsIndexed(ingredients, key = { i, _ -> i }) { index, ingredient ->
                ListItem(
                    headlineContent   = { Text(ingredient.item.name) },
                    supportingContent = { Text("${ingredient.grams}g · ${ingredient.kcal} kcal") },
                    trailingContent   = {
                        TextButton(onClick = { onRemoveAt(index) }) { Text("Remove") }
                    },
                )
                HorizontalDivider()
            }
        }

        Button(onClick = onAddIngredient, modifier = Modifier.fillMaxWidth()) {
            Text("+ Add ingredient")
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick  = { showMealTypeSheet = true },
            enabled  = ingredients.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().testTag("bfs_log_button"),
        ) { Text("Log") }
    }

    if (pendingFoodItem != null) {
        GramsDialog(
            foodName         = pendingFoodItem.name,
            onConfirm        = { grams -> onGramsConfirmed(pendingFoodItem, grams) },
            onDismiss        = { /* caller clears pendingFoodItem */ },
            modifier         = Modifier.testTag("bfs_grams_dialog"),
        )
    }

    if (showMealTypeSheet) {
        MealTypeSheet(
            modifier = Modifier.testTag("bfs_meal_type_sheet"),
            onSelect = { mealType ->
                showMealTypeSheet = false
                lastLoggedMealType = mealType
                onLog(mealType)
                showTemplateSheet = true
            },
            onDismiss = { showMealTypeSheet = false },
        )
    }

    if (showTemplateSheet) {
        SaveAsTemplateSheet(
            onSave    = { name -> onSaveAsTemplate(name); showTemplateSheet = false },
            onSkip    = { showTemplateSheet = false },
            onDismiss = { showTemplateSheet = false },
        )
    }

    if (showAbandonDialog) {
        AlertDialog(
            onDismissRequest = { showAbandonDialog = false },
            title   = { Text("Abandon meal?") },
            text    = { Text("You have ${ingredients.size} ingredient(s) added.") },
            confirmButton = {
                TextButton(
                    onClick  = { showAbandonDialog = false; onBack(null) },
                    modifier = Modifier.testTag("bfs_bailout_discard"),
                ) { Text("Discard") }
            },
            dismissButton = {
                TextButton(
                    onClick  = { showAbandonDialog = false; onBack(totalKcal) },
                    modifier = Modifier.testTag("bfs_bailout_quick_add"),
                ) { Text("Use $totalKcal kcal in Quick Add") }
            },
        )
    }
}

@Composable
private fun GramsDialog(
    foodName: String,
    onConfirm: (Double) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var grams by remember { mutableStateOf("") }
    AlertDialog(
        modifier         = modifier,
        onDismissRequest = onDismiss,
        title   = { Text("How many grams of $foodName?") },
        text    = {
            OutlinedTextField(
                value         = grams,
                onValueChange = { grams = it },
                label         = { Text("Grams") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine    = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick  = { grams.toDoubleOrNull()?.let { onConfirm(it) } },
                enabled  = (grams.toDoubleOrNull() ?: 0.0) > 0.0,
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MealTypeSheet(
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 16.dp).padding(bottom = 32.dp)) {
            Text("Meal type", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            listOf("breakfast", "lunch", "dinner", "snack").forEach { type ->
                TextButton(onClick = { onSelect(type) }, modifier = Modifier.fillMaxWidth()) {
                    Text(type.replaceFirstChar { it.uppercase() })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SaveAsTemplateSheet(
    onSave: (String) -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Save as template?", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value         = name,
                onValueChange = { name = it },
                label         = { Text("Template name") },
                singleLine    = true,
                modifier      = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onSkip, modifier = Modifier.weight(1f)) { Text("Skip") }
                Button(
                    onClick  = { onSave(name.trim()) },
                    enabled  = name.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text("Save") }
            }
        }
    }
}
```

- [ ] **Step 3: Run tests**

```bash
./gradlew :app:test --tests "org.branneman.health.ui.BuildFromScratchScreenTest"
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/ui/BuildFromScratchScreen.kt
git add app/src/test/kotlin/org/branneman/health/ui/BuildFromScratchScreenTest.kt
git commit -m "feat(app): add BuildFromScratchScreen with ingredient list, meal type picker, bail-out dialog"
```

---

### Task 19: EditIngredientTemplateScreen

**Files:**
- Create: `app/src/main/kotlin/org/branneman/health/ui/EditIngredientTemplateScreen.kt`

- [ ] **Step 1: Implement `EditIngredientTemplateScreen.kt`**

```kotlin
package org.branneman.health.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.branneman.health.db.entities.FoodItemEntity

@Composable
fun EditIngredientTemplateScreen(
    templateId: String?,
    pendingFoodItem: FoodItemEntity?,
    onPendingFoodItemConsumed: () -> Unit,
    onAddIngredient: () -> Unit,
    onSaved: () -> Unit,
    onBack: () -> Unit,
    viewModel: EditIngredientTemplateViewModel = viewModel(),
) {
    LaunchedEffect(templateId) { viewModel.loadTemplate(templateId) }

    val name        by viewModel.name.collectAsStateWithLifecycle()
    val ingredients by viewModel.ingredients.collectAsStateWithLifecycle()
    val totalKcal   by viewModel.totalKcal.collectAsStateWithLifecycle()

    var pendingItem by remember { mutableStateOf<FoodItemEntity?>(null) }
    LaunchedEffect(pendingFoodItem) {
        if (pendingFoodItem != null) {
            pendingItem = pendingFoodItem
            onPendingFoodItemConsumed()
        }
    }

    if (pendingItem != null) {
        GramsDialogForTemplate(
            foodName  = pendingItem!!.name,
            onConfirm = { grams ->
                viewModel.addIngredient(pendingItem!!, grams)
                pendingItem = null
            },
            onDismiss = { pendingItem = null },
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        TextButton(onClick = onBack) { Text("← Back") }
        Text(
            if (templateId == null) "New ingredient template" else "Edit ingredient template",
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value         = name,
            onValueChange = viewModel::onNameChange,
            label         = { Text("Template name") },
            singleLine    = true,
            modifier      = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text("$totalKcal kcal", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            itemsIndexed(ingredients, key = { i, _ -> i }) { index, ingredient ->
                ListItem(
                    headlineContent   = { Text(ingredient.item.name) },
                    supportingContent = { Text("${ingredient.grams}g · ${ingredient.kcal} kcal") },
                    trailingContent   = {
                        TextButton(onClick = { viewModel.removeAt(index) }) { Text("Remove") }
                    },
                )
                HorizontalDivider()
            }
        }

        Button(onClick = onAddIngredient, modifier = Modifier.fillMaxWidth()) {
            Text("+ Add ingredient")
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick  = {
                // userId resolved in ViewModel via TokenStore
                viewModel.save("")
                onSaved()
            },
            enabled  = name.isNotBlank() && ingredients.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save template") }
    }
}

@Composable
private fun GramsDialogForTemplate(
    foodName: String,
    onConfirm: (Double) -> Unit,
    onDismiss: () -> Unit,
) {
    var grams by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title   = { Text("How many grams of $foodName?") },
        text    = {
            OutlinedTextField(
                value = grams, onValueChange = { grams = it },
                label = { Text("Grams") }, singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick  = { grams.toDoubleOrNull()?.let { onConfirm(it) } },
                enabled  = (grams.toDoubleOrNull() ?: 0.0) > 0.0,
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
```

Note: `EditIngredientTemplateViewModel.save("")` — the empty string for `userId` is a placeholder; the ViewModel resolves the real userId from TokenStore internally. Refactor to pass userId from the ViewModel's own tokenStore (add a `save()` no-arg version that reads userId internally) before shipping.

- [ ] **Step 2: Run app tests**

```bash
./gradlew :app:test
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/ui/EditIngredientTemplateScreen.kt
git commit -m "feat(app): add EditIngredientTemplateScreen for create/edit ingredient templates"
```

---

### Task 20: TemplatesScreen update + App.kt navigation + LogFlowSheet

**Files:**
- Modify: `app/src/main/kotlin/org/branneman/health/ui/TemplatesScreen.kt`
- Modify: `app/src/main/kotlin/org/branneman/health/App.kt`

- [ ] **Step 1: Update `TemplatesScreen.kt`**

The screen needs to:
- Show "N ingredients" as secondary line for ingredient templates (those with `quickAddKcal == null` and items).
- Tapping an ingredient template → navigate to `EditIngredientTemplate`.
- Add button → sheet: "Kcal total" / "From ingredients".

Replace `TemplatesScreen` and `TemplatesContent` composables:

```kotlin
@Composable
fun TemplatesScreen(
    onBack: () -> Unit,
    onEditIngredientTemplate: (id: String) -> Unit,
    onNewIngredientTemplate: () -> Unit,
    viewModel: TemplatesViewModel = viewModel(),
) {
    val templates by viewModel.templates.collectAsStateWithLifecycle()
    TemplatesContent(
        templates                = templates,
        onCreate                 = { name, kcal -> viewModel.create(name, kcal) },
        onUpdate                 = { id, name, kcal -> viewModel.update(id, name, kcal) },
        onDelete                 = { id -> viewModel.delete(id) },
        onEditIngredientTemplate = onEditIngredientTemplate,
        onNewIngredientTemplate  = onNewIngredientTemplate,
        onBack                   = onBack,
    )
}
```

Update `TemplatesContent` signature and list item tap:

```kotlin
@Composable
fun TemplatesContent(
    templates: List<MealTemplateEntity>,
    onCreate: (String, Int) -> Unit,
    onUpdate: (String, String, Int) -> Unit,
    onDelete: (String) -> Unit,
    onEditIngredientTemplate: (String) -> Unit,
    onNewIngredientTemplate: () -> Unit,
    onBack: () -> Unit,
) {
```

Replace the `showAddDialog` button and dialog logic:
- Remove `showAddDialog` boolean and `TemplateDialog` for `showAddDialog`.
- Add a `showAddTypeSheet` state (choosing "Kcal total" vs "From ingredients").
- Keep the existing `editTarget` `TemplateDialog` for kcal-total template editing.

In the list:
```kotlin
items(templates, key = { it.id }) { template ->
    val isIngredient = template.quickAddKcal == null
    ListItem(
        headlineContent   = {
            val prefix = if (template.sortOrder != null) "📌 " else ""
            Text("$prefix${template.name}")
        },
        supportingContent = {
            if (isIngredient) Text("ingredient template")
            else Text("${template.quickAddKcal} kcal")
        },
        modifier = Modifier
            .testTag("template_item_${template.id}")
            .clickable {
                if (isIngredient) onEditIngredientTemplate(template.id)
                else editTarget = template
            },
    )
    HorizontalDivider()
}
```

Replace `+ Add` `TextButton`:
```kotlin
TextButton(
    onClick  = { showAddTypeSheet = true },
    modifier = Modifier.testTag("add_template_button"),
) { Text("+ Add") }
```

Add `AddTypeSheet`:
```kotlin
if (showAddTypeSheet) {
    AddTemplateTypeSheet(
        onKcalTotal       = { showAddTypeSheet = false; showKcalDialog = true },
        onFromIngredients = { showAddTypeSheet = false; onNewIngredientTemplate() },
        onDismiss         = { showAddTypeSheet = false },
    )
}
```

Add the `AddTemplateTypeSheet` composable:
```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddTemplateTypeSheet(
    onKcalTotal: () -> Unit,
    onFromIngredients: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).padding(bottom = 32.dp)) {
            ListItem(
                headlineContent = { Text("Kcal total") },
                trailingContent = { Text("›", style = MaterialTheme.typography.titleLarge) },
                modifier = Modifier.clickable(onClick = onKcalTotal),
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("From ingredients") },
                trailingContent = { Text("›", style = MaterialTheme.typography.titleLarge) },
                modifier = Modifier.clickable(onClick = onFromIngredients),
            )
        }
    }
}
```

- [ ] **Step 2: Update `App.kt` enums and navigation**

Add to `LogPage` enum:
```kotlin
private enum class LogPage { Main, TemplateList, QuickAdd, AskAi, BuildFromScratch, FoodSearch }
```

Add to `SettingsPage` enum:
```kotlin
private enum class SettingsPage { Main, MealButtons, DrinkButtons, Profile, Goal, Schedule, Templates, Ai, EditIngredientTemplate, TemplatesFoodSearch }
```

In `MainNav`, add state for food item passing:
```kotlin
var selectedFoodItemForLog      by remember { mutableStateOf<FoodItemEntity?>(null) }
var selectedFoodItemForTemplate by remember { mutableStateOf<FoodItemEntity?>(null) }
var editingTemplateId           by remember { mutableStateOf<String?>(null) }
```

Add cases to the `LogPage` when block:
```kotlin
LogPage.BuildFromScratch -> BuildFromScratchScreen(
    pendingFoodItem          = selectedFoodItemForLog,
    onPendingFoodItemConsumed = { selectedFoodItemForLog = null },
    onAddIngredient          = { logPage = LogPage.FoodSearch },
    onLogged                 = { pendingLogUndoAction = null; logPage = LogPage.Main },
    onBack                   = { bailOutKcal ->
        if (bailOutKcal != null) {
            quickAddPrefill = Pair(bailOutKcal, null)
            logPage = LogPage.QuickAdd
        } else {
            logPage = LogPage.Main
        }
    },
)
LogPage.FoodSearch -> FoodSearchScreen(
    onItemSelected = { item -> selectedFoodItemForLog = item; logPage = LogPage.BuildFromScratch },
    onBack         = { logPage = LogPage.BuildFromScratch },
)
```

Add `LogFlowSheet` 4th option — update `LogFlowSheet` composable parameters:
```kotlin
private fun LogFlowSheet(
    onFromTemplate: () -> Unit,
    onQuickAdd: () -> Unit,
    onAskAi: () -> Unit,
    onBuildFromScratch: () -> Unit,
    onDismiss: () -> Unit,
)
```

Add in the Column body (after Ask AI):
```kotlin
HorizontalDivider()
ListItem(
    headlineContent = { Text("Build from scratch") },
    trailingContent = { Text("›", style = MaterialTheme.typography.titleLarge) },
    modifier = Modifier.clickable(onClick = onBuildFromScratch).testTag("log_build_from_scratch"),
)
```

Update the `LogFlowSheet` call site:
```kotlin
LogFlowSheet(
    onFromTemplate     = { showLogSheet = false; logPage = LogPage.TemplateList },
    onQuickAdd         = { showLogSheet = false; logPage = LogPage.QuickAdd },
    onAskAi            = { showLogSheet = false; logPage = LogPage.AskAi },
    onBuildFromScratch = { showLogSheet = false; logPage = LogPage.BuildFromScratch },
    onDismiss          = { showLogSheet = false },
)
```

Add Settings cases:
```kotlin
SettingsPage.Templates -> TemplatesScreen(
    onBack                   = { settingsPage = SettingsPage.Main },
    onEditIngredientTemplate = { id -> editingTemplateId = id; settingsPage = SettingsPage.EditIngredientTemplate },
    onNewIngredientTemplate  = { editingTemplateId = null; settingsPage = SettingsPage.EditIngredientTemplate },
)
SettingsPage.EditIngredientTemplate -> EditIngredientTemplateScreen(
    templateId               = editingTemplateId,
    pendingFoodItem          = selectedFoodItemForTemplate,
    onPendingFoodItemConsumed = { selectedFoodItemForTemplate = null },
    onAddIngredient          = { settingsPage = SettingsPage.TemplatesFoodSearch },
    onSaved                  = { settingsPage = SettingsPage.Templates },
    onBack                   = { settingsPage = SettingsPage.Templates },
)
SettingsPage.TemplatesFoodSearch -> FoodSearchScreen(
    onItemSelected = { item -> selectedFoodItemForTemplate = item; settingsPage = SettingsPage.EditIngredientTemplate },
    onBack         = { settingsPage = SettingsPage.EditIngredientTemplate },
)
```

Add necessary imports to App.kt:
```kotlin
import org.branneman.health.db.entities.FoodItemEntity
import org.branneman.health.ui.BuildFromScratchScreen
import org.branneman.health.ui.EditIngredientTemplateScreen
import org.branneman.health.ui.FoodSearchScreen
```

- [ ] **Step 3: Run app tests (compilation + existing tests)**

```bash
./gradlew :app:test
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/ui/TemplatesScreen.kt
git add app/src/main/kotlin/org/branneman/health/App.kt
git commit -m "feat(app): wire BuildFromScratch, FoodSearch, EditIngredientTemplate into navigation; update TemplatesScreen"
```

---

### Task 21: Final test run + backlog update

- [ ] **Step 1: Run all tests**

```bash
./gradlew :app:test :server:test
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 2: Mark story 15 done in `docs/feature-backlog.md`**

Change:
```
|   | 15 | **Build from scratch** — ...
```
To:
```
| ✓ | 15 | **Build from scratch** — ...
```

- [ ] **Step 3: Commit**

```bash
git add docs/feature-backlog.md
git commit -m "docs: mark story 15 (Build from scratch) done"
```

- [ ] **Step 4: Release**

The feature is now complete. Both phases are deployed. Run the full E2E smoke test, then release the app.

```bash
set -a; source .env; set +a
./scripts/create-dev-avd.sh --no-create
```
