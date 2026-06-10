# Onboarding — Story 5

**Scope:** First-launch onboarding flow — biometrics, activity level, target deficit.
Delivers a working BMR-based daily calorie budget. Polar connect is deferred to story 11.

Cross-references: `docs/ux/2-scenarios.md` S01 · `docs/ux/4-flows.md` F01 ·
`docs/specs/math-model.md` §1.2–1.3 · `docs/specs/api-design.md`

---

## What this story delivers

After story 5 the user can:

1. Log in for the first time and complete a 3-step profile setup.
2. Land on the dashboard with a working daily budget (BMR × activity multiplier − target
   deficit), clearly labelled as an estimate until Polar is connected.
3. On reinstall or new device: login syncs the existing profile; onboarding is skipped.

Story 6 (dashboard daily zone) is what surfaces the budget visually. Story 5 creates
the data it needs.

---

## What is NOT in this story

- Polar connect (story 11) — step 4 from F01 is omitted entirely.
- Drink shortcuts banner on dashboard landing — story 10.

---

## Schema

No new Flyway migration. V5 (`V5__profile_shortcuts.sql`) already defines `user_profile`
and `shortcut` with every field this story needs.

---

## Auth layer

### `AuthState` — new state

```kotlin
sealed class AuthState {
    data object Loading         : AuthState()
    data object LoggedOut       : AuthState()
    data object Expired         : AuthState()
    data object NeedsOnboarding : AuthState()   // ← new
    data object LoggedIn        : AuthState()
}
```

### `AuthRepository` — `db` becomes required

`db: HealthDatabase` is currently an optional parameter (default `null`). The new
`flatMapLatest` calls `db.userProfileDao().existsFlow(...)` and cannot tolerate a null
database. Change the parameter to required (non-nullable). Tests that construct
`AuthRepository` without a database will need to provide an in-memory Room database or
a test double.

### `AuthRepository.authState` — reactive Room check

Replace the current `tokenFlow.map { … LoggedIn }` with a `flatMapLatest` that
consults Room for profile existence:

```kotlin
val authState: Flow<AuthState> = merge(
    tokenFlow.flatMapLatest { stored ->
        when {
            stored == null   -> flowOf(LoggedOut)
            stored.isExpired -> flowOf(Expired)
            else -> db.userProfileDao()
                .existsFlow()
                .map { exists -> if (exists) LoggedIn else NeedsOnboarding }
        }
    },
    _expiredChannel.receiveAsFlow().map { Expired }
)
```

`existsFlow` emits the current value immediately on subscription, so:

- **Startup:** `Loading` (initial `_authState` value in `AuthViewModel`) is replaced
  as soon as the first emission arrives — no flash of wrong content.
- **Mid-session token refresh:** `flatMapLatest` re-subscribes to `existsFlow` with
  the same `userId`; Room immediately re-emits `true` → `LoggedIn`. No observable
  flicker.
- **Onboarding complete:** `OnboardingViewModel` writes the profile to Room; Room's
  Flow emits `true` automatically → `authState` transitions to `LoggedIn` without any
  manual call. No `onboardingCompleted()` method needed.

`proactiveRefreshIfNeeded()` requires no changes for the profile check — the reactive
flow handles it.

### `UserProfileDao` — new query

```kotlin
@Query("SELECT EXISTS(SELECT 1 FROM user_profile LIMIT 1)")
fun existsFlow(): Flow<Boolean>
```

No `userId` parameter — Room holds only one user's data at a time. Read queries
in this codebase never filter by `userId` (that pattern is for the server's Postgres
queries). The `userId` column only appears in DAO `DELETE` queries (logout cleanup).

### `App.kt` — new branch

```kotlin
AuthState.NeedsOnboarding -> OnboardingScreen()
```

`OnboardingScreen` has no explicit completion callback. Navigation to `MainNav` is
driven automatically when Room's `existsFlow` flips to `true` after the profile save.

---

## Edge cases

| Scenario | Behaviour |
|---|---|
| Hard close / phone restart | DataStore + Room persist. Startup re-checks token + profile via `existsFlow`. Routes to correct screen immediately. |
| App data clear | DataStore wiped → `LoggedOut`. Room wiped → fresh login → `sync()` returns false → `NeedsOnboarding`. ✓ |
| Killed between steps 1–3 | Nothing saved (in-memory only). Next launch → `NeedsOnboarding` → user restarts from step 1. Acceptable for a 3-step flow. |
| Offline at step 3 "Done" | Server calls fail → inline error on Done button → user retries when online. Room is not written until both server calls succeed. |
| Reinstall / new device | Login calls `sync()`; if server has profile, it is pulled into Room → `existsFlow` emits `true` → skips onboarding. ✓ |

---

## Server

### New endpoint: `POST /body/weight`

Defined in `docs/specs/api-design.md` but not yet implemented in `server/`.

**Request:**
```json
{ "date": "2026-06-10", "kg": 84.0 }
```

**Response `201`:**
```json
{ "id": "uuid", "date": "2026-06-10", "kg": 84.0 }
```

**`409 Conflict`** if a weight entry already exists for this user on this date.

Add inside the existing `authenticate("api") { route("/body") { … } }` block in
`Application.kt`, mirroring the `GET /body/weight` handler style.

### Why onboarding save is server-first (not offline-first)

All other logging in the app is offline-first (write Room, sync later). Onboarding
is the exception: the save requires both server calls to succeed before Room is
written and navigation proceeds.

Rationale: onboarding happens immediately after login, which already requires network.
A network failure at step 3 is rare and recoverable via retry. Writing a `PENDING`
profile to Room without server confirmation would cause the reactive flow to navigate
away before the error is shown — the snackbar would appear on an unmounted screen.
Server-first sidesteps this entirely.

