# CLAUDE.md

Health is a personal health-tracking app. Single user (the owner). Goal: get healthier,
and build AI skills/knowledge along the way. Friction is the enemy — if logging is too
hard, it won't get used.

## Engineering rules

- **IDs are always UUIDs** — no auto-incrementing integers anywhere (database columns,
  Kotlin data classes, DTOs, Room entities). Postgres: `UUID DEFAULT gen_random_uuid()`.
  Kotlin: `java.util.UUID`. Never `Int`, `Long`, or `BIGSERIAL` for an identifier.

## Goals & constraints

- **Scope:** personal use, one user, but built properly (clean, maintainable, a real
  project — not throwaway).
- **What it does:** combine *calories-out* (from a Polar watch) and *calories-in*
  (food logging) into insights about whether the user is on track to lose weight.
- **Platform:** Android only.
- **Homescreen widget** is a priority: one glance shows whether things are on
  track, plus one insight/tip. The full app shows history and detail.
- **Offline:** basic functionality must work without internet. UI performance matters
  a lot.
- **Data residency:** keep data out of big tech and preferably out of the US.

## Tech stack

- **App:** Kotlin + Jetpack Compose (native — best UI performance, and Android-only
  removes any reason for Flutter).
- **Widget:** Jetpack Glance (the Compose-based widget layer). Reads straight from
  local DB so it's instant and works offline.
- **Local storage:** Room (SQLite) on the phone. Offline-first: the app always writes
  locally first, then syncs to the server when there's a network. The widget reads
  only from Room.
- **Server (system of record):** small VPS in the EU.
- **Backend API:** Ktor (Kotlin) so the whole project is one language and data models
  can be shared between app and server.
- **Database:** PostgreSQL on the VPS.
- **Food/barcode data:** Open Food Facts (French non-profit, EU). Query per barcode
  and cache locally what's actually eaten. No need to self-host the full dataset
  unless this ever scales up.

### Why "out" is easy and "in" is hard

- *calories-out* is roughly one number per day (plus optional per-workout). Flat,
  narrow schema.
- *calories-in* is nested: meal → multiple items → each item with a quantity and
  per-100g nutrition → totals. This is the heavy part of the project.

### How friction stays low

Breakfast and lunch are fairly fixed, so:

- "Log my usual breakfast" / "Log my usual lunch" become **one-tap buttons** that
  populate a log entry from a saved meal template.
- Only dinner needs real per-item logging (Open Food Facts barcode + search).
- Net effect: a custom app tuned to this eating pattern can be *lower* friction than a
  generic app like MyFitnessPal.

## Monorepo structure

One repo, multiple **Gradle modules** (not multiple repos). The point of Kotlin
everywhere is sharing data models between app and server.

```
health/
├── settings.gradle.kts          # registers all modules
├── gradle/libs.versions.toml    # one version catalog for all modules
│
├── shared/                      # KMP: API models (DTOs), JVM + Android
│
├── server/                     # Ktor server (JVM)
│   └── .../db/migrations/       # Flyway SQL → Postgres schema lives HERE
│
├── core-data/                   # Room DB + repositories (Android)
├── widget/                      # Glance widget
└── app/                         # Compose UI, navigation, app entry
```

Key points:

- **`shared` = the API contract, not "all shared things".** Only the DTOs that go over
  the wire (`DailyEnergyDto`, `LogEntryDto`, …), as a KMP module compiling to both JVM
  and Android. No UI, no DB code here.
- **There is no shared DB schema.** Two databases with different jobs: Postgres on the
  server (system of record) and Room on the phone (offline cache + widget source).
  They do **not** share a schema.
    - Postgres migrations (Flyway/Liquibase) live in `server`.
    - Room entities + schema export live in `core-data`.
    - The `shared` DTOs are the bridge between them — not a shared schema.
- **`core-data` is split out from `app` because of the widget.** Both the Glance widget
  and the app UI need the same local data (Room + repositories). Putting that in `app`
  would make the widget depend on the whole UI layer. (For a personal project you *may*
  collapse the widget into `app`; given "properly" + "strong widget" the split is
  justified.)
- **`libs.versions.toml`** (version catalog) is the "properly" glue: one place for all
  dependency versions so server / shared / Android modules don't drift (e.g. on the
  kotlinx-serialization version).

### Module dependency direction

Dependencies point **one way, downward, never back**. This prevents cycles.
(A → B means "A depends on B".)

- **`shared` → nothing.** The leaf. Only external libs (kotlinx-serialization). Never
  imports another module. Everyone may point at `shared`; `shared` never points back.
