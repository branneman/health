# Story 7 — Quick-add logging

Log anything as kcal + optional label. Offline-first; budget updates immediately on the
Dashboard.

---

## What this story delivers

- A working Log screen: inline quick-add form + today's entry list + tap-to-delete.
- The Dashboard `caloriesIn` updates the moment an entry is written to Room — no sync
  required for the display to be correct.
- Entries sync to the server in the background (SyncWorker) when online. They survive
  offline indefinitely and upload on the next SyncWorker run.

---

## Architecture decision: data ownership split

`caloriesIn` and `caloriesOut` have different sources and different latency. Keeping
them separate makes the Dashboard correct in all connectivity states without any
sync-before-fetch dance.

| Field | Source | How |
|---|---|---|
| `caloriesIn` | Room (reactive) | `logEntryDao().observeAll()` Flow; recomputed on every emission |
| `caloriesOut`, `caloriesOutSource`, `targetDeficit` | Server (`GET /summary/today`) | Existing fetch; unchanged |
| `budgetRemaining` | Computed locally | `caloriesOut − targetDeficit − caloriesIn`; recomputed whenever either input changes |

The server's `TodaySummaryDto.caloriesIn` field continues to exist but the app no longer
reads it. The server still computes it (no DTO change needed).

`DashboardViewModel.load()` is extended with a second coroutine that collects the Room
log entry Flow and keeps `caloriesIn` live. The server call updates only `caloriesOut`
and related fields.

---

## mealType column change

`log_entry.meal_type` in Postgres is currently an ENUM `('breakfast','lunch','dinner','snack')`.
Two new values are added:

- `'unknown'` — quick-add entries where the user hasn't classified the meal. Stays
  `unknown` forever unless a future story lets the user reclassify it; that is fine.
- `'drink'` — reserved for story 10 (drink shortcuts). Added now so the schema is
  ready.

**Flyway V6:**

```sql
ALTER TYPE meal_type ADD VALUE 'drink';
ALTER TYPE meal_type ADD VALUE 'unknown';
```

`LogEntryEntity.mealType: String` stays non-null in Room. Quick-add entries store
`"unknown"`. No Room entity change, no Room migration needed.

---

## New server endpoint: POST /in/log/quick-add

**Request body (`QuickAddRequestDto`):**

```json
{
  "id": "uuid",
  "quickAddKcal": 350,
  "quickAddLabel": "Usual lunch",
  "loggedAt": "2026-06-11T13:02:00+02:00"
}
```

- `id` — client-generated UUID (same as the Room entity). Allows idempotent retries
  from SyncWorker: re-sending the same UUID after a network failure does not create a
  duplicate (server can upsert or ignore `409 Conflict`).
- `loggedAt` — optional; defaults to server `now()` if absent.
- `quickAddLabel` — optional.

**Response:** `LogEntryDto` — `201 Created`.

**Auth:** bearer token required; entry is scoped to the authenticated user.

---

## New server endpoint: DELETE /in/log/{id}

Deletes a single log entry. Scoped to the authenticated user — attempting to delete
another user's entry returns `404` (not `403`, to avoid leaking existence).

**Response:** `204 No Content`.

---

## Sync flow

**PENDING_CREATE (upload):**

1. SyncWorker queries `logEntryDao().getByStatus(PENDING_CREATE)`.
2. For each entry, POSTs to `/in/log/quick-add` with the entity's UUID as `id`.
3. On `201` or `409` (already exists): mark `SYNCED`.
4. On network error or `5xx`: leave as `PENDING_CREATE`, retry next run.

**PENDING_DELETE (server delete + local hard-delete):**

1. SyncWorker queries `logEntryDao().getByStatus(PENDING_DELETE)`.
2. For each entry, sends `DELETE /in/log/{id}`.
3. On `204` or `404` (already gone): hard-delete from Room.
4. On network error or `5xx`: leave as `PENDING_DELETE`, retry next run.

---

## Log screen UI

### Layout

```
┌──────────────────────────────────────┐
│ Log                                  │
├──────────────────────────────────────┤
│  KCAL          LABEL (optional)      │
│  [ 350 ]  [ What was it?     ] [Add] │
│                                      │
│ ──────────────── Today ───────────── │
│  13:02  Usual lunch        560 kcal  │
│  08:14  Usual breakfast    430 kcal  │
│                              ────────│
│                    990 kcal logged   │
└──────────────────────────────────────┘
```

- Numeric keyboard opens immediately when the screen is entered (kcal field focused).
- Add button is disabled while the kcal field is empty or zero.
- Entries listed reverse-chronologically (newest at top), filtered to today, excluding
  `PENDING_DELETE` rows.
- Each row: `HH:mm · label (or blank) · N kcal`.
- Running total at the bottom right: `N kcal logged today`.
- Empty state: `"Nothing logged today."` (neutral, no instruction).

### Add interaction

1. User enters kcal, optionally a label, taps Add.
2. `LogViewModel` validates: positive integer required; non-numeric prevented by
   keyboard type.
3. Writes `LogEntryEntity` to Room (`syncStatus = PENDING_CREATE`, `mealType = "unknown"`).
4. Form resets (kcal cleared, label cleared, focus returns to kcal field).
5. Entry appears at top of list immediately via the Room Flow.
6. Snackbar: `"Logged · Undo"` — 4 seconds. Undo hard-deletes from Room (entry has
   not left the device yet; no server call needed).

### Delete interaction

1. User taps an entry → modal bottom sheet:
   ```
   Usual lunch — 560 kcal — 13:02
   [ Delete ]
   [ Cancel ]
   ```
2. Tap Delete → `syncStatus` set to `PENDING_DELETE` in Room; entry disappears
   immediately from the list.
3. Snackbar: `"Deleted · Undo"` — 4 seconds. Undo restores previous `syncStatus`
   (`PENDING_CREATE` if never synced, `SYNCED` if it was).
4. SyncWorker handles the server DELETE on next run.

---

## Dashboard reactivity

`DashboardViewModel` gains a second coroutine launched in `init`:

```kotlin
viewModelScope.launch {
    val userId = tokenStore.tokenFlow.first()?.userId ?: return@launch
    val today = LocalDate.now().toString()
    logEntryDao().observeAll()
        .collect { entries ->
            val caloriesIn = entries
                .filter { it.userId == userId
                    && it.loggedAt.startsWith(today)
                    && it.syncStatus != SyncStatus.PENDING_DELETE }
                .sumOf { it.quickAddKcal ?: 0 }
            _uiState.update { state ->
                val budget = state.caloriesOut - state.targetDeficit - caloriesIn
                val sport = state.sportTonight
                state.copy(
                    caloriesIn              = caloriesIn,
                    budgetRemaining         = budget,
                    adjustedBudgetRemaining = budget + (sport?.estimatedKcal ?: 0),
                )
            }
        }
}
```

The server call (`getTodaySummary`) continues to run independently and updates only
`caloriesOut`, `caloriesOutSource`, and `targetDeficit` — it no longer touches
`caloriesIn`.

---

## HealthApiClient additions

```kotlin
suspend fun postQuickAdd(token: String, dto: QuickAddRequestDto): LogEntryDto
suspend fun deleteLogEntry(token: String, id: String)  // throws on non-2xx/404
```

---

## LogEntryDao additions

```kotlin
@Query("DELETE FROM log_entry WHERE id = :id")
suspend fun deleteById(id: String)
```

