# Multi-User + Full Server Sync Design

**Date:** 2026-06-04
**Scope:** Making the app multi-user and syncing all user data to the server so a user can
switch phones, log in, and get their full history back. Offline-first is preserved — the app
writes locally first and syncs in the background, invisibly to the user.

---

## Goals

- Multiple users can have accounts on the same server instance
- All user data is stored per-user on the server (system of record)
- A user can log in on a new phone and restore all history
- Offline-first is preserved: every action writes to Room first; sync is background-only
- One active device at a time (no concurrent multi-device conflict resolution needed)
- Accounts are provisioned by the administrator (no self-registration)

## Non-goals

- Concurrent multi-device use (two phones actively writing simultaneously)
- Real-time cross-device sync (changes on one device appear on another without logout/login)
- Self-registration or invite-link flow

---

## Session model

Multiple concurrent sessions are allowed per user — logging in on a new device does **not**
invalidate the previous device's token. Tokens expire naturally (next 2 AM Amsterdam, per the
existing token auth design). This is intentional: the user simply does not use two devices
simultaneously, so there is no conflict to resolve.

A `401` on any authenticated endpoint (token expired or manually invalidated via logout) clears
the token from DataStore and navigates to the login screen with the message "Your session has
ended — please sign in again." Room is **not** wiped on `401` — pending local data is preserved
for upload on the next login.

---

## Database changes

### V3 — Per-user data scoping (`V3__multi_user.sql`)

Add `user_id` to every data table and re-scope uniqueness constraints.

**Backfill note:** The server already has data from the original single user (
`username = 'health'`).
`ADD COLUMN ... NOT NULL` will fail on non-empty tables without a default. The migration must
backfill existing rows to that user's UUID before adding the `NOT NULL` constraint:

```sql
-- Backfill pattern (repeat for each table):
ALTER TABLE body_weight ADD COLUMN user_id UUID;
UPDATE body_weight SET user_id = (SELECT id FROM users WHERE username = 'health');
ALTER TABLE body_weight ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE body_weight ADD CONSTRAINT ... ;
-- Then add the REFERENCES constraint (separate step, after NOT NULL is set)
ALTER TABLE body_weight ADD FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
```

```sql
-- body_weight
ALTER TABLE body_weight
  ADD COLUMN user_id    UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  DROP CONSTRAINT body_weight_date_key,
  ADD CONSTRAINT body_weight_user_date UNIQUE (user_id, date);

-- daily_energy: date was PK; becomes compound PK
ALTER TABLE daily_energy
  ADD COLUMN user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE daily_energy DROP CONSTRAINT daily_energy_pkey;
ALTER TABLE daily_energy ADD PRIMARY KEY (user_id, date);

-- workout
ALTER TABLE workout
  ADD COLUMN user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE;

-- log_entry: add user_id, timestamps, and quick-add columns
ALTER TABLE log_entry
  ADD COLUMN user_id         UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  ADD COLUMN created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  ADD COLUMN quick_add_kcal  INTEGER,
  ADD COLUMN quick_add_label TEXT;
-- quick_add_kcal non-null = quick-add entry; log_entry_item rows absent in that case

-- meal_template: name was globally unique; now unique per user
ALTER TABLE meal_template
  ADD COLUMN user_id    UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  DROP CONSTRAINT meal_template_name_key,
  ADD CONSTRAINT meal_template_user_name UNIQUE (user_id, name);

-- food_item: per-user catalog
ALTER TABLE food_item
  ADD COLUMN user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  DROP CONSTRAINT food_item_barcode_key,
  ADD CONSTRAINT food_item_user_barcode UNIQUE (user_id, barcode);

-- polar_auth: link Polar's user to our users table
-- polar_auth.user_id (TEXT) remains as the Polar platform identifier
ALTER TABLE polar_auth
  ADD COLUMN health_user_id UUID REFERENCES users(id) ON DELETE SET NULL;
-- Backfill: existing polar_auth row belongs to 'health'
UPDATE polar_auth SET health_user_id = (SELECT id FROM users WHERE username = 'health')
  WHERE health_user_id IS NULL;

-- Indexes
CREATE INDEX ON body_weight   (user_id, date DESC);
CREATE INDEX ON daily_energy  (user_id, date DESC);
CREATE INDEX ON workout       (user_id, date DESC);
CREATE INDEX ON log_entry     (user_id, logged_at DESC);
CREATE INDEX ON meal_template (user_id);
CREATE INDEX ON food_item     (user_id);
```

### V4 — New tables (`V4__profile_shortcuts.sql`)

Two tables that were missing from the schema entirely.

