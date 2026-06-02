# VPS Deployment Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Get the full deployment pipeline running — code pushed to `main` automatically builds, pushes to ghcr.io, and deploys to the Hetzner VPS via Watchtower.

**Architecture:** GitHub Actions builds a Docker image from the Ktor server and pushes it to ghcr.io with `:latest` and git SHA tags. Watchtower on the VPS polls ghcr.io every 60 seconds and restarts the Ktor container on new images. Caddy handles TLS automatically via Let's Encrypt. Flyway runs migrations on app startup before Exposed connects to the DB.

**Tech Stack:** Kotlin/Ktor, Flyway 10.22.0, Docker (multi-stage, eclipse-temurin:21), Docker Compose, Caddy 2, Watchtower, GitHub Actions, ghcr.io, Hetzner CPX22/Debian 12.

**Reference:** `docs/specs/2026-06-02-vps-deployment-design.md` for all config snippets.

---

## File Map

| File | Action | Purpose |
|------|--------|---------|
| `gradle/libs.versions.toml` | Modify | Add Flyway version + library entries |
| `server/build.gradle.kts` | Modify | Add Flyway dependencies |
| `server/src/main/resources/db/migration/V1__initial_schema.sql` | Create | Full Postgres schema (Flyway migration) |
| `server/src/main/kotlin/org/branneman/health/Application.kt` | Modify | Wire Flyway before Exposed connects |
| `server/Dockerfile` | Create | Multi-stage JDK build → slim JRE image |
| `docker-compose.yml` | Modify | Production services (ktor, postgres, caddy, watchtower) |
| `docker-compose.override.yml` | Create | Dev-only additions (postgres-mcp, port exposure, init mount) |
| `Caddyfile` | Create | TLS + reverse proxy to ktor:8080 |
| `.env.example` | Modify | Add DATABASE_URL, API_DOMAIN, STORAGE_BOX_* |
| `.github/workflows/deploy.yml` | Create | Build + push Docker image on push to main |
| `ansible/playbook.yml` | Create | Idempotent VPS app setup |
| `ansible/inventory.yml` | Create | VPS host + connection config |
| `ansible/requirements.yml` | Create | ansible-galaxy collection deps |
| `ansible/templates/env.j2` | Create | .env template (vars from vault) |
| `ansible/templates/backup.sh.j2` | Create | Backup script template |
| `ansible/vars/vault.yml` | Create (local, git-ignored) | Encrypted secrets |

---

## Task 1: Add Flyway to version catalog and server build

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `server/build.gradle.kts`

- [ ] **Step 1: Add Flyway to version catalog**

In `gradle/libs.versions.toml`, add to `[versions]`:
```toml
flyway = "10.22.0"
```

Add to `[libraries]`:
```toml
flyway-core = { module = "org.flywaydb:flyway-core", version.ref = "flyway" }
flyway-postgres = { module = "org.flywaydb:flyway-database-postgresql", version.ref = "flyway" }
```

- [ ] **Step 2: Add Flyway to server dependencies**

In `server/build.gradle.kts`, add inside the `dependencies { }` block (after `implementation(libs.hikari)`):
```kotlin
implementation(libs.flyway.core)
implementation(libs.flyway.postgres)
```

- [ ] **Step 3: Verify the project compiles**

```bash
./gradlew :server:build
```
Expected: `BUILD SUCCESSFUL`. If Gradle complains about `libs.flyway.core` not found, re-check the alias name — the version catalog converts hyphens to dots in accessor names, so `flyway-core` becomes `libs.flyway.core`.

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml server/build.gradle.kts
git commit -m "feat: add flyway dependency to server"
```

---

## Task 2: Create V1 migration file

**Files:**
- Create: `server/src/main/resources/db/migration/V1__initial_schema.sql`

The full schema lives in `docs/specs/2026-06-01-database-schema-design.md`. This migration supersedes `init/01_schema.sql` (which only has `body_weight`). Never edit this file once it has been applied — add `V2__...` for future changes.

- [ ] **Step 1: Create the migration directory and file**

Create `server/src/main/resources/db/migration/V1__initial_schema.sql` with this content (copied and consolidated from the schema design spec):

```sql
CREATE TABLE body_weight (
    id   UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    date DATE          NOT NULL UNIQUE,
    kg   NUMERIC(5, 2) NOT NULL
);

CREATE TABLE daily_energy (
    date         DATE    PRIMARY KEY,
    bmr_kcal     INTEGER NOT NULL,
    active_kcal  INTEGER NOT NULL,
    total_kcal   INTEGER NOT NULL,
    steps        INTEGER,
    source       TEXT    NOT NULL DEFAULT 'polar'
);

