# AI Description + Quick-Add Entry Editing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a short AI-generated meal name to all Ask AI log entries, and let users edit kcal + label on any quick-add entry.

**Architecture:** Two independent features, two commits. Feature 1 threads a new `description` field from Claude's JSON response through the server DTO into the app's label logic. Feature 2 adds a `PENDING_UPDATE` sync state, a `PATCH /in/log/{id}` server endpoint, and an edit dialog on the log screen that replaces the delete-only tap behaviour for quick-add entries.

**Tech Stack:** Kotlin, Ktor (server), Jetpack Compose + Room (app), shared KMP DTOs, JUnit + Robolectric + Compose test rule (app tests), Ktor `testApplication` + Exposed + local Postgres `health_test` (server integration tests), `MockEngine` (sync service tests).

## Global Constraints

- All IDs are UUIDs — never `Int`/`Long`/`BIGSERIAL`.
- Conventional commits: `feat(ai): …` for commit 1; `feat(log): …` for commit 2.
- Run `./gradlew :server:test` after any server change; run `./gradlew :app:test` after any app/shared change. Both must report `BUILD SUCCESSFUL` before committing.
- Never commit on a failing or unrun test suite.
- Feature 1 and Feature 2 land in separate commits — do not mix.
- Spec: `docs/specs/ai-description-and-entry-edit.md`.

---

## File Map

### Feature 1 — AI description field

| Action | File |
|--------|------|
| Modify | `shared/src/commonMain/kotlin/org/branneman/health/AiEstimateResponseDto.kt` |
| Modify | `server/src/main/kotlin/org/branneman/health/ai/AiEstimateService.kt` |
| Test   | `server/src/test/kotlin/org/branneman/health/ai/AiEstimateServiceTest.kt` |
| Modify | `app/src/main/kotlin/org/branneman/health/ai/AskAiViewModel.kt` |
| Test   | `app/src/test/kotlin/org/branneman/health/ai/AskAiViewModelTest.kt` |
| Modify | `app/src/main/kotlin/org/branneman/health/ui/AskAiScreen.kt` |

### Feature 2 — Quick-add entry editing

| Action | File |
|--------|------|
| Create | `shared/src/commonMain/kotlin/org/branneman/health/QuickAddUpdateRequestDto.kt` |
| Modify | `app/src/main/kotlin/org/branneman/health/db/SyncStatus.kt` |
| Modify | `app/src/main/kotlin/org/branneman/health/db/dao/LogEntryDao.kt` |
| Test   | `app/src/test/kotlin/org/branneman/health/db/dao/LogEntryDaoTest.kt` |
| Modify | `app/src/main/kotlin/org/branneman/health/network/HealthApiClient.kt` |
| Modify | `app/src/main/kotlin/org/branneman/health/sync/LogEntrySyncService.kt` |
| Test   | `app/src/test/kotlin/org/branneman/health/sync/LogEntrySyncServiceTest.kt` |
| Modify | `app/src/main/kotlin/org/branneman/health/log/LogViewModel.kt` |
| Test   | `app/src/test/kotlin/org/branneman/health/log/LogViewModelTest.kt` |
| Modify | `server/src/main/kotlin/org/branneman/health/Application.kt` |
| Test   | `server/src/test/kotlin/org/branneman/health/LogEntryIntegrationTest.kt` |
| Modify | `app/src/main/kotlin/org/branneman/health/ui/LogScreen.kt` |
| Test   | `app/src/test/kotlin/org/branneman/health/ui/LogScreenTest.kt` |
| Modify | `server/src/apiTest/kotlin/org/branneman/health/LogEntryApiTest.kt` |
| Modify | `docs/api-design.md` |
| Modify | `docs/ubiquitous-language.md` |
| Modify | `docs/domain-model.md` |
| Modify | `docs/ux/3-features/logging.md` |
| Modify | `docs/ux/4-flows.md` |
| Modify | `docs/ux/2-scenarios.md` |

---

## Task 1: Add `description` field to server AI estimate path

**Files:**
- Modify: `shared/src/commonMain/kotlin/org/branneman/health/AiEstimateResponseDto.kt`
- Modify: `server/src/main/kotlin/org/branneman/health/ai/AiEstimateService.kt`
- Test: `server/src/test/kotlin/org/branneman/health/ai/AiEstimateServiceTest.kt`

**Interfaces:**
- Produces: `AiEstimateResponseDto(kcal, explanation, description)` — Task 3 reads `description` from this DTO via `AiEstimateApiResult.Success`.
- Produces: `ClaudeEstimate(kcal, explanation, description)` — internal to server, used in Task 2.

- [ ] **Step 1: Write failing tests in `AiEstimateServiceTest`**

Add to the end of the class:

```kotlin
@Test
fun `description field is passed through when Claude returns it`() {
    val service = makeService { ClaudeEstimate(500, "Short sentence.", "grilled chicken salad") }
    val result = service.estimate("key", "chicken salad", null, null)
    assertEquals("grilled chicken salad", result.description)
}

@Test
fun `description field is null when Claude omits it`() {
    val service = makeService { ClaudeEstimate(500, null, null) }
    val result = service.estimate("key", "food", null, null)
    assertNull(result.description)
}

@Test
fun `AiEstimateResponseDto serializes without description field when null`() {
    val dto = AiEstimateResponseDto(350, null, null)
    val json = Json.encodeToString(AiEstimateResponseDto.serializer(), dto)
    assertFalse(json.contains("description"))
}

@Test
fun `AiEstimateResponseDto deserializes when description field is present`() {
    val json = """{"kcal":500,"description":"tiramisu"}"""
    val dto = Json.decodeFromString(AiEstimateResponseDto.serializer(), json)
    assertEquals("tiramisu", dto.description)
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```
./gradlew :server:test --tests "*.AiEstimateServiceTest" 2>&1 | tail -20
```

Expected: compilation error — `ClaudeEstimate` and `AiEstimateResponseDto` don't have `description` yet.

- [ ] **Step 3: Add `description` to `AiEstimateResponseDto`**

```kotlin
@Serializable
data class AiEstimateResponseDto(
    val kcal: Int,
    val explanation: String? = null,
    val description: String? = null,
)
```

- [ ] **Step 4: Add `description` to `ClaudeEstimate` in `AiEstimateService.kt`**

```kotlin
@Serializable
data class ClaudeEstimate(val kcal: Int, val explanation: String? = null, val description: String? = null)
```

- [ ] **Step 5: Pass `description` through in `AiEstimateService.estimate()`**

The return line currently reads:
```kotlin
return AiEstimateResponseDto(result.kcal, result.explanation)
```

Change to:
```kotlin
return AiEstimateResponseDto(result.kcal, result.explanation, result.description)
```

- [ ] **Step 6: Run tests to confirm they pass**

```
./gradlew :server:test --tests "*.AiEstimateServiceTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

