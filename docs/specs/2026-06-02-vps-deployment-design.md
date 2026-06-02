# VPS Deployment Design

**Date:** 2026-06-02
**Scope:** Full CD pipeline — server bootstrap (cloud-init), Docker image build, GitHub Actions,
Hetzner VPS, TLS, Flyway migrations, nightly backups.

---

## Architecture Overview

```
GitHub (repo — currently private, going public later)
  └─ push to main
       └─ GitHub Actions
            ├─ Multi-stage Docker build (Gradle installDist → slim JRE)
            └─ Push → ghcr.io/branvandemeer/health-backend:latest
                                                    :git-sha

Hetzner CPX22 (Debian 12, EU)
  └─ docker compose (4 services):
       ├─ caddy      — TLS termination + reverse proxy → ktor:8080
       ├─ ktor       — Ktor server (image from ghcr.io)
       ├─ postgres   — PostgreSQL 17, data on named volume
       └─ watchtower — polls ghcr.io every 60s, restarts ktor on new image

DNS: api.health.bran.name → Hetzner VPS IP
Caddy: automatic Let's Encrypt cert (no manual cert management)
Postgres: never exposed outside the Docker network
Secrets: .env on VPS only — never in git
```

CI never touches the server. The only credential GitHub holds is the auto-injected
`GITHUB_TOKEN` (repo-scoped, not long-lived). Watchtower needs no credentials because the
ghcr.io package is set to **public** visibility (the image contains no secrets — those live
in `.env` on the server).

**Rollback:** images are tagged with both `:latest` and the git SHA. To roll back, edit
`image:` in `docker-compose.yml` on the VPS to pin a specific SHA and run
`docker compose up -d ktor`. Watchtower ignores pinned tags.

---

## Docker Artifacts

### `server/Dockerfile`

Multi-stage: Gradle build stage compiles with a full JDK; runtime stage is a slim JRE only.

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

`installDist` produces a `bin/` + `lib/` distribution. The container runs `bin/server` — a
plain JVM process, not Gradle. Docker's `restart: unless-stopped` handles crash recovery.

### `docker-compose.yml` (production — on VPS)

```yaml
services:
  ktor:
    image: ghcr.io/branvandemeer/health-backend:latest
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

### `docker-compose.override.yml` (dev only — auto-merged locally, not on VPS)

```yaml
services:
  postgres:
    ports:
      - "${POSTGRES_PORT:-5432}:5432"
    volumes:
      - ./init:/docker-entrypoint-initdb.d:ro

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

### `Caddyfile`

Domain read from env so the file is safe to commit:

```
{env.API_DOMAIN} {
    reverse_proxy ktor:8080
}
```

---

## GitHub Actions Workflow — `.github/workflows/deploy.yml`

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
            ghcr.io/branvandemeer/health-backend:latest
            ghcr.io/branvandemeer/health-backend:${{ github.sha }}
```

`GITHUB_TOKEN` is auto-injected by GitHub — no PAT or stored secret needed.

**Note on private repo:** GitHub Actions free tier includes 2,000 minutes/month for private
repos (each run ~5 min → ~400 pushes/month headroom). After the first push, set the
`health-backend` package to **public** in GitHub → Packages → Package settings. This lets
Watchtower pull without credentials. If you want fully private images, add a PAT with
`read:packages` to the VPS `.env` as `WATCHTOWER_REGISTRY_1_PASSWORD` and configure
Watchtower accordingly — but public package is simpler and the image contains no secrets.

---

## Flyway Wiring

Migrations run automatically on app startup before Exposed connects.

### `server/build.gradle.kts` additions

```kotlin
implementation("org.flywaydb:flyway-core:10.22.0")
implementation("org.flywaydb:flyway-database-postgresql:10.22.0")
```

### `Application.kt` addition (before `Database.connect(...)`)

```kotlin
Flyway.configure()
    .dataSource(dbUrl, dbUser, dbPassword)
    .load()
    .migrate()
```

### Migration file

```
server/src/main/resources/db/migration/V1__initial_schema.sql
```

Content: the schema currently in `init/01_schema.sql` (minus dummy data).
`init/01_schema.sql` stays for local dev bootstrap (mounted via override file).
Future schema changes: add `V2__...`, `V3__...` — never edit an applied migration.

---

## Environment Variables

**`.env.example`** (committed — no real values):

```
PROJECT_NAME=health

API_USER=health
API_PASSWORD=secret

POSTGRES_USER=health
POSTGRES_PASSWORD=secret
POSTGRES_DB=health
POSTGRES_PORT=5432        # dev only — not used on VPS

DATABASE_URL=jdbc:postgresql://postgres:5432/health

API_DOMAIN=api.health.bran.name

STORAGE_BOX_USER=uXXXXXX
STORAGE_BOX_HOST=uXXXXXX.your-storagebox.de
```

On the VPS the real `.env` lives at `/home/deploy/health/.env`. Never committed.
`POSTGRES_PORT` is a no-op on the VPS (Postgres has no port mapping in production).
`DATABASE_URL` uses the Docker network service name `postgres`, not `localhost`.

---

## Nightly Backups

### One-time setup

Generate a dedicated SSH key on the VPS and add the public key to the Storage Box's
authorized keys:

```bash
ssh-keygen -t ed25519 -f /home/deploy/.ssh/storagebox_key -N ""
# Add the public key to Hetzner Storage Box via the Robot panel or:
ssh-copy-id -i /home/deploy/.ssh/storagebox_key.pub -p 23 uXXXXXX@uXXXXXX.your-storagebox.de
```

### `/home/deploy/backup.sh`

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

# Prune remote copies older than retention period
ssh $SSH_OPTS "$STORAGE_BOX" \
    "find backups/ -name '*.sql.gz' -mtime +$RETENTION_DAYS -delete"

# Prune local copies
find "$BACKUP_DIR" -name "*.sql.gz" -mtime +$RETENTION_DAYS -delete
```

### Cron (as `deploy` user)

```
0 2 * * * /home/deploy/backup.sh >> /var/log/health-backup.log 2>&1
```

### Restore

```bash
gunzip -c health_20260101_020000.sql.gz \
  | docker exec -i health_postgres psql -U "$POSTGRES_USER" "$POSTGRES_DB"
```

---

## Server Bootstrap

The file `cloud-config.yml` in the repo root is pasted into Hetzner's "User data" field when
creating the VPS. It runs once on first boot via cloud-init and covers everything that would
otherwise be a manual hardening step.

What it does:

- Creates the `deploy` user (sudo, SSH key auth only)
- Installs Docker (official Debian repo) and adds `deploy` to the `docker` group
- Installs `fail2ban`, `unattended-upgrades`, `rsync`
- Configures UFW: deny all inbound except SSH, 80/tcp, 443/tcp, 443/udp
- Hardens SSH: `PasswordAuthentication no`, `PermitRootLogin no`
- Enables automatic security updates
- Creates `/home/deploy/health/` and `/home/deploy/backups/`

After the box is provisioned, connect as `deploy` — root login is disabled by cloud-init.

---

## Out of scope

- DNS record creation (`api.health.bran.name → VPS IP`) — manual step before first deploy
- Polar AccessLink integration — separate spec
- Android app / Room sync — separate spec
