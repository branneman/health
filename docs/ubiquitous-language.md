# Ubiquitous Language

Authoritative glossary for the health app domain. Use these terms consistently across
code, docs, commits, and conversations. When a term here conflicts with a generic word,
prefer this definition in this context.

See `docs/domain-model.md` for how these concepts relate structurally.

---

## Core energy vocabulary

These are the canonical terms for the calorie-balance loop. The UI uses the short forms
(`in`, `out`, `left`); code and APIs use the long forms.

| Term                 | Short form | Definition                                                                                           |
|----------------------|------------|------------------------------------------------------------------------------------------------------|
| **calories in**      | `in`       | Total kcal consumed from food and drink logged today                                                 |
| **calories out**     | `out`      | Total energy expended today (from Polar or estimated)                                                |
| **budget remaining** | `left`     | `out − target deficit − in`. Negative means over budget.                                             |
| **target deficit**   | —          | The user's configured daily calorie shortfall goal (kcal/day). 0 = maintenance.                      |
| **budget**           | —          | The daily calorie allowance: `out − target deficit`. What the user can eat and still hit their goal. |

Never use "burned" or "eaten" — these carry gym-culture connotations. The in/out pair
names the direction of calorie *flow*, not an action the user performed.

---

## Logging concepts

### Log Entry

An immutable record of food or drink consumed at a point in time. Created via one of
three paths: **quick-add**, **from template**, or **build from scratch**. Never edited
in place — to correct a mistake, delete and re-log.

A log entry is either:

- a **quick-add entry** — a kcal estimate with an optional short label. No ingredient
  detail.
- a **food-item entry** — one or more log entry items, each with snapshotted nutrition.

These are mutually exclusive within a single log entry.

### Log Entry Item

A single ingredient within a food-item log entry. Stores the food item reference,
quantity in grams, and a **snapshot** of the nutrition values at the time of logging.
Snapshots are permanent — later changes to the food catalog do not alter logged history.

### Snapshotted nutrition

Nutrition values (kcal, protein, carbs, fat per 100g) copied from the food item into
the log entry item at the moment of logging. Ensures historical accuracy even if catalog
data is later corrected.

### Quick-add

A log entry path where the user records a kcal estimate and an optional free-text label,
without specifying ingredients. First-class path — not a fallback. Used when the calorie
count is known but ingredient detail is not wanted or not available.

### Meal template

A saved, named composition of food items with per-item gram quantities. Used to log a
recurring meal in 2–3 taps. Templates are replaced atomically — there is no partial
patch.

### Meal type

The meal slot a log entry belongs to. Values: `breakfast`, `lunch`, `dinner`, `snack`.
Assigned at log time; the template's name does not determine the meal type.

### Food item

An entry in the user's personal food catalog. Contains name, optional barcode, and
nutrition values per 100g. Created by picking from the OFD product reference, scanning
a barcode, or manual entry. Referenced by meal templates and log entries. Never deleted
(snapshot integrity — removing a food item would corrupt log entry items that reference it).

### Product

An entry in the Open Food Facts reference catalog, mirrored server-side. Large, read-only
from the app's perspective. Users never own product rows. The app queries `/food/search`
and `/food/barcode` server endpoints — it never calls Open Food Facts directly.

Distinct from a **food item**: a product is a reference; a food item is a personal
catalog entry derived from (or independent of) a product.

### Shortcut

A one-tap calorie-logging button configured by the user. Has an emoji icon, a short
label, and a fixed kcal value. Appears on both the log screen and the homescreen widget.
Not restricted to drinks — any frequently-consumed item can be a shortcut.

### Portion

The gram quantity of a food item in a log entry item or meal template item.

---

## Body & profile concepts

### Weight entry

A single body-weight measurement, in kg, recorded on a specific date. Expected to be
taken in the morning after waking (most consistent window). Stored as a raw value;
never shown as a standalone headline — always smoothed for display.

### Smoothed weight

The 7-day simple moving average (SMA) of the most recent weight entries. The primary
signal for trend analysis and verdict calculation. Absorbs daily water-weight noise.
Requires ≥ 3 entries to display; shown as dashed/low-confidence at 3–6 entries.

### BMR (Basal Metabolic Rate)

Energy the body burns at complete rest, in kcal/day. Estimated via the Mifflin-St Jeor
formula using height, weight, age, and sex. Used as the bootstrap calories-out before
Polar is connected. Superseded by Polar's measured total once data is available.

### TDEE (Total Daily Energy Expenditure)

BMR multiplied by an activity level multiplier. The full estimated daily expenditure
used in the bootstrap period. Not computed once Polar provides real data.

### Activity level

A coarse activity multiplier selected at onboarding. Values: `sedentary` (1.20),
`lightly_active` (1.375), `moderately_active` (1.55). Retired once Polar takes over.

### Biometrics

The set of physical measurements stored in the user profile: height (cm), birth year,
biological sex, and current goal weight. Used to compute BMR.

### Phase

The app's operating mode. Values:

- **loss** — target deficit > 0; verdict measures whether weight trend matches target loss rate.
- **maintenance** — target deficit = 0; verdict measures weight stability.

Switching phase is an explicit user action. Never automatic.

### Vacation mode

A pause state where weekly verdicts are suspended and pattern insights skip the paused
period. Logging and Polar sync continue normally during vacation mode. Activated and
deactivated explicitly by the user.

