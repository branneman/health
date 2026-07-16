# Remove "Set sport tonight"

## Why

"Set sport tonight" (backlog Story 6) let a user pre-emptively flag that they'd
exercise later today, switching their calorie budget to a higher "sport day"
average before the workout actually happened. It isn't being used, and further
research shows that eating more against an *estimated* future burn is an
anti-pattern — it just invites over-eating on the promise of exercise that may
not happen, get logged, or match the estimate. The app already has a better
signal: Polar's actual burn, synced after the fact, which overrides the
estimate today. Removing the toggle removes the pre-emptive guess entirely and
leans on that existing, more honest mechanism.

This is a removal, not a replacement — no new UI or logic is being added.

## Scope

### Client (`app`)

- Delete `SportTonightEntity`, `SportTonightDao`, `SportTonightDaoTest`.
- Remove the `sport_tonight` table from `HealthDatabase`; add `MIGRATION_8_9`
  that drops it (DB version 8 → 9). The original `MIGRATION_1_2` (which
  created the table) is left untouched — Room migrations are append-only.
- `DashboardViewModel`: delete `computeSportEstimate`, `setSportTonight`,
  `clearSportTonight`, the `sportTonight` field on `DashboardUiState`.
  Simplify `refreshCaloriesLeft` to always use the single `expectedToday`
  value (see below) — no more branching on a user-set flag.
- `DashboardScreen`: delete `SportTonightSection`, `SportTonightActive`,
  `SportTonightPicker`, and their call sites.
- `DashboardLogicTest` / `DashboardScreenTest`: remove sport-tonight cases
  (MET math, stale-date-clears behavior, toggle interaction).
- `TestFactories`: remove `aSportTonight`.

### Server + shared

- `BudgetComputer`: collapse `isSportDay` bucketing and
  `computeExpected(history, isSport)` into a single `computeExpected(history)`
  — one 30-day average, no sport/non-sport split.
- `Application.kt` `/summary/today`: remove the `sportDates` /
  workout-based classification that fed the bucketing.
- `TodaySummaryDto`: replace `expectedTodaySport` + `expectedTodayNonSport`
  with one `expectedToday` field.
- `DynamicBudgetParamsEntity` / DAO (client-side Room cache of the server
  value): collapse to a single `expectedToday` column. Folded into the same
  `MIGRATION_8_9` — recreate the table, seeding `expectedToday` from the old
  `expectedTodayNonSport` column (the new, only default). No Postgres
  migration is needed: the sport/non-sport split was computed in-memory from
  the existing `daily_energy`/`workout` tables, never persisted server-side.

### Docs

- Delete: the "Sport tonight" glossary entry in `docs/ubiquitous-language.md`,
  UX scenario S05 in `docs/ux/2-scenarios.md` (and its reference in S02's
  steps and F03's scenario list), the F03 wireframe block + "Sport-tonight
  picker" section in `docs/ux/4-flows.md`, the toggle's bullet in
  `docs/ux/3-features/dashboard.md`, and §1.4 (MET default tables) in
  `docs/math-model.md`.
- Update `docs/math-model.md` §2.2/2.3/2.4/2.5 to describe a single
  `expectedToday` average in place of the sport/non-sport split, and drop
  "sport toggle change" as a recalculation trigger. Update the §8 open
  question on Polar calibration, which currently cites "relative
  sport/non-sport variation" as still-useful signal — reword since the split
  itself is going away.
- Update `docs/api-design.md`'s `/summary/today` sample response and field
  docs: `expectedTodaySport`/`expectedTodayNonSport` → single `expectedToday`.
- Update `docs/domain-model.md:239` — `expectedTodaySport/NonSport` →
  `expectedToday` in the "no math on the client" exception note.
- Remove the `SportTonightDaoTest` line from `docs/testing-manifesto.md:141`
  (and update "all nine DAO tests" → "all eight DAO tests").
- `docs/feature-backlog.md`: **no change.** Story 6's row ("Dashboard — daily
  zone — estimated calories-out + budget remaining") describes the dashboard
  daily zone as a whole, which mostly remains (budget remaining, calories
  in/out) — it doesn't name the sport-tonight toggle specifically, so nothing
  there is stale.

## Out of scope

- No new automatic sport-day detection (e.g., from a calendar or routine) —
  that would be a new feature, not part of this removal.
- No changes to weekly verdict / insights logic — confirmed it doesn't read
  `isSportDay` or the sport-tonight flag.
- No widget changes — no widget module exists yet.

## Testing

Touches both `app` and `server` — run both tiers before committing:
`./gradlew :app:test` and `./gradlew :server:test`. `:server:apiTest` should
also be run once against a local server since `TodaySummaryDto` changes
shape (field rename/removal), per the feedback-loop hierarchy in CLAUDE.md.
