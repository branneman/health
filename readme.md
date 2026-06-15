# Health

This app helps you see whether you're on track to stay healthy (lose weight + gain strength/stamina)
by bringing together the two sides of the equation in one place: the energy you burn each day and
the food you eat. It pulls your daily calorie expenditure automatically from your smartwatch and
pairs it with quick food logging — designed so your regular meals take a single tap to record — then
turns that into a clear picture of how your day, and your trend over time, is shaping up. An android
homescreen widget gives you an at-a-glance read on whether you're on track plus a useful insight,
while the full app shows the history and detail behind it. It works offline, so logging never has to
wait for a connection.

## Stack

Kotlin throughout — Android app, shared DTOs, and backend server in one monorepo. One language means the `/shared` KMP module defines API types once, and both the Android app and Ktor server depend on it. No language boundary between client and server.

* [`/app`](./app/src/main/kotlin) — Android app (Jetpack Compose + Jetpack Glance widget)
* [`/shared`](./shared/src/commonMain/kotlin) — KMP module: API DTOs shared between app and server
* [`/server`](./server/src/main/kotlin) — Ktor server (JVM), backed by PostgreSQL

## Architecture

**Offline-first:** the app writes to Room (SQLite) on-device first and syncs to Postgres in the background via WorkManager. The homescreen widget (Jetpack Glance) reads Room directly — instant and no network needed.

**Two sources of truth:** calorie in/out balance gives fast daily feedback; body weight is the weekly reality check. When they disagree over weeks, the scale wins and the calorie math recalibrates. See [math model](docs/math-model.md).

**Low-friction logging:** fixed meals (breakfast, lunch) are one-tap templates. Only dinner — the variable meal — needs per-ingredient logging. Configurable shortcuts handle drinks and snacks. The widget surfaces the same shortcuts for truly in-the-moment logging.

**Food data pipeline:** a weekly Open Food Facts Netherlands export is imported into Postgres with full-text search. The app queries the server — it never calls OFD directly, which keeps the app free of rate-limit exposure and gives fast autocomplete.

**Polar integration:** hourly cron pull via the AccessLink REST API (hand-written Ktor client, no code generation). Polar access tokens are permanent and encrypted at the application layer (AES-256-GCM) before hitting the database. See [polar sync spec](docs/specs/polar-sync.md).

## Documentation

- [Ubiquitous language](docs/ubiquitous-language.md) — canonical domain terms used throughout the
  codebase, plus a naming-divergence TODO list
- [Domain model](docs/domain-model.md) — bounded contexts, aggregates, value objects, domain
  services, and a context map
- [Feature backlog](docs/feature-backlog.md) — stack-ranked product backlog; each story is an
  end-to-end vertical slice
- [API design](docs/api-design.md) — server endpoints and DTO shapes
- [Math model](docs/math-model.md) — algorithms behind the daily budget, weight trend, weekly
  verdict, and insights
- [Security](docs/security.md) — auth, rate limiting, Polar token handling
- [UX principles](docs/ux/1-principles.md) — the design philosophy behind every interaction decision
- [UX scenarios](docs/ux/2-scenarios.md) — concrete user flows from trigger to outcome

## Running the apps

Use the run configurations provided by the run widget in your IDE’s toolbar. You can also use these
commands and options:

- Android app: `./gradlew :app:assembleDebug`
- Server: `set -a; source .env; set +a; ./gradlew :server:run`

## Running tests

Use the run button in your IDE’s editor gutter, or run tests using Gradle tasks:

**Server unit + integration tests** (requires local Docker Postgres running):

```bash
./gradlew :server:test
```

**App component tests** (Robolectric — no device needed):

```bash
./gradlew :app:testDebugUnitTest
```

**API tests against local server** (start the server first):

```bash
set -a; source .env; set +a
./gradlew :server:run &   # or start via IDE
API_TEST_SERVER_URL=http://localhost:8080 ./gradlew :server:apiTest
```

