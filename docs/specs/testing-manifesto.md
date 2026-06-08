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
   [server integration]      ← Testcontainers Postgres, real routing + SQL
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
`HealthApiClientTest`, `AuthRepositoryTest`.

---

## Tier 2a — Server Integration Tests

**Charter:** the real Ktor application wired to a real database, exercised over HTTP. Tests call
actual endpoints and assert on actual database state.

Each test class covers one feature area (auth, profile, shortcuts, sync download, body weight,
etc.). The Ktor `Application.module()` runs against a Testcontainers Postgres instance. Flyway
migrates it before the suite. Tests insert their own seed data, make HTTP calls, and assert on
response bodies and status codes. Isolation is per-test: each test either uses a transaction it
rolls back, or inserts and deletes its own rows.

This is the tier that verifies: routing correctness, user-ownership enforcement in SQL, correct HTTP
status codes for all branches, and auth middleware wiring. It is not the place for business logic
edge cases — those belong in unit tests.

**What needs to change:** `ApplicationTest`, `MultiUserEndpointTest`, and `SyncDownloadTest` are
currently fake — they define their own stub routing instead of booting the real application. They
must be rewritten against the real `Application.module()` with a Testcontainers database. The
existing test names and scenarios are largely worth keeping; the infrastructure underneath them must
be replaced.

---

## Tier 2b — App Component Tests

**Charter:** Android components in isolation — Room DAOs and Compose UI screens — without a real
device.

**Room DAOs:** Robolectric + in-memory Room database. Every DAO gets a test class. Each test inserts
known data, calls a DAO method, and asserts on the result. The pattern established in
`BodyWeightDaoTest` (Robolectric, in-memory builder, `@Before`/`@After` setup/teardown) applies to
all remaining DAOs. `LoginSyncServiceTest` must replace its `assertTrue(true)` placeholder with a
real Robolectric test that exercises the sync service against an in-memory Room database.

**Compose UI screens:** Robolectric + `ComposeTestRule`. Each screen (`LoginScreen`,
`DashboardScreen`, `LogScreen`, `SettingsScreen`) gets a test that exercises its primary
interactions: form validation, button enabled/disabled state, navigation callbacks, loading and
error states. Dependencies (ViewModels, repositories) are injected as fakes. These are behaviour
tests, not screenshot tests — they assert on what the user sees and can interact with, not on
pixels.

---

## Tier 3 — API Tests

**Charter:** the real production server, called over HTTP from the JVM, using a dedicated test
account. No Android app involved.

Where server integration tests prove the code is correct (in isolation), API tests prove the
deployment is correct. They catch problems that Testcontainers cannot: a Flyway migration that
succeeded locally but failed on the VPS, a misconfigured environment variable, a reverse proxy that
drops or rewrites auth headers, rate limiting that behaves differently when `X-Forwarded-For` comes
from real Nginx.

Tests exercise the full authentication cycle (login with real bcrypt verification, token use,
refresh, logout), all data endpoints, and multi-user isolation (authenticate as the API test
account, attempt to read the E2E account's data, assert 403 or 404).

The API test account is `test+api@bran.name`. Its initial state is maintained by
`local-db-seed/test-api-account-seed.sql`. Because this tier exercises mutating operations more
aggressively than E2E, each test that writes data must delete it in teardown. Tests that cannot
guarantee cleanup should prefer read-only assertions or use data that is distinguishable and can be
bulk-deleted by a dedicated teardown step.

The API test suite is implemented in Kotlin using `ktor-client`, so it shares DTOs from the `shared`
module. It can live in `server/src/apiTest/` or a dedicated `api-tests` Gradle module. It runs on
demand — not on every push — and is a natural gate before deploying changes to the server.

---

## Tier 4 — E2E Smoke Tests

**Charter:** the real Android app on a real device or emulator, calling the real production server,
exercising only the core user journey.

This suite is intentionally small. Its job is to verify that the app and server work together
end-to-end, not to be a comprehensive regression suite. Scope: authenticate → view dashboard → log a
meal → verify it appears → sign out. That is the happy path. Edge cases belong in lower tiers.

Tests run as Android instrumented tests (`androidTest` source set) using the Compose UI test API.
The E2E test account is `test+e2e@bran.name`. Its data is pre-seeded via
`local-db-seed/test-e2e-account-seed.sql` with realistic but clearly synthetic data: plausible
weight history, a few food log entries, named shortcuts. Any test that writes to the database must
delete its own writes in `@After`. If a write cannot be cleaned up deterministically, the test
should be redesigned to be read-only.

Credentials (email and password for both test accounts) are supplied via environment variables or CI
secrets. They are never committed to the repository.

The E2E suite is a manual gate — not run on every push. It is the final check before a significant
release.

---

## Test Data Strategy

Three contexts, three strategies. Never share test data across contexts.

**Unit tests** use inline factory helpers defined in a `TestFactories.kt` file per module. A factory
creates a minimal valid instance of any entity or DTO, with sensible defaults that tests override
only for the field under test:

```kotlin
fun aWeightEntry(
    id: UUID = UUID.randomUUID(),
    userId: UUID = UUID.randomUUID(),
    date: String = "2026-01-01",
    kg: Double = 80.0,
) = BodyWeightEntity(id = id.toString(), userId = userId.toString(), date = date, kg = kg)
```

Tests read cleanly: `aWeightEntry(kg = 95.0)` says exactly what matters and nothing else.

**Server integration tests** insert their own data into the Testcontainers database at the start of
each test. The container is discarded after the suite. There is no shared state between tests or
test runs.

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

1. **Rewrite the three fake server tests** (`ApplicationTest`, `MultiUserEndpointTest`,
   `SyncDownloadTest`) using real routing + Testcontainers. This is the highest-value change: it
   gives the first real coverage of the Exposed SQL queries and user-ownership logic that currently
   have zero test coverage.
2. **Replace `LoginSyncServiceTest`'s placeholder** with a real Robolectric test.
3. **Add DAO tests** for the seven Room DAOs that have no coverage: `DailyEnergyDao`, `FoodItemDao`,
   `LogEntryDao`, `MealTemplateDao`, `ShortcutDao`, `UserProfileDao`, `WorkoutDao`.
4. **Add Compose UI tests** for the four screens, starting with `LoginScreen` (most logic) and
   `DashboardScreen` (most visible to the user).
5. **Establish the API test suite** with the `test+api@bran.name` account, starting with the auth
   cycle and the sync-download endpoints.
6. **Write the two seed SQL files** and create the test accounts on the production server.
7. **Write the E2E smoke suite**, starting with the login → dashboard → log flow.
