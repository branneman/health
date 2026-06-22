# Security

**Date:** 2026-06-07  
**Scope:** Full audit before making the repo public on GitHub. Covers server code, Android
app, infrastructure-as-code, deployment pipeline, git history, and documentation.

---

## Threat model

**Who this app defends against:**
- Anyone who finds the public repo and tries to exploit the running server
- An attacker who gets a copy of the APK
- Someone who finds the server hostname via DNS and probes it
- Cross-user data leakage: a bug that lets one user read or modify another's data —
  even accidentally; users are not adversarial but bugs do not discriminate

**What's not in scope:**
- Physical device access (stolen phone) — Android FBE handles storage encryption
- Compromised SSH private key — hardware security key or passphrase mitigates this
- The server host being rooted — addressed partially by container isolation, but not fully
- A compromised dependency in the Docker image chain (supply chain attack)
- A malicious authenticated user deliberately attacking the server infrastructure —
  account provisioning is controlled; this is an accepted residual risk at current scale

**Trust assumptions:**
- Only pre-provisioned users log in; there is no self-registration
- Authenticated users are assumed not to probe or attack the server infrastructure
- Data confidentiality between users is **not** an assumption — it is a control,
  enforced at the database layer via `user_id` scoping on every query
- The VPS host (Hetzner EU) is trusted for infrastructure

**Data sensitivity:**
The data stored here borders on health data under GDPR Article 9: body weight over
time, detailed eating patterns (food, quantities, meal timing), workout history, and
calorie deficit trends. The server is EU-hosted and the owners are EU residents. Even
under the "personal/household activity" exemption in Article 2(2)(c), treat this data
as sensitive in all engineering decisions: don't log it, don't expose it in error
messages, don't retain it longer than needed, and enforce that it never crosses user
boundaries.

---

## What was audited (June 2026)

- Full git history (`git log --all -p`) — checked for committed secrets, credentials, PII
- All configuration files: `.env.example`, `cloud-config.yml`, `docker-compose.yml`,
  `docker-compose.override.yml`, `Caddyfile`, `ansible/playbook.yml`,
  `ansible/templates/env.j2`, `ansible/templates/backup.sh.j2`
- Server source: `Application.kt`, `AuthService.kt`, `RateLimiter.kt`
- Android source: `TokenStore.kt`, `AuthRepository.kt`, `AuthPlugin.kt`,
  `HealthApiClient.kt`, all manifests
- CI/CD: `.github/workflows/deploy.yml`
- Database migrations: `V1__initial_schema.sql`, `V2__auth.sql`
- All docs in `docs/`

---

## Findings and fixes

### Fixed in this audit

**[CRITICAL] logback.xml was set to TRACE level**  
File: `server/src/main/resources/logback.xml`  
The Ktor application logged at `TRACE` in production. This level logs full request and
response bodies, SQL queries, and internal Ktor/Netty pipeline details. A login attempt
would have its password body logged to stdout (which Docker captures to disk/log systems).  
**Fixed:** changed root level from `trace` to `info`.

**[HIGH] `android:allowBackup="true"` in the release manifest**  
File: `app/src/main/AndroidManifest.xml`  
Android ADB backup (`adb backup`) can extract all app-private data including the
Preferences DataStore file, which stores the bearer token. Anyone with USB access to
an unlocked development-mode phone could silently extract the session token.  
**Fixed:** set `android:allowBackup="false"`.

**[HIGH] No request body size limit on the Ktor server**  
File: `server/src/main/kotlin/org/branneman/health/Application.kt`  
The server accepted arbitrarily large request bodies. A client (or bot that found the
public endpoint) could POST a 1 GB body to `/auth/token`, causing memory exhaustion.  
**Fixed:** added a `Content-Length` interceptor that returns `413 Payload Too Large`
for any body declared larger than 64 KB. All planned endpoints have payloads well under
this limit. Note: this guards against well-behaved clients declaring a large body; a
chunked-encoding attack without a `Content-Length` header is stopped by Ktor/Netty's
own buffering limits.