**API tests against production:**

```bash
./gradlew :server:apiTest   # picks up API_TEST_* vars from .env automatically
```

**All non-device tests:**

```bash
./gradlew :server:test :app:testDebugUnitTest
```

## Setting up development environment

- Install Docker + Docker Compose:  
  `brew install colima docker docker-compose && colima start`
- Run PostgreSQL via Docker Compose: `docker compose up -d postgres postgres-mcp`
- Install Android Studio via JetBrains Toolbox.
- Install the `Claude Code [Beta]` plugin in Android Studio.
- Add `$HOME/Library/Android/sdk/platform-tools` to your path.
- Create the following virtual device in the Android Studio Device Manager:
  ```
  Virtual Device: Pixel 6a
  API: 34 "UpsideDownCake"; Android 14.0
  Services: Android Open Source
  ```
- Add to `local.properties` to point the app at the local server instead of production:  
  `server.baseUrl=http://10.0.2.2:8080`  
  (`10.0.2.2` is the emulator's alias for `localhost`; use your LAN IP for a real device.)
- Connect Postgres MCP server to Claude:  
  `claude mcp add --transport sse postgres http://localhost:8001/sse`
- Add [Mobile-MCP](https://github.com/mobile-next/mobile-mcp#readme) to Claude:  
  `claude mcp add mobile-mcp -- npx -y @mobilenext/mobile-mcp@latest`
- Add [Superpowers](https://github.com/obra/superpowers#readme) to Claude:  
  `claude plugin install superpowers@claude-plugins-official`
- Install Ansible
- Install bcrypt (used by the Ansible playbook to BCrypt-hash passwords locally):  
  `pip3 install --break-system-packages bcrypt`
- Store the Ansible vault password in macOS Keychain:  
  `security add-generic-password -a ansible-vault -s health -w 'your-vault-password'`
- Create a retrieval script so Ansible reads it from Keychain automatically:  
  `printf '#!/bin/bash\nsecurity find-generic-password -a ansible-vault -s health -w\n' > ~/.ansible-vault-pass.sh && chmod 700 ~/.ansible-vault-pass.sh`
- Point Ansible at the script (add to `~/.bashrc`):  
  `export ANSIBLE_VAULT_PASSWORD_FILE=~/.ansible-vault-pass.sh`

## Local database

Schema is managed by Flyway and applied automatically when the server starts.

Start a fresh database:

```
docker compose up -d postgres postgres-mcp
./gradlew :server:run   # Flyway creates all tables on first run
```

Load seed data (optional, after server has started):

```
psql $DATABASE_URL < local-db-seed/seed_data.sql
```

Reset and reload from scratch:

```
docker compose down -v && docker compose up -d postgres postgres-mcp
./gradlew :server:run
psql $DATABASE_URL < local-db-seed/seed_data.sql
```

## Other useful stuff for deployment observability:

```bash
# github actions status - is my pipeline finished yet?
watch -n 2 --color "GH_FORCE_TTY=true gh run list --limit 5"

# watchtower logs - did my new image deploy yet?
watch -n 2 "ssh deploy@api.health.bran.name docker logs health-watchtower-1 --tail 20"
```

## Deploying to device via ADB over Wi-Fi

A wireless alternative to manual APK transfers. Works on the same local network.

### One-time setup

1. On the phone: **Settings → Developer Options → Wireless debugging** → enable it
2. Tap into the Wireless debugging screen and note the IP address and port shown
3. Tap **Pair device with pairing code** and note the separate pairing port and 6-digit code
4. On your Mac:

  ```bash
  # Pair (one-time per machine)
  adb pair <ip>:<pairing-port>
  # enter the 6-digit code shown on the phone when prompted
  adb connect <ip>:<connect-port>
  # install only for user in primary profile
  adb -s <ip>:<connect-port> install --user 0 app/build/outputs/apk/debug/app-debug.apk
  ```
