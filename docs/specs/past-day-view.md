# Spec — Story 18 (Past-day view)

## Scope

1. **Date navigation on the Log tab** — full-page horizontal swipe between days; tap date label for picker; no future access.
2. **Full CRUD on past days** — add, edit, delete entries on any past date using the same log flow as today.
3. **Drag-to-reorder entries** — persistent `sortOrder` synced to the server; replaces per-entry time display.
4. **Remove per-entry time from UI** — time is stored in `loggedAt` but never shown; drag handle takes its place.
5. **04:00 day rollover** — client-side only; driven by `effectiveDateFlow` with timezone-change awareness.

---

## Navigation & UX

### HorizontalPager

The Log tab content is wrapped in a `HorizontalPager`:

- Page 0 = today (effective date), page N = N days ago. Max 365 pages.
- Swipe right → earlier day. Swipe left → later day. Hard stop at page 0 (no future).
- The pager is the full-screen swipe surface — not just the date header.

### Date header

A tappable chip/label above the meal buttons:

- Shows **"Today"**, **"Yesterday"**, or **"Mon 23 Jun"** for older dates.
- Tap → `DatePickerDialog` bounded to [365 days ago, today]. Picking a date animates the pager to that page.

### Past-day content

Past days look identical to today: meal buttons, drink shortcuts, Log button, entry list. The full log flow (template, quick-add, build from scratch, AI, single item, scan) works from any past day.

**`loggedAt` for past-day entries** is set to noon on the effective date in the device's current UTC offset: `effectiveDate.atTime(12, 0).atOffset(ZoneOffset.systemDefault().getRules().getOffset(effectiveDate.atTime(12,0)))`. The time is never displayed; noon is a neutral placeholder that keeps the entry inside the correct date-prefix filter.

### Entry list

- No per-entry timestamp shown.
- Entries ordered by `sortOrder ASC`.
- Each row has a drag handle on the right. Long-press → drag to reorder.
- New entries appended with `sortOrder = currentMax + 1` for that day.
- Tap entry → existing edit/delete dialog (quick-add entries editable; food-item entries delete-only).
- Footer shows **"X kcal logged"** (not "today").

---

## Effective date & 04:00 rollover

### Pure function

```
fun effectiveDate(now: LocalDateTime = LocalDateTime.now()): LocalDate
```

In `app/util/EffectiveDate.kt`. Returns `now.toLocalDate().minusDays(1)` if `now.toLocalTime() < 04:00`, else `now.toLocalDate()`. Used for one-shot date lookups (DashboardViewModel, loggedAt stamping).

### Reactive flow

```
fun effectiveDateFlow(context: Context): Flow<LocalDate>
```

Same file. Emits the current effective date immediately, then re-emits on either:

- A `callbackFlow` `BroadcastReceiver` for `Intent.ACTION_TIMEZONE_CHANGED` (fires within ~1 second of system timezone change — no app restart needed).
- A coroutine `delay` until the next 04:00 in the current local timezone.

**Replaces `dateFlow()`** in `LogViewModel`. The existing `dateFlow()` function is deleted.

`DashboardViewModel` uses `effectiveDate()` (pure function, not the flow) at load time — same behaviour as its current `LocalDate.now()` calls, just correct for the 04:00 boundary.

Sync services (`WorkoutSyncService`, `DailyEnergySyncService`, `LoginSyncService`) keep `LocalDate.now()` — their "last N days" windows are unaffected by a 4-hour offset.

### Timezone travel

`loggedAt` is stored as `OffsetDateTime` including the UTC offset at time of logging. Entries logged in one timezone may appear under a shifted calendar date when viewed from another timezone. This is the same accepted limitation as Polar and Fitbit. No per-user timezone setting is added.

---

## Data model

### `LogEntryEntity` (Room)

Add field: `val sortOrder: Int = 0`

New Room migration: add column `sort_order INTEGER NOT NULL DEFAULT 0`. Existing rows get 0; among them, display order falls back to `loggedAt` until first explicit reorder.

### `LogEntryDto` (shared)

Add field: `val sortOrder: Int`

### Server — Flyway `V11__log_entry_sort_order.sql`

```sql
ALTER TABLE log_entry ADD COLUMN sort_order INT NOT NULL DEFAULT 0;
```

### `LogEntryDao`

New queries:

- `observeForDate(userId: String, datePrefix: String): Flow<List<LogEntryWithKcal>>` — entries for one day, `ORDER BY sort_order ASC`. Replaces the `observeAllWithKcal()` + in-memory filter currently in `LogViewModel`.
- `updateSortOrders(updates: List<Pair<String, Int>>)`: batch-updates `sort_order` and sets `syncStatus = PENDING_UPDATE` for each entry ID.

---

## Sync

No new sync paths. `sortOrder` uses the existing `PENDING_UPDATE` mechanism:

- Drag-reorder → `updateSortOrders()` → `PENDING_UPDATE` → existing `LogEntrySyncWorker` syncs on next run.
- Add entry to past day → `PENDING_CREATE` path (unchanged).
- Delete from past day → `PENDING_DELETE` path (unchanged).

Login sync downloads entries with `sort_order` populated; `upsertAll` stores them; `observeForDate` orders by `sort_order` automatically.

---

## Out of scope

- Server-side 04:00 rollover (budget calculation dates are passed by the client; server is unaware of the boundary).
- Manual timezone setting.
- History beyond 365 days.
- Editing `loggedAt` of an existing entry (delete and re-add on the correct day).
