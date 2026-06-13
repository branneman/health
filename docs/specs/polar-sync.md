# Polar Sync — Story 11

**Date:** 2026-06-12
**Scope:** Server-side Polar OAuth flow, hourly cron pull of daily energy and workouts,
app-side download sync, Polar status in Settings, skippable Connect Polar step added
to the end of Onboarding.

---

## Security

The Polar access token is **permanent** — it never expires unless explicitly revoked.
It gives full read access to the user's Polar health data (heart rate, sleep, workouts,
location routes). Leaking it is a serious, unrecoverable breach. Every design decision
below exists to keep the token invisible outside the server process.

### Token encryption — mandatory

The token must be encrypted at the application layer before it reaches Postgres.

- **Algorithm:** AES-256-GCM (authenticated encryption — also detects ciphertext tampering)
- **Key:** `POLAR_TOKEN_ENCRYPTION_KEY` env var — 32 random bytes, base64-encoded,
  stored in the Ansible vault, never committed to git
- **Storage format:** `base64(IV || ciphertext || auth_tag)` stored as `TEXT` in
  `polar_auth.access_token`
- **Implementation:** a small `TokenCipher` utility class with `encrypt(plaintext, key)`
  and `decrypt(ciphertext, key)`. Unit-testable with a fixed key and known test vector.

A Postgres dump without the server's `.env` is useless. The key never enters the DB.

### OAuth CSRF — state parameter

The `state` parameter is the sole CSRF protection on `/polar/callback`. It must be
generated with `java.security.SecureRandom` (256 bits of entropy, not `UUID.randomUUID()`):

```kotlin
val state = ByteArray(32).also { SecureRandom().nextBytes(it) }
    .joinToString("") { "%02x".format(it) }
```

The state is validated and deleted atomically before any other action in the callback
handler. The 400 error response does not distinguish "not found" from "expired" —
both return the same generic error page.

### Log discipline

These rules apply to every class added in this story:

- The Polar `access_token` must never appear at any log level
- `POLAR_CLIENT_SECRET` must never appear at any log level
- Exception messages thrown by `PolarApiClient` must not carry credential values —
  they carry status codes and descriptions only
- `PolarApiClient` must not log the `Authorization` header it sends to Polar's API

### Token confinement

The Polar access token is server-side only. It must not appear in:

- Any API response sent to the Android app
- Any URL (query parameter, path segment, redirect target)
- The callback HTML page (which only contains `branneman-health://polar/connected`)
- Any error response

The app has no path to the token. Every app-facing endpoint (`/polar/connect-url`,
`/polar/status`, `/out/energy`, `/out/workouts`) returns derived data only.

### Token revocation — incident response

If the Polar access token is suspected to have leaked:

1. **Immediate (no API token needed):** log into `flow.polar.com` → account settings →
   connected apps → revoke access for this client. Token is invalidated instantly.
2. **Alternative:** call `DELETE https://www.polaraccesslink.com/v3/users/{x_user_id}`
   with the (potentially compromised) Bearer token → 204, token revoked, user
   de-registered from the client.
3. **Reconnect:** go through the "Connect Polar" flow in the app to issue a new token.
4. **If `POLAR_CLIENT_SECRET` is also compromised:** contact `b2bhelpdesk@polar.com`
   to rotate the client credentials. This invalidates all tokens issued under that
   `client_id`.

Token encryption (AES-256-GCM) means a DB dump alone is not enough — an attacker
also needs `POLAR_TOKEN_ENCRYPTION_KEY` from the server environment. Revocation is
still required on confirmed leak; encryption buys time and limits blast radius.

### Env vars — placement

`POLAR_TOKEN_ENCRYPTION_KEY` and the other Polar secrets are **server-side only**:

- Local development: `.env` in repo root (gitignored), loaded by Docker Compose
- Production: `ansible/vars/vault.yml` (gitignored) → templated into server `.env`
  via `ansible/templates/env.j2`
- **Never** in `local.properties` (that file is Android-build-only — `server.baseUrl`
  only). Gradle does not read `.env`. No Polar secret can reach the APK.

