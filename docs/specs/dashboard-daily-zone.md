# Spec — Dashboard Daily Zone (Story 6)

**Scope:** The first live dashboard screen. Shows estimated calories out, calories in so far
today, budget remaining, and the sport-tonight toggle. No weight logging (Story 8) and no
quick-add logging (Story 7) — those stories wire into the budget display built here.

---

## What the screen shows

Layout: **Option A** — budget remaining as the dominant number, in/out as a calm secondary row.

```
┌─────────────────────────────────┐
│  Today                          │
│                                 │
│          1 847                  │  ← big: budget remaining (kcal)
│     left (estimated)            │  ← source label (see states below)
│                                 │
│   ──────────────────────────   │
│    0 in        2 147 out (est.) │  ← secondary: calories in · calories out
│                                 │
│  ┌──────────────────────────┐  │
│  │ 🧗 Tonight: Climbing     │  │  ← sport-tonight (when active)
│  │ [Light] [Normal] [Hard]  │  │
│  │ +600 kcal · tap to clear │  │
│  └──────────────────────────┘  │
│  [Set sport tonight ›]          │  ← sport-tonight (when inactive)
│                                 │
│  includes planned climb ~600 k  │  ← footnote when toggle active
└─────────────────────────────────┘
```

**Label vocabulary:** `in · out · left` throughout. Never "eaten/burned" or "eaten/out".
See `docs/ux/1-principles.md` — Tone & language.

### Budget remaining label states

Mirrors `docs/specs/math-model.md` §2.4:

| State | Label under the big number |
|---|---|
| Polar connected (future) | `left` |
| Using yesterday's Polar (future) | `left (based on yesterday)` |
| Bootstrap — no Polar | `left (estimated)` |
| Sport toggle active | `left (includes planned climb ~N kcal)` |
| Over budget | `−X kcal over` |

### Sport-tonight toggle

- **Inactive:** a subtle row "Set sport tonight ›" — tappable, not prominent.
- **Active:** shows activity + intensity chips + estimated kcal + "tap to clear".
- Activity options: **Climbing · Rowing · Other**. Intensity: **Light · Normal · Hard**.
- MET defaults per `docs/specs/math-model.md` §1.4. "Other" uses Light 4.0/60 min,
  Normal 5.5/75 min, Hard 7.0/75 min.
- Budget label updates immediately on selection (no network call needed).
- Cleared automatically when the stored date differs from today.
- State persists across app restarts within the same day (Room).

---

## Architecture (Approach A)

Server computes the base budget. Sport-tonight is local Room state applied client-side on top.

```
Server                    App (Room + ViewModel)
──────                    ──────────────────────
GET /summary/today   →    TodaySummaryEntity (cached)
                          + SportTonightEntity (local only)
                          → DashboardViewModel
                          → DashboardScreen
```

**Offline:** if the server call fails, the ViewModel computes locally from cached Room data
using `computeBmr()` / `activityMultiplier()` (already in `OnboardingRepository.kt`).

---

## Shared module — `TodaySummaryDto`

New DTO in `shared/`:

```kotlin
@Serializable
data class TodaySummaryDto(
    val date: String,                  // YYYY-MM-DD
    val caloriesIn: Int,
    val caloriesOut: Int,
    val budgetRemaining: Int,          // caloriesOut − targetDeficit − caloriesIn
    val targetDeficit: Int,
    val caloriesOutSource: String,     // "polar_today" | "polar_yesterday" | "estimate"
)
```

`caloriesOut` is always non-null — the server always falls back to BMR × multiplier.

---

## Server — `GET /summary/today`

### Computation

**`caloriesOut` source priority** (§2.2 of math-model.md):
1. `daily_energy` row for today → use `total_kcal`, source = `"polar_today"`
2. `daily_energy` row for yesterday → use `total_kcal`, source = `"polar_yesterday"`
3. Fallback: compute BMR × activity multiplier, source = `"estimate"`

**BMR fallback computation** (§1.2, §1.3 of math-model.md):
- Uses `user_profile` (height_cm, birth_year, sex, activity_level) + latest `body_weight` row.
- If no body weight entry exists: use `goal_weight_kg` from `user_profile` as a proxy.
- Age = current year − birth_year (server-side, at request time).

**`caloriesIn`:** sum of today's log entries:
```sql
SELECT COALESCE(SUM(lei.kcal_per_100g * lei.grams / 100), 0)
FROM log_entry le
LEFT JOIN log_entry_item lei ON lei.log_entry_id = le.id
WHERE le.user_id = ? AND DATE(le.logged_at AT TIME ZONE 'UTC') = TODAY
UNION ALL -- quick-add entries
SELECT COALESCE(SUM(le.quick_add_kcal), 0)
FROM log_entry le
WHERE le.user_id = ? AND le.quick_add_kcal IS NOT NULL
  AND DATE(le.logged_at AT TIME ZONE 'UTC') = TODAY
```

(In Exposed: join log_entry + log_entry_item for item-based entries; separate pass for
quick_add_kcal entries; sum both.)

**`budgetRemaining`:** `caloriesOut − targetDeficit − caloriesIn`