```sql
-- User profile: survives phone switches; pre-fills onboarding on reinstall
CREATE TABLE user_profile (
  user_id        UUID        PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  height_cm      INTEGER     NOT NULL,
  birth_year     INTEGER     NOT NULL,
  sex            TEXT        NOT NULL CHECK (sex IN ('male', 'female')),
  goal_weight_kg NUMERIC(5,2) NOT NULL,
  activity_level TEXT        NOT NULL
    CHECK (activity_level IN ('sedentary', 'lightly_active', 'moderately_active')),
  target_deficit INTEGER     NOT NULL DEFAULT 300,  -- kcal/day; 0 = maintenance mode
  phase          TEXT        NOT NULL DEFAULT 'loss' CHECK (phase IN ('loss', 'maintenance')),
  vacation_mode  BOOLEAN     NOT NULL DEFAULT false,
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Shortcuts: per-user quick-log buttons (drinks, snacks, etc.)
CREATE TABLE shortcut (
  id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id    UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  emoji      TEXT        NOT NULL,
  label      TEXT        NOT NULL,
  kcal       INTEGER     NOT NULL,
  sort_order INTEGER     NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (user_id, sort_order)
);

CREATE INDEX ON shortcut (user_id, sort_order);
```

### Food catalog: per-user

`food_item` is scoped per user. Each user owns their own catalog entries (Open Food Facts lookups
and manual items). Barcode uniqueness is per-user. Minor duplication if two users scan the same
product is acceptable at family/friends scale. Benefit: privacy for manually-entered items and
simple ownership semantics.

### Quick-add log entries

`log_entry.quick_add_kcal` (non-null) signals a quick-add entry. `log_entry_item` rows are absent.
`log_entry.quick_add_label` is the optional memory-aid label ("Pasta at work"). The `(est.)` marker
shown in the UI is derived from the presence of `quick_add_kcal`, not stored.

### `user_profile.birth_year` not age

Age is computed at display/calculation time (`currentYear - birth_year`). Storing birth year means
the BMR calculation stays correct as the user ages without any data migration.

---

## API changes

### Silent user-scoping on all existing endpoints

No URL changes. Every authenticated handler extracts `user_id` from the validated session and adds
it as a `WHERE` clause. The Android client sends the same Bearer token as before — nothing changes
from its perspective.

DELETE and GET-by-id endpoints also assert `AND user_id = $sessionUserId` to prevent one user from
touching another user's data even if a UUID is guessed.

### `POST /logout`

```
POST /logout
Authorization: Bearer <token>
→ 204 No Content
```

Deletes the session row from the `sessions` table. Token is invalid immediately. The app wipes
Room and navigates to the login screen on receipt of `204`.

### `GET /profile` and `PUT /profile`

```
GET  /profile   → 200 | 404 (404 if onboarding not yet complete)
PUT  /profile   → 200 upsert (INSERT ... ON CONFLICT(user_id) DO UPDATE)
```

Request/response shape:

```json
{
  "heightCm": 177,
  "birthYear": 1986,
  "sex": "male",
  "goalWeightKg": 74.0,
  "activityLevel": "lightly_active",
  "targetDeficit": 300,
  "phase": "loss",
  "vacationMode": false
}
```

`GET /profile` returning `404` signals "onboarding not complete" to the Android app — show the
full onboarding flow. A `200` signals a returning user — pre-fill steps 1–3 and allow one-tap
confirmation per step (per S01 scenario).

`PUT /profile` is called at the end of each onboarding step and whenever the user changes a
setting (maintenance mode toggle, vacation mode, deficit slider in settings).

### `GET /shortcuts` and `PUT /shortcuts`

```
GET /shortcuts   → 200 ordered array
PUT /shortcuts   → 200 full replacement in a transaction
```

Response/request shape:

```json
[
  {
    "id": "uuid",
    "emoji": "🍺",
    "label": "Pils",
    "kcal": 140,
    "sortOrder": 0
  },
  {
    "id": "uuid",
    "emoji": "🍷",
    "label": "Wine",
    "kcal": 120,
    "sortOrder": 1
  },
  {
    "id": "uuid",
    "emoji": "🥃",
    "label": "Scotch",
    "kcal": 65,
    "sortOrder": 2
  }
]
```

`PUT /shortcuts` deletes all existing shortcut rows for the user and inserts the new list
atomically. IDs are generated server-side on each PUT. The client does not track shortcut UUIDs.

### Quick-add log entry (updated `POST /in/log/food`)

When `items` is absent, `quickAddKcal` is used instead:

```json
{
  "mealType": "snack",
  "loggedAt": "2026-06-04T21:15:00+02:00",
  "quickAddKcal": 140,
  "quickAddLabel": "🍺 Pils"
}
```

