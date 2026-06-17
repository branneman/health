# Budget Model Swap — Spec

**Scope:** Replace the time-decay formula (with eating fractions, per-minute tick, wake/bedtime
clock, and post-workout mode flag) with the simple running balance described in
`docs/math-model.md §2`. The new formula is:

```
calories_out_today = actual_burned_today   if actual_burned_today ≥ 0.9 × expected_today(bucket)
                   = expected_today(bucket)  otherwise

calories_left = calories_out_today − targetDeficit − calories_in_today
```

This doc is deleted once the implementation is complete.

---

## What changes

### shared — `TodaySummaryDto`

Remove four fields that existed only to support eating-fraction and post-workout logic:

| Field removed | Reason |
|---|---|
| `eatingFractionSport: Double?` | formula dropped |
| `eatingFractionNonSport: Double?` | formula dropped |
| `postWorkoutModeSport: Boolean` | client derives this itself |
| `postWorkoutModeNonSport: Boolean` | client derives this itself |
| `wakeTime: String` | no longer drives budget; client reads from `UserProfileDto` |
| `bedtime: String` | same |

Keep: `expectedTodaySport`, `expectedTodayNonSport`, `actualBurnedSoFar`.

---

### server — `BudgetComputer`

**`DynamicBudgetParams`** — remove the four dropped fields, keep the two expected values and
`actualBurnedSoFar`.

**`computeDynamic()`** — remove the `targetDeficit` parameter (no longer needed); remove the
`computeFraction()` call; just compute and return `expectedTodaySport`, `expectedTodayNonSport`,
`actualBurnedSoFar`.

**`computeFraction()`** — delete.

**`Application.kt`** — remove `eatingFractionSport/NonSport`, `postWorkoutModeSport/NonSport`,
`wakeTime`, `bedtime` from the `TodaySummaryDto(…)` construction.

---

### server — `BudgetComputerTest`

Remove 9 fraction/postWorkout tests:
- `< 5 logged sport days → Approach 1 fraction`
- `≥ 5 logged sport days → Approach 2 fraction`
- `≥ 10 qualifying sport days → Approach 3 fraction`
- `qualifying day boundary passes at +100`
- `qualifying day boundary fails at +101`
- `maintenance D=0 qualifying threshold = expected+100`
- `each bucket upgrades independently`
- `post-workout triggers at exactly 90 percent of expected`
- `post-workout does not trigger at 89 percent`
- `post-workout stays off when actualBurnedToday is null`

Keep and update: `no history returns null expected and fraction` → rename to
`no history → expectedTodaySport and expectedTodayNonSport are null`.

Keep: `non-sport expected is independent of sport history`.

Add:
- `expectedTodaySport = avg of sport-day calories-out`
- `expectedTodayNonSport = avg of non-sport-day calories-out`
- `actualBurnedSoFar is passed through from actualBurnedToday`

---

### server — `DynamicBudgetIntegrationTest`

Remove test scenarios and seed data tied to eating-fraction approaches:
- `5 logged sport days → Approach 2 fraction returned`
- `10 qualifying sport days → Approach 3 fraction returned`
- Remove the 10 qualifying `log_entry` rows from seed data (keep the 30 `daily_energy` + 15
  `workout` rows — these still exercise `expectedToday` computation).

Keep:
- `No Polar history → expectedTodaySport null, eatingFractionSport absent from response`
- `wakeTime/bedtime in response` → update: these are no longer in `TodaySummaryDto`, so remove
  this scenario. Assert instead that `expectedTodaySport` is computed correctly.
- `Profile PUT round-trip` → move to `UserProfileIntegrationTest` or leave in place.

---

### app — `DynamicBudgetParamsEntity` + Room migration

Remove `eatingFractionSport`, `eatingFractionNonSport`, `postWorkoutModeSport`,
`postWorkoutModeNonSport` from the entity.

Add `MIGRATION_5_6` to `HealthApplication.kt` — drop and recreate the table without those
columns. Dropping the cached rows is safe; they are a server-sync cache with no user data.

```kotlin
private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `dynamic_budget_params`")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `dynamic_budget_params` (
                `date` TEXT NOT NULL,
                `expectedTodaySport` INTEGER,
                `expectedTodayNonSport` INTEGER,
                PRIMARY KEY(`date`)
            )
        """)
    }
}
```