### New server unit to extract

The source-priority logic and BMR computation belong in a standalone function/object
(`BudgetComputer` or similar) that takes pure data inputs — no DB access inside it.
This makes it unit-testable without a database.

### Route

```
GET /summary/today?date=YYYY-MM-DD   →   authenticate("api")   →   respond(TodaySummaryDto)
```

`?date=` is required. The client passes its local date so "today" is resolved in the user's
timezone, not UTC. Without this, a late-night entry in UTC+2 would filter as the wrong day.

No new Flyway migration needed. All required data already exists in the schema.

---

## App — Room

### `SportTonightEntity`

```kotlin
@Entity(tableName = "sport_tonight")
data class SportTonightEntity(
    @PrimaryKey val date: String,       // YYYY-MM-DD; only today's row is used
    val activityType: String,           // "climbing" | "rowing" | "other"
    val intensity: String,              // "light" | "normal" | "hard"
    val estimatedKcal: Int,
)
```

DAO: `upsert(entity)`, `getForDate(date): SportTonightEntity?`, `deleteForDate(date)`.

No `SyncStatus` — this entity is local only, never synced to the server.

### Room migration

New Room schema version (increment from current). Add `sport_tonight` table via
`@Database(version = N, autoMigrate = true)` with `@AutoMigration(from = N-1, to = N)`.

---

## App — `DashboardViewModel`

State:

```kotlin
data class DashboardUiState(
    val isLoading: Boolean = true,
    val caloriesIn: Int = 0,
    val caloriesOut: Int = 0,
    val caloriesOutSource: String = "estimate",   // drives label
    val targetDeficit: Int = 0,
    val budgetRemaining: Int = 0,
    val sportTonight: SportTonightEntity? = null, // null = inactive
    val adjustedBudgetRemaining: Int = 0,         // budgetRemaining + sportTonight?.estimatedKcal ?: 0
    val error: String? = null,
)
```

**Load sequence:**
1. Compute immediately from cached Room data (`computeBmr()` + `activityMultiplier()` +
   today's log entries + today's `daily_energy` row if present) — zero-latency first render,
   always works offline.
2. Launch coroutine: call `GET /summary/today?date=<localDate>` → on success, re-emit with
   server-computed values (more accurate, handles Polar data once connected).
3. On network failure: stay on the locally-computed values from step 1; no error shown
   (offline is normal operation).
4. Read today's `SportTonightEntity` from Room; merge into state.

**Sport-tonight actions:**
- `setSportTonight(activityType, intensity)` — computes `estimatedKcal` via MET table,
  upserts `SportTonightEntity`, updates `adjustedBudgetRemaining`.
- `clearSportTonight()` — deletes today's `SportTonightEntity`, reverts budget.

---

## App — `DashboardScreen`

Replace the current stub. Wire `DashboardViewModel` via `viewModel()`.

Key composables:
- `BudgetSection(state)` — big number + source label + in/out secondary row
- `SportTonightSection(state, onSet, onClear)` — inactive row vs. active picker

The screen is the top destination of the app's bottom nav. It replaces the current
"Dashboard" text stub.

---

## API client

Add to `HealthApiClient`:

```kotlin
suspend fun getTodaySummary(token: String): TodaySummaryDto =
    client.get("$baseUrl/summary/today") {
        header(HttpHeaders.Authorization, "Bearer $token")
    }.body()
```

---

## Testing

### Tier 1 — Unit tests (written first)

**Server (`BudgetComputerTest`):**
- Source priority: today's Polar → yesterday's → estimate
- BMR formula for male / female inputs
- Activity multiplier values
- `budgetRemaining` arithmetic including negative (over-budget)
- Edge: no body weight → falls back to goal_weight_kg

**App (`DashboardViewModelTest` or equivalent pure-logic test):**
- Sport-tonight auto-clear: stored date ≠ today → treated as inactive
- `adjustedBudgetRemaining` = base + sport estimate when active; base only when inactive
- MET calculation for each activity/intensity combination

### Tier 2a — Server integration test

`SummaryIntegrationTest`:
- `/summary/today` returns estimate when no Polar data
- `caloriesIn` includes today's log entries (item-based and quick-add)
- `caloriesIn` excludes yesterday's entries
- Returns 401 without token

### Tier 2b — App component test

`DashboardScreenTest` (Robolectric + Compose):
- Shows budget remaining and source label
- Shows "in" and "out" secondary values
- Sport-tonight inactive state: "Set sport tonight" row visible
- Sport-tonight active state: activity + intensity + estimated kcal visible
- Tapping an intensity chip updates the displayed budget
- Loading state renders without crash

---

## Out of scope for Story 6

- Weight logging field (Story 8)
- Quick-add logging (Story 7 — the `caloriesIn` plumbing is here; the log UI is not)
- Polar sync (Story 11 — `caloriesOutSource = "polar_today"` path exists in the code but
  won't fire until Polar is wired)
- Weekly verdict zone (separate section of the dashboard, later story)
- E2E smoke test (first priority after Story 6, as noted in testing manifesto)