---

## Task 2: Update `HttpAnthropicGateway` JSON schema and system prompt

**Files:**
- Modify: `server/src/main/kotlin/org/branneman/health/ai/AiEstimateService.kt`

**Interfaces:**
- Consumes: `ClaudeEstimate(kcal, explanation, description)` from Task 1.

No automated test covers the live Claude schema — the change is verified by the existing integration test suite passing (which uses a `fakeGateway`).

- [ ] **Step 1: Add `description` to the JSON schema in `HttpAnthropicGateway.outputConfig`**

In `AiEstimateService.kt`, find the `outputConfig` val inside `HttpAnthropicGateway`. The `properties` map currently has `"kcal"` and `"explanation"`. Add `"description"`:

```kotlin
putAdditionalProperty(
    "properties",
    JsonValue.from(
        mapOf(
            "kcal"         to mapOf("type" to "integer"),
            "explanation"  to mapOf("type" to "string"),
            "description"  to mapOf("type" to "string"),
        )
    )
)
```

(`"required"` stays `["kcal"]` — `description` is optional.)

- [ ] **Step 2: Update the system prompt in `HttpAnthropicGateway.estimate()`**

Find the `.system(...)` call. Append the description instruction to the existing prompt string:

```kotlin
.system(
    "You are a nutritionist AI. Estimate the total calorie content of the described " +
    "or shown meal. Return only a JSON object with a required 'kcal' integer (1–9999) " +
    "and an optional 'explanation' string. If you include an explanation, keep it to " +
    "one short sentence starting with the calorie count, e.g. '350 kcal — typical " +
    "cocktail with one spirit measure and mixer.' " +
    "Also include an optional 'description' string: a very short meal name (2–5 words), " +
    "e.g. 'tiramisu' or 'grilled chicken salad'."
)
```

- [ ] **Step 3: Run full server tests**

```
./gradlew :server:test 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

---

## Task 3: Thread `aiDescription` through `AskAiViewModel`

**Files:**
- Modify: `app/src/main/kotlin/org/branneman/health/ai/AskAiViewModel.kt`
- Test: `app/src/test/kotlin/org/branneman/health/ai/AskAiViewModelTest.kt`

**Interfaces:**
- Consumes: `AiEstimateApiResult.Success(dto)` where `dto.description: String?` now exists (Task 1).
- Produces: `AskAiState.Result(kcal, explanation, inputText, aiDescription)` — consumed by Task 4.
- Produces: `logDirectly(kcal, inputText, aiDescription)` — writes to Room with `aiDescription ?: inputText` as label.

- [ ] **Step 1: Write failing tests in `AskAiViewModelTest`**

The existing `success response transitions to Result state` test uses `AiEstimateResponseDto(650, "Tiramisu portion.")` (no description). Keep it — it should still pass once `description` defaults to null.

Add these tests:

```kotlin
@Test
fun `ai description from dto is set on Result state`() = runTest {
    val vm = makeVm(repo(estimateResult = AiEstimateApiResult.Success(
        AiEstimateResponseDto(500, "Short sentence.", "grilled chicken")
    )))
    vm.setText("chicken")
    testDispatcher.scheduler.advanceUntilIdle()
    vm.estimate()
    testDispatcher.scheduler.advanceUntilIdle()
    val state = vm.state.value
    assertIs<AskAiState.Result>(state)
    assertEquals("grilled chicken", state.aiDescription)
}

@Test
fun `ai description is null on Result state when dto omits it`() = runTest {
    val vm = makeVm(repo(estimateResult = AiEstimateApiResult.Success(
        AiEstimateResponseDto(500, "Some explanation.")  // description defaults to null
    )))
    vm.setText("food")
    testDispatcher.scheduler.advanceUntilIdle()
    vm.estimate()
    testDispatcher.scheduler.advanceUntilIdle()
    val state = vm.state.value
    assertIs<AskAiState.Result>(state)
    assertNull(state.aiDescription)
}
```

- [ ] **Step 2: Run tests to confirm new ones fail due to missing `aiDescription`**

```
./gradlew :app:test --tests "*.AskAiViewModelTest" 2>&1 | tail -20
```

Expected: compilation error — `AskAiState.Result` and `logDirectly` don't have `aiDescription` yet.

- [ ] **Step 3: Add `aiDescription` to `AskAiState.Result`**

In `AskAiViewModel.kt`, change:

```kotlin
data class Result(val kcal: Int, val explanation: String?, val inputText: String?) : AskAiState
```

to:

```kotlin
data class Result(val kcal: Int, val explanation: String?, val inputText: String?, val aiDescription: String?) : AskAiState
```

- [ ] **Step 4: Populate `aiDescription` in `estimate()`**

In the `estimate()` function, change:

```kotlin
is AiEstimateApiResult.Success     ->
    AskAiState.Result(result.dto.kcal, result.dto.explanation, text.value.trim().ifEmpty { null })
```

to:

```kotlin
is AiEstimateApiResult.Success     ->
    AskAiState.Result(
        kcal          = result.dto.kcal,
        explanation   = result.dto.explanation,
        inputText     = text.value.trim().ifEmpty { null },
        aiDescription = result.dto.description,
    )
```

- [ ] **Step 5: Update `logDirectly` to accept and use `aiDescription`**

Change the signature and label logic:

```kotlin
fun logDirectly(kcal: Int, label: String?, aiDescription: String?) {
    viewModelScope.launch {
        val userId = tokenStore.tokenFlow.first()?.userId ?: return@launch
        val entity = LogEntryEntity(
            userId        = userId,
            loggedAt      = OffsetDateTime.now().toString(),
            mealType      = "unknown",
            quickAddKcal  = kcal,
            quickAddLabel = (aiDescription ?: label)?.trim()?.ifEmpty { null },
        )
        db.logEntryDao().upsert(entity)
        lastLogged = entity
    }
}
```

- [ ] **Step 6: Run tests**

```
./gradlew :app:test --tests "*.AskAiViewModelTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

