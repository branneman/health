# Weight Trend Chart — Design Spec

Story: 20 (Weight trend chart). A 7-day smoothed line on the dashboard, so progress
is visible, not just a weekly verdict word.

Derived from `docs/math-model.md` §3 (weight trend smoothing) and
`docs/ux/3-features/dashboard.md` (trend charts, chart conventions). This spec pins
the decisions those documents left open; the SMA algorithm itself is not redefined
here — it already exists (story 19 (Weekly verdict)) and is reused, not reimplemented.

## Decisions

1. **Client-only computation.** Same as story 19 (Weekly verdict): all inputs already
   live in Room, and the dashboard must render offline. No server endpoint, no DTO, no
   schema change — this story has no server component (an accepted exception to the
   vertical-slice rule, like story 19 before it).
2. **Extract the shared SMA function.** `WeeklyVerdict.kt`'s private `smoothed(...)`
   moves to a new `WeightSmoothing.kt` (internal, `dashboard` package) so the verdict
   and the trend chart use the identical calculation — no risk of the two drifting
   apart. `WeeklyVerdict.kt` calls the extracted function instead of its own copy.
3. **Confidence gate applies globally, not per selected range.** Per math-model §3.2
   (< 3 readings → no line, 3–6 → partial/dashed, ≥ 7 → full), the gate is evaluated
   once over the entire reading history. The range buttons (below) only crop which
   part of the already-computed line is visible — they never change whether the line
   is dashed or solid, or whether it renders at all. Re-evaluating the gate per window
   would let switching from 1M to 1W make the line abruptly lose confidence, which
   reads as a bug, not honesty.
4. **Range buttons: `1W 1M 3M 6M 1Y ALL`, conditionally shown.** `1W`, `1M`, and `ALL`
   always render. `3M` renders only once the reading history spans more than 90 days
   (earliest reading date to today); `6M` only past 180 days; `1Y` only past 365 days.
   "Spans more than N days" uses the earliest reading's date vs. `today`, not the
   count of readings — a user who weighed in twice a year apart still has a
   long-spanning, sparse history that ALL/1Y should show.
5. **Default selected range: `1M`.** Enough history to show real direction without
   clutter. Selecting a range is pure client-side filtering over already-computed
   points — no reload, no recomputation.
6. **Raw dots are cropped to the selected range**, not always shown in full — a `1W`
   view should only show a week's worth of faint dots, not the full history
   compressed into the same pixel width.
7. **Goal line included now.** `UserProfileEntity.goalWeightKg` already exists (set at
   onboarding). A flat, dashed, muted reference line at that value renders alongside
   the trend line whenever a chart renders at all (i.e. even at `PARTIAL` confidence) —
   it doesn't depend on trend confidence, since it's just the user's stated goal, not a
   derived signal.
8. **No drill-down / tap interaction.** The chart is display + range-button selection
   only. Per-day drill-down already exists via story 18 (Past-day view); this story
   does not add a second entry point to it.
9. **Hand-rolled Compose `Canvas`, no charting library.** This is the first chart in
   the app. The shape needed (one smoothed line, a faint dot underlay, one flat
   reference line, honest un-truncated axes) is simple enough to draw directly with
   `Canvas`/`Path`, matching how `WeeklyVerdictCard` was built — plain Compose
   primitives, no new Gradle dependency. Revisit only if a future chart (e.g. story 21
   (Calorie balance bars)) needs enough shared machinery to justify one.

## Calculation

New file `app/src/main/kotlin/org/branneman/health/dashboard/WeightTrend.kt`:

```kotlin
enum class TrendConfidence { NONE, PARTIAL, FULL }

enum class TrendRange(val days: Int?) {
    WEEK(7), MONTH(30), THREE_MONTHS(90), SIX_MONTHS(180), YEAR(365), ALL(null)
}

data class WeightTrendPoint(
    val date: LocalDate,
    val smoothedKg: Double,
    val rawKg: Double,
)

data class WeightTrendData(
    val points: List<WeightTrendPoint>,   // one per reading date, sorted ascending
    val confidence: TrendConfidence,
    val availableRanges: Set<TrendRange>, // always includes WEEK, MONTH, ALL
)

fun computeWeightTrend(readings: List<BodyWeightEntity>, today: LocalDate): WeightTrendData
```

Behaviour:

