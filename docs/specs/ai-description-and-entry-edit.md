# Spec: AI description field + log entry editing

## Scope

Two independent improvements to food logging:

1. **AI description** — Ask AI log entries currently use the user's raw typed text as the
   label (e.g. "tiramisu, restaurant portion, very rich"), and fall back to "Unknown" when
   only a photo was provided. Claude now returns a concise meal name used as the label for
   all three Ask AI paths (text-only, text+photo, photo-only). The user's typed text becomes
   a secondary fallback if Claude returns no description.

2. **Edit log entry** — tapping a quick-add log entry opens an edit dialog (kcal + label).
   Edits sync to the server via a new `PATCH /in/log/{id}` endpoint. Food-item entries
   (Build from scratch only) remain delete-only; their nutrition snapshot is still immutable.

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

In `logDirectly(kcal, label, aiDescription)`, use `aiDescription ?: label` as
`quickAddLabel`. Claude's concise description wins over the user's raw typed text; the
typed text is the fallback; null means Room falls back to `mealType` ("Unknown").

`AskAiScreen` passes `state.aiDescription` through to `onUseThis` and `logDirectly`.

---

## Feature 2 — Edit log entry

### Invariant: quick-add vs. food-item

There are currently four logging paths. Three of them produce **quick-add entries**
(`quickAddKcal IS NOT NULL`, no `log_entry_item` rows):

| Path | How it logs |
|------|-------------|
| Quick-add kcal | `QuickAddScreen` → `LogEntryEntity(quickAddKcal = kcal, quickAddLabel = label)` |
| Ask AI | `AskAiViewModel.logDirectly()` → same shape |
| Template / shortcut | `LogViewModel.logFromTemplate/Shortcut()` → same shape (uses `template.quickAddKcal`) |

Only **Build from scratch** produces a **food-item entry** (`quickAddKcal IS NULL`, with
`log_entry_item` rows that snapshot nutrition values at log time). These remain delete-only
— their snapshot integrity is still immutable.

The edit dialog is only shown for quick-add entries; tapping a food-item entry still opens
the existing delete-confirm dialog.

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

- **Commit 1** (`feat(ai): add description field to estimate response`) — shared DTO +
  server + app changes for feature 1.
- **Commit 2** (`feat(log): add quick-add entry editing with PATCH /in/log/{id}`) — all of
  feature 2 (shared DTO, server endpoint, SyncStatus, DAO, sync service, API client, ViewModel,
  UI, doc updates).

---

## Test coverage

### Feature 1
- **Unit** `AiEstimateServiceTest`: description passed through when Claude returns it; null when absent.
- **Unit** `AskAiViewModelTest`: `aiDescription` used as label when present; user text used as fallback when `aiDescription` is null; null when neither present.

### Feature 2
- **App component** `LogEntryDaoTest`: `updateQuickAdd` sets correct fields and `PENDING_UPDATE` status.
- **App component** `LogEntrySyncServiceTest` (Robolectric + in-memory Room + `MockEngine`): `PENDING_UPDATE` entries call `patchQuickAdd`; on success status → `SYNCED`.
- **App component** `LogViewModelTest`: `editEntry` calls DAO correctly.
- **App component** `LogScreenTest`: tapping quick-add entry opens edit dialog; tapping food-item entry opens delete dialog.
- **Server integration** `LogEntryIntegrationTest` (UUID slot 4): `PATCH /in/log/{id}` happy path (204); 404 on unknown id / other user's entry; 422 on food-item entry.
- **API test** `LogEntryApiTest`: `PATCH /in/log/{id}` against live server — 204 happy path; cleanup in teardown.
