# Server Status Endpoint — Design Spec

Story: 29 (Status endpoint). One authenticated endpoint that answers "is the system
actually working", rather than "is the process alive".

## Why this exists

After the 2026-08-24 infra upgrade (76s planned downtime) two green signals were
checked, and neither proved what it appeared to:

- `GET /version` and `GET /server-health` both returned 200. Both are **hardcoded
  literals** — neither touches Postgres. A wiped database returns the same 200.
- The app's "Sync now" reported success and updated its "last synced" timestamp.
  `SyncWorker` wraps the Polar call in `runCatching { … }.onFailure { Log.w(…) }` and
  writes `saveLastSyncedAt(…)` unconditionally, so that timestamp updates whether the
  server-side Polar sync succeeded or threw a 500.

Confirming Polar sync had actually run required comparing Postgres `xmin` transaction
ids against a row with a known wall-clock time. That is not a repeatable operational
procedure. This story replaces it with a single authenticated request.

The failure mode is not hypothetical: `PolarSyncService` carries a comment recording
that a swallowed exception there hid a five-week outage.

## Decisions

1. **`/server-health` stays a hardcoded literal — do not add a DB check to it.**
   It is not a monitoring endpoint; it is the Docker healthcheck that gates Caddy
   (`caddy: depends_on: ktor: condition: service_healthy`). Coupling it to Postgres
   creates a startup cascade: on any `compose up`, a slow migration or recovery could
   fail the check past `start_period: 30s` + 3 retries, ktor never becomes healthy,
   **Caddy never starts, and the site loses TLS entirely** — during exactly the window
   where downtime is already elevated. Liveness and readiness stay separate signals.

2. **Authenticated, not public.** An unauthenticated `/status` would disclose
   operational detail (integration inventory, sync recency, migration version, row
   counts) for an app with no public audience, and would be a DoS amplifier — each
   request costs DB queries. Guarded by the existing `X-Admin-Secret` header, the same
   mechanism as `/admin/ofd-import`; the secret is already in `.env` on the box and is
   trivial to use from `curl` or a scheduled check.

3. **No live outbound calls to Polar or Anthropic.** Report *stored* state instead.
   Live-calling third parties would let a slow vendor hang the status endpoint, burn
   Polar rate limit (`PolarRateLimitException` is a real path), and turn one request
   into several outbound. Freshness of stored state answers the same question.

4. **The endpoint never throws.** A failing check reports its own `"down"` status with
   a short reason; the response is still 200 with an aggregate `status`. A monitoring
   endpoint that 500s tells you less than one that reports which leg is broken. Every
   check is individually timeout-bounded, and the payload is cached ~10s so repeated
   polling cannot amplify load.

5. **Server-only story, no app UI.** Accepted exception to the vertical-slice rule, with
   precedent in story 14 (OFD import pipeline). The consumer is an operator with `curl`
   or a scheduled check, not the user.

## Schema change — `polar_sync_state`

The core enabler. Nothing currently records that a Polar sync happened: `daily_energy`
has no `updated_at`, and a successful sync logs nothing. New table (V14 migration):

| column           | type          | note                                        |
|------------------|---------------|---------------------------------------------|
| `health_user_id` | `UUID` PK     | FK to `users.id`                            |
| `last_success_at`| `timestamptz` | set on every successful `syncUser`          |
| `last_error_at`  | `timestamptz` | set when a sync attempt fails               |
| `last_error`     | `text`        | truncated class + message, **never** a token |

Written by `PolarSyncService` on both paths (`syncAll` hourly loop and `syncForUser`
from `POST /polar/sync`). This alone closes the silent-outage class: `last_success_at`
going stale is visible, where a swallowed exception was not.

`last_error` must never contain the decrypted access token or the request. The existing
comment in `PolarSyncService` about not widening the log applies verbatim here.

## Backup visibility — `ops_heartbeat`

"Are backups still running" was one of the most valuable post-upgrade checks and is
invisible from inside the container (the backup script and its offsite `rsync` run on
the host). `backup-health.sh` writes a heartbeat row after a **successful** rsync:

| column            | type          |
|-------------------|---------------|
| `name`            | `text` PK     |
| `last_success_at` | `timestamptz` |

Requires a host-side change to the templated backup script. The Ansible in this repo is
currently out of sync with the box (paths moved to `/home/deploy/apps/health` and
`/home/deploy/bin/backup-health.sh`); that reconciliation is a separate PR and is a
prerequisite for this part of the story.

## Payload

`GET /status` with `X-Admin-Secret` → `200`:

```json
{
  "status": "degraded",
  "sha": "b04a5ba9d0f3d03d9b71b4cc777ed3f90b05da5e",
  "uptimeSeconds": 4231,
  "checks": [
    { "name": "db",         "status": "ok",       "latencyMs": 3 },
    { "name": "migrations", "status": "ok",       "version": "13", "applied": 13, "failed": 0 },
    { "name": "polar",      "status": "degraded", "connectedUsers": 1, "lastSuccessAt": "2026-08-23T20:28:11Z", "staleHours": 26 },
    { "name": "ofd",        "status": "ok",       "lastDeltaEndTs": 1756072200, "productCount": 31730 },
    { "name": "ai",         "status": "ok",       "configured": true },
    { "name": "backup",     "status": "ok",       "lastSuccessAt": "2026-08-24T02:00:02Z", "ageHours": 6 }
  ]
}
```

Without the header → `401` and no body.

**Aggregate rule:** `down` if `db` is down; otherwise `degraded` if any check is
degraded or down; otherwise `ok`.

**Staleness thresholds** (each check reports `degraded`, never `down`, when stale):

| check  | source                                          | degraded when         |
|--------|-------------------------------------------------|-----------------------|
| polar  | `polar_sync_state.last_success_at`              | > 3h (hourly loop)    |
| ofd    | `catalog.import_state.last_delta_end_ts`        | > 36h (daily 02:30)   |
| backup | `ops_heartbeat.last_success_at`                 | > 30h (nightly 02:00) |

`migrations` reads `flyway_schema_history`: `degraded` if any row has `success = false`.
`ai` reports only whether the cipher and config are present — a boolean, never key
material.

## Deliberately out of scope

Host-level facts the JVM cannot see, and which do not belong in an app endpoint:
disk usage, container health, crontab presence, TLS expiry, offsite backup
verification, publicly bound ports. These stay a documented runbook check, or a
future external-uptime story. The natural pairing is an external monitor hitting
`/server-health` for liveness and a scheduled job hitting `/status` for depth.

## Security notes

- Never expose: access tokens (encrypted or not), key material, usernames or emails,
  row-level user data, or stack traces. Counts are aggregate only.
- `OfdAdminRoute` compares the secret with `!=`. Extract a shared helper using a
  constant-time comparison and use it for both routes — small hardening, same story.

## Testing

- `:server:test` — status aggregation over faked check results: db down ⇒ `down`;
  one stale check ⇒ `degraded`; all fresh ⇒ `ok`; a check that throws is reported as
  down rather than propagating.
- `:server:apiTest` — `/status` without the header ⇒ 401; with it ⇒ 200, `db` ok,
  `migrations` version non-blank. Mirrors the existing auth-shape tests.