---

## Task 4: Update `AskAiScreen` to pass `aiDescription` + commit 1

**Files:**
- Modify: `app/src/main/kotlin/org/branneman/health/ui/AskAiScreen.kt`

**Interfaces:**
- Consumes: `AskAiState.Result.aiDescription: String?` (Task 3).
- Consumes: `AskAiViewModel.logDirectly(kcal, label, aiDescription)` (Task 3).

- [ ] **Step 1: Update `onUseThis` signature in `AskAiContent`**

Change:

```kotlin
onUseThis: (kcal: Int, label: String?) -> Unit,
```

to:

```kotlin
onUseThis: (kcal: Int, label: String?, aiDescription: String?) -> Unit,
```

- [ ] **Step 2: Pass `aiDescription` in the button's `onClick` inside the `is AskAiState.Result` branch**

Change:

```kotlin
Button(
    onClick  = { onUseThis(state.kcal, state.inputText) },
```

to:

```kotlin
Button(
    onClick  = { onUseThis(state.kcal, state.inputText, state.aiDescription) },
```

- [ ] **Step 3: Update the `AskAiScreen` lambda that wires `onUseThis`**

Change:

```kotlin
onUseThis = { kcal, label ->
    viewModel.logDirectly(kcal, label)
    onUseThis(kcal, label) { viewModel.undoDirectLog() }
},
```

to:

```kotlin
onUseThis = { kcal, label, aiDescription ->
    viewModel.logDirectly(kcal, label, aiDescription)
    onUseThis(kcal, label) { viewModel.undoDirectLog() }
},
```

- [ ] **Step 4: Run full app tests**

```
./gradlew :app:test 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Run full server tests**

```
./gradlew :server:test 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit feature 1**

```bash
git add \
  shared/src/commonMain/kotlin/org/branneman/health/AiEstimateResponseDto.kt \
  server/src/main/kotlin/org/branneman/health/ai/AiEstimateService.kt \
  server/src/test/kotlin/org/branneman/health/ai/AiEstimateServiceTest.kt \
  app/src/main/kotlin/org/branneman/health/ai/AskAiViewModel.kt \
  app/src/test/kotlin/org/branneman/health/ai/AskAiViewModelTest.kt \
  app/src/main/kotlin/org/branneman/health/ui/AskAiScreen.kt
git commit -m "feat(ai): add description field to estimate response and use as log label"
```

---

## Task 5: Add `QuickAddUpdateRequestDto` and `PENDING_UPDATE` sync status

**Files:**
- Create: `shared/src/commonMain/kotlin/org/branneman/health/QuickAddUpdateRequestDto.kt`
- Modify: `app/src/main/kotlin/org/branneman/health/db/SyncStatus.kt`

**Interfaces:**
- Produces: `QuickAddUpdateRequestDto(kcal: Int, label: String?)` — consumed by Tasks 6, 7, 9.
- Produces: `SyncStatus.PENDING_UPDATE` — consumed by Tasks 6, 7.

No dedicated test for these — they are data declarations; correctness is covered by the consuming tasks.

- [ ] **Step 1: Create `QuickAddUpdateRequestDto`**

```kotlin
package org.branneman.health

import kotlinx.serialization.Serializable

@Serializable
data class QuickAddUpdateRequestDto(
    val kcal: Int,
    val label: String?,
)
```

- [ ] **Step 2: Add `PENDING_UPDATE` to `SyncStatus`**

In `app/src/main/kotlin/org/branneman/health/db/SyncStatus.kt`, change:

```kotlin
enum class SyncStatus { SYNCED, PENDING_CREATE, PENDING_DELETE }
```

to:

```kotlin
enum class SyncStatus { SYNCED, PENDING_CREATE, PENDING_UPDATE, PENDING_DELETE }
```

Room stores enum values as their `.name` string in a `TEXT` column. Adding a new enum value does not change the SQLite schema, so no Room migration is required.

- [ ] **Step 3: Verify compilation**

```
./gradlew :app:test 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL` (no tests changed, but compilation verifies the new enum value is accepted).

---

## Task 6: Add `updateQuickAdd` DAO method + test

**Files:**
- Modify: `app/src/main/kotlin/org/branneman/health/db/dao/LogEntryDao.kt`
- Test: `app/src/test/kotlin/org/branneman/health/db/dao/LogEntryDaoTest.kt`

**Interfaces:**
- Consumes: `SyncStatus.PENDING_UPDATE` (Task 5).
- Produces: `LogEntryDao.updateQuickAdd(id, kcal, label)` — consumed by Tasks 7, 8.

- [ ] **Step 1: Write failing tests in `LogEntryDaoTest`**

Add after the existing tests:

```kotlin
@Test
fun `updateQuickAdd sets new kcal and label and marks PENDING_UPDATE`() = runTest {
    val entry = aQuickAddEntry(quickAddKcal = 400, quickAddLabel = "old label",
                               syncStatus = SyncStatus.SYNCED)
    dao.upsert(entry)

    dao.updateQuickAdd(entry.id, 600, "new label")

    val updated = dao.observeAll().first().single()
    assertEquals(600, updated.quickAddKcal)
    assertEquals("new label", updated.quickAddLabel)
    assertEquals(SyncStatus.PENDING_UPDATE, updated.syncStatus)
}

@Test
fun `updateQuickAdd sets null label when label is null`() = runTest {
    val entry = aQuickAddEntry(quickAddKcal = 300, quickAddLabel = "had label",
                               syncStatus = SyncStatus.SYNCED)
    dao.upsert(entry)

    dao.updateQuickAdd(entry.id, 300, null)

    val updated = dao.observeAll().first().single()
    assertNull(updated.quickAddLabel)
    assertEquals(SyncStatus.PENDING_UPDATE, updated.syncStatus)
}
```

- [ ] **Step 2: Run to confirm failure**

