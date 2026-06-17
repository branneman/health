# Budget Model Swap — Implementation Plan

**Goal:** Replace time-decay formula with simple running balance. Full spec in
`docs/specs/budget-model-swap.md`. Math model in `docs/math-model.md §2`.

Write tests before implementation at each layer (TDD). Mark each task done as you go.

---

## File map

### Modified
- `shared/src/commonMain/kotlin/org/branneman/health/TodaySummaryDto.kt`
- `server/src/main/kotlin/org/branneman/health/budget/BudgetComputer.kt`
- `server/src/main/kotlin/org/branneman/health/Application.kt`
- `server/src/test/kotlin/org/branneman/health/budget/BudgetComputerTest.kt`
- `server/src/test/kotlin/org/branneman/health/DynamicBudgetIntegrationTest.kt`
- `app/src/main/kotlin/org/branneman/health/db/entities/DynamicBudgetParamsEntity.kt`
- `app/src/main/kotlin/org/branneman/health/HealthApplication.kt`
- `app/src/main/kotlin/org/branneman/health/db/HealthDatabase.kt`
- `app/src/main/kotlin/org/branneman/health/dashboard/DashboardViewModel.kt`
- `app/src/test/kotlin/org/branneman/health/dashboard/DashboardLogicTest.kt`
- `app/src/test/kotlin/org/branneman/health/db/dao/DynamicBudgetParamsDaoTest.kt`
- `app/src/test/kotlin/org/branneman/health/ui/DashboardScreenTest.kt`

---

## Task 1 — Server: simplify BudgetComputer + DTO

**Files:** `BudgetComputer.kt`, `TodaySummaryDto.kt`

Write the new tests first, then make them pass by simplifying the production code.

- [ ] In `BudgetComputerTest.kt`:
  - Remove the 9 fraction/postWorkout tests listed in the spec.
  - Rename `no history → expected and fraction both null` to
    `no history → expectedTodaySport and expectedTodayNonSport are null`; update assertions
    to only check `expectedTodaySport` and `expectedTodayNonSport` (no fraction assertions).
  - Add `expectedTodaySport = avg of sport-day calories-out` — 4 sport days with 2400 each,
    assert `expectedTodaySport == 2400`.
  - Add `expectedTodayNonSport = avg of non-sport-day calories-out` — same pattern, non-sport.
  - Add `actualBurnedSoFar is passed through from actualBurnedToday` — pass `actualBurnedToday=1800`,
    assert `result.actualBurnedSoFar == 1800`.
- [ ] Run `BudgetComputerTest` → expect failures (fields still exist).
- [ ] In `TodaySummaryDto.kt`: remove `eatingFractionSport`, `eatingFractionNonSport`,
  `postWorkoutModeSport`, `postWorkoutModeNonSport`, `wakeTime`, `bedtime`.
- [ ] In `BudgetComputer.kt`:
  - Simplify `DynamicBudgetParams`: remove the four dropped fields.
  - Remove `targetDeficit` param from `computeDynamic()`; remove `computeFraction()` call;
    return only `expectedTodaySport`, `expectedTodayNonSport`, `actualBurnedSoFar`.
  - Delete `computeFraction()`.
- [ ] In `Application.kt`: remove the six dropped fields from `TodaySummaryDto(…)` construction;
  also remove `wakeTime`/`bedtime` variables if they are now unused at that call site.
- [ ] Run `BudgetComputerTest` → all pass.
- [ ] Run server tests (`./gradlew :server:test`) → all pass.

---

## Task 2 — Server: update integration test

**File:** `DynamicBudgetIntegrationTest.kt`

- [ ] Remove the Approach 2 and Approach 3 fraction scenarios (and their extra seed rows).
- [ ] Remove the `wakeTime/bedtime in response` scenario (those fields are gone from the DTO).
- [ ] Update `no history` scenario: assert `expectedTodaySport` is absent/null in response and
  that `eatingFractionSport` is NOT present in the JSON (field removed).
- [ ] Add a scenario that asserts `expectedTodaySport` is computed correctly when sport history
  exists (use the existing 15-sport-day seed; assert non-null value).
- [ ] Run `DynamicBudgetIntegrationTest` → all pass.

---

## Task 3 — App: Room entity + migration

**Files:** `DynamicBudgetParamsEntity.kt`, `HealthApplication.kt`, `HealthDatabase.kt`

- [ ] Update `DynamicBudgetParamsDaoTest.kt`: remove `eatingFractionSport/NonSport`,
  `postWorkoutModeSport/NonSport` from the entity construction and assertions. Run →
  expect compile failure.
- [ ] Update `DynamicBudgetParamsEntity.kt`: remove those four fields.
- [ ] Add `MIGRATION_5_6` to `HealthApplication.kt` (drop + recreate table — see spec).
  Add to `addMigrations(…, MIGRATION_5_6)`.
- [ ] Bump `HealthDatabase` version from 5 to 6; remove the four fields from the `entities`
  list (they're on the entity class, not the database — just ensure the entity compiles).
- [ ] Run `DynamicBudgetParamsDaoTest` → passes.
- [ ] Verify `TestFactories.kt`: there is no `DynamicBudgetParamsEntity` factory, so no change
  needed there. `aUserProfile()` retains `wakeTime`/`bedtime` params — leave them as-is.
- [ ] Run app tests (`./gradlew :app:test`) → all pass (expect compile errors in ViewModel — fix
  those in Task 4).

---

## Task 4 — App: new formula + ViewModel

**File:** `DashboardViewModel.kt`

- [ ] In `DashboardLogicTest.kt`:
  - Remove all 9 `computeDynamicCaloriesLeft` tests.
  - Add 6 `computeCaloriesLeft` tests (exact inputs/expected in spec §DashboardLogicTest).
  - Run → expect compile failure (function doesn't exist yet).
- [ ] In `DashboardViewModel.kt`:
  - Replace `computeDynamicCaloriesLeft()` with `computeCaloriesLeft()` (signature and body
    from spec).
  - Remove `eatingFractionSport`, `eatingFractionNonSport`, `postWorkoutModeSport`,
    `postWorkoutModeNonSport`, `wakeTime`, `bedtime` from `DashboardUiState`.
  - Delete `tickBudget()`. Remove its `viewModelScope.launch` from `init {}`.
  - Rewrite `refreshCaloriesLeft()` (simplified version from spec).
  - Update `load()`: remove the six dropped fields from the entity upsert and `_uiState.update`.
  - Update `computeLocalState()`: remove the six dropped fields from the returned state.
- [ ] Run `DashboardLogicTest` → all pass.

---

## Task 5 — App: fix DashboardScreenTest compile errors

**File:** `DashboardScreenTest.kt`

- [ ] Remove `wakeTime`, `bedtime`, `eatingFraction*`, `postWorkoutMode*` from any
  `DashboardUiState(…)` constructor calls in the test file.
- [ ] Run `DashboardScreenTest` → all pass.

---

## Task 6 — Full test run + verify

- [ ] `./gradlew :shared:build` → passes (DTO compile).
- [ ] `./gradlew :server:test` → passes.
- [ ] `./gradlew :app:test` → passes.
- [ ] Install app on device / emulator. Open dashboard. Confirm `calories_left` shows the
  correct running balance (not 0 at end of day when under budget).

---

## Out of scope

- Calibration of `expected_today` vs Polar's absolute bias — deferred to after 30 days of data
  (tracked in `docs/math-model.md §8`).
- Any changes to the Settings schedule section, onboarding, or widget.
