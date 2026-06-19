# Login Design

**Date:** 2026-06-05
**Story:** 2 (Login)
**Scope:** Android login screen, secure token storage, token refresh, session expiry
handling, and server-side auth endpoint refactor (`/auth/` prefix, 30-day tokens,
refresh + logout endpoints).

---

## Context

1 (Walking skeleton) proved the CI/CD pipeline and established the 3-tab nav.
The server already has a fully working `POST /token` endpoint with BCrypt, rate
limiting, and session management. 2 (Login) adds the Android auth gate: the app checks
for a stored token on launch and routes to either the login screen or the main nav.

Token refresh is included in this story because the security context is fresh and the
infrastructure is straightforward. Without it, the user would need to re-login every
day (tokens currently expire at 2 AM). Deferring refresh risks it never being built
with the same care.

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────┐
│  Server                                             │
│  POST /auth/token   (was /token — rename)           │
│  POST /auth/refresh (new — protected, rotating)     │
│  POST /auth/logout  (new — protected, deletes row)  │
│  computeExpiry: now + 30 days (was: next 2 AM)      │
└───────────────────────┬─────────────────────────────┘
                        │ HTTPS / JSON
┌───────────────────────▼─────────────────────────────┐
│  Android                                            │
│                                                     │
│  HealthApiClient  ←── AuthPlugin (Ktor plugin)      │
│       │               401 → refresh → retry once    │
│       │               refresh fails → emit Expired  │
│       ▼                                             │
│  AuthRepository                                     │
│       │  login() / logout()                         │
│       │  Flow<AuthState>                            │
│       ▼                                             │
│  TokenStore  (Preferences DataStore — plain)        │
│       stores: token + expiresAt                     │
│                                                     │
│  AuthViewModel  ←── AuthRepository                  │
│       drives navigation in App()                    │
│                                                     │
│  App()                                              │
│    LoggedOut / Expired → LoginScreen                │
│    LoggedIn           → 3-tab nav (unchanged)       │
└─────────────────────────────────────────────────────┘
```

---

## Security decisions

**No client-side password hashing.** Hashing on the client before sending over HTTPS
turns the hash into the credential — an intercepted or leaked hash can be replayed
directly without knowing the original password. HTTPS handles transit security; BCrypt
on the server handles storage security. This is the universal industry approach.

**No application-level encryption for token storage.** Android's File-Based Encryption
(FBE, guaranteed on `minSdk = 24`) encrypts all app-private storage at rest using the
device lock-screen credential. Adding AES on top via Android Keystore would be
defence-in-depth that adds operational complexity (key rotation, cipher exceptions,
migration) without meaningfully improving the threat model for a personal app. Plain
Preferences DataStore is the correct choice.

**Single token model (no access/refresh split).** The two-token pattern (short-lived
access token + long-lived refresh token) limits damage if an access token leaks to a
third party — e.g. a logging system, CDN, or proxy. This API is called only from the
owner's own app on their own device. The attack surface does not justify the added
complexity. A single rotating 30-day token is simpler and correct for this use case.

---

## Server changes

### Endpoint rename

`POST /token` → `POST /auth/token`. All existing logic (rate limiting, BCrypt, timing
floor, session insert) is unchanged. Only the route path moves.

### Token lifetime

`computeExpiry()` changes from "next 2 AM Europe/Amsterdam" to `OffsetDateTime.now()
+ 30 days`. No schema migration required — the `sessions` table shape is unchanged.

### New endpoints

**`POST /auth/refresh`** — protected by existing bearer auth (valid token in
`Authorization` header, no request body). Atomically deletes the current session row
and inserts a new one with a fresh token string and a new 30-day expiry. Returns
`TokenResponse`. Rate limiter does not apply — the caller already holds a valid token.

**`POST /auth/logout`** — protected by bearer auth, no request body. Deletes the
session row for the current token. Returns `204 No Content`.

### Updated system endpoint table

| Method | Path              | Auth | Description                      |
|--------|-------------------|------|----------------------------------|
| `GET`  | `/`               | No   | API reference docs               |
| `GET`  | `/server-health`  | No   | Health check                     |
| `POST` | `/auth/token`     | No   | Issue bearer token               |
| `POST` | `/auth/refresh`   | Yes  | Rotate token (30-day extension)  |
| `POST` | `/auth/logout`    | Yes  | Revoke session                   |

No new shared DTOs are needed. `POST /auth/refresh` reuses `TokenResponse`; logout
has no body in either direction.

### Files changed on server

- `Application.kt` — rename `/token` route to `/auth/token`, add `/auth/refresh` and
  `/auth/logout` routes
- `AuthService.kt` — update `computeExpiry()`, add `refresh(token)` and `logout(token)`
  methods

---

## Android architecture

### New dependencies

| Dependency | Purpose |
|------------|---------|
| `androidx.datastore:datastore-preferences` | Token persistence |
| `io.ktor:ktor-client-content-negotiation` | JSON in client requests |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | Already in version catalog; add to `app` |

### `TokenStore`

Thin Preferences DataStore wrapper. No business logic.

- Stores two keys: `token: String` and `expiresAt: String` (ISO-8601)
- Exposes `Flow<StoredToken?>` — `null` means nothing stored
- Methods: `save(token: String, expiresAt: String)`, `clear()`

### `AuthRepository`

Owns all auth state logic. Exposes `Flow<AuthState>` where `AuthState` is a sealed
class:

```kotlin
sealed class AuthState {
    data object LoggedOut : AuthState()
    data object Expired : AuthState()
    data object LoggedIn : AuthState()
}
```

Startup logic on flow collection (in order):

1. No stored token → emit `LoggedOut`
2. Token past expiry → emit `Expired`
3. Token expires within 7 days → call `refresh()`; on success save new token and emit
   `LoggedIn`; on failure clear store and emit `Expired`
4. Otherwise → emit `LoggedIn`

Public methods:

- `login(username: String, password: String): Result<Unit>` — calls `POST /auth/token`,
  saves token on success
- `logout()` — calls `POST /auth/logout`, clears store regardless of response
- `refresh()` — internal; also called by `AuthPlugin` on 401

### `AuthPlugin`

A Ktor `HttpClientPlugin` installed on `HealthApiClient`. Intercepts every response:

- On 401 from a non-`/auth/` path: calls `AuthRepository.refresh()`
  - Refresh succeeds → retry the original request once with the new token
  - Refresh fails → clear the stored token, emit `Expired` via a `MutableSharedFlow`
    the repository observes; let the 401 propagate to the caller
- On 401 from any `/auth/` path (i.e. the refresh call itself returned 401): clear
  the stored token, emit `Expired` directly — no retry, no further refresh attempt.
  This prevents an infinite refresh loop.
- On any other status: pass through unchanged
- No retry loop — one refresh attempt per failed request

The plugin is wired and fully implemented in 2 (Login). It will not be triggered until
4 (Multi-user) introduces the first authenticated app endpoint.

### `AuthViewModel`

Collects `AuthRepository.authState`, exposes it as `StateFlow<AuthState>` to the UI.
Exposes `login(username, password)` and `logout()` as suspend actions. `App()` observes
the state and routes accordingly:

- `LoggedOut` → `LoginScreen` (no message)
- `Expired` → `LoginScreen` with "Session expired" message
- `LoggedIn` → 3-tab nav (unchanged from 1 (Walking skeleton))

### `HealthApiClient` updates

- Install content negotiation plugin (kotlinx-serialization JSON)
- Install `AuthPlugin`
- Add `login(username, password): TokenResponse`
- Add `refresh(token: String): TokenResponse`
- Add `logout(token: String)`

### File layout

```
app/src/main/kotlin/org/branneman/health/
├── App.kt                    ← updated: observes AuthViewModel, routes on AuthState
├── auth/
│   ├── TokenStore.kt         ← new
│   ├── AuthRepository.kt     ← new
│   └── AuthViewModel.kt      ← new
├── network/
│   ├── HealthApiClient.kt    ← updated
│   └── AuthPlugin.kt         ← new
└── ui/
    └── LoginScreen.kt        ← new