- **`server` → `shared`.** Uses the DTOs for JSON (de)serialization. Touches no
  Android module (can't — different compile target).
- **`core-data` → `shared`.** Maps between DTOs (to/from server) and its own Room
  entities. Imports no `app`, `widget`, or `server`.
- **`widget` → `core-data`, `shared`.** Reads local data via repositories from
  `core-data`. Deliberately does **not** depend on `app`.
- **`app` → `core-data`, `widget`, `shared`.** Top of the stack. May import everything
  below; nothing imports `app`.

Two boundaries this enforces:

1. **The JVM/Android wall.** `server` and the Android modules never import each other;
   their only contact is `shared`. Change a DTO → server and app both see it, but no
   server code leaks into the app or vice versa.
2. **Forced clean mapping.** Because `core-data` may import `shared` but not the other
   way around, you're forced to map Room entities ↔ DTOs explicitly in `core-data`.
   That's where the "snapshot nutrition values" logic belongs — not in the UI, not in
   the DTOs.

Optional stricter variant: have `widget` import only `core-data` (not `shared`), so the
widget talks purely to local data and knows nothing of the network contract. Left as-is
for now — don't over-engineer.

## Calories: important reality check

**Do NOT recompute calories from raw heart rate.** Heart rate alone is only a decent
estimator of energy use *during moderate-to-intense exercise* and poor at rest; the
HR↔kcal relationship is personal and depends on fitness (VO2max). Polar already
computes a daily calorie figure (active + BMR) in Flow using personal fitness data and
its own algorithm — that estimate is better than anything reconstructed from loose HR
samples.

**Decision:** ingest Polar's own daily `calories` value. This also keeps the schema
simple — no need to store a raw HR stream for this purpose.

## Data model

Postgres = source of truth; Room mirrors what's needed locally.

### calories-out (flat)

- `daily_energy(date PK, bmr_kcal, active_kcal, total_kcal, steps, source)`
- `workout(id, date, type, duration, avg_hr, kcal)` — optional

### calories-in (nested)

- `food_item(id, barcode?, name, kcal/protein/carbs/fat per 100g, source)` — the catalog
- `meal_template(id, name)` + `meal_template_item(template_id, food_item_id, grams)`
  — the low-friction engine
- `log_entry(id, datetime, meal_type)` +
  `log_entry_item(log_entry_id, food_item_id, grams, snapshotted nutrition values)`

Two things that make it "properly":

1. **Snapshot nutrition values into `log_entry_item`** at log time. If Open Food Facts
   later edits a product, historical entries stay correct.
2. "Log usual breakfast" = one tap that fills a `log_entry` from a `meal_template`.

### Insights

Plain SQL on Postgres: 7-day rolling average of in-vs-out (the deficit), weekly trend,
etc. Widget shows 1 number + 1 tip; the app shows full history.

---

## Polar AccessLink integration (deep dive)

Polar is a Finnish company → fits the "outside the US" preference. The API is
**read-only** and reflects data **as of the last watch sync** (not real-time).

### Token model — note this

Classic AccessLink access tokens **do not expire** and there is **no refresh token**.
Authorize once, store the token, reuse it indefinitely. (Earlier assumption about a
"refresh token background process" was wrong — it doesn't apply here.) For a single
user this is simpler, not harder.

### One-time setup flow

1. **Register a client** at `admin.polaraccesslink.com` (log in with the Polar Flow
   account). Set the redirect URL (e.g. `https://yourdomain.eu/polar/callback`) and
   enable all three data types: exercise, daily activity, physical info. You receive a
   **client_id** + **client_secret**.
2. **Authorize** by visiting in a browser:
   `https://flow.polar.com/oauth2/authorization?response_type=code&client_id=...`
   Log in to Polar Flow, grant access, get redirected back to the callback with a
   `code` in the query string.
3. **Exchange the code for a token**: server-side POST to
   `https://polarremote.com/v2/oauth2/token`, using `client_id:client_secret` as HTTP
   Basic auth. The response contains the `access_token` and an `x_user_id`.
4. **Register the user once**: POST `/v3/users`. A `409 Conflict` means the user is
   already registered — ignore that error.

Store the token + user_id in Postgres. One-time setup done.

### Pulling data — transaction model (not a plain GET)

1. **Open an activity transaction.** If there's new daily-activity data since last
   time, you get a transaction id with a list of URLs. No new data → empty response.
2. **Fetch each URL** — these hold the day summary (incl. `calories`, steps, active
   time).
3. **Commit the transaction.** This marks the data "processed" so it won't reappear in
   the next transaction. If you *don't* commit, you can fetch it again later — useful
   when processing fails.

### Trigger strategy

- **Cron (start here, simplest for one user):** a job every few hours runs the
  transaction flow. No signature handling, robust; a failed run is just picked up next
  time.
- **Webhook (more elegant, later):** register a webhook subscription so Polar POSTs to
  an endpoint when the watch syncs. Requires a signature secret in the environment to
  verify incoming calls. Nicer, but more moving parts.

### Practical notes

- Data is only as fresh as the **last watch sync** — not real-time.
- **Rate limits:** short-term (15 min) and long-term (24 h). Current usage comes back
  in the HTTP headers of every response; exceeding a limit returns `429`. A single
  user will never hit these.
- Polar publishes a `swagger.yaml` — use it to generate a client SDK / autogenerate
  Kotlin models for Ktor instead of hand-writing them.

---

## Open questions / next steps

- Write the cron variant as concrete Kotlin/Ktor pseudocode (auth callback +
  transaction pull + Postgres storage).
- Nail down the phone↔server sync model (conflict handling, what Room mirrors).
- Decide webhook vs cron for the long term (start: cron).
- (Done: tech stack, schema, Polar flow, monorepo structure + module dependency
  direction.)