---

## Context

This is the calories-out leg of the system. Until Polar is connected, the dashboard
uses a BMR × activity multiplier estimate. Once connected, real watch-measured data
replaces the estimate and the budget becomes trustworthy.

Cross-cutting docs that apply to this story:
- `docs/specs/api-design.md` — `/out/` endpoints (read-only from app; cron writes server-side)
- `docs/specs/math-model.md` — §2.2 calories_out source priority
- `docs/specs/security.md` — Polar integration rules (OAuth CSRF, server-side token exchange, token exposure)
- `docs/specs/testing-manifesto.md` — test pyramid and UUID registry

---

## Polar API — verified facts

All details below were verified against the live Polar AccessLink API docs during
design. Do not rely on model knowledge; re-read the docs if anything looks wrong.

### Base URLs

| Purpose | URL |
|---|---|
| AccessLink API (v3) | `https://www.polaraccesslink.com` |
| OAuth token exchange | `https://polarremote.com/v2/oauth2/token` |
| OAuth authorisation | `https://flow.polar.com/oauth2/authorization` |

### OAuth token exchange

```
POST https://polarremote.com/v2/oauth2/token
Authorization: Basic base64(client_id:client_secret)
Content-Type: application/x-www-form-urlencoded
Accept: application/json;charset=UTF-8

grant_type=authorization_code&code=<code>&redirect_uri=<uri>
```

Response:
```json
{ "access_token": "…", "token_type": "bearer", "expires_in": 31535999, "x_user_id": 10579 }
```

`x_user_id` is an **integer**. `expires_in` is present in the response but Polar's
documentation states tokens do not expire unless explicitly revoked — treat as permanent.
There is no refresh token.

### User registration

```
POST https://www.polaraccesslink.com/v3/users
Authorization: Bearer <access_token>
Content-Type: application/json

{ "member-id": "<health user UUID>" }
```

409 Conflict = already registered → ignore, proceed. The `member-id` is our identifier
for the user in Polar's system; use the health `user_id` UUID so the link is traceable.

### Daily activity pull

```
GET https://www.polaraccesslink.com/v3/users/activities?from=YYYY-MM-DD&to=YYYY-MM-DD
Authorization: Bearer <access_token>
```

Returns one record per calendar day (date is the unique identifier — no separate ID
field). The `calories` field is the **running cumulative daily total** as of the last
watch sync; it only changes when the watch physically syncs to Polar's servers. Polar
handles all aggregation server-side — we never sum partial records.

Key response fields:

| Polar field | Maps to `daily_energy` column | Notes |
|---|---|---|
| `start_time` (datetime) | `date` | Extract date portion |
| `calories` | `total_kcal` | Total = BMR + active |
| `active_calories` | `active_kcal` | Active portion only |
| `calories - active_calories` | `bmr_kcal` | Derived |
| `steps` | `steps` | Nullable |

Default range: last 28 days. Max lookback: 365 days.
Upsert on `(user_id, date)` — matches the existing Postgres primary key exactly.

### Exercise pull

```
GET https://www.polaraccesslink.com/v3/exercises
Authorization: Bearer <access_token>
```

Returns exercises from the last **30 days only**, and only those uploaded to Flow
**after** the user registered with our OAuth client. Each exercise has a Polar-assigned
hashed string `id` (e.g. `"2AC312F"`) — not a UUID.

Key response fields:

| Polar field | Maps to `workout` column | Notes |
|---|---|---|
| `id` | `polar_exercise_id` | Hashed string, unique per user |
| `start_time` (datetime) | `date` | Extract date portion |
| `sport` | `type` | As-is string |
| `duration` (ISO 8601) | `duration_secs` | Parse PT2H44M → seconds |
| `calories` | `kcal` | Nullable |
| `heart_rate.average` | `avg_hr` | Nullable |

Upsert on `(user_id, polar_exercise_id)` — requires a new column added in V7 migration.

### Rate limits

