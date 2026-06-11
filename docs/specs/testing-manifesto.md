# Testing Manifesto

## Philosophy

Testing is about confidence, not coverage numbers. The goal is to catch regressions as cheaply as
possible — which means pushing risk mitigation as low in the pyramid as possible. A unit-level test
that catches a bug costs milliseconds; the same bug caught in E2E costs minutes and flakiness.

Two principles govern every test in this codebase:

**Tests must test real behaviour.** A test that defines its own stub routing, or asserts only
`assertTrue(true)`, or only verifies that a class compiles, provides no confidence. It is noise that
takes up CI time and breeds false security. If a test cannot be wrong, it has no value.

**Each tier has a charter.** A test belongs at the lowest tier whose tools can catch the failure.
Don't reach for a higher tier just because it's easier to write. Don't write an E2E test for
something a unit test can cover.

---

## The Pyramid

```
        [E2E smoke]          ← device + real server, core user journey only
        [API tests]          ← real server, no device, contract + deployment
   [server integration]      ← local Postgres (health_test), real routing + SQL
      [app component]        ← Robolectric, Room in-memory, Compose UI
          [unit]             ← pure logic, no I/O, the majority
```

---

## Tier 1 — Unit Tests

**Charter:** pure logic with no I/O. Business rules, state machines, auth token lifecycle, rate
limiting, HTTP client and plugin behaviour, DTO serialization edge cases.

No database. No network. No Android framework. Dependencies that cross a boundary (clock, storage,
HTTP) are replaced with fakes — not mocks. The gold standard in this codebase is `RateLimiterTest`:
injected `Clock.fixed()`, a `FakeLoginAttemptsStore` that is a real in-memory implementation of the
interface. That pattern applies everywhere at this tier.

These run in milliseconds. They are the first thing written when implementing any new logic.

**What currently meets the bar:** `AuthServiceTest`, `RateLimiterTest`, `AuthPluginTest`,
`HealthApiClientTest`, `AuthRepositoryTest`, `OnboardingRepositoryTest`.

---

## Tier 2a — Server Integration Tests

**Charter:** the real Ktor application wired to a real database, exercised over HTTP. Tests call
actual endpoints and assert on actual HTTP responses.

Each test class covers one feature area (auth, profile/shortcuts, sync download, body weight, etc.).
The Ktor `Application.module(ds)` runs against a local Postgres database (`health_test`). Flyway
migrates it on first connection via `TestDatabase`. Tests insert their own seed data, make HTTP
calls via Ktor's `testApplication`, and assert on response status codes and bodies.

**Infrastructure:** `TestDatabase` reads connection details from env vars
(`TEST_DATABASE_URL`, `TEST_POSTGRES_USER`, `TEST_POSTGRES_PASSWORD`) with fallback to
`localhost:5432/health_test`. No Docker container is spun up — the `health_test` database must
exist before running `./gradlew :server:test`. This keeps tests fast and avoids Docker overhead.

**Isolation model:** each test class owns a fixed test user UUID and email that no other class uses.
Mutable data (rows the tests write) is deleted in `@Before` so each test starts clean. Tests do not
use transaction rollback — they explicitly delete their own rows.

**UUID registry** — claim the next free slot when adding a new integration test class. The
`health_test` database persists between runs; a UUID that appears here must never be reused by
another class.

| # | UUID suffix | Test class | Email |
|---|-------------|------------|-------|
| 1 | `...000001` | `AuthIntegrationTest` | `integration@test.local` |
| 2 | `...000002` | `ProfileAndShortcutsIntegrationTest` | `profile-test@test.local` |
| 3 | `...000003` | `SyncDownloadIntegrationTest` | `sync-test@test.local` |
| 4 | `...000004` | `LogEntryIntegrationTest` | `logentry-test@test.local` |
| 5 | `...000005` | `BodyWeightIntegrationTest` | `bodyweight-test@test.local` |
| 6 | `...000006` | `MultiUserIsolationTest` (user A) | `isolation-a@test.local` |
| 7 | `...000007` | `MultiUserIsolationTest` (user B) | `isolation-b@test.local` |
| 8 | `...000008` | `SummaryIntegrationTest` | `summary-test@test.local` |
| 9 | `...000009` | *(free)* | |

**`init` block pattern** — the `health_test` database is persistent, so a UUID from a previous run
may still exist with a stale email, or vice versa. Delete by **both** UUID and email before
inserting to handle either case:

```kotlin
init {
    Database.connect(ds)
    transaction {
        Users.deleteWhere { username eq TEST_EMAIL }   // remove stale row if email matches
        Users.deleteWhere { id eq testUserId }          // remove stale row if UUID matches
        Users.insert {
            it[id]           = testUserId
            it[username]     = TEST_EMAIL
            it[passwordHash] = TEST_HASH
        }
    }
}
```

Deleting only by email is insufficient: if a previous partial run left the UUID with a different
email, the insert will PK-conflict. Deleting only by UUID is also insufficient: if the email exists
on a different UUID, a unique-constraint conflict follows. Both deletes together cover all cases.

This tier verifies: routing correctness, auth middleware wiring, correct HTTP status codes for all
branches, and that the Exposed SQL queries do what the application expects.

**What currently meets the bar:** `AuthIntegrationTest`, `ProfileAndShortcutsIntegrationTest`,
`SyncDownloadIntegrationTest`, `BodyWeightIntegrationTest`, `MultiUserIsolationTest`,
`SummaryIntegrationTest`, `LogEntryIntegrationTest`.

---