---

## Assessment concepts

### Verdict

The weekly assessment of whether the user is on track. Computed from the smoothed weight
trend over a rolling 7-day window. Three states:

| State          | Condition                                         | Example message                                                 |
|----------------|---------------------------------------------------|-----------------------------------------------------------------|
| **green**      | Losing at ≥ 40% of target pace, and ≤ 0.5 kg/week | "Down 0.3 kg this week — on track."                             |
| **amber-red**  | Losing at < 40% of target pace (flat or gaining)  | "Flat this week — slightly behind."                             |
| **amber-fast** | Losing > 0.5 kg/week regardless of target         | "Dropping quickly — make sure you're eating enough to perform." |

In maintenance phase the verdict measures stability, not loss rate.

Not shown until the grace period has elapsed (≥ 14 days of app use, ≥ 5 weight entries
in the last 14 days).

### Grace period

The first 14 days of app use, during which the verdict is suppressed. Early weight loss
from glycogen/water depletion would otherwise produce false amber-fast verdicts.

### Insight

A plain-language observation about a pattern in the user's logged data. Pull, not push —
shown when the user opens the app, never as an unsolicited notification. At most 1–2
shown at a time. Observational in tone; never prescriptive or moralising.

Current insight types: late-night snacking frequency, drink clusters, logging coverage
gaps, deficit too aggressive, calorie-vs-weight disagreement.

### Calories-out source

Indicates where today's `calories out` figure came from. Three values in priority order:

1. `polar_today` — Polar has synced today's data. Most accurate.
2. `polar_yesterday` — Polar has not yet synced today; yesterday's total is used as proxy.
3. `estimate` — No Polar data available; BMR × activity level multiplier used.

Shown as a qualifier on the budget display when the source is not `polar_today`.

### Logged day

A calendar day with at least one food log entry AND a Polar calories-out value. Used as
the unit of analysis for the calorie-vs-weight insight. Days missing either leg are
excluded from that calculation.

### Sport tonight

A toggle on the dashboard that adds an estimated exercise expenditure to today's
calories out. Set by the user when planning a session that hasn't happened yet. Cleared
each morning. Replaced silently by Polar's actual figure after the session syncs.

---

## Integration concepts

### Daily energy record

A Polar-sourced measurement of total energy expenditure for a single calendar day.
Contains BMR kcal, active kcal, total kcal, and optional step count. Stored with the
date as part of the primary key — one record per user per day. Upserted on each Polar
sync cycle.

### Workout

A Polar-sourced record of a single exercise session. Contains sport type, date, duration,
average heart rate, and kcal. Identified on the Polar side by a `polar_exercise_id`
(a hashed string, not a UUID). Our own UUID primary key is assigned on first insert.

### Polar sync

The hourly server-side process that pulls daily energy records and workouts from the
Polar AccessLink API and upserts them into Postgres. The app never calls Polar directly —
it reads the results via `/out/energy` and `/out/workouts`.

### Polar connection

The OAuth 2.0 authorization flow that links a user's Polar account to their health app
account. Performed once per user. Issues a permanent access token stored server-side
only, encrypted at rest. The app has no access to the token.

---

## Naming divergences (TODO)

Code and API names that diverge from the canonical domain terms above. Each is a
refactor candidate — remove entries as they are fixed.

| Location                                                    | Current name                    | Canonical term                          | Notes                                                                               |
|-------------------------------------------------------------|---------------------------------|-----------------------------------------|-------------------------------------------------------------------------------------|
| `TodaySummaryDto`, API response                             | `budgetRemaining`               | `left` / `budget remaining`             | Rename field                                                                        |
| `LogEntryDto`, `LogEntry` table                             | `quickAddKcal`, `quickAddLabel` | log entry type (quick-add vs food-item) | These two fields model a type distinction; refactor to a sealed/discriminated union |
| `WorkoutDto.type`, `workout.type` column                    | `type`                          | `sport`                                 | Matches Polar's own field name; `type` is ambiguous                                 |
| `Tables.kt` — `FoodItem.dataSource`, `Shortcut` etc.        | `dataSource` (Kotlin field)     | `source`                                | Kotlin field name leaks "dataSource"; column is correctly named `source`            |
| `UserProfileDto.phase`, `UserProfile.phase`                 | `String`                        | `Phase` enum/sealed                     | `"loss"` / `"maintenance"` should be typed                                          |
| `UserProfileDto.activityLevel`, `UserProfile.activityLevel` | `String`                        | `ActivityLevel` enum/sealed             | `"sedentary"` / `"lightly_active"` / `"moderately_active"`                          |
| `UserProfileDto.sex`, `UserProfile.sex`                     | `String`                        | `Sex` enum/sealed                       | `"male"` / `"female"`                                                               |
| `LogEntry.mealType`, `LogEntryDto.mealType`                 | `String`                        | `MealType` enum/sealed                  | `"breakfast"` / `"lunch"` / `"dinner"` / `"snack"`                                  |
| `DailyEnergyDto.source`, `daily_energy.source`              | `"polar"` (implicit)            | `CaloriesOutSource`                     | Source values not yet consistent across DTO and budget result                       |
| `BudgetResult.caloriesOutSource`                            | `String`                        | `CaloriesOutSource` enum/sealed         | Same as above                                                                       |