CREATE TABLE workout (
    id            UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
    date          DATE    NOT NULL,
    type          TEXT    NOT NULL,
    duration_secs INTEGER,
    avg_hr        INTEGER,
    kcal          INTEGER
);

CREATE TABLE food_item (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    barcode          TEXT         UNIQUE,
    name             TEXT         NOT NULL,
    kcal_per_100g    NUMERIC(7,2) NOT NULL,
    protein_per_100g NUMERIC(7,2),
    carbs_per_100g   NUMERIC(7,2),
    fat_per_100g     NUMERIC(7,2),
    source           TEXT         NOT NULL DEFAULT 'openfoodfacts'
);

CREATE TABLE meal_template (
    id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL UNIQUE
);

CREATE TABLE meal_template_item (
    template_id  UUID         NOT NULL REFERENCES meal_template(id) ON DELETE CASCADE,
    food_item_id UUID         NOT NULL REFERENCES food_item(id),
    grams        NUMERIC(7,1) NOT NULL,
    PRIMARY KEY (template_id, food_item_id)
);

CREATE TYPE meal_type AS ENUM ('breakfast', 'lunch', 'dinner', 'snack');

CREATE TABLE log_entry (
    id        UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    logged_at TIMESTAMPTZ NOT NULL,
    meal_type meal_type   NOT NULL
);

CREATE TABLE log_entry_item (
    log_entry_id     UUID         NOT NULL REFERENCES log_entry(id) ON DELETE CASCADE,
    food_item_id     UUID         NOT NULL REFERENCES food_item(id),
    grams            NUMERIC(7,1) NOT NULL,
    kcal_per_100g    NUMERIC(7,2) NOT NULL,
    protein_per_100g NUMERIC(7,2),
    carbs_per_100g   NUMERIC(7,2),
    fat_per_100g     NUMERIC(7,2),
    PRIMARY KEY (log_entry_id, food_item_id)
);