```
./gradlew :app:test --tests "*.LogEntryDaoTest" 2>&1 | tail -20
```

Expected: compilation error — `updateQuickAdd` not defined.

- [ ] **Step 3: Add `updateQuickAdd` to `LogEntryDao`**

Add after `updateSyncStatus`:

```kotlin
@Query("""
    UPDATE log_entry
    SET quickAddKcal = :kcal, quickAddLabel = :label, syncStatus = 'PENDING_UPDATE'
    WHERE id = :id
""")
suspend fun updateQuickAdd(id: String, kcal: Int, label: String?)
```

- [ ] **Step 4: Run tests to confirm they pass**

```
./gradlew :app:test --tests "*.LogEntryDaoTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

---

## Task 7: Add `patchQuickAdd` to API client + handle `PENDING_UPDATE` in sync service

**Files:**
- Modify: `app/src/main/kotlin/org/branneman/health/network/HealthApiClient.kt`
- Modify: `app/src/main/kotlin/org/branneman/health/sync/LogEntrySyncService.kt`
- Test: `app/src/test/kotlin/org/branneman/health/sync/LogEntrySyncServiceTest.kt`

**Interfaces:**
- Consumes: `SyncStatus.PENDING_UPDATE` (Task 5), `LogEntryDao.updateQuickAdd` (Task 6), `QuickAddUpdateRequestDto` (Task 5).
- Produces: `HealthApiClient.patchQuickAdd(token, id, dto)` — no external consumer; called by `LogEntrySyncService`.

- [ ] **Step 1: Write failing tests in `LogEntrySyncServiceTest`**

Add after the existing tests:

```kotlin
@Test
fun `PENDING_UPDATE is patched and marked SYNCED on 204`() = runTest {
    val entry = aQuickAddEntry(quickAddKcal = 600, quickAddLabel = "new label",
                               syncStatus = SyncStatus.PENDING_UPDATE)
    db.logEntryDao().upsert(entry)

    val api = mockApiClient { req ->
        if (req.method.value == "PATCH") respond("", HttpStatusCode.NoContent)
        else respond("", HttpStatusCode.InternalServerError)
    }

    LogEntrySyncService(api, db).sync("token")

    assertEquals(1, db.logEntryDao().getByStatus(SyncStatus.SYNCED).size)
    assertTrue(db.logEntryDao().getByStatus(SyncStatus.PENDING_UPDATE).isEmpty())
}

@Test
fun `PENDING_UPDATE stays PENDING_UPDATE on network error`() = runTest {
    val entry = aQuickAddEntry(quickAddKcal = 600, syncStatus = SyncStatus.PENDING_UPDATE)
    db.logEntryDao().upsert(entry)

    val api = HealthApiClient("http://test", HttpClient(MockEngine { error("connection refused") }) {
        install(ContentNegotiation) { json() }
    })

    LogEntrySyncService(api, db).sync("token")

    assertEquals(1, db.logEntryDao().getByStatus(SyncStatus.PENDING_UPDATE).size)
}
```

- [ ] **Step 2: Run to confirm failure**

```
./gradlew :app:test --tests "*.LogEntrySyncServiceTest" 2>&1 | tail -20
```

Expected: compilation error — `PENDING_UPDATE` not handled in sync service and `patchQuickAdd` doesn't exist.

- [ ] **Step 3: Add `patchQuickAdd` to `HealthApiClient`**

Add the `patch` import at the top of `HealthApiClient.kt`:

```kotlin
import io.ktor.client.request.patch
```

Add the method after `deleteLogEntry`:

```kotlin
suspend fun patchQuickAdd(token: String, id: String, dto: QuickAddUpdateRequestDto) {
    val response = client.patch("$baseUrl/in/log/$id") {
        header(HttpHeaders.Authorization, "Bearer $token")
        contentType(ContentType.Application.Json)
        setBody(dto)
    }
    if (!response.status.isSuccess()) {
        throw Exception("PATCH /in/log/$id failed: ${response.status}")
    }
}
```

Also add the import at the top of `HealthApiClient.kt`:

```kotlin
import org.branneman.health.QuickAddUpdateRequestDto
```

- [ ] **Step 4: Handle `PENDING_UPDATE` in `LogEntrySyncService`**

In `LogEntrySyncService.sync()`, add a new loop after the `PENDING_CREATE` block and before the `PENDING_DELETE` block:

```kotlin
db.logEntryDao().getByStatus(SyncStatus.PENDING_UPDATE).forEach { entity ->
    if (entity.quickAddKcal == null) return@forEach
    runCatching {
        api.patchQuickAdd(
            token = token,
            id    = entity.id,
            dto   = QuickAddUpdateRequestDto(
                kcal  = entity.quickAddKcal,
                label = entity.quickAddLabel,
            ),
        )
    }.onSuccess {
        db.logEntryDao().updateSyncStatus(entity.id, SyncStatus.SYNCED)
    }
}
```

Add the import at the top:

```kotlin
import org.branneman.health.QuickAddUpdateRequestDto
```

- [ ] **Step 5: Run tests**

```
./gradlew :app:test --tests "*.LogEntrySyncServiceTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

---

## Task 8: Add `editEntry` to `LogViewModel` + test

**Files:**
- Modify: `app/src/main/kotlin/org/branneman/health/log/LogViewModel.kt`
- Test: `app/src/test/kotlin/org/branneman/health/log/LogViewModelTest.kt`

**Interfaces:**
- Consumes: `LogEntryDao.updateQuickAdd(id, kcal, label)` (Task 6).
- Produces: `LogViewModel.editEntry(entry: LogEntryEntity, kcal: Int, label: String?)` — consumed by Task 10 (LogScreen).

- [ ] **Step 1: Write failing test in `LogViewModelTest`**

Add after the existing tests:

```kotlin
@Test
fun `editEntry updates kcal and label in Room`() = runTest {
    val farFuture = OffsetDateTime.now().plusDays(30).toString()
    tokenStore.save("test-token", farFuture, userId)

    val entry = org.branneman.health.aQuickAddEntry(
        userId       = userId,
        quickAddKcal = 400,
        quickAddLabel = "old",
        syncStatus   = org.branneman.health.db.SyncStatus.SYNCED,
    )
    db.logEntryDao().upsert(entry)

    viewModel.editEntry(entry, 600, "new label")
    testDispatcher.scheduler.advanceUntilIdle()

    val updated = db.logEntryDao().observeAll().first().single()
    assertEquals(600, updated.quickAddKcal)
    assertEquals("new label", updated.quickAddLabel)
    assertEquals(org.branneman.health.db.SyncStatus.PENDING_UPDATE, updated.syncStatus)
}
```

