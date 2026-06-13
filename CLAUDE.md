# CLAUDE.md

Health is a personal health-tracking app. Goal: get healthier,
and build AI skills/knowledge along the way. Friction is the enemy — if logging is too
hard, it won't get used.

## Development approach

This project follows **continuous delivery with XP-style vertical slices**. The
implication for every implementation session:

- **`main` is always deployable.** Never commit broken code. If a story isn't
  finished, leave the previous working state on `main` and work in a branch.
  A half-built feature must not break what was working before.
- **One story at a time, thin vertical slices.** Each story in the backlog
  (`docs/specs/feature-backlog.md`) delivers end-to-end value: server
  endpoint + local DB / Room + app UI together. Don't build server-only or
  UI-only layers speculatively. Ship the slice, then start the next one.
- **No work beyond the current story.** Don't add infrastructure, abstractions, or
  schema fields that aren't needed until a later story. YAGNI is strict here.
- **The user installs and uses the app after every story.** Design each slice so
  it's genuinely usable in isolation — graceful degradation beats "coming soon"
  placeholders.
- **Consult the backlog before starting.** Pick the next unstarted story in order.
  If you want to reorder, discuss it first rather than skipping stories silently.

## Engineering rules

- Never use `superpowers` in a folder name for docs, specs, designs, implementation plans, etc.
- **Spec and plan filenames do not include a date prefix.** Save them as `docs/specs/<topic>.md` and
  `docs/plans/<topic>.md`, not `docs/specs/YYYY-MM-DD-<topic>.md`. The superpowers skill suggests
  dated filenames — ignore that convention here.
- **Conventional commits:** use the format `type(scope): message`. Valid types:
  `feat`, `fix`, `docs`, `test`, `refactor`, `perf`, `chore`, `ci`, `security`.
  Always include a scope when it makes sense: use `app`, `server`, or `shared`
  for changes confined to one Gradle module; use a topic scope (e.g. `auth`,
  `onboarding`) when a change spans multiple modules but has a clear shared
  theme. Omit the scope only when no single module or topic fits (e.g. root
  build config changes).
- **Run `git` directly, never `git -C <path>`.** The working directory is always the repo root,
  so `git -C` is never needed and triggers unnecessary permission prompts.
- **IDs are always UUIDs** — no auto-incrementing integers anywhere (database columns,
  Kotlin data classes, DTOs, Room entities). Postgres: `UUID DEFAULT gen_random_uuid()`.
  Kotlin: `java.util.UUID`. Never `Int`, `Long`, or `BIGSERIAL` for an identifier.
  **Exception:** `BodyWeightEntity` uses `id = date` (a `String` date like `"2026-06-12"`)
  as its Room primary key. This is intentional: it enables `@Upsert` to overwrite the
  same-day entry rather than creating a duplicate, enforcing the one-entry-per-day
  invariant at the database level. Postgres `body_weight` has a `UNIQUE(user_id, date)`
  constraint instead of relying on a UUID PK for the same reason.

## Documentation index

Load these on demand when the topic comes up — don't load all upfront:

| When you need…                                                             | Read…                             |
|----------------------------------------------------------------------------|-----------------------------------|
| What a domain term means, or whether a name is canonical                   | `docs/ubiquitous-language.md`     |
| How the domain is structured (aggregates, bounded contexts, value objects) | `docs/domain-model.md`            |
| Which story to work on next, or the scope of a feature                     | `docs/specs/feature-backlog.md`   |
| API endpoint shapes or DTO fields                                          | `docs/specs/api-design.md`        |
| How the daily budget, verdict, or insights are calculated                  | `docs/specs/math-model.md`        |
| Auth, rate limiting, or Polar token security rules                         | `docs/specs/security.md`          |
| Polar OAuth flow, cron sync, or AccessLink API details                     | `docs/specs/polar-sync.md`        |
| Test pyramid, tier definitions, or UUID slot registry                      | `docs/specs/testing-manifesto.md` |
| Why a UX decision was made the way it was                                  | `docs/ux/1-principles.md`         |
| What a specific user flow looks like end-to-end                            | `docs/ux/2-scenarios.md`          |
| What a specific screen should contain                                      | `docs/ux/3-features/`             |

