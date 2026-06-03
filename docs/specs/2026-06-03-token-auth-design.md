# Token Auth Design

**Date:** 2026-06-03
**Scope:** Replace HTTP Basic Auth with a `POST /token` endpoint that validates credentials
against a database-backed users table and returns a short-lived opaque bearer token.

---

## Context

The current implementation uses HTTP Basic Auth with `API_USER` / `API_PASSWORD` env vars.
This works but has two problems: credentials travel on every request, and there is no
expiry mechanism. The new design issues a token at login time and uses that token for all
subsequent requests. The `API_USER` / `API_PASSWORD` env vars are removed.

---

## Architecture Overview

```
POST /token  ←  { username, password }
                     │
                     ├─ IP rate limiter check  (ConcurrentHashMap, in-memory)
                     ├─ username rate limiter check  (ConcurrentHashMap, in-memory)
                     ├─ look up user in DB  (always run BCrypt, see Security section)
                     ├─ verify password with BCrypt (cost 12)
                     ├─ compute expiry: next 2 AM Europe/Amsterdam
                     ├─ generate token: SecureRandom 32 bytes → 64-char hex string
                     └─ INSERT into sessions → return { token, expires_at }

GET /weight  ←  Authorization: Bearer <token>
(other routes)       │
                     ├─ look up token in sessions table
                     ├─ check expires_at > now()
                     └─ return UserIdPrincipal or 401
```

---

## Schema

New Flyway migration: `V2__auth.sql`

```sql
CREATE TABLE users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username      TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL
);

CREATE TABLE sessions (
    token      TEXT        PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX ON sessions (expires_at);
```

Notes:
- `UNIQUE` on `username` implicitly creates a B-tree index in Postgres — no separate index
  needed for the `WHERE username = ?` login query.
- The index on `sessions(expires_at)` makes lazy expiry cleanup efficient.

---

## POST /token Endpoint

**Request:**
```json
{ "username": "health", "password": "..." }
```

**Logic:**
1. Extract client IP from `X-Forwarded-For` (via Ktor `ForwardedHeaders` plugin).
2. Check IP rate limiter — return `429` with `Retry-After` header if locked.
3. Parse body, extract username. Check username rate limiter — return `429` if locked.
4. Look up user by username. If not found, run BCrypt against a dummy hash (see Security).
5. Verify `password` against `password_hash` with BCrypt.
6. On failure: increment both IP and username failure counters. Return `401`.
7. On success: reset both counters. Delete expired sessions for this user (lazy cleanup).
8. Compute expiry: next 2 AM in `Europe/Amsterdam`. Take today's date in that zone, set
   time to `02:00`. If that instant is already in the past, add 1 day.
9. Generate token: `SecureRandom` → 32 bytes → lowercase hex string (64 chars).
10. `INSERT INTO sessions(token, user_id, expires_at)`.
11. Return `200` with `{ "token": "...", "expires_at": "<ISO-8601 with offset>" }`.

**Response on any failure:** `401 Unauthorized`, empty body. Same response for wrong
username and wrong password — do not leak which field was wrong.

**BCrypt library:** `org.mindrot:jbcrypt:0.4` — zero dependencies, pure Java.
Cost factor: **12** (~300 ms on modern hardware, appropriate for a login that happens
once per session).

---

## Bearer Token Auth (existing routes)

Replace the current `basic("api")` Ktor auth block with `bearer("api")`:

```kotlin
install(Authentication) {
    bearer("api") {
        authenticate { credential ->
            val token = credential.token
            // SELECT * FROM sessions WHERE token = ? AND expires_at > now()
            // return UserIdPrincipal(userId) or null
        }
    }
}
```

All existing routes stay inside `authenticate("api") { ... }` unchanged.
`GET /` health check stays unauthenticated (used by the Docker healthcheck).

---

## Rate Limiting / Brute-Force Protection

Two independent in-memory maps, both checked on every login attempt:

```
ipFailures:       ConcurrentHashMap<String, FailureRecord>   // keyed by client IP
usernameFailures: ConcurrentHashMap<String, FailureRecord>   // keyed by username
```

`FailureRecord` holds `attempts: Int` and `lockedUntil: Instant?`.

**Lockout schedule:**
- After 5 failures: locked for 1 minute.
- Each additional failure while locked doubles the lockout duration.
- Cap: 1 hour.
- Successful login resets both counters for that IP and username.

**Response when locked:** `429 Too Many Requests` with
`Retry-After: <seconds>` header set to whichever of the two maps has the longer
remaining lockout.