Response matches the existing log entry shape; `items` is an empty array, `totalKcal` equals
`quickAddKcal`. `quickAddLabel` is returned as-is for display in history.

### Summary endpoints

`GET /summary/today` and `GET /summary/week` now join `user_profile` to read `target_deficit` and
`phase` when computing `budget_remaining` and the weekly verdict. No response shape change.

---

## Android sync architecture

### Core principle

Room is the source of truth for the UI. The server is the source of truth for data. The app never
waits for the server.

### `SyncStatus` on Room entities

Every entity that syncs uploads to the server gets a `syncStatus` column:

```kotlin
enum class SyncStatus { SYNCED, PENDING_CREATE, PENDING_DELETE }
```

Entities that are **read-only from the app** (daily_energy, workout — Polar writes these
server-side) carry no `syncStatus`. The app only downloads them.

Entities with `syncStatus`: `BodyWeightEntity`, `LogEntryEntity`, `MealTemplateEntity`,
`FoodItemEntity`, `ShortcutEntity`, `UserProfileEntity`.

### Write path (offline-first)

Every user action writes to Room first with `syncStatus = PENDING_CREATE`. The UI updates
immediately from Room. WorkManager picks up pending rows when network is available.

```
User taps "Log breakfast"
  → Room INSERT log_entry (syncStatus = PENDING_CREATE)
  → UI updates instantly from Room Flow
  → WorkManager SyncWorker fires on next network window
      → POST /in/log/template → 201
      → Room UPDATE log_entry SET syncStatus = SYNCED
```

Delete path:

```
User taps "Delete entry"
  → Room UPDATE log_entry SET syncStatus = PENDING_DELETE
  → UI hides entry immediately (queries filter out PENDING_DELETE rows)
  → SyncWorker fires
      → DELETE /in/log/{id} → 204
      → Room DELETE log_entry
```

On server error, `syncStatus` stays `PENDING_*` and the worker retries with exponential backoff.
The user never sees retry state.

### `SyncWorker` (WorkManager)

One `ConstrainedWorker` registered with `NetworkType.CONNECTED`. Runs in the background whenever
network becomes available. Idempotent — safe to retry.

Upload order respects FK dependencies:

```
SyncWorker.doWork():
  1. Upload PENDING_DELETE rows (all entity types, order doesn't matter for deletes)
  2. Upload PENDING_CREATE: food items first, then templates + items, then log entries,
     then body weight, then shortcuts, then profile
  3. Mark each uploaded row SYNCED on success
  4. Return Result.success()
```

No download in the background worker. Downloads only happen at login. This prevents the background
job from overwriting data the user is actively entering.

### Login sync (full download)

After `POST /token` succeeds, before navigating to the dashboard, a coroutine runs a full download
behind the existing loading state on the login screen. The user waits here — it is the only time
they ever wait for the server.

Download order respects FK dependencies:

```
LoginSync:
  1. GET /profile          → upsert UserProfileEntity (SYNCED); 404 → show full onboarding
  2. GET /shortcuts        → replace all ShortcutEntity (SYNCED)
  3. GET /in/food-items    → upsert all FoodItemEntity (SYNCED)
  4. GET /in/templates     → upsert all MealTemplateEntity + items (SYNCED)
  5. GET /body/weight      → upsert all BodyWeightEntity (SYNCED)
  6. GET /in/log           (?from=90 calendar days before login date) → upsert LogEntryEntity (SYNCED)
  7. GET /out/energy       (?from=90 calendar days before login date) → upsert DailyEnergyEntity
  8. GET /out/workouts     (?from=90 calendar days before login date) → upsert WorkoutEntity
```

The 90-day window keeps the initial sync fast. Full history back to day 1 is available on the
server via date-range queries if the app ever needs it.

After download completes, navigate to the dashboard (or onboarding if profile returned `404`).

### Logout

```
User taps "Sign out" and confirms
  → POST /logout → 204
  → Room: deleteAll() on all tables
  → DataStore: clear token
  → Navigate to login screen
```

### What does not sync

| Data                     | Reason                                                      |
|--------------------------|-------------------------------------------------------------|
| Sport-tonight toggle     | Ephemeral — today only, reset each morning. DataStore only. |
| Undo snackbar state      | Transient UI                                                |
| `sessions` / auth tokens | Server-only                                                 |
| `polar_auth`             | Server-only — app never sees Polar credentials              |

---

## UX changes

### Minimal surface changes

Almost all of the multi-user and sync work is invisible to the user. The only user-visible
additions are the Log out action and the 401 session-end message.

### F01 Step 0 — Login screen

