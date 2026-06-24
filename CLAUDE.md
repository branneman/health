# CLAUDE.md

Health is a health-tracking app for a small group of invited users. Goal: get healthier,
and build AI skills/knowledge along the way. Friction is the enemy — if logging is too
hard, it won't get used.

## Development approach

This project follows **continuous delivery with XP-style vertical slices**. The
implication for every implementation session:

- **`main` is always deployable.** Never commit broken code. If a story isn't
  finished, leave the previous working state on `main` and work in a branch.
  A half-built feature must not break what was working before.
- **One story at a time, thin vertical slices.** Each story in the backlog
  (`docs/feature-backlog.md`) delivers end-to-end value: server
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
- **Always run tests before committing.** After any code change, run the relevant test tier(s)
  and confirm `BUILD SUCCESSFUL` before running `git commit`. Never commit on a failed or
  unrun test suite. The rule: touch server code → `./gradlew :server:test`; touch app or
  shared code → `./gradlew :app:test`; touch both → run both.
- **Conventional commits:** use the format `type(scope): message`. Valid types:
  `feat`, `fix`, `docs`, `test`, `refactor`, `perf`, `chore`, `ci`, `security`.
  Always include a scope when it makes sense: use `app`, `server`, or `shared`
  for changes confined to one Gradle module; use a topic scope (e.g. `auth`,
  `onboarding`) when a change spans multiple modules but has a clear shared
  theme. Omit the scope only when no single module or topic fits (e.g. root
  build config changes).
- **Run `git` directly, never `git -C <path>`.** The working directory is always the repo root,
  so `git -C` is never needed and triggers unnecessary permission prompts.
- **Merge via rebase + fast-forward only.** Never create merge commits. Before merging a
  feature branch: `git rebase main`, then `git checkout main && git merge --ff-only <branch>`.
- **How to run all test tiers:**
  - **Unit + app component (Robolectric):** `./gradlew :app:test` — no device needed, runs in seconds.
  - **Server unit + integration:** `./gradlew :server:test` — needs local `health_test` Postgres DB.
  - **API tests (live server):** `./gradlew :server:apiTest` (reads `.env`). Against local server:
    `API_TEST_SERVER_URL=http://localhost:8080 ./gradlew :server:apiTest` (start server first with
    `./gradlew :server:run`). **Run before pushing any server change.**
  - **E2E smoke (Android device):** `set -a; source .env; set +a`, then
    `./scripts/create-dev-avd.sh` (first run — creates AVD, boots emulator, seeds test account,
    runs tests, tears down) or `./scripts/create-dev-avd.sh --no-create` (AVD already exists).
    Requires `E2E_PASSWORD` in `.env` and `server.baseUrl` in `local.properties`.
- **Story references in docs:** When citing a backlog story from any markdown file, use
  inline format `N (Short-name)`, e.g. `13 (Meal templates)`. Never a bare number — the
  short name makes stale references immediately obvious.
- **IDs are always UUIDs** — no auto-incrementing integers anywhere (database columns,
  Kotlin data classes, DTOs, Room entities). Postgres: `UUID DEFAULT gen_random_uuid()`.
  Kotlin: `java.util.UUID`. Never `Int`, `Long`, or `BIGSERIAL` for an identifier.
  **Exception:** `BodyWeightEntity` uses `id = date` (a `String` date like `"2026-06-12"`)
  as its Room primary key. This is intentional: it enables `@Upsert` to overwrite the
  same-day entry rather than creating a duplicate, enforcing the one-entry-per-day
  invariant at the database level. Postgres `body_weight` has a `UNIQUE(user_id, date)`
  constraint instead of relying on a UUID PK for the same reason.

## ViewModel state lifecycle — screens must reset on re-entry

`viewModel()` in this app's enum-based navigation (no NavHost) is scoped to the
Activity. The **same ViewModel instance persists** every time a screen re-enters
the composition. Never assume state is clean just because the composable re-appeared.

Two patterns — pick the right one for each screen:

