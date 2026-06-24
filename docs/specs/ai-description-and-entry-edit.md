# Spec: AI description field + log entry editing

## Scope

Two independent improvements to food logging:

1. **AI description** — when a user logs via Ask AI with a photo only (no text), the entry
   currently gets label "Unknown". Claude now returns a short meal description used as the
   label fallback.

2. **Edit log entry** — tapping a quick-add log entry opens an edit dialog (kcal + label).
   Edits sync to the server via a new `PATCH /in/log/{id}` endpoint. Food-item entries
   (Build from scratch / templates) remain delete-only; their nutrition snapshot is still
   immutable.

Out of scope: editing food-item entries, editing `loggedAt` timestamp, multi-device
conflict resolution.

---

## Feature 1 — AI description field

### Shared DTO

`AiEstimateResponseDto` gains an optional `description` field:

```kotlin
@Serializable
data class AiEstimateResponseDto(
    val kcal: Int,
    val explanation: String? = null,
    val description: String? = null,
)
```

### Server — AiEstimateService / HttpAnthropicGateway

`ClaudeEstimate` (server-internal) gains `description: String?`.

JSON schema in `HttpAnthropicGateway.outputConfig` adds `"description"` as an optional
string property (alongside `"kcal"` and `"explanation"`).

System prompt addition: *"If you include a description, make it a very short meal name
(2–5 words), e.g. 'tiramisu' or 'grilled chicken salad'."*

`AiEstimateService.estimate()` passes `result.description` through to the DTO.

### App — AskAiViewModel

`AskAiState.Result` gains `aiDescription: String?`:

```kotlin
data class Result(val kcal: Int, val explanation: String?, val inputText: String?, val aiDescription: String?) : AskAiState
```

In `estimate()`, populate it from `result.dto.description`.

In `logDirectly(kcal, label, aiDescription)`, use `label ?: aiDescription` as
`quickAddLabel`. The user's typed text always wins; Claude's description is the fallback.

`AskAiScreen` passes `state.aiDescription` through to `onUseThis` and `logDirectly`.

---

## Feature 2 — Edit log entry

### Invariant: quick-add vs. food-item

A log entry is a **quick-add entry** when `quickAddKcal IS NOT NULL` (no `log_entry_item`
rows). Only quick-add entries are editable. Food-item entries (`quickAddKcal IS NULL`,
with `log_entry_item` rows containing snapshotted nutrition) remain delete-only.

### Shared DTO

New `QuickAddUpdateRequestDto`:

```kotlin
@Serializable
data class QuickAddUpdateRequestDto(
    val kcal: Int,
    val label: String?,
)
```

### Server — PATCH /in/log/{id}

New endpoint added to the food log route group.

**Request:** `PATCH /in/log/{id}` with body `QuickAddUpdateRequestDto`.  
**Auth:** bearer token required.  
**Behaviour:**
1. Look up `log_entry` by `id` scoped to the caller's `user_id`. Return `404` if not found.
2. If `quick_add_kcal IS NULL` (food-item entry), return `422 Unprocessable Entity`.
3. Update `quick_add_kcal` and `quick_add_label` in place.
4. Return `204 No Content`.

`api-design.md` is updated: the immutability principle is narrowed to food-item entries;
the endpoint table gains `PATCH /in/log/{id}`; the DELETE note is updated.

Docs updated to reflect that quick-add fields are editable:
`ubiquitous-language.md`, `domain-model.md`, `ux/3-features/logging.md`,
`ux/4-flows.md`, `ux/2-scenarios.md`.

### App — SyncStatus

```kotlin
enum class SyncStatus { SYNCED, PENDING_CREATE, PENDING_UPDATE, PENDING_DELETE }
```

### App — LogEntryDao

```kotlin
@Query("""
    UPDATE log_entry
    SET quickAddKcal = :kcal, quickAddLabel = :label, syncStatus = 'PENDING_UPDATE'
    WHERE id = :id
""")
suspend fun updateQuickAdd(id: String, kcal: Int, label: String?)
```

### App — HealthApiClient

```kotlin
suspend fun patchQuickAdd(token: String, id: String, dto: QuickAddUpdateRequestDto)
```

### App — LogEntrySyncService

After the existing `PENDING_CREATE` and `PENDING_DELETE` loops, add a `PENDING_UPDATE`
loop: for each entry with that status, call `patchQuickAdd()`; on success set
`syncStatus = SYNCED`.

### App — LogViewModel

```kotlin
fun editEntry(entry: LogEntryEntity, kcal: Int, label: String?) {
    viewModelScope.launch {
        db.logEntryDao().updateQuickAdd(entry.id, kcal, label?.trim()?.ifEmpty { null })
    }
}
```

### App — LogScreen UI

Current behaviour: tapping any entry opens `DeleteConfirmDialog`.

New behaviour:
- If `entry.quickAddKcal != null` → open `EditEntryDialog`
- Otherwise → open `DeleteConfirmDialog` (unchanged)

`EditEntryDialog` is a new private composable in `LogScreen.kt`:
- Title: time of entry (same format as the row)
- Label `OutlinedTextField` (pre-populated, optional)
- Kcal `OutlinedTextField` (pre-populated, numeric, required — Save disabled while empty or non-numeric)
- Buttons: **Save** (primary), **Delete** (text button, opens existing `DeleteConfirmDialog`),
  **Cancel** (text button)
- On Save: calls `onEdit(entry.entry, kcal, label)`, shows "Saved" snackbar (no undo needed
  since the original value is gone)

`LogContent` receives a new `onEdit: (LogEntryEntity, Int, String?) -> Unit` parameter.
`LogScreen` wires it to `viewModel.editEntry(...)` and shows the snackbar.

---

## Commit plan

- **Commit 1** (`feat(server): add description field to AI estimate response`) — shared DTO +
  server + app changes for feature 1.
- **Commit 2** (`feat(app): add PATCH /in/log/{id} and quick-add entry editing`) — all of
  feature 2 (shared DTO, server endpoint, SyncStatus, DAO, sync service, API client, ViewModel,
  UI). Doc updates included in this commit.

---

## Test coverage

### Feature 1
- `AiEstimateServiceTest`: description field passed through when Claude returns it; null when absent.
- `AskAiViewModelTest`: `logDirectly` uses `aiDescription` when `label` is null.

### Feature 2
- `LogEntryDaoTest`: `updateQuickAdd` sets correct fields and `PENDING_UPDATE` status.
- `LogEntrySyncServiceTest`: `PENDING_UPDATE` entries call `patchQuickAdd`; on success status → `SYNCED`.
- `AiIntegrationTest` / server apiTest: `PATCH /in/log/{id}` happy path (204); 404 on unknown id; 422 on food-item entry.
- `LogViewModelTest`: `editEntry` calls DAO correctly.
- `LogScreenTest`: tapping quick-add entry opens edit dialog; tapping food-item entry opens delete dialog.