Short-term: 520 requests / 15 minutes (for 1 user).
Long-term: 5,100 requests / 24 hours (for 1 user).
An hourly cron pull costs 2 API calls. That is under 1% of the daily limit.
Response headers `RateLimit-Usage`, `RateLimit-Limit`, `RateLimit-Reset` are present on
every Polar API response. A 429 means log and skip that cycle.

### Scope required

`accesslink.read_all` — covers both daily activity and exercises.

---

## Database — V7 migration

Two changes:

```sql
-- Short-lived OAuth CSRF state tokens
CREATE TABLE polar_connect_state (
    state      TEXT        PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL
);

-- Polar exercise identifier for idempotent upsert
ALTER TABLE workout ADD COLUMN polar_exercise_id TEXT;
ALTER TABLE workout ADD CONSTRAINT workout_user_polar_id
    UNIQUE (user_id, polar_exercise_id);
```

`polar_connect_state` rows are one-time-use (deleted on callback) and GC'd by the cron
at the top of each cycle. `polar_exercise_id` is nullable — existing manually-entered
workouts have none.

---

## New server endpoints

### `GET /polar/connect-url` — authenticated

Generates a cryptographically random 32-byte hex `state` value. Stores
`(state, user_id, expires_at = now() + 15 minutes)` in `polar_connect_state`.
Returns the Polar authorisation URL for the app to open:

```json
{
  "url": "https://flow.polar.com/oauth2/authorization?response_type=code&client_id=...&redirect_uri=...&scope=accesslink.read_all&state=..."
}
```

The app opens this URL directly in the browser. Our server is not in the navigation
path — the browser goes straight to `flow.polar.com`.

### `GET /polar/callback` — public (called by Polar)

Registered as the OAuth redirect URI in Polar's developer console.
Receives `?code=...&state=...`.

Steps (in order; abort with 400 HTML on any failure):

1. Look up `state` in `polar_connect_state` — 400 if missing or expired
2. Delete that row immediately — one-time use, prevents replay
3. POST to `https://polarremote.com/v2/oauth2/token` (server-side only, credentials
   never exposed) — receive `access_token` + `x_user_id`
4. POST to `https://www.polaraccesslink.com/v3/users` with
   `{ "member-id": "<health_user_id UUID>" }` — ignore 409
5. Upsert into `polar_auth` on `health_user_id`:
   sets `user_id = x_user_id.toString()`, `access_token = <token>`
6. Serve HTML success page (see below)

On any failure after step 1 (token exchange error, DB error): serve an HTML error page.
Do not redirect to the app. The user sees the error in the browser and can retry from
Settings.

**Success HTML response:**

```html
<!DOCTYPE html>
<html>
<head><meta charset="utf-8"><title>Polar Connected</title></head>
<body>
  <p>Polar connected successfully. Returning to the Health app…</p>
  <a href="branneman-health://polar/connected">Open Health App</a>
  <script>
    setTimeout(function () {
      window.location = 'branneman-health://polar/connected';
    }, 500);
  </script>
</body>
</html>
```

The 500 ms delay gives the page time to render before the redirect fires. The `<a>`
link is the fallback for browsers that block auto-redirect to custom schemes.

### `GET /polar/status` — authenticated

Returns whether the session user has a connected Polar account:

```json
{ "connected": true }
```

Checks `polar_auth` for a row where `health_user_id` matches the session user.
No Polar API call involved — purely a DB read.

---

## Server: updated endpoints

### `GET /out/energy` — add `from` query parameter

```
GET /out/energy?from=YYYY-MM-DD
```

Returns `daily_energy` rows where `date >= from` for the session user, ordered by date
descending. `from` is optional; without it, returns all rows (existing behaviour).

### `GET /out/workouts` — add `from` query parameter

```
GET /out/workouts?from=YYYY-MM-DD
```

Same pattern. Returns `workout` rows where `date >= from` for the session user.

---

## Server: `PolarApiClient`

Hand-written Ktor `HttpClient` wrapper. No code generation.
Takes `httpClient`, `clientId`, `clientSecret`, `redirectUri` as constructor parameters
so tests can inject a `MockEngine`.