- [ ] **Step 2: Run to confirm failure**

```
./gradlew :app:test --tests "*.LogViewModelTest" 2>&1 | tail -20
```

Expected: compilation error — `editEntry` not defined.

- [ ] **Step 3: Add `editEntry` to `LogViewModel`**

Add after `undoDelete()`:

```kotlin
fun editEntry(entry: LogEntryEntity, kcal: Int, label: String?) {
    viewModelScope.launch {
        db.logEntryDao().updateQuickAdd(entry.id, kcal, label?.trim()?.ifEmpty { null })
    }
}
```

- [ ] **Step 4: Run tests**

```
./gradlew :app:test --tests "*.LogViewModelTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

---

## Task 9: Add `PATCH /in/log/{id}` to server + integration test

**Files:**
- Modify: `server/src/main/kotlin/org/branneman/health/Application.kt`
- Test: `server/src/test/kotlin/org/branneman/health/LogEntryIntegrationTest.kt`

**Interfaces:**
- Consumes: `QuickAddUpdateRequestDto` (Task 5) — needs import in `Application.kt`.
- Produces: `PATCH /in/log/{id}` → 204 on success, 404 on unknown entry, 422 on food-item entry.

- [ ] **Step 1: Write failing tests in `LogEntryIntegrationTest`**

Add the import at the top of the file (if not already present):

```kotlin
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
```

Add after the existing `DELETE` tests:

```kotlin
@Test fun `PATCH in-log updates kcal and label and returns 204`() = appTest {
    val token = login()
    val id = UUID.randomUUID().toString()
    client.post("/in/log/quick-add") {
        bearerAuth(token); contentType(ContentType.Application.Json)
        setBody("""{"id":"$id","quickAddKcal":300,"quickAddLabel":"old","loggedAt":"2026-06-11T12:00:00Z"}""")
    }

    val patch = client.patch("/in/log/$id") {
        bearerAuth(token); contentType(ContentType.Application.Json)
        setBody("""{"kcal":500,"label":"updated"}""")
    }
    assertEquals(HttpStatusCode.NoContent, patch.status)

    val entries = client.get("/in/log") { bearerAuth(token) }
    val arr = Json.parseToJsonElement(entries.bodyAsText()).jsonArray
    val entry = arr.first { it.jsonObject["id"]!!.jsonPrimitive.content == id }.jsonObject
    assertEquals(500, entry["quickAddKcal"]!!.jsonPrimitive.content.toInt())
    assertEquals("updated", entry["quickAddLabel"]!!.jsonPrimitive.content)
}

@Test fun `PATCH in-log returns 404 for unknown entry`() = appTest {
    val token = login()
    val r = client.patch("/in/log/${UUID.randomUUID()}") {
        bearerAuth(token); contentType(ContentType.Application.Json)
        setBody("""{"kcal":500,"label":null}""")
    }
    assertEquals(HttpStatusCode.NotFound, r.status)
}

@Test fun `PATCH in-log returns 404 for another user's entry`() = appTest {
    val token = login()
    val otherId = UUID.randomUUID()
    val entryId = UUID.randomUUID()
    transaction {
        Users.insert {
            it[id]           = otherId
            it[username]     = "other-patch@test.local"
            it[passwordHash] = TEST_HASH
        }
        LogEntry.insert {
            it[LogEntry.id]           = entryId
            it[LogEntry.userId]       = otherId
            it[LogEntry.loggedAt]     = OffsetDateTime.now(ZoneOffset.UTC)
            it[LogEntry.mealType]     = "unknown"
            it[LogEntry.quickAddKcal] = 300
            it[LogEntry.createdAt]    = OffsetDateTime.now(ZoneOffset.UTC)
        }
    }
    val r = client.patch("/in/log/$entryId") {
        bearerAuth(token); contentType(ContentType.Application.Json)
        setBody("""{"kcal":500,"label":null}""")
    }
    assertEquals(HttpStatusCode.NotFound, r.status)
    transaction {
        LogEntry.deleteWhere { LogEntry.id eq entryId }
        Users.deleteWhere { Users.id eq otherId }
    }
}

@Test fun `PATCH in-log returns 422 for food-item entry`() = appTest {
    val token = login()
    val id = UUID.randomUUID()
    transaction {
        LogEntry.insert {
            it[LogEntry.id]           = id
            it[LogEntry.userId]       = testUserId
            it[LogEntry.loggedAt]     = OffsetDateTime.now(ZoneOffset.UTC)
            it[LogEntry.mealType]     = "unknown"
            it[LogEntry.quickAddKcal] = null   // food-item entry
            it[LogEntry.createdAt]    = OffsetDateTime.now(ZoneOffset.UTC)
        }
    }
    val r = client.patch("/in/log/${id}") {
        bearerAuth(token); contentType(ContentType.Application.Json)
        setBody("""{"kcal":500,"label":null}""")
    }
    assertEquals(HttpStatusCode.UnprocessableEntity, r.status)
    transaction { LogEntry.deleteWhere { LogEntry.id eq id } }
}