**IP checked before username:** a request from a fully locked IP is rejected before the
body is parsed, avoiding unnecessary work under a flood.

### Why rate limiting lives only on POST /token

The password is a human-chosen secret with limited entropy — repeated guessing is a
real and practical attack. BCrypt slows each attempt, but without a lockout a determined
attacker can still try millions of passwords over time.

Bearer tokens are 32 bytes from `SecureRandom` (256 bits of entropy). Brute-forcing that
is computationally infeasible: at one trillion guesses per second it would take longer
than the age of the universe to find a valid token. Rate limiting the bearer token
endpoints would add complexity with no security benefit.

DoS against any endpoint (not specific to auth) is handled at the infrastructure level:
Caddy connection limits, `fail2ban`, and the Hetzner firewall configured in `cloud-init`.

---

## Security: Timing Attack Prevention

**Problem:** if the code returns immediately when a username is not found (skipping
BCrypt) but takes ~300 ms when the password is wrong (running BCrypt), an attacker can
distinguish "username does not exist" from "wrong password" by measuring response time.
This leaks valid usernames.

**Fix:** always run BCrypt, even for non-existent users. Keep a module-level dummy hash
compiled at startup:

```kotlin
val DUMMY_HASH = BCrypt.hashpw("dummy", BCrypt.gensalt(12))
```

When the username lookup returns no row, call `BCrypt.checkpw(password, DUMMY_HASH)`
anyway (it will return false), then return `401`. The code path is identical in both
cases.

**Floor:** add a `delay()` after BCrypt to ensure the total response time never drops
below 500 ms. This covers variance in BCrypt timing across different hardware and cost
paths, and ensures 429 responses (which skip BCrypt) also take the same minimum time.

**Why timing attacks are not a concern on bearer token endpoints:** the DB lookup
`WHERE token = ?` is either found or not — there is no partial match, no secret
derivation, and no hash comparison. An attacker cannot exploit timing differences
because the token space (2²⁵⁶) makes enumeration infeasible regardless of timing.
Even if string comparison were not constant-time at the hardware level, extracting a
useful signal would require billions of requests with zero chance of success per guess.

---

## Environment Variables

`API_USER` and `API_PASSWORD` are **removed** from:
- `Application.kt`
- `.env.example`
- `ansible/templates/env.j2`
- `ansible/vars/vault.yml`

No new env vars are introduced — credentials move entirely into the database.

---

## Ansible: Seeding Users

**Vault variable naming convention:** `user_<username>_password`. Adding a second user
in the future means adding `user_alice_password` to the vault and a new upsert task —
no schema change needed.

```yaml
# ansible/vars/vault.yml (local only, never committed)
user_health_password: "strong-random-value"
```

**Ansible tasks (added to `playbook.yml`):**

```yaml
- name: Hash password for user 'health'
  command: >
    python3 -c "import bcrypt;
    h=bcrypt.hashpw('{{ user_health_password }}'.encode(), bcrypt.gensalt(12)).decode();
    print(h[:2]+'a'+h[3:] if h[2]=='b' else h)"
  register: health_pw_hash
  changed_when: false
  no_log: true
  delegate_to: localhost

- name: Upsert user 'health'
  command: >
    docker exec health_postgres psql -U {{ postgres_user }} -d {{ postgres_db }} -c
    "INSERT INTO users (username, password_hash)
     VALUES ('health', '{{ health_pw_hash.stdout }}')
     ON CONFLICT (username) DO UPDATE SET password_hash = EXCLUDED.password_hash;"
  no_log: true
```

Idempotent: re-running the playbook updates the password if the vault value changes.

**Python dependency:** `bcrypt` must be available on the Ansible control machine
(the developer's laptop). Install once: `pip3 install --break-system-packages bcrypt`.

---

## Dependencies Added

| Library | Version | Purpose |
|---------|---------|---------|
| `org.mindrot:jbcrypt` | `0.4` | BCrypt password hashing |

Ktor's `ForwardedHeaders` plugin is already in the Ktor bundle — no new dependency.

---

## Out of Scope

- Token refresh (tokens are issued fresh at login; there is no refresh endpoint)
- Logout / token revocation endpoint (tokens expire naturally; rows can be deleted
  manually if needed)
- Multi-user management UI — vault + playbook is sufficient for the current scale
- Persistent rate limiter state across restarts (in-memory is fine; a restart resets
  counters, which is an acceptable tradeoff for a personal app)