```kotlin
class PolarApiClient(...) {
    fun buildAuthorizationUrl(state: String): String
    suspend fun exchangeCode(code: String): PolarTokenResponse
    suspend fun registerUser(accessToken: String, memberIdUuid: UUID)
    suspend fun getActivities(accessToken: String, from: LocalDate, to: LocalDate): List<PolarActivity>
    suspend fun getExercises(accessToken: String): List<PolarExercise>
}

data class PolarTokenResponse(val accessToken: String, val xUserId: Long)
data class PolarActivity(val date: LocalDate, val totalKcal: Int, val activeKcal: Int, val steps: Int?)
data class PolarExercise(
    val polarId: String, val date: LocalDate, val sport: String,
    val durationSecs: Int?, val kcal: Int?, val avgHr: Int?,
)
```

`PolarActivity.date` and `PolarExercise.date` are extracted from Polar's `start_time`
ISO 8601 datetime (take the date portion). `PolarExercise.durationSecs` is parsed from
an ISO 8601 duration string (e.g. `PT2H44M` → 9840).

On 429 from any Polar endpoint, `PolarApiClient` throws a typed `PolarRateLimitException`
so the sync service can handle it distinctly from other errors.

---

## Server: `PolarSyncService`

Owns the hourly pull-and-upsert logic. Takes `PolarApiClient` and a `DataSource`.
Has no scheduling knowledge.

```kotlin
class PolarSyncService(
    private val polarClient: PolarApiClient,
    private val dataSource: DataSource,
) {
    suspend fun syncAll() { … }
}
```

`syncAll()` steps:

1. GC expired `polar_connect_state` rows (`DELETE WHERE expires_at < now()`)
2. Load all rows from `polar_auth`
3. For each user, in a per-user try/catch:
   a. `getActivities(from = today - 2 days, to = today)` — 3-day window catches late-arriving data
   b. Upsert each activity into `daily_energy` on `(user_id, date)`
   c. `getExercises()` — returns Polar's full 30-day window
   d. Upsert each exercise into `workout` on `(user_id, polar_exercise_id)`, generating
      a new UUID `id` only on first insert
   e. On `PolarRateLimitException`: log a warning, skip this user for this cycle
   f. On any other exception: log the error, skip this user, do not rethrow

One user's failure does not abort other users.

### Scheduling — in `Application.module()`

```kotlin
launch {
    while (true) {
        delay(1.hours)  // delay-first: avoids racing Postgres on startup
        runCatching { polarSyncService.syncAll() }
            .onFailure { log.error("Polar cron failed", it) }
    }
}
```

The outer `runCatching` is a safety net only — `syncAll()` already isolates per-user
errors. Its purpose is to prevent an unexpected exception from killing the coroutine.

---

## App: Android manifest

Register the custom scheme on `MainActivity`:

```xml
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="branneman-health" android:host="polar" />
</intent-filter>
```

`MainActivity` handles `branneman-health://polar/connected` in `onCreate` and
`onNewIntent`. When received, navigates to the Settings screen. By the time this deep
link arrives, onboarding is complete (the profile was saved in earlier steps).

## App: `HealthApiClient` additions

```kotlin
suspend fun getPolarConnectUrl(token: String): String   // GET /polar/connect-url → url field
suspend fun getPolarStatus(token: String): PolarStatusDto  // GET /polar/status
```

## App: new sync services

**`DailyEnergySyncService`** — calls `GET /out/energy?from=<30 days ago>`, upserts
results into Room via `DailyEnergyDao.upsertAll()`.

**`WorkoutSyncService`** — calls `GET /out/workouts?from=<30 days ago>`, upserts
results into Room via `WorkoutDao.upsertAll()`.

Both follow the pattern of existing sync services: injected `HealthApiClient` and DAO,
testable with `MockEngine`.

`SyncWorker.doWork()` calls both after the existing sync services.

## App: Settings screen — Polar status

Four explicit states, derived from a single `getPolarStatus()` call made on composition:

| State | Shown when | Connect button? |
|---|---|---|
| Loading | Request in flight | No |
| Connected | Server reachable, `polar_auth` row exists | No |
| Not connected | Server reachable, no `polar_auth` row | Yes |
| Unknown | Network error or server unreachable | No |

The Connect button appears only in the "Not connected" state. Showing it when offline
would be misleading — the OAuth flow requires network anyway.

Tapping Connect: calls `getPolarConnectUrl()`, opens the returned URL with
`Intent.ACTION_VIEW`. Works for first-time connection and reconnection after token
clearance.

## App: Onboarding — `ConnectPolarScreen`

Added as the last step of the onboarding flow, after profile creation. The profile is
already saved at this point — this screen is genuinely optional.

Contains:
- Brief explanation of what Polar adds (real calories-out vs. estimate)
- **Connect Polar** button — same flow as Settings
- **Skip for now** button — navigates directly to the dashboard

After tapping Connect Polar the browser opens. On return via deep link, the app lands
on Settings (not back in onboarding — that flow is done). The user navigates to the
dashboard from there.

---

## Testing

### Unit (Tier 1)

**`PolarApiClientTest`** — `MockEngine` injected. Covers:
- `buildAuthorizationUrl()` includes `client_id`, `redirect_uri`, `state`
- `exchangeCode()` sends correct Basic auth header and form-encoded body; parses
  `access_token` and `x_user_id` correctly
- `registerUser()` sends `{ "member-id": "…" }`, accepts 200 and 409 without throwing
- `getActivities()` correctly maps `start_time` → `LocalDate`, `calories` → `totalKcal`,
  `active_calories` → `activeKcal`, derives `bmrKcal = totalKcal - activeKcal`
- `getExercises()` correctly maps `id`, `start_time` → `LocalDate`, ISO 8601 duration
  → seconds, `heart_rate.average`
- 429 response throws `PolarRateLimitException`

**`PolarSyncServiceTest`** — fake `PolarApiClient` (in-memory implementation of an
interface, same philosophy as `FakeLoginAttemptsStore`). Covers:
- Activities upserted with correct field mapping
- Re-running sync with same date → one row, not two
- Re-running sync with updated total for same date → new value overwrites old
- Same exercise fetched twice → one row
- 429 on one user → that user skipped, others unaffected
- Expired `polar_connect_state` rows GC'd at start of cycle

### Server integration (Tier 2a)

**`PolarIntegrationTest`** — UUID slot **#9** (`...000009`, `polar-test@test.local`).
`PolarApiClient` is injected into the Ktor module; tests supply a fake returning canned
responses (no real Polar credentials used in tests).

Covers:
- `GET /polar/connect-url` returns URL with correct `state` and `client_id`; requires auth
- `GET /polar/callback` with valid unexpired state → `polar_auth` row written, HTML
  response contains `branneman-health://polar/connected`
- `GET /polar/callback` with expired state → 400, no row written
- `GET /polar/callback` with replayed (already consumed) state → 400
- `GET /polar/status` returns `false` before OAuth, `true` after
- `GET /out/energy?from=` filters correctly
- `GET /out/workouts?from=` filters correctly

### App component (Tier 2b)

**`DailyEnergySyncServiceTest`** — Robolectric + in-memory Room + `MockEngine`.
Verifies correct upsert and update-on-conflict behaviour.

**`WorkoutSyncServiceTest`** — same pattern. Verifies `polar_exercise_id` uniqueness
prevents duplicate rows.

**`SettingsScreenTest`** — covers all four Polar status states: loading indicator,
connected text (no button), not-connected text + Connect button enabled, unknown/offline
text (no button).

**`ConnectPolarScreenTest`** — verifies Connect button present and enabled; Skip
navigates away without calling the connect URL.

### API (Tier 3)

**`PolarApiTest`** — exercises `GET /polar/status` against the real production server
using the existing API test account. Verifies the endpoint is deployed, authenticated,
and returns 200 with `{ "connected": false }` (the test account has no Polar token).