@Test fun `PATCH in-log returns 401 without token`() = appTest {
    val r = client.patch("/in/log/${UUID.randomUUID()}") {
        contentType(ContentType.Application.Json)
        setBody("""{"kcal":500,"label":null}""")
    }
    assertEquals(HttpStatusCode.Unauthorized, r.status)
}
```

- [ ] **Step 2: Run to confirm failure**

```
./gradlew :server:test --tests "*.LogEntryIntegrationTest" 2>&1 | tail -20
```

Expected: tests fail — endpoint doesn't exist yet (404 or routing error).

- [ ] **Step 3: Add `PATCH /in/log/{id}` to `Application.kt`**

Add these imports near the other imports at the top of `Application.kt` (if not already present — `update` is not currently used in this file):

```kotlin
import org.branneman.health.QuickAddUpdateRequestDto
import org.jetbrains.exposed.sql.update
```

In the `authenticate("api")` block, after `delete("/in/log/{id}")` and before `get("/summary/today")`, insert:

```kotlin
patch("/in/log/{id}") {
    val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
    val entryId = call.parameters["id"]
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: return@patch call.respond(HttpStatusCode.BadRequest)
    val dto = call.receive<QuickAddUpdateRequestDto>()
    if (dto.kcal <= 0) return@patch call.respond(HttpStatusCode.BadRequest)

    val result = transaction {
        val row = LogEntry.selectAll()
            .where { (LogEntry.id eq entryId) and (LogEntry.userId eq userId) }
            .singleOrNull() ?: return@transaction "not_found"
        if (row[LogEntry.quickAddKcal] == null) return@transaction "food_item"
        LogEntry.update({ (LogEntry.id eq entryId) and (LogEntry.userId eq userId) }) {
            it[LogEntry.quickAddKcal]  = dto.kcal
            it[LogEntry.quickAddLabel] = dto.label
        }
        "ok"
    }
    when (result) {
        "not_found" -> call.respond(HttpStatusCode.NotFound)
        "food_item" -> call.respond(HttpStatusCode.UnprocessableEntity,
                           mapOf("error" to "not_a_quick_add_entry"))
        else        -> call.respond(HttpStatusCode.NoContent)
    }
}
```

- [ ] **Step 4: Run integration tests**

```
./gradlew :server:test --tests "*.LogEntryIntegrationTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Run full server tests**

```
./gradlew :server:test 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

---

## Task 10: Add `EditEntryDialog` to `LogScreen` + update tap logic

**Files:**
- Modify: `app/src/main/kotlin/org/branneman/health/ui/LogScreen.kt`
- Test: `app/src/test/kotlin/org/branneman/health/ui/LogScreenTest.kt`

**Interfaces:**
- Consumes: `LogViewModel.editEntry(entry, kcal, label)` (Task 8).

- [ ] **Step 1: Write failing tests in `LogScreenTest`**

Update the `render` helper to include `onEdit`:

```kotlin
private fun render(
    entries: List<LogEntryWithKcal> = emptyList(),
    onDelete: (LogEntryEntity) -> Unit = {},
    onEdit: (LogEntryEntity, Int, String?) -> Unit = { _, _, _ -> },
    onOpenLogFlow: () -> Unit = {},
) {
    compose.setContent {
        MaterialTheme {
            LogContent(
                entries       = entries,
                onDelete      = onDelete,
                onEdit        = onEdit,
                onOpenLogFlow = onOpenLogFlow,
            )
        }
    }
}
```

Update the existing `tapping entry calls onDelete` test — it currently uses `aQuickAddEntry()` which will now open the edit dialog, not delete. Change it to use a food-item entry:

```kotlin
@Test fun `tapping food-item entry opens delete dialog and delete calls onDelete`() {
    val rawEntry = aLogEntry(loggedAt = "2026-06-11T08:00:00Z", mealType = "breakfast")
    val entry = aLogEntryWithKcal(rawEntry)
    var deleted: LogEntryEntity? = null
    render(entries = listOf(entry), onDelete = { deleted = it })
    compose.onNodeWithText("Breakfast", substring = true).performClick()
    compose.onNodeWithText("Delete").performClick()
    assert(deleted?.id == rawEntry.id)
}
```

Add new tests:

```kotlin
@Test fun `tapping quick-add entry opens edit dialog with pre-populated kcal`() {
    val entry = aLogEntryWithKcal(
        aQuickAddEntry(loggedAt = "2026-06-11T08:00:00Z", quickAddKcal = 430, quickAddLabel = "Lunch")
    )
    render(entries = listOf(entry))
    compose.onNodeWithText("Lunch", substring = true).performClick()
    compose.onNodeWithTag("edit_entry_kcal_field").assertExists()
    compose.onNodeWithText("430", substring = true).assertExists()
}

@Test fun `save in edit dialog calls onEdit`() {
    val rawEntry = aQuickAddEntry(
        loggedAt = "2026-06-11T08:00:00Z", quickAddKcal = 430, quickAddLabel = "Lunch"
    )
    val entry = aLogEntryWithKcal(rawEntry)
    var editedId: String? = null
    var editedKcal: Int? = null
    render(
        entries = listOf(entry),
        onEdit  = { e, kcal, _ -> editedId = e.id; editedKcal = kcal },
    )
    compose.onNodeWithText("Lunch", substring = true).performClick()
    compose.onNodeWithTag("edit_entry_kcal_field").performTextClearance()
    compose.onNodeWithTag("edit_entry_kcal_field").performTextInput("600")
    compose.onNodeWithTag("edit_entry_save_button").performClick()
    assertEquals(rawEntry.id, editedId)
    assertEquals(600, editedKcal)
}