Bump `HealthDatabase` version from 5 to 6.

---

### app — `DashboardViewModel`

**`computeDynamicCaloriesLeft()`** — replace with a new pure function `computeCaloriesLeft()`:

```kotlin
fun computeCaloriesLeft(
    expectedToday: Int,
    targetDeficit: Int,
    actualBurnedToday: Int?,
    caloriesIn: Int,
): Int {
    val caloriesOut = if (actualBurnedToday != null && actualBurnedToday >= expectedToday * 0.9) {
        actualBurnedToday
    } else {
        expectedToday
    }
    return caloriesOut - targetDeficit - caloriesIn
}
```

**`DashboardUiState`** — remove `eatingFractionSport`, `eatingFractionNonSport`,
`postWorkoutModeSport`, `postWorkoutModeNonSport`, `wakeTime`, `bedtime`.

**`tickBudget()`** — delete. Remove from `init {}`.

**`refreshCaloriesLeft()`** — simplify to:
1. Resolve `expectedToday` for active bucket (sport or non-sport), falling back to `state.caloriesOut`.
2. Call `computeCaloriesLeft(expectedToday, state.targetDeficit, state.actualBurnedSoFar, state.caloriesIn)`.
3. Compute `budgetLabel`:

```kotlin
val usingFallback = (isSportTonight && state.expectedTodaySport == null) ||
                    (!isSportTonight && state.expectedTodayNonSport == null)
budgetLabel = when {
    caloriesLeft < 0         -> "kcal over"
    state.targetDeficit == 0 -> "left (balance)"
    usingFallback            -> "left (estimated)"
    else                     -> "left"
}
```

**`load()`** — remove `eatingFractionSport/NonSport`, `postWorkoutModeSport/NonSport`,
`wakeTime`, `bedtime` from the `DynamicBudgetParamsEntity` upsert and `_uiState.update`.

**`computeLocalState()`** — remove the same six fields from the returned `DashboardUiState`.

---

### app — `DashboardLogicTest`

Remove all 9 `computeDynamicCaloriesLeft` tests (the entire section under that heading).

Add 6 `computeCaloriesLeft` tests:

| Test | Input | Expected |
|---|---|---|
| under budget → positive | expected=2387, D=300, actual=null, in=1773 | 314 |
| on budget → zero | expected=2387, D=300, actual=null, in=2087 | 0 |
| over budget → negative | expected=2387, D=300, actual=null, in=2200 | −113 |
| uses actual when actual ≥ 90% | expected=2400, D=300, actual=2160, in=1500 | 360 |
| uses expected when actual < 90% | expected=2400, D=300, actual=2159, in=1500 | 600 |
| uses expected when actual is null | expected=2400, D=300, actual=null, in=1500 | 600 |

---

### app — `DynamicBudgetParamsDaoTest`

Update the single round-trip test: remove `eatingFractionSport`, `eatingFractionNonSport`,
`postWorkoutModeSport`, `postWorkoutModeNonSport` from the entity construction. Assert only
`expectedTodaySport` and `expectedTodayNonSport`.

---

### app — `DashboardScreenTest`

No structural changes needed. The three label tests already reference only `caloriesLeft` and
`budgetLabel` in `DashboardUiState`. Any constructor calls that pass `wakeTime`, `bedtime`,
`eatingFraction*`, or `postWorkoutMode*` will fail to compile and must be removed — these are
the only callsite edits needed.

---

## What does NOT change

- `UserProfileEntity`, `UserProfileDto` — `wakeTime`/`bedtime` stay (used by other features).
- `SettingsViewModel` and `SettingsScreen` — schedule section is unchanged.
- `OnboardingScreen` — unchanged.
- `computeSportEstimate()`, `isValidWeightInput()` — unchanged.
- `BudgetComputer.compute()` and `resolveCaloriesOut()` — unchanged.
- Room entities other than `DynamicBudgetParamsEntity` — unchanged.
- Flyway migrations — no Postgres schema changes.
- `testing-manifesto.md` UUID registry — slot #10 (`DynamicBudgetIntegrationTest`) is kept,
  just with fewer scenarios.
- `docs/api-design.md` — already updated: three kept DTO fields documented.
- `docs/domain-model.md` — already updated: client-side exception noted.