## Goals & constraints

- **Scope:** personal use (the owner plus invited users — family, friends), built
  properly (clean, maintainable, a real project — not throwaway).
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
- **Food/barcode data:** Open Food Facts (French non-profit, EU). A weekly export
  of the NL subset (EU as fallback) is imported into a `product` table on the
  server and indexed for full-text search. The app never calls OFD directly —
  it queries the server's `/food/search` and `/food/barcode` endpoints instead.
  This avoids rate-limit exposure and gives fast autocomplete via Postgres.

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
├── server/                      # Ktor server (JVM)
│   └── .../db/migration/        # Flyway SQL → Postgres schema lives HERE
│
├── local-db-seed/               # Local dev only — SQL seed data (never production)
│
└── app/                         # Compose UI, Room DB, sync, widget, app entry
```

Key points:

- **`shared` = the API contract, not "all shared things".** Only the DTOs that go over
  the wire (`DailyEnergyDto`, `LogEntryDto`, …), as a KMP module compiling to both JVM
  and Android. No UI, no DB code here.
- **There is no shared DB schema.** Two databases with different jobs: Postgres on the
  server (system of record) and Room on the phone (offline cache).
  They do **not** share a schema.
    - Postgres migrations (Flyway) live in `server/src/main/resources/db/migration/`.
      Naming convention: `V{n}__{description}.sql` (double underscore). Never edit a
      migration after it has been applied — add a new `V{n+1}__` file instead.
    - Room entities + DAOs live in `app`.
    - The `shared` DTOs are the bridge between them — not a shared schema.
- **`local-db-seed/` is local dev only.** Contains SQL seed data to populate the DB
  for development. Never run in production. Load manually after Flyway has created the
  schema: `psql $DATABASE_URL < local-db-seed/seed_data.sql`. Do not use
  `docker-entrypoint-initdb.d` for application schema — that is Flyway's job.
- **Everything Android lives in `app`.** Room entities, DAOs, sync workers, the Glance
  widget, and the Compose UI are all in `app`. If the widget grows complex enough to
  justify extraction, split it out then — not speculatively.
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
- **`app` → `shared`.** Top of the stack. Imports `shared` for DTOs; nothing imports
  `app`. Maps Room entities ↔ DTOs explicitly inside `app`.

One boundary this enforces:

**The JVM/Android wall.** `server` and `app` never import each other; their only contact
is `shared`. Change a DTO → server and app both see it, but no server code leaks into
the app or vice versa.

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

Two food tables with distinct jobs — do not conflate them:

- `product(id, barcode?, name, kcal/protein/carbs/fat per 100g, source, …)` —
  the OFD mirror. Large, server-only. Populated by the weekly NL+EU import.
  Users never own rows here; it's a reference catalog to search against.
- `food_item(id, barcode?, name, kcal/protein/carbs/fat per 100g, source)` —
  the user's personal catalog. Items actually eaten: copied from `product` on
  first use, or created manually. Small, synced to the device.
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
Authorize once, store the token, reuse it indefinitely. For a single user this is
simpler, not harder.

### One-time setup flow

1. **Register a client** at `admin.polaraccesslink.com` (log in with the Polar Flow
   account). Set the redirect URL (e.g. `https://yourdomain.eu/polar/callback`) and
   enable the data types needed: exercise, daily activity. You receive a
   **client_id** + **client_secret**.
2. The in-app "Connect Polar" flow (story 11) handles authorization from there —
   the app opens the Polar authorization URL in a browser, the user grants access,
   Polar redirects to the server callback, and the server exchanges the code for a token.

Store the token + Polar user id in Postgres (`polar_auth` table). One-time setup done.

### API base URLs (verified)

- **AccessLink API:** `https://www.polaraccesslink.com` (all `/v3/` endpoints)
- **OAuth token exchange:** `https://polarremote.com/v2/oauth2/token`
- **OAuth authorisation:** `https://flow.polar.com/oauth2/authorization`