---

## Client

### `HealthApiClient` — new method

```kotlin
suspend fun postBodyWeight(token: String, dto: WeightEntryDto): WeightEntryDto =
    client.post("$baseUrl/body/weight") {
        header(HttpHeaders.Authorization, "Bearer $token")
        contentType(ContentType.Application.Json)
        setBody(dto)
    }.body()
```

`WeightEntryDto` (`date: String`, `kg: Double`) already covers the request shape. The
server response includes an `id` field that kotlinx.serialization silently ignores —
no DTO change needed.

---

## OnboardingViewModel

`AndroidViewModel`. Holds all step state in memory — no persistence between sessions.

### UI state

```
step: Int                    // 1, 2, or 3
sex: String                  // "male" | "female", no default
heightCm: String             // raw field input, validated on Continue
currentWeightKg: String      // raw field input
goalWeightKg: String         // raw field input; must be ≤ currentWeightKg
age: String                  // converted to birthYear = currentYear - age on save
activityLevel: String        // "lightly_active" default
targetDeficit: Int           // 300 default
isSaving: Boolean
saveError: String?
```

String fields for numeric inputs avoid parsing edge cases (empty, partial input) in
Compose text fields. Validation runs on Continue/Done, not on keystroke.

### Live preview computations (presentation only)

These run locally in the ViewModel and are displayed during input. They are not
authoritative — the server computes the real budget once the profile is saved.

**Step 2 — estimated TDEE:**
```
bmr = 10 × weightKg + 6.25 × heightCm − 5 × age + sex_constant
      (sex_constant: +5 male, −161 female)
estimatedTdee = round(bmr × activityMultiplier)
```
Multipliers: sedentary 1.20 · lightly_active 1.375 · moderately_active 1.55

**Step 3 — projected pace:**
```
kgPerWeek   = targetDeficit / 7700.0
monthsToGoal = (currentWeightKg − goalWeightKg) × 7700 / targetDeficit / 30
```
At `targetDeficit == 0`: show `"Maintain weight — no active deficit"` instead of
the pace/timeline lines.

### Save flow (step 3 "Done")

Server-first — Room is not written until both server calls succeed. This avoids
navigation firing before the error can be shown (see §Why onboarding save is
server-first).

All writes are guarded by `isSaving = true` while in progress.

1. Read `token` and `userId` from `TokenStore`.
2. Attempt `PUT /profile` via `HealthApiClient.putProfile()`. On failure: set
   `saveError`, clear `isSaving`, return.
3. Attempt `POST /body/weight` via `HealthApiClient.postBodyWeight()`. On `409`: a
   weight entry for today already exists (reinstall edge case) — treat as success,
   continue. On other failure: set `saveError`, clear `isSaving`, return.
4. Write `UserProfileEntity(syncStatus = SYNCED)` → `UserProfileDao.upsert()`.
5. Write `BodyWeightEntity(date = today, syncStatus = SYNCED)` → `BodyWeightDao.upsert()`.
6. Room's `existsFlow` emits `true` → `authState` transitions to `LoggedIn` →
   `App.kt` renders `MainNav` automatically.

`saveError` message: `"Couldn't reach the server — check your connection and try again."`
Shown as a snackbar; Done is re-enabled after dismissal for retry.

---

## OnboardingScreen

Single `@Composable`, switches on `viewModel.step`. Back button on steps 2–3
decrements step (in-memory only). No Back on step 1. No back-stack navigation entry.

### Step 1 — Biometrics (1/3)

Fields (per F01):
- **Sex** — two-button toggle: `Male` / `Female`. No default — Continue stays
  disabled until selected.
- **Height** — integer, numeric keyboard, cm.
- **Current weight** — decimal, kg.
- **Goal weight** — decimal, kg. Inline validation: muted error label
  `"Goal must be ≤ current weight"` when invalid; Continue disabled.
- **Age** — integer. Stored as `birthYear = currentYear − age`.

Continue is disabled until all five fields are non-empty and goal weight ≤ current
weight.

### Step 2 — Activity level (2/3)

Three radio options with subtitle lines (per F01), default `lightly_active`:

| Option | Subtitle | Multiplier |
|---|---|---|
| Mostly sitting | Desk job, ≤1 sport/week | 1.20 |
| Lightly active | 2–4 sport sessions/week | 1.375 |
| Moderately active | 5+ sessions/week | 1.55 |

Live preview below options:
`"Estimated output: ~2,400 kcal/day (estimated)"` — updates on selection change.
The `"(estimated)"` label is always shown.

### Step 3 — Target deficit (3/3)

- Slider range 0–600 kcal, default 300. Step granularity: 25 kcal.
- Visual highlight on slider track for the 250–400 recommended zone.
- Live lines below slider:
  - `"≈ 0.27 kg/week"`
  - `"Goal reached in ~8 months"` (hidden when `currentWeight == goalWeight` or
    deficit = 0)
  - `"Maintain weight — no active deficit"` (shown at deficit = 0)
- Warning: `"⚠ Above 500 kcal/day risks muscle loss."` — shown only when
  slider > 500, never blocks Done.
- Done button: disabled while `isSaving`. Shows spinner while saving.
- `saveError` shown as a snackbar with a dismiss action; after dismiss, Done is
  re-enabled for retry.

---

## What the spec deliberately excludes

- **Step 4 (Connect Polar):** deferred to story 11.
- **Pre-fill from account:** the reinstall case is handled by skipping onboarding
  entirely (profile already in Room after `sync()`). No partial pre-fill UI needed.
- **Activity level as a one-time bootstrap:** the field is editable in Settings later
  (story TBD). This story only writes the initial value.