**[HIGH] No Docker network isolation — Watchtower shared network with Postgres**  
File: `docker-compose.yml`  
All containers were on the default bridge network. Watchtower has the Docker socket
mounted (`/var/run/docker.sock`), which grants it effective root on the host. If
Watchtower or any image it pulls is ever compromised, it would have direct network
access to Postgres.  
**Fixed:** added explicit `backend` and `watchtower` networks. `ktor`, `postgres`, and
`caddy` are on `backend`. `watchtower` is on its own isolated network. It can still
reach Docker Hub (internet-facing) but cannot reach Postgres or Ktor.

**[LOW] Caddyfile leaked `Server` header and lacked basic API headers**  
File: `Caddyfile`  
Responses included `Server: Caddy/2.x` (minor version disclosure). No
`X-Content-Type-Options` header was set.  
**Fixed:** added `header` block to remove the `Server` header and add
`X-Content-Type-Options: nosniff`.

---

### Known risks — accepted or not yet fixed

**[HIGH] `deploy` user has unrestricted `NOPASSWD:ALL` sudo**  
File: `cloud-config.yml`  
If the server is compromised through any vector (container escape, a future Ktor
vulnerability, a malicious image in the watchtower chain), the attacker has a trivial
path to root via `sudo`.  
**Mitigation path:** scope sudo to specific commands:
```
deploy ALL=(ALL) NOPASSWD:/usr/bin/docker,/usr/bin/apt-get,/usr/bin/systemctl restart docker
```
The deploy user needs Docker commands (via group membership handles this) and
occasionally apt/systemctl for maintenance. The unrestricted sudo is retained for now
with a comment in `cloud-config.yml`. Change it before provisioning any additional
servers or granting third-party access.

**[HIGH] In-memory rate limiter resets on server restart — backlog story 3 (Persist rate-limit state)**  
File: `server/src/main/kotlin/org/branneman/health/auth/RateLimiter.kt`  
The `RateLimiter` uses `ConcurrentHashMap`. A restart wipes all lockout state. An
attacker can deliberately trigger OOM restarts — flooding `/auth/token` with concurrent
requests spikes the JVM heap; `restart: unless-stopped` brings the container back clean
— resetting the lockout window and enabling a sustained brute-force loop. BCrypt cost 12
(~250 ms/attempt) caps throughput at ~32 attempts/second even with full parallelism, so
a strong password is safe in practice. But this is not acceptable once more than one
user exists: both real users and the e2e test account become targets.  
**Fix (3 (Persist rate-limit state)):** `V3__login_attempts.sql` adds a `login_attempts` table; `RateLimiter`
loads state on startup and writes through on every failure and reset. The extra Postgres
round-trip is negligible against BCrypt's 250 ms. See backlog for scope.