### OAuth token exchange (verified)

`POST https://polarremote.com/v2/oauth2/token`

- Auth: HTTP Basic with `client_id:client_secret` (base64-encoded)
- Headers: `Content-Type: application/x-www-form-urlencoded`,
  `Accept: application/json;charset=UTF-8`
- Body (form-encoded): `grant_type=authorization_code&code=<code>&redirect_uri=<uri>`
- Response:
  `{ "access_token": "…", "token_type": "bearer", "expires_in": 31535999, "x_user_id": 10579 }`
- `x_user_id` is an **integer**, stored as TEXT in `polar_auth.user_id`.
- `expires_in` is present in the response but Polar's docs state tokens do not expire
  unless explicitly revoked — treat the token as permanent.

### User registration (verified)

`POST https://www.polaraccesslink.com/v3/users`

- Auth: Bearer token (the freshly exchanged `access_token`)
- Body: `{ "member-id": "<our health user UUID>" }` — Polar's identifier for us; use
  the health `user_id` UUID as the member-id so Polar links back to the right user.
- 409 Conflict = user already registered with this client → ignore, proceed.
- Response on 200 includes `polar-user-id` (integer) — same value as `x_user_id` from
  token exchange. No need to store separately.

### Pulling data — current REST API (not the deprecated transaction model)

**Do not use the deprecated transaction-based API** (open transaction → fetch URLs →
commit). The current Polar AccessLink API is a straightforward REST approach:

- **Daily activity:**
  `GET https://www.polaraccesslink.com/v3/users/activities?from=YYYY-MM-DD&to=YYYY-MM-DD`
  Scope: `accesslink.read_all`. Returns one record per day (date is the unique key).
  Fields: `start_time` (ISO 8601 datetime — extract date portion), `calories`
  (total = BMR + active), `active_calories` (active portion only), `steps`.
  Default range: last 28 days. Max lookback: 365 days.
  Upsert on `(user_id, date)` — matches the existing Postgres primary key exactly.

- **Exercises:** `GET https://www.polaraccesslink.com/v3/exercises`
  Scope: `accesslink.read_all`. Returns exercises from the last **30 days** only,
  and only those uploaded **after** the user registered with our client.
  Each exercise has a Polar-assigned hashed string `id` (e.g. `"2AC312F"`) — not a UUID.
  Fields: `id`, `start_time`, `sport`, `duration` (ISO 8601 duration), `calories`,
  `heart_rate.average`.
  Upsert on `(user_id, polar_exercise_id)` — requires a `polar_exercise_id TEXT`
  column on the `workout` table (added in story 11 migration). Our own UUID `id`
  remains the primary key per project convention.

### Trigger strategy

- **Cron (hourly, coroutine loop in Ktor):** a coroutine launched at startup runs
  `while (true) { pullPolarData(); delay(1.hour) }`. A failed run is safe — the next
  run fetches the same date window and upserts idempotently.
- **Webhook (future):** register a webhook subscription so Polar POSTs to an endpoint
  when the watch syncs. Requires an HMAC signature secret. Not in scope for story 11.

### Practical notes

- Data is only as fresh as the **last watch sync** — not real-time.
- **Rate limits:** 520 requests/15 min and 5,100 requests/24 h (for 1 user). An
  hourly pull costs ~2 API calls. That is well under 1% of the daily limit.
  The `RateLimit-Usage`, `RateLimit-Limit`, and `RateLimit-Reset` headers are returned
  on every response. A 429 means back off; log it and skip that cycle.
- **Do not use code generation** for the Polar client. The API surface we need is small
  (2 endpoints); a hand-written Ktor `HttpClient` wrapper is simpler and has no
  codegen build dependency.

---

## Open questions / next steps

- Write the cron variant as concrete Kotlin/Ktor pseudocode (auth callback +
  transaction pull + Postgres storage).
- Nail down the phone↔server sync model (conflict handling, what Room mirrors).
- Decide webhook vs cron for the long term (start: cron).
- (Done: tech stack, schema, Polar flow, monorepo structure + module dependency
  direction.)