The note "No 'create account' — single-user app; account is pre-provisioned" becomes:
"No 'create account' — accounts are provisioned by the administrator."

No layout change.

### F06 Settings — Log out

A **Sign out** entry at the bottom of the Settings screen, visually separated from other settings.

Tap → confirmation bottom sheet:

```
┌──────────────────────────────┐
│ Sign out?                    │
│ Your data will be removed    │
│ from this device. It's all   │
│ saved on the server.         │
├──────────────────────────────┤
│ [ Cancel ]    [ Sign out ]   │
└──────────────────────────────┘
```

On confirm: `POST /logout` → wipe Room → clear token → navigate to login.

The "saved on the server" line is explicit reassurance — users need to know logout is safe.

### 401 cross-cutting handler

An OkHttp interceptor (or Ktor client plugin) catches `401` on any authenticated endpoint:

- Clear token from DataStore
- Navigate to login screen
- Show: "Your session has ended — please sign in again."
- Do **not** wipe Room — pending local data is preserved for upload on next login

### `docs/ux/1-principles.md`

Remove "Single user (the owner)." from line 6. The rest of the principles doc is already
user-agnostic.

### `docs/ux/4-flows.md` F01 Step 0

Update implementation note (see above). No other flow changes.

---

## Polar cron — multi-user impact

The Polar sync cron job must now iterate over all rows in `polar_auth` (keyed by `health_user_id`)
rather than treating a single global user. For each row it pulls activity data from Polar and
inserts `daily_energy` and `workout` rows scoped to that `health_user_id`. No API change — the
cron runs server-side only.

---

## Ansible / provisioning

The repo is (or will be) open source, so the user list must never be committed. It lives
implicitly in the vault: every variable matching `user_<username>_password` defines a user.
Adding a user means adding one vault variable — no other file changes.

The playbook derives the user list at runtime by filtering the loaded vault variables:

```yaml
# ansible/vars/vault.yml (never committed — gitignored)
user_health_password: "strong-random-value"
user_alice_password: "another-strong-value"
```

```yaml
# playbook tasks

- name: Derive user list from vault
  set_fact:
    users: "{{ vars | dict2items
                     | selectattr('key', 'match', '^user_.+_password$')
                     | map(attribute='key')
                     | map('regex_replace', '^user_(.+)_password$', '\\1')
                     | list }}"

- name: Hash password for {{ item }}
  command: >
    python3 -c "import bcrypt;
    h=bcrypt.hashpw('{{ lookup('vars', 'user_' + item + '_password') }}'.encode(),
    bcrypt.gensalt(12)).decode(); print(h)"
  loop: "{{ users }}"
  register: pw_hashes
  changed_when: false
  no_log: true

- name: Upsert user {{ item.item }}
  command: >
    docker exec health_postgres psql -U {{ postgres_user }} -d {{ postgres_db }} -c
    "INSERT INTO users (username, password_hash)
     VALUES ('{{ item.item }}', '{{ item.stdout }}')
     ON CONFLICT (username) DO UPDATE SET password_hash = EXCLUDED.password_hash;"
  loop: "{{ pw_hashes.results }}"
  no_log: true
```

Adding a user: add `user_<username>_password` to the vault. Nothing else. The playbook
provisions exactly the users whose passwords exist in the vault — no committed user list,
no divergence between the list and the vault.

---

## Data inventory — everything that syncs

| Entity                  | Direction             | Notes                                      |
|-------------------------|-----------------------|--------------------------------------------|
| `user_profile`          | ↑ upload + ↓ download | Set during onboarding; updated in settings |
| `shortcuts`             | ↑ upload + ↓ download | Full-replace on PUT                        |
| `body_weight`           | ↑ upload + ↓ download | Per-user, per-day                          |
| `food_item`             | ↑ upload + ↓ download | Per-user catalog                           |
| `meal_template` + items | ↑ upload + ↓ download | Items cascade with template                |
| `log_entry` + items     | ↑ upload + ↓ download | Immutable; quick-add via new columns       |
| `daily_energy`          | ↓ download only       | Written server-side by Polar cron          |
| `workout`               | ↓ download only       | Written server-side by Polar cron          |
| `sessions` / tokens     | server-only           | Never sent to device                       |
| `polar_auth`            | server-only           | Polar credentials never leave server       |
| Sport-tonight toggle    | device-only           | Ephemeral, DataStore                       |

---

## Out of scope

- Concurrent multi-device conflict resolution
- Self-registration or invite-link account creation
- Cross-device real-time sync (changes visible without logout/login)
- Admin UI for user management (Ansible + playbook is sufficient)
- Token invalidation on new login (multiple concurrent sessions allowed; expire naturally)
