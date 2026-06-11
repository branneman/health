# Spec — Story 8: Weight Logging

Inline weigh-in field on the dashboard. Enables the morning ritual and feeds the
weight-trend data that drives the weekly verdict.

---

## UI

The daily zone of the dashboard gains a **weight chip row**, positioned between the
budget section and the sport-tonight section.

### Chip states

| State | Display | Interaction |
|---|---|---|
| Not logged today | `⚖ -- kg` | Tap opens entry dialog |
| Logged today | `⚖ 82.5 kg` | Tap opens pre-filled edit dialog |

The chip is always tappable — editing is allowed (to correct a mistake). One entry
per date per user is enforced at the server; editing overwrites the existing value.

### Entry dialog

- Title: **Log weight**
- Input: decimal number field, Android decimal keyboard, pre-filled with today's value if editing
- Validation: value must be in range **20–300 kg**, at most **1 decimal place**; Save disabled otherwise
- Display: always rendered to 1 d.p. (user types `85`, chip shows `85.0 kg`)
- Actions: **Save** / **Cancel**

---

## Components & changes

### Server

`POST /body/weight` changes from insert-or-409 to **upsert on `(user_id, date)`**:
insert on first call for a date, update `kg` on subsequent calls. Always returns
`200 OK` (no more 409). No migration required — the uniqueness check remains in
application code.

`GET /body/weight` — unchanged.

### Shared

No changes. `WeightEntryDto(date, kg)` already exists.

### App

| File | Change |
|---|---|
| `BodyWeightDao` | Add `getForDate(userId: String, date: String): BodyWeightEntity?` |
| `DashboardUiState` | Add `weightKgToday: Double?` (null = not logged today) |
| `DashboardViewModel` | Observe today's weight from Room; add `logWeight(kg: Double)` |
| `DashboardScreen` | Add weight chip row between budget and sport-tonight sections |
| `BodyWeightSyncService` *(new)* | Upload `PENDING_CREATE` entries; mark `SYNCED` on success |
| `SyncWorker` | Call `BodyWeightSyncService.sync()` alongside `LogEntrySyncService` |

---

## Data flow

### Logging (online or offline)

1. User taps chip → dialog opens
2. User enters weight, taps Save
3. ViewModel upserts `BodyWeightEntity(date=today, kg=..., syncStatus=PENDING_CREATE)` into Room;
   updates `weightKgToday` in UI state immediately
4. Chip shows new value
5. `SyncWorker` (next run, up to 4 hours, network required) calls
   `BodyWeightSyncService.sync()` → `POST /body/weight` → `200 OK` → marks entry `SYNCED`;
   any error → leaves `PENDING_CREATE` for the next run

### On login

`LoginSyncService` already pulls all weight history from `GET /body/weight` and
upserts into Room. No change needed.

### Dashboard load

`DashboardViewModel.init` reads today's weight entry from Room (filtered to current
user and today's date) and populates `weightKgToday`.

---

## Error handling

**Offline** — Room write succeeds regardless. The chip updates immediately. The entry
syncs when `SyncWorker` next runs with network. No error shown to the user.

**Sync failure** — `BodyWeightSyncService` leaves the entry as `PENDING_CREATE`.
Retried on next `SyncWorker` run.

**Invalid input** — Save button is disabled if value is outside 20–300 kg or has
more than 1 decimal place. No other validation errors needed.

**Concurrent edits across devices** — last write wins at the server (upsert
semantics). Acceptable for a single-user personal app.

---

## Testing

### Tier 1 — Unit

`DashboardLogicTest` — add cases for `weightKgToday`: null when no entry for today;
correct value when today's entry exists.

### Tier 2a — Server integration

`BodyWeightIntegrationTest` (slot 5) — add upsert test: POST same date twice with
different kg values, both return 2xx, GET returns the second (updated) value.

### Tier 2b — App component

- `BodyWeightDaoTest` — add test for `getForDate`: returns correct entity for today,
  null for a different date
- `BodyWeightSyncServiceTest` *(new, mirrors `LogEntrySyncServiceTest`)* —
  Robolectric + in-memory Room + `MockEngine`:
  - `PENDING_CREATE` entries are uploaded via `POST /body/weight`
  - Entry marked `SYNCED` on success
  - Entry left `PENDING_CREATE` on network failure
- `DashboardScreenTest` — add:
  - Chip shows "-- kg" when `weightKgToday` is null
  - Chip shows "82.5 kg" when `weightKgToday = 82.5`
  - Tapping chip opens dialog
  - Valid weight + Save calls `logWeight`
  - Out-of-range and >1 d.p. values keep Save disabled

### Tier 3 — API test

`BodyWeightApiTest` — add upsert case: POST same date twice with different kg values;
assert GET returns the second value.

### Tier 4 — E2E

No change. Weight logging is fully covered at lower tiers.
