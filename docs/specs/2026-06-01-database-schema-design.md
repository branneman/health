# Database Schema Design

**Date:** 2026-06-01  
**Scope:** Initial Postgres schema — SQL migration file only. Flyway wiring in Kotlin is a separate
task.

---

## Context

Single-user health-tracking app. Two sides of the energy equation:

- **Calories-out:** pulled automatically from Polar watch via AccessLink API
- **Calories-in:** food logging with one-tap templates for fixed meals (breakfast, lunch) and
  per-item logging for dinner

Postgres is the system of record on the VPS. Room on the phone mirrors what's needed locally. This
spec covers only the Postgres side.

---

## File location

```
server/src/main/resources/db/migration/V1__initial_schema.sql
```

Flyway convention: `V{version}__{description}.sql` (double underscore). Never edit a migration after
it has been applied — add `V2__...` for future changes.

The `init/` directory (mounted as `docker-entrypoint-initdb.d`) is for one-time container bootstrap
only (e.g. Postgres extensions). Application schema lives exclusively in Flyway migrations.

---

## Type decisions

| Use case             | Type           | Reason                                                                                    |
|----------------------|----------------|-------------------------------------------------------------------------------------------|
| Primary keys         | `UUID`         | Safe to generate on the client before insertion; `gen_random_uuid()` built-in since PG 13 |
| Polar calorie totals | `INTEGER`      | Polar delivers whole-number kcal                                                          |
| Per-100g nutrition   | `NUMERIC(7,2)` | Fractional precision matters for food data                                                |
| Timestamps           | `TIMESTAMPTZ`  | Always store with timezone                                                                |

---

## Tables

### calories-out

```sql
CREATE TABLE daily_energy (
    date         DATE    PRIMARY KEY,
    bmr_kcal     INTEGER NOT NULL,
    active_kcal  INTEGER NOT NULL,
    total_kcal   INTEGER NOT NULL,
    steps        INTEGER,
    source       TEXT    NOT NULL DEFAULT 'polar'
);

CREATE TABLE workout (
    id            UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
    date          DATE    NOT NULL,
    type          TEXT    NOT NULL,
    duration_secs INTEGER,
    avg_hr        INTEGER,
    kcal          INTEGER
);
```

`workout.date` has no FK to `daily_energy.date` — Polar may deliver workouts and daily summaries in
separate transactions.

### food catalog

```sql
CREATE TABLE food_item (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    barcode          TEXT         UNIQUE,
    name             TEXT         NOT NULL,
    kcal_per_100g    NUMERIC(7,2) NOT NULL,
    protein_per_100g NUMERIC(7,2),
    carbs_per_100g   NUMERIC(7,2),
    fat_per_100g     NUMERIC(7,2),
    source           TEXT         NOT NULL DEFAULT 'openfoodfacts'
);
```

### meal templates

```sql
CREATE TABLE meal_template (
    id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL UNIQUE
);

CREATE TABLE meal_template_item (
    template_id  UUID         NOT NULL REFERENCES meal_template(id) ON DELETE CASCADE,
    food_item_id UUID         NOT NULL REFERENCES food_item(id),
    grams        NUMERIC(7,1) NOT NULL,
    PRIMARY KEY (template_id, food_item_id)
);
```

"Log usual breakfast" = one tap that creates a `log_entry` from a `meal_template`.

### food log

```sql
CREATE TYPE meal_type AS ENUM ('breakfast', 'lunch', 'dinner', 'snack');

CREATE TABLE log_entry (
    id        UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    logged_at TIMESTAMPTZ NOT NULL,
    meal_type meal_type   NOT NULL
);

CREATE TABLE log_entry_item (
    log_entry_id     UUID         NOT NULL REFERENCES log_entry(id) ON DELETE CASCADE,
    food_item_id     UUID         NOT NULL REFERENCES food_item(id),
    grams            NUMERIC(7,1) NOT NULL,
    -- snapshotted at log time so historical entries survive food_item edits
    kcal_per_100g    NUMERIC(7,2) NOT NULL,
    protein_per_100g NUMERIC(7,2),
    carbs_per_100g   NUMERIC(7,2),
    fat_per_100g     NUMERIC(7,2),
    PRIMARY KEY (log_entry_id, food_item_id)
);
```

Nutrition values are snapshotted into `log_entry_item` at log time. If Open Food Facts later edits a
product, historical entries are unaffected.

### Polar auth

```sql
CREATE TABLE polar_auth (
    user_id      TEXT        PRIMARY KEY,
    access_token TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Classic AccessLink tokens do not expire and there is no refresh token. Authorize once, store, reuse
indefinitely.

---

## Indexes

```sql
CREATE INDEX ON log_entry (logged_at);  -- date-range queries for history/insights
CREATE INDEX ON workout    (date);      -- join to daily_energy by date
```

`food_item.barcode` and `meal_template.name` are already indexed via their UNIQUE constraints.

---

## Out of scope

- Flyway dependency and wiring in Ktor (`Application.kt`) — separate task
- Room schema on Android — separate module (`core-data`)
- DTOs in `shared` module — separate task
