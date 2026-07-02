# Weekly Verdict — Design Spec

Story: 19 (Weekly verdict). The honest weekly signal: on-track / behind / dropping-fast,
driven by the smoothed weight trend.

Derived from `docs/math-model.md` §3–4 and `docs/ux/3-features/dashboard.md` (weekly zone).
This spec pins the decisions those documents left open; the algorithm itself is not
redefined here.

## Decisions

1. **Client-only computation.** All inputs (weight history, target deficit) already live
   in Room, and the dashboard must render offline. The verdict is a pure Kotlin function
   in the `app` module. No server endpoint, no DTO, no schema change. This story has no
   server component — an accepted exception to the vertical-slice rule (like story 14
   (OFD import pipeline) in reverse). The widget (story 22 (Widget small)) will reuse the
   same function against Room.
2. **Grace-period anchor = earliest weigh-in date.** Math-model §4.4 requires "app in use
   ≥ 14 days" but nothing records an install date. The earliest `body_weight` date is the
   anchor: the grace period exists to absorb the week-1 water-weight drop, which starts
   when weighing starts. Survives reinstalls (weight history restores on login sync).
3. **Weight-only verdict.** No calorie-balance context line in this story. The verdict
   math never depended on calories (math-model §4.5: the scale is ground truth). Weekly
   in/out context arrives with story 21 (Calorie balance bars).
4. **No vacation-mode handling.** The `vacationMode` flag exists on `UserProfileEntity`
   but no UI can set it true yet. The "On pause" verdict state belongs to story 27
   (Vacation mode).
5. **Not tappable.** The card is display-only; drill-down arrives with story 20
   (Weight trend chart).

## Calculation

New file `app/src/main/kotlin/org/branneman/health/dashboard/WeeklyVerdict.kt`:

```kotlin
sealed interface WeeklyVerdict {
    data class Green(val deltaKg: Double) : WeeklyVerdict
    data class AmberBehind(val deltaKg: Double) : WeeklyVerdict
    data class AmberFast(val deltaKg: Double) : WeeklyVerdict
    data object GracePeriod : WeeklyVerdict
    data object NotEnoughData : WeeklyVerdict
}

fun computeWeeklyVerdict(
    readings: List<BodyWeightEntity>,  // any order; function sorts by date
    targetDeficit: Int,
    today: LocalDate,
): WeeklyVerdict
```

Per math-model §4, evaluated in this order:

1. **Grace gate:** earliest reading date > `today − 14` → `GracePeriod`.
2. **Data gate:** fewer than 5 readings with date in `[today − 14, today]` → `NotEnoughData`.
3. `E = targetDeficit × 7 / 7700.0` (kg/week).
4. `smoothed(d)` = mean of the `kg` values of the last ≤ 7 readings with date ≤ `d`.
5. `actual = smoothed(today) − smoothed(today − 7)`.
6. Verdict:
   - `actual < −0.5` → `AmberFast`
   - `actual ≤ −(E × 0.4)` → `Green`  (so at `−0.5` exactly, Green — the ceiling is inclusive)
   - otherwise → `AmberBehind`

The grace gate guarantees at least one reading exists on or before `today − 14`, so
`smoothed(today − 7)` is always computable once gates pass. `today` is `effectiveDate()`,
consistent with the rest of the dashboard. `targetDeficit = 0` degrades honestly:
`E = 0`, so flat-or-down is Green (within the −0.5 ceiling) — story 28 (Maintenance mode)
replaces this later with proper stability states.

## Messages

One line per state, delta rounded to 0.1 kg. The delta phrase is shared across the
three verdict states: `|delta| < 0.05` → "Flat this week", down → "Down X.X kg this
week", up → "Up X.X kg this week" (so `Green` with `targetDeficit = 0` and a flat
trend reads "Flat this week — on track.", never "Down 0.0 kg").

| State           | Message                                                        |
|-----------------|----------------------------------------------------------------|
| `Green`         | "Down 0.3 kg this week — on track."                            |
| `AmberBehind`   | "Flat this week — slightly behind." / "Up 0.2 kg this week — slightly behind." / "Down 0.1 kg this week — slightly behind." |
| `AmberFast`     | "Down 0.8 kg this week — dropping quickly, watch your intake." |
| `GracePeriod`   | "Building baseline — keep logging."                            |
| `NotEnoughData` | "Not enough weigh-ins for a reliable verdict this week."       |

Formatting is a pure function next to the verdict function so it is unit-testable.
Tone per dashboard UX doc: direct, trend-focused, impersonal — never "Great job!",
never a reprimand.

## Data

One new user-scoped query on `BodyWeightDao` returning all readings for a user ordered
by date. No schema change, no server change, no DTO change.

## UI

A verdict card on `DashboardScreen`, directly below the daily zone — the start of the
"weekly zone" (dashboard UX doc §two-speed rule). Colored surface for verdict states
(green for `Green`, amber for both `AmberBehind` and `AmberFast`), neutral surface for
the two gate states. Single line of text as above.

Computed in `DashboardViewModel` from Room during `load()` — pure function over a tiny
table, no caching, recomputed on every dashboard load.

## Testing

Per `docs/testing-manifesto.md`, everything meaningful lands in Tier 1.

**Tier 1 — unit** (pure functions, test data via `TestFactories.aBodyWeightEntry`):

- Threshold boundaries: `actual` exactly `−0.4E` (Green), just above (AmberBehind),
  exactly `−0.5` (Green), just below (AmberFast).
- Gates: earliest weigh-in 13 vs 14 days ago; 4 vs 5 readings in the last 14 days;
  gate ordering — in grace with 2 readings → `GracePeriod`, not `NotEnoughData`.
- SMA: endpoint with < 7 readings (mean of what exists); exactly 7 (8th-oldest excluded);
  readings after an endpoint date excluded from that endpoint's window; sparse history
  where the `today − 7` window reaches past 14 days back.
- Degenerate deficits: `targetDeficit = 0`; large deficit where `0.4E` approaches the
  0.5 ceiling.
- Message formatting: 0.1 kg rounding, flat band, up/down/flat wording per state.

**Tier 2b — app component (Robolectric):**

- `BodyWeightDaoTest`: new query — ordering, other users' rows excluded.
- `DashboardScreenTest`: one case per verdict state via fake state; asserts message text
  shown and neutral vs colored styling. Behaviour, not pixels.

**Tiers 2a/3/4:** nothing — no server code touched; E2E charter is core journey only.

Run before commit: `./gradlew :app:test`.