@Test fun `save button is disabled when kcal field is empty`() {
    val entry = aLogEntryWithKcal(
        aQuickAddEntry(loggedAt = "2026-06-11T08:00:00Z", quickAddKcal = 430)
    )
    render(entries = listOf(entry))
    compose.onNodeWithText("430", substring = true).performClick()
    compose.onNodeWithTag("edit_entry_kcal_field").performTextClearance()
    compose.onNodeWithTag("edit_entry_save_button").assertIsNotEnabled()
}
```

- [ ] **Step 2: Run to confirm failure**

```
./gradlew :app:test --tests "*.LogScreenTest" 2>&1 | tail -20
```

Expected: compilation error — `LogContent` doesn't have `onEdit` yet.

- [ ] **Step 3: Update `LogContent` in `LogScreen.kt`**

Add `onEdit` parameter and `entryToEdit` state, add `EditEntryDialog` show logic, and change the entry row tap:

```kotlin
@Composable
fun LogContent(
    entries: List<LogEntryWithKcal>,
    onDelete: (LogEntryEntity) -> Unit,
    onEdit: (LogEntryEntity, Int, String?) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier,
    pinnedTemplates: List<MealTemplateEntity> = emptyList(),
    shortcuts: List<ShortcutEntity> = emptyList(),
    onSetUpMealButtons: () -> Unit = {},
    onLogTemplate: (MealTemplateEntity) -> Unit = {},
    onSetUpDrinkButtons: () -> Unit = {},
    onLogShortcut: (ShortcutEntity) -> Unit = {},
    onOpenLogFlow: () -> Unit = {},
) {
    var entryToDelete by remember { mutableStateOf<LogEntryWithKcal?>(null) }
    var entryToEdit   by remember { mutableStateOf<LogEntryWithKcal?>(null) }

    entryToDelete?.let { ewk ->
        DeleteConfirmDialog(
            entry     = ewk,
            onConfirm = { onDelete(ewk.entry); entryToDelete = null },
            onDismiss = { entryToDelete = null },
        )
    }

    entryToEdit?.let { ewk ->
        EditEntryDialog(
            entry     = ewk,
            onSave    = { kcal, label -> onEdit(ewk.entry, kcal, label); entryToEdit = null },
            onDelete  = { entryToDelete = ewk; entryToEdit = null },
            onDismiss = { entryToEdit = null },
        )
    }

    // ... rest of Column unchanged until LazyColumn items block:

    // Change onClick in LazyColumn items:
    items(entries, key = { it.entry.id }) { ewk ->
        LogEntryRow(
            entry   = ewk,
            onClick = {
                if (ewk.entry.quickAddKcal != null) entryToEdit = ewk
                else entryToDelete = ewk
            },
        )
        HorizontalDivider()
    }
    // ... rest unchanged
}
```

- [ ] **Step 4: Add `EditEntryDialog` composable to `LogScreen.kt`**

Add after `DeleteConfirmDialog`:

```kotlin
@Composable
private fun EditEntryDialog(
    entry: LogEntryWithKcal,
    onSave: (kcal: Int, label: String?) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val time = remember(entry.entry.loggedAt) {
        runCatching { OffsetDateTime.parse(entry.entry.loggedAt).format(timeFmt) }.getOrDefault("--:--")
    }
    var kcalText  by remember { mutableStateOf(entry.entry.quickAddKcal?.toString() ?: "") }
    var labelText by remember { mutableStateOf(entry.entry.quickAddLabel ?: "") }

    val kcalValue = kcalText.toIntOrNull()
    val saveEnabled = kcalValue != null && kcalValue > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title  = { Text(time) },
        text   = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value         = labelText,
                    onValueChange = { labelText = it },
                    label         = { Text("Label (optional)") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value         = kcalText,
                    onValueChange = { kcalText = it },
                    label         = { Text("kcal") },
                    singleLine    = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                    ),
                    modifier      = Modifier
                        .fillMaxWidth()
                        .testTag("edit_entry_kcal_field"),
                )
            }
        },
        confirmButton = {
            Button(
                onClick  = { onSave(kcalValue!!, labelText.trim().ifEmpty { null }) },
                enabled  = saveEnabled,
                modifier = Modifier.testTag("edit_entry_save_button"),
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete)  { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
```

Add the missing import at the top of `LogScreen.kt`:

```kotlin
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
```

- [ ] **Step 5: Update `LogScreen` composable to wire `onEdit` and show "Saved" snackbar**

In `LogScreen`, add `LogAction.Saved` to the sealed interface:

```kotlin
private sealed interface LogAction {
    val message: String
    data class Added(override val message: String)   : LogAction
    data class Deleted(override val message: String) : LogAction
    data class Saved(override val message: String)   : LogAction
}
```

Update the `LaunchedEffect(lastAction)` to handle `Saved` without offering undo:

```kotlin
LaunchedEffect(lastAction) {
    val action = lastAction ?: return@LaunchedEffect
    val result = snackbarHostState.showSnackbar(
        message     = action.message,
        actionLabel = if (action is LogAction.Saved) null else "Undo",
        duration    = SnackbarDuration.Short,
    )
    if (result == SnackbarResult.ActionPerformed) {
        when (action) {
            is LogAction.Added   -> viewModel.undoAdd()
            is LogAction.Deleted -> viewModel.undoDelete()
            is LogAction.Saved   -> Unit
        }
    }
    lastAction = null
}
```

Wire `onEdit` in the `LogContent` call inside `LogScreen`:

```kotlin
LogContent(
    entries             = entries,
    pinnedTemplates     = pinnedTemplates,
    shortcuts           = shortcuts,
    onDelete            = { entry ->
        viewModel.deleteEntry(entry)
        lastAction = LogAction.Deleted("Deleted")
    },
    onEdit              = { entry, kcal, label ->
        viewModel.editEntry(entry, kcal, label)
        lastAction = LogAction.Saved("Saved")
    },
    onSetUpMealButtons  = onSetUpMealButtons,
    onLogTemplate       = { template ->
        viewModel.logFromTemplate(template)
        lastAction = LogAction.Added("Logged")
    },
    onSetUpDrinkButtons = onSetUpDrinkButtons,
    onLogShortcut       = { shortcut ->
        onLogShortcut(shortcut)
        lastAction = LogAction.Added("Logged")
    },
    onOpenLogFlow       = onOpenLogFlow,
    modifier            = Modifier.padding(padding),
)
```

- [ ] **Step 6: Run tests**

```
./gradlew :app:test --tests "*.LogScreenTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Run full app tests**

```
./gradlew :app:test 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

---

## Task 11: Update immutability docs and API design

**Files:**
- Modify: `docs/api-design.md`
- Modify: `docs/ubiquitous-language.md`
- Modify: `docs/domain-model.md`
- Modify: `docs/ux/3-features/logging.md`
- Modify: `docs/ux/4-flows.md`
- Modify: `docs/ux/2-scenarios.md`

No tests — doc-only changes.

- [ ] **Step 1: Update `docs/api-design.md`**

In the Design Principles section, change:

> **Log entries are immutable** — `POST` + `DELETE` only, no update. Nutrition is snapshotted at log time so history is correct even if catalog data changes later.

to:

> **Food-item entries are immutable** — `POST` + `DELETE` only for entries with ingredient rows (`log_entry_item`). Nutrition values are snapshotted at log time so history is correct even if catalog data changes later. **Quick-add entries** (kcal + label only, no ingredient rows) may be edited in place via `PATCH /in/log/{id}`.

In the calories-in food log endpoint table, add:

```
| `PATCH`  | `/in/log/{id}`          | Yes  | Update kcal + label on a quick-add entry      |
```

Find the DELETE note near the bottom of the file and change:

> `DELETE /in/log/{id}` → `204 No Content`. No update — log entries are immutable once created.

to:

> `DELETE /in/log/{id}` → `204 No Content`.
> `PATCH /in/log/{id}` → `204 No Content`. Body: `{"kcal": int, "label": string|null}`. Returns `422` if the entry is a food-item entry (has ingredient rows — those remain immutable).

- [ ] **Step 2: Update `docs/ubiquitous-language.md`**

Find:

> An immutable record of food or drink consumed at a point in time.

Change to:

> A record of food or drink consumed at a point in time. Quick-add entries (kcal + optional label) may be edited. Food-item entries (with snapshotted ingredient rows) are immutable.

- [ ] **Step 3: Update `docs/domain-model.md`**

Find the line:

> `log_entry(id, datetime, meal_type)` + `log_entry_item(log_entry_id, food_item_id, grams, snapshotted nutrition)` — nutrition snapshotted at log time; never changes after creation

Change "never changes after creation" to:

> nutrition snapshotted at log time; `log_entry_item` rows never change after creation. Quick-add fields (`quick_add_kcal`, `quick_add_label`) on quick-add entries may be updated.

Also find any line that says "These values never" (in the LogEntryItem section) — leave it as-is since it refers to `log_entry_item` item rows, which remain immutable.

- [ ] **Step 4: Update `docs/ux/3-features/logging.md`**

Find:

> Log entries are **immutable** — no in-place edit. To correct a mistake: delete the entry and re-log using any logging path. This is an API-level constraint (snapshot integrity).

Change to:

> **Quick-add entries** (from Quick-add kcal, Ask AI, template buttons, drink shortcuts) can be edited in place — tap the entry to open an edit dialog for kcal and label. **Food-item entries** (from Build from scratch) are immutable — to correct one, delete and re-log. Food-item entries snapshot ingredient nutrition at log time; editing them would corrupt history.

- [ ] **Step 5: Update `docs/ux/4-flows.md`**

Find:

> - No edit option (entries are immutable — see `3-features/logging.md`)

Change to:

> - Tap a quick-add entry → edit dialog (kcal + label)
> - Tap a food-item entry → delete-confirm dialog (immutable — see `3-features/logging.md`)

- [ ] **Step 6: Update `docs/ux/2-scenarios.md`**

Find all references to immutability of log entries. There are two:

First occurrence (around "Option: Delete"):

> 2. Option: `Delete`. Log entries are immutable — no in-place edit. To correct, delete …

Change to:

> 2. For quick-add entries: tap to open edit dialog (kcal + label). For food-item entries: tap to confirm deletion.

Second occurrence (the "Notes:" paragraph about immutability being an API-level constraint):

> **Notes:** The immutability is an API-level constraint (snapshot integrity). The UX …

Change to:

> **Notes:** Food-item entries are immutable (snapshot integrity — changing ingredient rows post-hoc would corrupt nutrition history). Quick-add entries can be edited because they contain only a kcal number and a label string, with no snapshotted nutrition to protect.

---

## Task 12: Add API test for PATCH + commit feature 2

**Files:**
- Modify: `server/src/apiTest/kotlin/org/branneman/health/LogEntryApiTest.kt`

- [ ] **Step 1: Add PATCH api test**

In `LogEntryApiTest`, add after the existing test:

```kotlin
@Test
fun `PATCH quick-add updates kcal and label`() = runTest {
    val token = login()
    val id = UUID.randomUUID().toString()

    client.post("$serverUrl/in/log/quick-add") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(QuickAddRequestDto(id = id, quickAddKcal = 300, quickAddLabel = "original"))
    }

    val patchResp = client.patch("$serverUrl/in/log/$id") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(QuickAddUpdateRequestDto(kcal = 500, label = "updated"))
    }
    assertEquals(HttpStatusCode.NoContent, patchResp.status)

    val entries = client.get("$serverUrl/in/log") { bearerAuth(token) }.body<List<LogEntryDto>>()
    val entry = entries.first { it.id == id }
    assertEquals(500, entry.quickAddKcal)
    assertEquals("updated", entry.quickAddLabel)

    // cleanup
    client.delete("$serverUrl/in/log/$id") { bearerAuth(token) }
}
```

Add the import at the top:

```kotlin
import io.ktor.client.request.patch
import org.branneman.health.QuickAddUpdateRequestDto
```

- [ ] **Step 2: Run full server tests**

```
./gradlew :server:test 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Run full app tests**

