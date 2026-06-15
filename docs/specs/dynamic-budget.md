# Dynamic Calorie Budget — Testing Guide

The mathematical model and formula are in `docs/math-model.md §2`. This file
contains only the testing specification for the story.

---

## Testing

Testing follows the project's five-tier pyramid (`docs/testing-manifesto.md`).
The dynamic budget adds pure computational logic (ideal for Tier 1) and new server
query behaviour and DTO fields (Tier 2a), plus two new onboarding fields that need a
Tier 2b check.

---

### Tier 1 — Unit tests (written first)

**`BudgetComputerTest`** (extends the existing class or creates a new file alongside it)

Core scenarios for `computeDynamic` and the private helpers:

| Test                               | Verifies                                                                 |
|------------------------------------|--------------------------------------------------------------------------|
| no history → nulls                 | Both expected\_today values and both fractions are null                  |
| < 5 logged sport days → Approach 1 | Fraction = `(expected − D) / expected` (target-derived)                  |
| ≥ 5 logged days → Approach 2       | Fraction = avg\_in / avg\_out over all logged bucket days                |
| ≥ 10 qualifying days → Approach 3  | Fraction filters to qualifying days only                                 |
| qualifying day filter              | `calories_in = expected − D + 100` passes; `+ 101` does not              |
| maintenance (D = 0)                | Qualifying threshold = `expected + 100`; fraction still computed         |
| each bucket upgrades independently | Sport bucket on Approach 3 while non-sport is still on Approach 2        |
| post-workout mode triggers at 90 % | `actual = 0.9 × expected` activates; `actual = 0.89 × expected` does not |
| post-workout stays off when null   | `actualBurnedToday = null` → both post-workout flags false               |

**Test data pattern** — construct `HistoricalDay` lists inline; no fakes needed:

```kotlin
private fun sportDay(out: Int, caloriesIn: Int? = null) =
    HistoricalDay(
        LocalDate.of(2026, 1, 1),
        caloriesOut = out,
        caloriesIn = caloriesIn,
        isSportDay = true
    )
private fun nonSportDay(out: Int, caloriesIn: Int? = null) =
    HistoricalDay(
        LocalDate.of(2026, 1, 1),
        caloriesOut = out,
        caloriesIn = caloriesIn,
        isSportDay = false
    )
```

---

### Tier 2a — Server integration (UUID slot #10)

**`DynamicBudgetIntegrationTest`**

- UUID: `00000000-0000-0000-0000-000000000010`
- Email: `dynamic-budget-test@test.local`

Claims the next free slot in the testing-manifesto registry. Follows the `init` block
pattern (delete by both UUID and email before inserting).

Scenarios (all via `GET /summary/today?date=…` after seeding):

| Scenario                 | Seed                                                     | Expected                                                  |
|--------------------------|----------------------------------------------------------|-----------------------------------------------------------|
| No Polar history         | User profile only                                        | `expectedTodaySport` = null, `eatingFractionSport` = null |
| 5 logged sport days      | 5 `daily_energy` rows (sport) + 5 `log_entry` day totals | Approach 2 fraction returned                              |
| 10 qualifying sport days | 10 sport days where `calories_in ≤ expected − D + 100`   | Approach 3 fraction returned                              |
| wakeTime/bedtime in DTO  | Profile with `wake_time = '06:30'`, `bedtime = '22:30'`  | Returns `"06:30"` and `"22:30"`                           |
| profile PUT round-trip   | PUT `/profile` with new wake/bedtime; GET `/profile`     | Returns same values                                       |

Seed data (inserted in `companion object { init {} }`, cleaned in `@Before`):
30 `daily_energy` rows split evenly — 15 with a matching `workout` row (sport),
15 without (non-sport). 10 sport days have `log_entry` quick-adds that qualify
under the Approach 3 threshold. Used across multiple tests without re-seeding.

---

### Tier 2b — App component

**`UserProfileDaoTest`** (extend existing): verify `wakeTime` and `bedtime` round-trip
through `@Upsert`. One test: insert with defaults `07:00`/`23:00`, read back, assert
both fields match.

**`OnboardingScreenTest`** (extend existing): add a test that navigates to step 4
("Your schedule"), asserts wake time and bedtime labels exist, taps the `+30m` button
on wake time and asserts the displayed value changes from `07:00` to `07:30`.

---

### Test data

**`TestFactories.kt`** — add a `wakeTime`/`bedtime` override to the profile factory:

```kotlin
fun aUserProfile(
    userId: String = uuid(),
    wakeTime: String = "07:00",
    bedtime: String = "23:00",
    // ... other existing fields ...
) = UserProfileEntity(userId = userId, wakeTime = wakeTime, bedtime = bedtime, ...)
```

**Integration test seed** — 30 `daily_energy` rows plus 15 `workout` rows covering
the last 30 calendar days. 10 sport days have a `log_entry` row with `quick_add_kcal`
that qualifies (≤ expected\_sport − D + 100). Inserted once in `init {}`, deleted by
user ID in `@Before` to reset between tests.