CREATE TABLE polar_auth (
    user_id      TEXT        PRIMARY KEY,
    access_token TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ON log_entry (logged_at);
CREATE INDEX ON workout    (date);
```

- [ ] **Step 2: Commit**

```bash
git add server/src/main/resources/db/migration/V1__initial_schema.sql
git commit -m "feat: add V1 flyway migration with full schema"
```

---

## Task 3: Wire Flyway into Application.kt

**Files:**
- Modify: `server/src/main/kotlin/org/branneman/health/Application.kt`

- [ ] **Step 1: Add Flyway import and migrate() call**

In `Application.kt`, add the import at the top:
```kotlin
import org.flywaydb.core.Flyway
```

Add the `Flyway.migrate()` call at the very start of `Application.module()`, before the `Database.connect(...)` call (the env vars `dbUrl`, `dbUser`, `dbPassword` are already read above it):

```kotlin
fun Application.module() {
    val dbUrl = System.getenv("DATABASE_URL") ?: error("DATABASE_URL not set")
    val dbUser = System.getenv("POSTGRES_USER") ?: error("POSTGRES_USER not set")
    val dbPassword = System.getenv("POSTGRES_PASSWORD") ?: error("POSTGRES_PASSWORD not set")
    val apiUser = System.getenv("API_USER") ?: error("API_USER not set")
    val apiPassword = System.getenv("API_PASSWORD") ?: error("API_PASSWORD not set")

    Flyway.configure()
        .dataSource(dbUrl, dbUser, dbPassword)
        .load()
        .migrate()

    Database.connect(HikariDataSource(HikariConfig().apply {
        // ... rest unchanged
```

- [ ] **Step 2: Run existing tests to confirm nothing broke**

```bash
./gradlew :server:test
```
Expected: `BUILD SUCCESSFUL`. The existing `ApplicationTest` uses a stub routing block and never calls `Application.module()`, so it does not need a real DB — it will still pass.

- [ ] **Step 3: Smoke test with a real database**

Start the local DB (if not already running):
```bash
docker compose up -d postgres
```

Run the server:
```bash
set -a; source .env; set +a
./gradlew :server:run
```

Expected in the logs: lines like `Flyway ... Successfully applied 1 migration`. If you see `Schema "public" is up to date. No migration necessary.` that's also fine — it means Flyway already ran.

Stop the server with Ctrl+C.

- [ ] **Step 4: Commit**

```bash
git add server/src/main/kotlin/org/branneman/health/Application.kt
git commit -m "feat: run flyway migrations on server startup"
```

---

## Task 4: Create the server Dockerfile

**Files:**
- Create: `server/Dockerfile`

- [ ] **Step 1: Create the Dockerfile**

Create `server/Dockerfile`:
```dockerfile
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build
COPY . .
RUN ./gradlew :server:installDist --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /build/server/build/install/server .
CMD ["bin/server"]
```

- [ ] **Step 2: Build the image locally to verify**

Run from the repo root (the `context: .` in the compose file means the build context is the whole repo):
```bash
docker build -t health-server-test -f server/Dockerfile .
```
Expected: `Successfully tagged health-server-test:latest`. First build is slow (downloads JDK layer + runs Gradle). Subsequent builds use the layer cache.

- [ ] **Step 3: Commit**

```bash
git add server/Dockerfile
git commit -m "feat: add multi-stage dockerfile for ktor server"
```

---

## Task 5: Restructure docker-compose + create override + update .env.example

**Files:**
- Modify: `docker-compose.yml`
- Create: `docker-compose.override.yml`
- Modify: `.env.example`

- [ ] **Step 1: Replace docker-compose.yml with production config**

Replace the full contents of `docker-compose.yml`:
```yaml
services:
  ktor:
    image: ghcr.io/branvandemeer/health-server:latest
    restart: unless-stopped
    env_file: .env
    depends_on:
      postgres:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:8080/"]
      interval: 10s
      timeout: 5s
      retries: 3
      start_period: 30s

  postgres:
    image: postgres:17-alpine
    container_name: health_postgres
    restart: unless-stopped
    environment:
      POSTGRES_USER: ${POSTGRES_USER}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
      POSTGRES_DB: ${POSTGRES_DB}
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER} -d ${POSTGRES_DB}"]
      interval: 10s
      timeout: 5s
      retries: 5

  caddy:
    image: caddy:2
    restart: unless-stopped
    ports: ["80:80", "443:443", "443:443/udp"]
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile
      - caddy_data:/data
    depends_on:
      ktor:
        condition: service_healthy

  watchtower:
    image: containrrr/watchtower
    restart: unless-stopped
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
    command: --interval 60 --cleanup

volumes:
  pgdata: {}
  caddy_data: {}
```

- [ ] **Step 2: Create docker-compose.override.yml**

The init/ mount is **not** included here. `init/01_schema.sql` creates `body_weight`, but Flyway V1 also creates it — mounting both would cause a conflict when the server runs locally. Flyway is the sole schema owner; the `init/` directory is kept for reference only.

Create `docker-compose.override.yml`:
```yaml
services:
  postgres:
    ports:
      - "${POSTGRES_PORT:-5432}:5432"

  postgres-mcp:
    image: crystaldba/postgres-mcp
    restart: unless-stopped
    environment:
      DATABASE_URI: postgresql://${POSTGRES_USER}:${POSTGRES_PASSWORD}@postgres:5432/${POSTGRES_DB}
    command: ["--access-mode=unrestricted", "--transport=sse", "--sse-port=8001"]
    ports:
      - "127.0.0.1:8001:8001"
    depends_on:
      postgres:
        condition: service_healthy
```

- [ ] **Step 3: Update .env.example**

Replace the full contents of `.env.example`:
```
PROJECT_NAME=health

API_USER=health
API_PASSWORD=secret

POSTGRES_USER=health
POSTGRES_PASSWORD=secret
POSTGRES_DB=health
POSTGRES_PORT=5432

DATABASE_URL=jdbc:postgresql://postgres:5432/health

API_DOMAIN=api.health.bran.name

STORAGE_BOX_USER=uXXXXXX
STORAGE_BOX_HOST=uXXXXXX.your-storagebox.de
```

- [ ] **Step 4: Update readme.md**

The compose restructure changes two commands in `readme.md`. Update:

```markdown
## Setting up development environment
...
- Run PostgreSQL via Docker Compose: `docker compose up -d postgres postgres-mcp`
```

And the reset section at the bottom:
```markdown
## Re-applying schema changes

`docker compose down -v && docker compose up -d postgres postgres-mcp`

Schema is applied automatically by Flyway when the server starts (`./gradlew :server:run`).
```

- [ ] **Step 5: Verify the local dev stack still starts**

```bash
docker compose up -d postgres postgres-mcp
```
Expected: only `health_postgres` and `health_mcp` start. Verify:
```bash
docker compose ps
```
Expected: two services running; ktor/caddy/watchtower not present.

- [ ] **Step 6: Commit**

```bash
git add docker-compose.yml docker-compose.override.yml .env.example readme.md
git commit -m "feat: split docker-compose into prod and dev-override"
```

---

## Task 6: Create Caddyfile

**Files:**
- Create: `Caddyfile`

- [ ] **Step 1: Create Caddyfile**

Create `Caddyfile` in the repo root:
```
{env.API_DOMAIN} {
    reverse_proxy ktor:8080
}
```

The domain is read from the `API_DOMAIN` env var so the file is safe to commit. On the VPS `.env` has `API_DOMAIN=api.health.bran.name`.

- [ ] **Step 2: Commit**

```bash
git add Caddyfile
git commit -m "feat: add caddyfile for tls + reverse proxy"
```

---

## Task 7: Create GitHub Actions workflow

**Files:**
- Create: `.github/workflows/deploy.yml`

- [ ] **Step 1: Create the workflow directory and file**

```bash
mkdir -p .github/workflows
```

Create `.github/workflows/deploy.yml`:
```yaml
name: deploy
on:
  push:
    branches: [main]

jobs:
  build-push:
    runs-on: ubuntu-latest
    permissions:
      contents: read
      packages: write
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          java-version: 21
          distribution: temurin

      - uses: gradle/actions/setup-gradle@v4

      - uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - uses: docker/build-push-action@v6
        with:
          context: .
          file: server/Dockerfile
          push: true
          tags: |
            ghcr.io/branvandemeer/health-server:latest
            ghcr.io/branvandemeer/health-server:${{ github.sha }}
```

- [ ] **Step 2: Commit and push**

```bash
git add .github/workflows/deploy.yml
git commit -m "feat: add github actions workflow to build and push docker image"
git push
```

- [ ] **Step 3: Verify the workflow runs**

Go to the repo on GitHub → Actions tab. The `deploy` workflow should be running. Wait for it to complete (~5–10 min first time, faster after Gradle cache warms up). Expected: green checkmark.

If it fails, check the logs — common issues:
- Gradle wrapper not executable: add `chmod +x gradlew` as a step before the Gradle steps
- Package visibility: after the first successful push, go to GitHub → Packages → `health-server` → Package settings → Change visibility to **Public**

---

## Task 8: Create Ansible playbook

**Files:**
- Create: `ansible/playbook.yml`
- Create: `ansible/inventory.yml`
- Create: `ansible/requirements.yml`
- Create: `ansible/templates/env.j2`
- Create: `ansible/templates/backup.sh.j2`
- Modify: `.gitignore`

- [ ] **Step 1: Create ansible/ directory structure**

```bash
mkdir -p ansible/templates ansible/vars
```

- [ ] **Step 2: Create ansible/inventory.yml**

```yaml
all:
  hosts:
    health-vps:
      ansible_host: api.health.bran.name
      ansible_user: deploy
      ansible_ssh_private_key_file: ~/.ssh/id_ed25519
```

- [ ] **Step 3: Create ansible/requirements.yml**

```yaml
collections:
  - name: community.docker
    version: ">=3.0.0"
  - name: community.crypto
    version: ">=2.0.0"
```

- [ ] **Step 4: Create ansible/playbook.yml**

```yaml
- hosts: all
  vars_files:
    - vars/vault.yml
  tasks:
    - name: Create app directory
      file:
        path: /home/deploy/health
        state: directory
        owner: deploy
        group: deploy
        mode: "0755"

    - name: Copy docker-compose.yml
      copy:
        src: ../docker-compose.yml
        dest: /home/deploy/health/docker-compose.yml
        owner: deploy
        group: deploy

    - name: Copy Caddyfile
      copy:
        src: ../Caddyfile
        dest: /home/deploy/health/Caddyfile
        owner: deploy
        group: deploy

    - name: Template .env
      template:
        src: templates/env.j2
        dest: /home/deploy/health/.env
        owner: deploy
        group: deploy
        mode: "0600"

    - name: Create backups directory
      file:
        path: /home/deploy/backups
        state: directory
        owner: deploy
        group: deploy

    - name: Generate Storage Box SSH key
      community.crypto.openssh_keypair:
        path: /home/deploy/.ssh/storagebox_key
        type: ed25519
        owner: deploy
        group: deploy

    - name: Read Storage Box public key
      command: cat /home/deploy/.ssh/storagebox_key.pub
      register: storagebox_pubkey
      changed_when: false

    - name: Show Storage Box public key (add this to Hetzner Robot panel)
      debug:
        msg: "{{ storagebox_pubkey.stdout }}"

    - name: Template backup script
      template:
        src: templates/backup.sh.j2
        dest: /home/deploy/backup.sh
        owner: deploy
        group: deploy
        mode: "0755"

    - name: Add nightly backup cron
      cron:
        name: nightly postgres backup
        minute: "0"
        hour: "2"
        job: /home/deploy/backup.sh >> /var/log/health-backup.log 2>&1
        user: deploy

    - name: Start docker compose stack
      community.docker.docker_compose_v2:
        project_src: /home/deploy/health
        state: present
```

- [ ] **Step 5: Create ansible/templates/env.j2**

```
PROJECT_NAME=health

API_USER=health
API_PASSWORD={{ api_password }}

POSTGRES_USER=health
POSTGRES_PASSWORD={{ postgres_password }}
POSTGRES_DB=health

DATABASE_URL=jdbc:postgresql://postgres:5432/health

API_DOMAIN=api.health.bran.name

STORAGE_BOX_USER={{ storage_box_user }}
STORAGE_BOX_HOST={{ storage_box_host }}
```

- [ ] **Step 6: Create ansible/templates/backup.sh.j2**

```bash
#!/usr/bin/env bash
set -euo pipefail
source /home/deploy/health/.env

BACKUP_FILE="health_$(date +%Y%m%d_%H%M%S).sql.gz"
BACKUP_DIR="/home/deploy/backups"
RETENTION_DAYS=30
SSH_OPTS="-p 23 -i /home/deploy/.ssh/storagebox_key"
STORAGE_BOX="${STORAGE_BOX_USER}@${STORAGE_BOX_HOST}"

mkdir -p "$BACKUP_DIR"

docker exec health_postgres \
    pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB" \
    | gzip > "$BACKUP_DIR/$BACKUP_FILE"

rsync -e "ssh $SSH_OPTS" \
    "$BACKUP_DIR/$BACKUP_FILE" \
    "${STORAGE_BOX}:backups/"

ssh $SSH_OPTS "$STORAGE_BOX" \
    "find backups/ -name '*.sql.gz' -mtime +$RETENTION_DAYS -delete"

find "$BACKUP_DIR" -name "*.sql.gz" -mtime +$RETENTION_DAYS -delete
```

- [ ] **Step 7: Add vault.yml to .gitignore**

Add to `.gitignore`:
```
ansible/vars/vault.yml
```

- [ ] **Step 8: Commit**

```bash
git add ansible/ .gitignore
git commit -m "feat: add ansible playbook for vps app setup"
```

---

## Task 9: Deploy to VPS via Ansible

This task runs from your local machine. No code changes.

- [ ] **Step 1: Install Ansible collections**

```bash
ansible-galaxy collection install -r ansible/requirements.yml
```

- [ ] **Step 2: Create the vault file with real secrets**

```bash
ansible-vault create ansible/vars/vault.yml
```

Enter a vault password when prompted. Add these contents:
```yaml
api_password: "generate-with: openssl rand -base64 32"
postgres_password: "generate-with: openssl rand -base64 32"
storage_box_user: "uXXXXXX"
storage_box_host: "uXXXXXX.your-storagebox.de"
```

- [ ] **Step 3: Add DNS record**

In your DNS provider for `bran.name`, add an A record:
```
api.health.bran.name → <VPS IP>
```
Verify propagation before continuing:
```bash
dig api.health.bran.name +short
```
Expected: the VPS IP.

- [ ] **Step 4: Set ghcr.io package to public**

GitHub → your profile → Packages → `health-server` → Package settings → Change visibility to **Public**. Do this after Task 7 (GitHub Actions) has pushed the first image.

- [ ] **Step 5: Run the playbook**

```bash
ansible-playbook ansible/playbook.yml -i ansible/inventory.yml --ask-vault-pass
```

Expected: all tasks green or yellow (changed). If any task fails, Ansible prints the error with full context — fix and re-run (idempotent, safe to repeat).

When the `Show Storage Box public key` task runs, copy the printed key and add it in Hetzner Robot → Storage Box → SSH Keys before the next run (needed for backups to work).

- [ ] **Step 6: Verify the stack is up**

```bash
ssh deploy@api.health.bran.name "docker compose -f /home/deploy/health/docker-compose.yml ps"
```
Expected: all four services running. Ktor may still be starting — Watchtower pulls the image within 60 seconds of it being pushed.

- [ ] **Step 7: Verify the API is reachable**

```bash
curl https://api.health.bran.name/
```
Expected: `OK`

- [ ] **Step 8: Test the backup script**

```bash
ssh deploy@api.health.bran.name "/home/deploy/backup.sh"
```
Expected: no errors. Verify a `.sql.gz` file appears in `/home/deploy/backups/` on the VPS and under `backups/` on the Storage Box.