**[LOW] `polar_auth` stores Polar access tokens as plaintext in Postgres**  
File: `server/src/main/resources/db/migration/V1__initial_schema.sql`  
Polar access tokens are permanent (no expiry, no refresh per Polar's token model).
Storing them in plaintext means a DB dump exposes them directly. Mitigation: encrypt at
the column level using Postgres `pgcrypto` before Polar integration is built. This is
low priority until the Polar integration story is started.

**[LOW] No certificate pinning in the Android app**  
The app does not pin the server's TLS certificate. A MITM attack with a CA-trusted
certificate (e.g. a corporate proxy on the user's network, or a compromised root CA)
could intercept requests. For an admin-provisioned app with a small trusted user base,
this is an acceptable tradeoff — pinning adds operational fragility (app breaks if the
cert changes before the app is updated).

---

### What was checked and found clean

**No secrets ever committed to git.**  
Full history scan found:
- `POSTGRES_PASSWORD=secret` appears in `.env.example` only — this is the documented
  placeholder, not a real credential.
- `API_PASSWORD=secret` appeared in early history (commits `d2617ff`, `8957a93`) in
  `.env.example` — same placeholder, removed in `8957a93` when auth model changed.
- No real passwords, API keys, or tokens were found in any commit.
- The Ansible `vars/vault.yml` is correctly gitignored; the vault password is in macOS
  Keychain, not in the repo.
- `local.properties` (contains `server.baseUrl`) is correctly gitignored.

**The SSH public key in `cloud-config.yml` is fine to publish.**  
SSH public keys are safe by definition. The key comment includes the email address
(`bran.van.der.meer@protonmail.com`), which is already public information associated
with the GitHub account.

**Auth implementation is solid.**  
- BCrypt with cost factor 12 (≈250 ms on modern hardware)
- `DUMMY_HASH` prevents username-enumeration via timing: even for unknown usernames,
  BCrypt is always called before returning 401
- 500 ms response floor prevents timing leaks on fast code paths
- Rate limiting on both IP address and username simultaneously
- 256-bit `SecureRandom` tokens (32 bytes → 64 hex chars)
- Tokens stored in the database, revocable via logout
- `XForwardedHeaders` plugin correctly installed for IP extraction behind Caddy

**No SQL injection risk.**  
All queries use Exposed ORM with parameterized bindings throughout. The Ansible
`psql` command uses a bcrypt hash as the inserted value — safe even if it contains
single quotes (bcrypt hashes don't).

**Postgres is not externally reachable in production.**  
The base `docker-compose.yml` does not expose Postgres ports. Port exposure only
happens via `docker-compose.override.yml`, which is local development only.

**The Postgres MCP server is loopback-only.**  
`docker-compose.override.yml` binds the MCP SSE port to `127.0.0.1:8001` — not
`0.0.0.0`. It never runs in production (override file only).

**Android token storage is correctly chosen.**  
The `TokenStore` uses Preferences DataStore. The login design doc correctly explains why
application-level AES encryption is not needed: Android's File-Based Encryption (FBE,
mandatory on `minSdk = 24`) encrypts all app-private storage at rest using the device
lock-screen credential. DataStore is the correct choice; `EncryptedSharedPreferences`
would add complexity without improving the threat model.

**CI/CD pipeline is minimal and safe.**  
The GitHub Actions workflow only:
1. Checks out code
2. Builds the server JAR
3. Builds a Docker image
4. Pushes to `ghcr.io` using the ephemeral `GITHUB_TOKEN`

No secrets beyond `GITHUB_TOKEN` are used. Watchtower on the VPS polls the registry
and deploys the new image automatically.

---

### Auto-update story (22 (Auto update)) — security requirements and risks

The auto-update spec (`auto-update.md`) is pending implementation and
introduces several security-relevant concerns.

**[CRITICAL] APK signing keystore must never be committed**  
The spec defers signing config to "a deployment concern." Before implementing 22 (Auto update):
- Generate a release keystore (`keytool -genkey -v -keystore release.jks ...`).
- **Never commit the keystore file or its password anywhere in the repo.** Store:
  - `KEYSTORE_FILE` — base64-encoded `.jks` file → GitHub Actions secret
  - `KEYSTORE_PASSWORD` — keystore password → GitHub Actions secret
  - `KEY_ALIAS` — key alias → GitHub Actions secret
  - `KEY_PASSWORD` — key password → GitHub Actions secret
- Back the keystore up separately (if lost, users cannot install APK updates from the
  same publisher without a full reinstall).
- Add `*.jks`, `*.keystore`, `keystore.properties` to `.gitignore` before creating them.
- The `app/build.gradle.kts` signing config reads these from env vars, not from files in
  the repo. Example:
  ```kotlin
  signingConfigs {
      create("release") {
          storeFile = file(System.getenv("KEYSTORE_PATH") ?: "debug.keystore")
          storePassword = System.getenv("KEYSTORE_PASSWORD")
          keyAlias = System.getenv("KEY_ALIAS")
          keyPassword = System.getenv("KEY_PASSWORD")
      }
  }
  ```

**[HIGH] Version enforcement interceptor must exclude `/server-health` and `/`**  
The spec's `X-App-Version` enforcement intercepts all requests and returns 426 on
mismatch, excluding only `/api/update`. But the Docker healthcheck calls `/server-health`
using `wget` — which sends no `X-App-Version` header. After the interceptor is wired,
the healthcheck will return 426, Docker will mark the container unhealthy, and Caddy
will stop receiving traffic.  
**Rule:** the version enforcement interceptor must exclude at minimum:
```kotlin
val path = call.request.path()
val excluded = path == "/api/update" || path == "/server-health" || path == "/"
if (!excluded && clientVersion != currentVersion) { ... }
```

**[MEDIUM] `/api/update` is an unauthenticated, public, enumerable endpoint**  
`GET /api/update` exposes the current server version string and a direct APK download
URL. This is intentional and necessary for the update flow. But it means:
- The exact server version is public (acceptable for an open-source app).
- The APK URL is fully predictable and public (consistent with the APK being on public
  GitHub Releases).
- Any external party can see when updates are deployed (timing of releases is public).
This is accepted. Add `/api/update` to the canonical list of unauthenticated endpoints.

**CI/CD deploy model stays Docker/Watchtower — no SSH deploy key needed**  
22 (Auto update) keeps the existing model: GitHub Actions pushes a Docker image to `ghcr.io`
using the ephemeral `GITHUB_TOKEN`; Watchtower on the VPS polls and deploys. The
`publish-apk` job adds a new step before `deploy-server` (the Docker image build). No
SSH private key is needed in CI; the pseudo-code in the spec showing `scp`/`ssh` is not
the implementation.

---

## Seed data

**File: `local-db-seed/seed_data.sql`**

Contains 90 days of body weight measurements (87.2 kg → 81.9 kg). Confirmed to be
synthetic data generated to represent a realistic downward trend. Safe to publish.

---

## Security rules for future development

These rules apply to every story, feature, and PR. They're ordered by frequency of
relevance.

### Never commit secrets

- `.env`, `ansible/vars/vault.yml`, `local.properties` are gitignored — keep them that
  way. Never add exceptions.
- New secrets (Polar API credentials, future OAuth tokens, SMTP passwords) go into the
  Ansible vault (`ansible/vars/vault.yml`), then are templated into `.env` on the server.
- Never hardcode credentials in Kotlin source, build scripts, or test fixtures.
- Before opening a PR, run `git diff origin/main` and grep for anything that looks like
  a key: `grep -i "secret\|password\|token\|key" changed_files`.
- Use placeholder values in `.env.example` that are obviously fake (e.g. `secret`,
  `changeme`, `your-token-here`).

### Data isolation — every query must scope by user_id

After the multi-user migration (V4, see `docs/specs/multi-user-sync-design.md`), every user-data table has a `user_id` column.
This is the primary control against cross-user data leakage — not trust in the users,
but enforcement at the data layer on every single query.

**The rule:** every Exposed query on a user-data table must include
`.where { Table.userId eq sessionUserId }`. No exceptions for reads that "seem harmless."

Specifically:
- **List/aggregate queries:** `WHERE user_id = ?` always. A missing scope makes every
  user's data visible to every other user.
- **GET by ID:** `WHERE id = ? AND user_id = ?`. Without the user_id check, any user
  who guesses or obtains another user's UUID can read their entry directly.
- **DELETE by ID:** same — `AND user_id = ?`. Without it, any user can delete another
  user's data by UUID.
- **Joins:** if joining two user-data tables, scope both sides. Don't rely on one side's
  scope to implicitly limit the other.
- **Server-side writes (Polar cron):** inserts for `daily_energy` and `workout` must
  always include the `user_id` derived from `polar_auth.health_user_id`.

**Code review gate:** any PR that adds or modifies an endpoint touching a user-data
table must have an explicit reviewer check that user_id scoping is present on every
read, write, and delete path.

**The test automation user is subject to the same isolation.**  
When e2e tests run in production against a test account, the test user's data is
isolated from real users by the same `user_id` mechanism — there must be no
"test mode" code path that bypasses scoping. The test dataset must be predictable and
reset-able without touching any other user's rows. The test account's credentials live
in the Ansible vault under a clearly named key (e.g. `user_e2etest_password`) and are
separate from any real user's credentials.

---

### All new API endpoints must be authenticated by default

- Use the `authenticate("api") { ... }` block in Ktor routing. Unauthenticated endpoints
  require an explicit deliberate exception.
- Currently unauthenticated (and must stay that way): `GET /`, `GET /server-health`,
  `POST /auth/token`, `GET /api/update` (once 22 (Auto update) lands).
- Never add unauthenticated read endpoints for user data, even "harmless" summary data.

### Input validation at all API boundaries

- All `POST`/`PUT` endpoints must validate incoming data before inserting into the DB:
  - Numeric ranges (e.g. weight in kg: `0.1..600.0`)
  - String lengths (names, usernames)
  - Date/time validity (reject far-future or clearly invalid dates)
- Return `400 Bad Request` with a descriptive but non-leaking message.
- The current 64 KB body size limit protects against memory exhaustion. The limit is in
  `Application.kt` as an interceptor; review it if a future endpoint needs to accept
  larger bodies (e.g. an Open Food Facts import batch).

### No direct SQL — use Exposed ORM

All database access goes through Exposed's type-safe DSL or DAO layer. Raw SQL strings
in `exec()` are forbidden. The only SQL in the codebase should be Flyway migration files
(`server/src/main/resources/db/migration/`).

### Rate limiting applies to all public mutation endpoints

- `POST /auth/token` is currently rate-limited. Any future public endpoint (e.g. a
  hypothetical health-share link) must also have rate limiting.
- The current in-memory `RateLimiter` is documented as resetting on restart (accepted
  for the current threat model). If higher assurance is needed, move state to the
  `sessions` table or a dedicated Postgres table.

### Log level discipline

- `logback.xml` root level is now `INFO`. Keep it there.
- Never log request bodies, passwords, tokens, or personally identifiable information
  at any log level.
- Structured log lines are OK for debugging context (user ID, endpoint, status code).
  Never log the user's food data, health metrics, or the raw token string.

### Secrets in logs — the Kotlin rule

```kotlin
// NEVER:
log.info("Login attempt for user: $username, password: $password")
log.debug("Token: $token")

// OK:
log.info("Login attempt for user: $username")
log.info("Token issued, expires: $expiresAt")
```

### Database migrations

- Each story that changes the schema adds a new `V{n}__description.sql` file. Never
  edit a migration that has already been applied to production.
- Migrations run automatically on Ktor startup (Flyway). Test them locally against a
  fresh DB with `docker compose down -v && docker compose up -d postgres && ./gradlew :server:run`.
- Multi-user migration (`V3__multi_user.sql`, 4 (Multi-user)) must include backfill logic for
  existing rows before adding `NOT NULL` constraints. See the migration design in
  `docs/specs/multi-user-sync-design.md`.

### Dependency hygiene

- Dependabot or manual review: run `./gradlew dependencyUpdates` before each release
  to check for CVEs in transitive dependencies.
- The PostgreSQL JDBC driver and Flyway are the highest-risk transitive dependencies
  (network-facing, parsing untrusted SQL). Keep them current.
- The Ktor version pins all server-side HTTP handling. Update it on minor versions
  regularly.

### Docker / infrastructure

- **Never expose Postgres externally in production.** The `ports` directive for
  `postgres` lives only in `docker-compose.override.yml`. The base `docker-compose.yml`
  must never add a `ports` section to `postgres`.
- **Watchtower polls every 60 seconds.** Every push to `main` that passes CI will be
  deployed automatically within ~60 s. Broken builds should never merge to `main`
  (enforced by convention — add branch protection once the repo is public).
- **The `backend` and `watchtower` Docker networks must stay separate.** Adding a
  service to both networks (e.g. for debugging) is a security regression.
- When adding new services to `docker-compose.yml`, explicitly assign them to `backend`
  (if they need to talk to ktor/postgres) or `watchtower` (never). Do not rely on
  Docker's default network assignment.

### Android

- `android:allowBackup="false"` — never revert this. ADB backup can extract DataStore
  content (the auth token).
- `android:debuggable` must not be set in the main manifest; it may only appear in
  `src/debug/AndroidManifest.xml` (which it currently does not, correctly).
- The debug network security config (`src/debug/res/xml/network_security_config.xml`)
  allows cleartext to `10.0.2.2` (emulator localhost). Never add production domains or
  `*` wildcards to this config.
- `isMinifyEnabled = false` in release is acceptable for an app distributed via ADB to
  known users. If the app is ever distributed via Play Store, enable minification to
  reduce reverse-engineering exposure.

### Polar integration (future story)

When the Polar integration is built:
- The Polar OAuth callback (`GET /polar/callback`) must:
  - Validate the `state` parameter (CSRF protection on the OAuth flow)
  - Be accessible only when the server operator triggers it (not publicly linked)
  - Exchange the code server-side only (never expose `client_secret` to the Android app)
- The `polar_auth` table stores the access token as plaintext. Before implementing,
  consider encrypting it at the column level using `pgcrypto` (`gen_random_uuid()` as
  the key is wrong — use a server-side secret). The token is permanent and gives full
  read access to the user's Polar data.
- The Polar webhook endpoint (future) must verify the `X-Polar-Webhook-Signature` HMAC
  before processing any payload.

### AI key encryption (`AI_KEY_ENCRYPTION_KEY`)

Per-user Anthropic API keys are stored encrypted at rest in the `ai_config` table.
The server-side symmetric encryption key is `AI_KEY_ENCRYPTION_KEY` (AES-256 via
`TokenCipher`).

**Consequence of omission:** if `AI_KEY_ENCRYPTION_KEY` is missing from the environment,
`Application.kt` silently skips registering the `/ai/*` routes. The AI Calorie Estimate
feature will be entirely absent — no 500 error, no log warning, just a 404 on every AI
endpoint. A deployment that forgets this variable loses the feature silently.

**How to generate:**
```
openssl rand -base64 32
```
Store the result in the Ansible vault under `ai_key_encryption_key` and template it into
`.env` via `ansible/templates/env.j2` alongside `POLAR_TOKEN_ENCRYPTION_KEY`.

**Rotation:** rotating `AI_KEY_ENCRYPTION_KEY` requires re-encrypting all rows in
`ai_config`. There is no automatic rotation path — if the key must be rotated, decrypt
all rows with the old key, set the new key, re-encrypt and update all rows.

---

### Open Food Facts proxy (future story)

When the food search proxy is built:
- The server proxies OFD requests (`GET /in/food-items?q=` cache miss). Never pass
  the raw user query string directly to OFD without sanitisation and length limits.
- Do not expose OFD error responses verbatim to the Android client — they may contain
  internal OFD implementation details.

---

## Infrastructure hardening checklist (for future server provisioning)

These are improvements to `cloud-config.yml` to consider for any future VPS:

- [ ] Scope `deploy` user sudo to specific commands instead of `ALL`:
  `deploy ALL=(ALL) NOPASSWD:/usr/bin/docker,/usr/bin/apt-get,/usr/bin/systemctl`
- [ ] Add `fail2ban` jail for the Caddy access log to ban IPs with repeated 401s on
  the API (not just SSH)
- [ ] Enable automatic Docker log rotation (`log-driver: json-file` with `max-size` and
  `max-file` in `/etc/docker/daemon.json`) to prevent disk fill from excessive logging
- [ ] Consider `read_only: true` and `no-new-privileges: true` on Ktor and Caddy
  containers once paths are stable
