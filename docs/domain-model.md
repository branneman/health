# Domain Model

Structural view of the health app domain through a DDD lens. Read alongside
`docs/ubiquitous-language.md` (term definitions) and `CLAUDE.md` (tech stack and
engineering rules).

---

## Bounded Contexts

Six contexts, two of which are core (directly embody the product's value proposition):

| Context                 | Role          | Responsibility                                                  |
|-------------------------|---------------|-----------------------------------------------------------------|
| **Energy Balance**      | Core          | Daily budget computation, calorie-in/out accounting             |
| **Food Logging**        | Core          | Recording what was eaten; meal templates; shortcuts             |
| **Food Catalog**        | Supporting    | Personal food item catalog; OFD product reference (server-only) |
| **Body Metrics**        | Supporting    | Weight entries, user profile, BMR/TDEE estimation               |
| **Verdicts & Insights** | Supporting    | Weekly verdict; insight pattern detection; trend analysis       |
| **Polar Integration**   | Generic / ACL | OAuth flow; daily energy and workout ingestion from Polar       |

---

## Aggregates

An aggregate is a cluster of objects treated as a single unit for data changes. Each
has one **aggregate root** — the only entry point for mutations. Invariants listed are
enforced at the domain level (not just at the DB layer).

### Log Entry

**Root:** `LogEntry`  
**Children:** `LogEntryItem[]`  
**Context:** Food Logging

**Invariants:**

- Immutable once created. To correct a mistake: delete and re-log.
- A log entry is either a *quick-add* (kcal + optional label, no items) or a
  *food-item entry* (one or more `LogEntryItem`s). Never both.
- Each `LogEntryItem` snapshots nutrition values at creation time. These values never
  change after the fact, even if the referenced food item is later updated.

**Lifecycle:** created → (exists forever or) deleted. No update.

---

### Meal Template

**Root:** `MealTemplate`  
**Children:** `MealTemplateItem[]`  
**Context:** Food Logging

**Invariants:**

- Items are always replaced atomically. There is no partial patch — a PUT replaces
  all items in a single transaction.
- A template can be used to log a meal entry at any meal type, regardless of the
  template's name.

---

### User Profile

**Root:** `UserProfile` (one per user)  
**Children:** none  
**Context:** Body Metrics

**Invariants:**

- Exactly one profile per user, created at onboarding.
- `targetDeficit = 0` defines maintenance phase; `targetDeficit > 0` defines loss phase.
- Phase transitions are explicit user actions (never automatic).
- Vacation mode and phase are independent flags — both can be active simultaneously.

---

### Food Item

**Root:** `FoodItem`  
**Children:** none  
**Context:** Food Catalog

**Invariants:**

- Never deleted. Log entry items reference food items by id; deletion would corrupt
  snapshotted log history.
- Nutrition values may be updated (e.g. a user corrects a gram figure), but this does
  not affect already-snapshotted log entry items.

---

## Simpler Entities

These are domain entities without child aggregates or strong mutation invariants:

| Entity                  | Context           | Notes                                                                            |
|-------------------------|-------------------|----------------------------------------------------------------------------------|
| **Weight Entry**        | Body Metrics      | One per date per user. Upsert on same-day re-entry.                              |
| **Daily Energy Record** | Polar Integration | Keyed by `(user_id, date)`. Upserted each Polar sync cycle.                      |
| **Workout**             | Polar Integration | Identified by Polar's `polar_exercise_id`; our UUID PK assigned on first insert. |
| **Shortcut**            | Food Logging      | User-configured one-tap button. Ordered by `sort_order`.                         |

---

## Value Objects

Immutable; compared by value, not identity. Currently modelled as primitive types in
the codebase — see the naming divergences in `docs/ubiquitous-language.md` for the
refactor list.

| Value Object        | Values                                                              | Used in                       |
|---------------------|---------------------------------------------------------------------|-------------------------------|
| `Phase`             | `loss`, `maintenance`                                               | User Profile, Verdict         |
| `MealType`          | `breakfast`, `lunch`, `dinner`, `snack`                             | Log Entry                     |
| `CaloriesOutSource` | `polar_today`, `polar_yesterday`, `estimate`                        | Energy Balance, Today Summary |
| `ActivityLevel`     | `sedentary`, `lightly_active`, `moderately_active`                  | User Profile, BudgetComputer  |
| `Nutrition`         | `(kcalPer100g, proteinPer100g, carbsPer100g, fatPer100g)`           | Food Item, Log Entry Item     |
| `Verdict`           | state (`green`, `amber-red`, `amber-fast`) + message string         | Verdicts & Insights           |
| `EnergyBalance`     | `(caloriesIn, caloriesOut, budgetRemaining, targetDeficit, source)` | Energy Balance                |

---

## Domain Services

Logic that does not naturally belong on a single entity or aggregate:

### `BudgetComputer` *(implemented — `server/budget/BudgetComputer.kt`)*

Resolves the calories-out source (priority: `polar_today` → `polar_yesterday` →
`estimate`) and computes the full `EnergyBalance` for a given day. Operates on a
`UserProfileInput`, a list of `EnergyRow`s from Polar, the latest weight, and a
calories-in total. Pure function — no side effects.

### Weight Trend Analyser *(not yet implemented)*

Computes the 7-day SMA over a series of weight entries. Returns the smoothed weight
series, the 7-day change, and whether minimum data gates are met (≥ 3 entries to show,
≥ 7 for full confidence).

### Verdict Calculator *(not yet implemented)*

Takes the smoothed weekly weight change and the target deficit to produce a `Verdict`.
Also enforces the grace period gate (≥ 14 days of use, ≥ 5 weigh-ins in last 14 days)
and the amber-fast safety ceiling (> 0.5 kg/week loss regardless of target).

### Insight Generator *(not yet implemented)*

Scans rolling 7-day windows of log data to detect patterns: late-night snacking
frequency, drink clusters, logging coverage gaps, deficit too aggressive, and
calorie-vs-weight disagreement. Each insight has its own minimum data gate. Returns
at most 1–2 insights at a time.

---

## Context Map

How the bounded contexts integrate with each other:

```
┌─────────────────────┐        ┌───────────────────────┐
│   Polar Integration │───────▶│   Energy Balance      │
│  (ACL — translates  │        │                       │
│   Polar's model)    │        │  Consumes:            │
└─────────────────────┘        │  · calories-out from  │
                               │    Polar Integration  │
┌─────────────────────┐        │  · calories-in total  │◀──┐
│   Food Logging      │───────▶│    from Food Logging  │   │
│                     │        └───────────────────────┘   │
│  References:        │                 │                  │
│  · Food Catalog     │◀──┐             │                  │
│    (food items)     │   │             ▼                  │
└─────────────────────┘   │  ┌───────────────────────┐     │
                          │  │  Verdicts & Insights  │     │
┌─────────────────────┐   │  │                       │     │
│   Food Catalog      │───┘  │  Reads:               │     │
│                     │      │  · smoothed weight    │     │
│  · food_item        │      │    from Body Metrics  │     │
│    (personal)       │      │  · energy balance     │─────┘
│  · product          │      │    totals             │
│    (OFD, srv-only)  │      └───────────────────────┘
└─────────────────────┘                  ▲
                                         │
                        ┌────────────────┴──────┐
                        │   Body Metrics        │
                        │                       │
                        │  · weight entries     │
                        │  · user profile       │
                        │  · BMR / TDEE         │
                        └───────────────────────┘
```

**Key integration rules:**

- **Energy Balance** is downstream of both Food Logging and Polar Integration. It never
  writes to either.
- **Polar Integration** is an Anti-Corruption Layer. It translates Polar's API shapes
  (`PolarActivity`, `PolarExercise`) into our internal entities (`DailyEnergyRecord`,
  `Workout`) before anything else touches the data.
- **Food Logging** reads food items from the Food Catalog but owns the log entry
  lifecycle. The catalog is a reference context; the logging context is the writer.
- **Verdicts & Insights** is purely read — it aggregates data from Body Metrics and
  Energy Balance but owns no persistent state of its own.
- **No math on the client.** All values in the context map are computed server-side.
  The Android app reads computed results and renders them; it does not re-implement
  budget or verdict logic.