1. **Always-fresh screens** (search, empty form): add `fun reset()` to the ViewModel
   and call it from a `LaunchedEffect(Unit)` inside the composable. Runs once on every
   entry. Example: `FoodSearchScreen` — the query and results must always start empty.

2. **Session-aware screens** (multi-step flow where the user navigates to a sub-screen
   and returns mid-session): hoist the ViewModel one level up to `MainNav`, call
   `vm.reset()` explicitly when starting a **new** session (e.g. on the button tap that
   navigates there), and **not** on mid-session sub-navigation. Pass the instance down
   explicitly so the dependency is visible. Example: `BuildFromScratchScreen` — must
   reset when a new meal is started, but not when returning from food search.

When in doubt, ask: "If the user leaves this screen and comes back later, should they
see a blank slate or pick up where they left off?" Blank slate → reset on entry.
Pick up → session-aware reset at the session boundary.

## Feedback loops

Always optimise for the shortest possible feedback cycle. Before writing code and pushing
to CI, stop and ask: **is there a faster way to learn whether this approach is correct?**
The cost of a slow feedback loop is not just time — it's also noise (broken CI, failed
deploys, restarted servers, muddled context) that obscures the real signal.

**The hierarchy, fastest to slowest:**

1. A throwaway script or REPL against the real external API
2. A unit or integration test (`./gradlew :server:test` / `./gradlew :app:test`)
3. A local server + API test (`./gradlew :server:apiTest` against localhost)
4. Push to CI and wait for the pipeline
5. Deploy to production and test manually in the app

**Default to the fastest loop available, and say which one you are using and why before
proceeding.**

Concrete rules:

- **Integrating an external API?** Before writing any abstraction, write a minimal
  throwaway script (Python, curl, etc.) that calls the real API and prints the raw
  response. Learn from the actual system first; build the abstraction second.
- **Fixing a server bug?** Before pushing to CI, ask: can this be verified with
  `./gradlew :server:test` or `./gradlew :server:apiTest` against a local server? Use
  one of those first.
- **Debugging a production issue?** Before SSHing to read logs, ask: can the same
  scenario be reproduced locally or pinned with a unit test?
- **About to push to CI to "see if it works"?** Stop. That is level 4. A faster loop
  almost certainly exists — find it and use it first.
- **A fix required a full deploy-wait-test cycle?** That is a signal: test coverage is
  insufficient. Add a test that catches the failure locally before declaring done.

## Documentation index

Load these on demand when the topic comes up — don't load all upfront:

| When you need…                                                             | Read…                             |
|----------------------------------------------------------------------------|-----------------------------------|
| What a domain term means, or whether a name is canonical                   | `docs/ubiquitous-language.md`     |
| How the domain is structured (aggregates, bounded contexts, value objects) | `docs/domain-model.md`            |
| Which story to work on next, or the scope of a feature                     | `docs/feature-backlog.md`         |
| API endpoint shapes or DTO fields                                          | `docs/api-design.md`              |
| How the daily budget, verdict, or insights are calculated                  | `docs/math-model.md`              |
| Auth, rate limiting, or Polar token security rules                         | `docs/security.md`                |
| Polar OAuth flow, cron sync, or AccessLink API details                     | `docs/specs/polar-sync.md`        |
| Test pyramid, tier definitions, or UUID slot registry                      | `docs/testing-manifesto.md`       |
| Why a UX decision was made the way it was                                  | `docs/ux/1-principles.md`         |
| What a specific user flow looks like end-to-end                            | `docs/ux/2-scenarios.md`          |
| What a specific screen should contain                                      | `docs/ux/3-features/`             |

## Goals & constraints

- **Scope:** small group of invited users (family, friends), accounts provisioned by
  the admin — built properly (clean, maintainable, a real project — not throwaway).
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
- **Food/barcode data:** Open Food Facts (French non-profit, EU). A one-time full
  import seeds `catalog.product` with the NL subset; a daily delta sync (cron,
  2:30 AM) keeps it current. Non-NL products are cached on barcode miss (EU fallback).
  The app never calls OFD directly — it queries the server's `/food/search` and
  `/food/barcode` endpoints instead.

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