```
./gradlew :app:test 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit feature 2**

```bash
git add \
  shared/src/commonMain/kotlin/org/branneman/health/QuickAddUpdateRequestDto.kt \
  app/src/main/kotlin/org/branneman/health/db/SyncStatus.kt \
  app/src/main/kotlin/org/branneman/health/db/dao/LogEntryDao.kt \
  app/src/test/kotlin/org/branneman/health/db/dao/LogEntryDaoTest.kt \
  app/src/main/kotlin/org/branneman/health/network/HealthApiClient.kt \
  app/src/main/kotlin/org/branneman/health/sync/LogEntrySyncService.kt \
  app/src/test/kotlin/org/branneman/health/sync/LogEntrySyncServiceTest.kt \
  app/src/main/kotlin/org/branneman/health/log/LogViewModel.kt \
  app/src/test/kotlin/org/branneman/health/log/LogViewModelTest.kt \
  server/src/main/kotlin/org/branneman/health/Application.kt \
  server/src/test/kotlin/org/branneman/health/LogEntryIntegrationTest.kt \
  app/src/main/kotlin/org/branneman/health/ui/LogScreen.kt \
  app/src/test/kotlin/org/branneman/health/ui/LogScreenTest.kt \
  server/src/apiTest/kotlin/org/branneman/health/LogEntryApiTest.kt \
  docs/api-design.md \
  docs/ubiquitous-language.md \
  docs/domain-model.md \
  docs/ux/3-features/logging.md \
  docs/ux/4-flows.md \
  docs/ux/2-scenarios.md
git commit -m "feat(log): add quick-add entry editing with PATCH /in/log/{id}"
```