1. Sort `readings` by date. If fewer than 3 → `WeightTrendData(emptyList(), NONE, setOf(WEEK, MONTH, ALL))`.
2. `confidence` = `PARTIAL` if 3–6 readings total, `FULL` if ≥ 7 — matches math-model §3.2 exactly (same gate as story 19).
3. `points` = one entry per **reading date** (not per calendar day — matches math-model
   §3.1's "regardless of calendar date"), using the extracted `WeightSmoothing` function
   for `smoothedKg` at each date, and that date's own logged value for `rawKg`.
4. `availableRanges`: start with `{WEEK, MONTH, ALL}`; add `THREE_MONTHS` if
   `today - earliestReadingDate > 90` days, `SIX_MONTHS` if `> 180`, `YEAR` if `> 365`.
5. Filtering to a selected range (done in the ViewModel/UI layer, not stored on
   `WeightTrendData`): `points.filter { it.date >= today.minusDays(range.days) }` for a
   bounded range, or all points for `ALL`.

## Data

No new query. Reuses `BodyWeightDao.getAllForUser(userId)` (already added for story 19
(Weekly verdict)) — the same list already fetched in `DashboardViewModel.computeLocalState`
for `computeWeeklyVerdict` is passed to `computeWeightTrend` too. No schema change, no
server change, no DTO change.

`DashboardUiState` gains:

```kotlin
val weightTrend: WeightTrendData? = null
val selectedTrendRange: TrendRange = TrendRange.MONTH
```

Computed in `computeLocalState` alongside the verdict, and recomputed in `logWeight()`
the same way the verdict already is. A new `selectTrendRange(range: TrendRange)`
function on `DashboardViewModel` just updates `selectedTrendRange` in state — no I/O.

## UI

New composable `WeightTrendChart.kt` (`ui` package), placed in `DashboardScreen.kt`
directly after `WeeklyVerdictCard`, under the same "This week" trend section
(dashboard UX doc's ordering: verdict, then trend charts).

- **`TrendConfidence.NONE`:** section header renders, but only the neutral message
  "Log a few more weigh-ins to see your trend." — no canvas, no range buttons.
- **`PARTIAL` / `FULL`:** renders the chart:
  - Bold SMA line — solid at `FULL`, dashed at `PARTIAL`.
  - Faint raw-reading dots underneath, cropped to `selectedTrendRange`.
  - Flat dashed goal line at `goalWeightKg`, muted styling, small "Goal" label —
    spans the visible window, always shown regardless of confidence.
  - Y-axis: 2-4 gridlines at "nice" rounded kg values (standard d3-style tick
    rounding — the data's own min/max is rounded outward to the nearest clean step,
    never inward), each with a small kg label. Added after initial manual
    verification showed a flat trend line floating in empty space with no visual
    reference — matches math-model §3.3 / dashboard UX doc chart conventions (never
    truncate to exaggerate movement; the outward-only rounding preserves that).
  - X-axis: a few date labels (start / mid / end of the visible window).
  - Range button row below the chart: only buttons in `availableRanges` render;
    `selectedTrendRange` highlighted; tapping calls `selectTrendRange(...)` —
    instant, client-side only.
- **State ownership:** `selectedTrendRange` is a "what am I currently looking at"
  preference on an always-visible dashboard screen, not a form — per CLAUDE.md's
  ViewModel lifecycle rules this is pattern 1 (local-ish, blank-slate-not-required)
  territory, but since it's driven by dashboard data already in `DashboardUiState`, it
  naturally lives there rather than in `remember` and needs no `reset()`.

## Testing

Per `docs/testing-manifesto.md`:

**Tier 1 — unit** (new `WeightTrendTest.kt`, pure functions, `TestFactories.aBodyWeightEntry`):

- Confidence boundaries: 2 → `NONE`, 3 → `PARTIAL`, 6 → `PARTIAL`, 7 → `FULL`.
- `WeightSmoothing` extraction: values match what `WeeklyVerdictTest` already expects
  (regression guard that the refactor didn't change behaviour).
- `availableRanges`: history spans of exactly 89/90/91, 179/180/181, 364/365/366 days.
- `points`: one per reading date, not per calendar day; correct `smoothedKg`/`rawKg` pairing.
- Range filtering: points/dots correctly excluded/included at a window's exact boundary date.
- Goal line data is independent of `confidence` (present whenever a profile has a
  `goalWeightKg`, even at `PARTIAL`).

**Tier 2a — server integration:** none. No endpoint, no schema, no Postgres involvement.

**Tier 2b — app component (Robolectric):** no new DAO test — reuses
`BodyWeightDao.getAllForUser`, already covered by `BodyWeightDaoTest`. Extend
`DashboardScreenTest.kt` with cases for: empty state message at `NONE`, range buttons
shown match `availableRanges`, tapping a range button updates the displayed window.
Behaviour, not pixels — same convention as the existing verdict card tests.

**Tiers 3/4 (API, E2E smoke):** none. No server code touched; E2E charter is core
journey only (login → dashboard → log → sign out) — a chart is not core-journey-breaking.

Run before commit: `./gradlew :app:test`.