```

---

## Login screen (F01, Step 0)

```
┌──────────────────────────────┐
│  Health                      │
│  Sign in to your account     │
├──────────────────────────────┤
│  [Session expired —          │  ← shown only when arriving from Expired state
│   please sign in again]      │
│                              │
│  Email   [ ______________ ]  │
│  Password[ ______________ ]  │  ← dots, no show/hide toggle
│                              │
│  [Wrong credentials]         │  ← inline, under password, on 401
│  [Check your connection —    │  ← inline, under password, on network error
│   login requires internet]   │
├──────────────────────────────┤
│          [ Sign in ]         │  ← disabled while request in flight
└──────────────────────────────┘
```

Notes:

- Field label is "Email"; the value is sent as `username` in the request body. The
  stored username is an email address (seeded via Ansible), so the label is accurate
  from the user's perspective.
- No "create account" option — account is pre-provisioned; this is a personal app.
- Sign in requires a network connection. All subsequent app use (3 (Persist rate-limit state) onwards) works
  offline once logged in.
- Error responses from the server are deliberately non-specific (wrong username and
  wrong password return the same 401 — see `token-auth-design.md`). The inline error
  reads "Wrong credentials" without specifying which field, consistent with the server
  design.
- The Sign in button is disabled while the request is in flight. No spinner — the
  500ms BCrypt timing floor on the server means the button returns to enabled within
  a predictable short window.

---

## Testing

### Server (`ApplicationTest`)

- `POST /auth/token` — success (existing test, path updated)
- `POST /auth/token` — wrong credentials → 401
- `POST /auth/token` — rate limited → 429
- `POST /auth/refresh` — success: new token issued, old token invalid
- `POST /auth/refresh` — expired token → 401
- `POST /auth/logout` — success: session deleted, token no longer valid

### Server (`AuthServiceTest`)

- `computeExpiry()` returns approximately `now + 30 days` (replaces existing 2 AM test)

### Android (`HealthApiClientTest`, mock engine)

- `login()` success
- `login()` wrong credentials (401)
- `login()` network failure
- `refresh()` success
- `logout()` success

### Android (`AuthRepositoryTest`, unit)

- No token → `LoggedOut`
- Expired token → `Expired`
- Token expiring within 7 days + refresh succeeds → `LoggedIn`
- Token expiring within 7 days + refresh fails → `Expired`
- Valid token with > 7 days remaining → `LoggedIn`

### Android (`AuthPluginTest`, mock engine)

- 401 response → refresh attempted → original request retried with new token
- 401 response + refresh fails → `Expired` emitted, 401 propagated

### What is not tested

- `TokenStore` — no logic; testing it would test DataStore itself
- `LoginScreen` UI — no instrumented tests for 2 (Login); the repository and client
  tests cover the real risk surface

---

## Out of scope

- Multi-user support — one account, pre-provisioned
- Token management UI — no screen for viewing or revoking sessions
- Biometric / PIN unlock as a second factor — not needed for this threat model
- Proper access/refresh token split — single token model is correct for this use case;
  revisit if the API is ever exposed to third-party clients