## Tier 2b — App Component Tests

**Charter:** Android components in isolation — Room DAOs, sync services, and Compose UI screens —
without a real device.

**Room DAOs:** Robolectric + in-memory Room database. Every DAO has a test class. Each test inserts
known data, calls a DAO method, and asserts on the result. The pattern established in
`BodyWeightDaoTest` (Robolectric, in-memory builder, `@Before`/`@After` setup/teardown) is used
consistently across all DAO tests.

**Sync services:** Robolectric + in-memory Room + `MockEngine` Ktor client. `LoginSyncServiceTest`
verifies that the sync service populates Room correctly from mock API responses and returns the right
boolean (has profile / no profile).

**Compose UI screens:** Robolectric + `ComposeTestRule`. Each screen gets a test that exercises its
primary interactions: form validation, button enabled/disabled state, loading and error states.
Dependencies (ViewModels, repositories) are injected as fakes. These are behaviour tests, not
screenshot tests — they assert on what the user sees and can interact with, not on pixels.

**What currently meets the bar:** all nine DAO tests (`BodyWeightDaoTest`, `DailyEnergyDaoTest`,
`FoodItemDaoTest`, `LogEntryDaoTest`, `MealTemplateDaoTest`, `ShortcutDaoTest`, `UserProfileDaoTest`,
`WorkoutDaoTest`, `SportTonightDaoTest`), `LoginSyncServiceTest`, `LogEntrySyncServiceTest`,
`LoginScreenTest`, `OnboardingScreenTest`, `DashboardScreenTest`, `LogScreenTest`.

**What is still missing:** UI tests for `SettingsScreen` — to be written alongside story 15.

---

## Tier 3 — API Tests

**Charter:** the real production server, called over HTTP from the JVM, using a dedicated test
account. No Android app involved.

Where server integration tests prove the code is correct (in isolation), API tests prove the
deployment is correct. They catch problems that a local test database cannot: a Flyway migration
that succeeded locally but failed on the VPS, a misconfigured environment variable, a reverse proxy
that drops or rewrites auth headers, rate limiting that behaves differently when `X-Forwarded-For`
comes from real Nginx.

The suite lives in `server/src/apiTest/`. It reads connection details from env vars
(`API_TEST_SERVER_URL`, `API_TEST_EMAIL`, `API_TEST_PASSWORD`). It runs on demand — not on every
push — and is a natural gate before deploying changes to the server.

Tests exercise the full authentication cycle (login, token use, refresh, logout), data endpoints,
and multi-user isolation.

The API test account is `test+api@bran.name`. Its initial state is maintained by
`local-db-seed/test-api-account-seed.sql`. Each test that writes data must delete it in teardown.

**What currently meets the bar:** `AuthApiTest`, `SyncDownloadApiTest`, `ProfileApiTest`,
`ShortcutsApiTest`, `BodyWeightApiTest`, `LogEntryApiTest`.

---

## Tier 4 — E2E Smoke Tests

**Charter:** the real Android app on a real device or emulator, calling the real production server,
exercising only the core user journey.

This suite is intentionally small. Its job is to verify that the app and server work together
end-to-end, not to be a comprehensive regression suite. Scope: authenticate → view dashboard → log a
meal → verify it appears → sign out. Edge cases belong in lower tiers.

Tests run as Android instrumented tests (`androidTest` source set) using the Compose UI test API.
The E2E test account is `test+e2e@bran.name`. Its data is pre-seeded via
`local-db-seed/test-e2e-account-seed.sql` with realistic but clearly synthetic data. Any test that
writes to the database must delete its own writes in `@After`.

Credentials are supplied via environment variables or CI secrets. Never committed to the repository.

**What currently meets the bar:** `E2ESmokeTest` — login → dashboard → log a meal → sign out.

---

## Test Data Strategy

Three contexts, three strategies. Never share test data across contexts.

**Unit and app component tests** use inline factory helpers defined in `TestFactories.kt` in the
`app` module. A factory creates a minimal valid instance of any entity or DTO, with sensible defaults
that tests override only for the field under test:

```kotlin
fun aBodyWeightEntry(
    id: String = uuid(),
    userId: String = uuid(),
    date: String = "2026-01-01",
    kg: Double = 80.0,
    syncStatus: SyncStatus = SyncStatus.SYNCED,
) = BodyWeightEntity(id = id, userId = userId, date = date, kg = kg, syncStatus = syncStatus)
```

Tests read cleanly: `aBodyWeightEntry(kg = 95.0)` says exactly what matters and nothing else.

**Server integration tests** insert their own data at suite start (`companion object { init {} }`)
and clean up mutable rows in `@Before`. Each test class owns a fixed UUID and email address that
does not overlap with any other class or with the API/E2E test accounts. The `health_test` database
persists between runs; tests must leave it in a state that does not break subsequent runs.

**API tests and E2E smoke tests** use dedicated production accounts with SQL seed files:

- `local-db-seed/test-api-account-seed.sql` — `test+api@bran.name`
- `local-db-seed/test-e2e-account-seed.sql` — `test+e2e@bran.name`

Both files are checked into the repository. Each documents the fixed UUID of its test user at the
top of the file. Seed data uses clearly synthetic values: labels like "Test Breakfast", weight
values in a plausible but obviously fictional arc, dates well in the past. The seed files are
applied manually to reset an account to a known state after disruptive test runs.

---

## What Needs to Change Now

Ordered by impact:

1. **Write UI tests alongside each new screen** — `SettingsScreen` gets a `*ScreenTest` in the same story that implements it.
