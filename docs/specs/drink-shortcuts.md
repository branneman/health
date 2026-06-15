# Spec — Drink shortcuts (story 10)

One-tap drink buttons on the log screen. Each button shows an emoji + short label (e.g.
🍺 Pils, 🍷 Wine) and logs a preset kcal in one tap. Buttons are user-configured via a
Settings sub-page.

---

## Scope

- User configures any number of drink shortcut buttons (emoji + label + kcal each) via a
  Settings sub-page.
- Buttons appear on the log screen in configured order, as a second row below the meal
  buttons row.
- Tapping a button creates a `log_entry` instantly (offline-first, same undo pattern as
  quick-add and meal buttons).
- Unconfigured state: a single "Set up drink buttons →" outlined button navigates to the
  Settings sub-page.
- Log entry label is `"${emoji} ${label}"` (e.g. "🍺 Pils").

Out of scope: emoji picker widget (free-text emoji field is sufficient), widget integration
(story 21).

---

## Data model

### Room — no migration needed

`ShortcutEntity` already has all required fields: `emoji`, `label`, `kcal`, `sortOrder`,
`syncStatus`. No schema change.

Two new DAO methods on `ShortcutDao`:

```kotlin
@Query("SELECT * FROM shortcut WHERE syncStatus = :status")
suspend fun getByStatus(status: SyncStatus): List<ShortcutEntity>

@Query("UPDATE shortcut SET syncStatus = :status WHERE id = :id")
suspend fun updateSyncStatus(id: String, status: SyncStatus)
```

### Shared DTO / server

`ShortcutDto` and `GET/PUT /shortcuts` endpoints already exist. No server changes in this
story.

---

## Sync layer

### ShortcutSyncService (new)

Mirrors `MealTemplateSyncService`:

```kotlin
class ShortcutSyncService(private val api: HealthApiClient, private val db: HealthDatabase) {

    suspend fun pushPending(token: String, userId: String) {
        val pending = db.shortcutDao().getByStatus(SyncStatus.PENDING_CREATE)
        if (pending.isEmpty()) return
        val allActive = pending + db.shortcutDao().getByStatus(SyncStatus.SYNCED)
        runCatching {
            api.putShortcuts(token, allActive.map { it.toDto() })
        }.onSuccess {
            allActive.forEach { db.shortcutDao().updateSyncStatus(it.id, SyncStatus.SYNCED) }
        }
    }

    suspend fun pull(token: String, userId: String) {
        val shortcuts = api.getShortcuts(token)
        db.shortcutDao().upsertAll(shortcuts.map { dto ->
            ShortcutEntity(
                id        = dto.id,
                userId    = userId,
                emoji     = dto.emoji,
                label     = dto.label,
                kcal      = dto.kcal,
                sortOrder = dto.sortOrder,
                syncStatus = SyncStatus.SYNCED,
            )
        })
    }
}
```

`LoginSyncService` replaces its inline shortcut-pull code with
`ShortcutSyncService(api, db).pull(token, userId)`.

`SyncWorker` adds one line alongside the existing `MealTemplateSyncService` call:

```kotlin
ShortcutSyncService(apiClient, db).pushPending(stored.token, stored.userId)
```

---

## App architecture

### LogViewModel (extend)

```kotlin
val shortcuts: StateFlow<List<ShortcutEntity>> = db.shortcutDao().observeAll()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

fun logFromShortcut(shortcut: ShortcutEntity) {
    viewModelScope.launch {
        val userId = tokenStore.tokenFlow.first()?.userId ?: return@launch
        val entity = LogEntryEntity(
            userId        = userId,
            loggedAt      = OffsetDateTime.now().toString(),
            mealType      = "unknown",
            quickAddKcal  = shortcut.kcal,
            quickAddLabel = "${shortcut.emoji} ${shortcut.label}",
        )
        db.logEntryDao().upsert(entity)
        _undoPending.value = entity to SyncStatus.PENDING_CREATE
    }
}
```

### DrinkButtonsViewModel (new)

```kotlin
class DrinkButtonsViewModel(application: Application) : AndroidViewModel(application) {
    private val db = (application as HealthApplication).db
    private val tokenStore = TokenStore(application.authDataStore)
    private var userId: String? = null

    val draft: StateFlow<List<ShortcutEntity>?> = flow<List<ShortcutEntity>?> {
        emit(null)
        userId = tokenStore.tokenFlow.first()?.userId
        emitAll(db.shortcutDao().observeAll())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun save(rows: List<ShortcutEntity>) {
        val uid = userId ?: return
        viewModelScope.launch {
            db.shortcutDao().deleteAllForUser(uid)
            db.shortcutDao().upsertAll(rows.mapIndexed { i, entity ->
                entity.copy(userId = uid, sortOrder = i, syncStatus = SyncStatus.PENDING_CREATE)
            })
        }
    }
}
```

### DrinkButtonsScreen (new)

Settings sub-page. Each row has three fields: emoji (narrow text), label, kcal (number).
Add button opens a dialog collecting all three fields. Save enabled only when every row has
non-empty emoji, non-empty label, and kcal > 0. On save: calls `DrinkButtonsViewModel.save`
then navigates back.

### LogScreen (extend)

`LogContent` gains three new parameters: `shortcuts: List<ShortcutEntity>`,
`onSetUpDrinkButtons: () -> Unit`, `onLogShortcut: (ShortcutEntity) -> Unit`.

A drink shortcuts row is rendered below the meal buttons row:

- Empty (`shortcuts.isEmpty()`): outlined "Set up drink buttons →" button, full width.
- Configured: horizontal scrollable row of buttons, each showing `"${emoji} ${label}"`.

Tapping a configured button calls `onLogShortcut`; snackbar + undo appears (same
`LogAction.Added` path as meal buttons).

### SettingsScreen (extend)

`SettingsContent` gains `onNavigateDrinkButtons: () -> Unit`. A "Drink buttons →"
`TextButton` row is added directly below the existing "Meal buttons →" row.

### Navigation (App.kt)

`SettingsPage` enum extends to `{ Main, MealButtons, DrinkButtons }`.

`DrinkButtons` branch renders `DrinkButtonsScreen(onBack = { settingsPage = SettingsPage.Main })`.

The log screen's `onSetUpDrinkButtons` sets `currentTab = Tab.Settings` and
`settingsPage = SettingsPage.DrinkButtons` in one step.

---

## Edge cases

| Case | Behaviour |
|------|-----------|
| No shortcuts configured | Log screen shows "Set up drink buttons →" outlined button |
| Offline when saving config | Room write succeeds; sync retries in background |
| Offline when tapping a button | Log entry written to Room; synced when online |
| Undo after tap | Same snackbar + undo pattern as quick-add and meal buttons |
| Invalid row in config | Save button disabled; no partial saves |
| Many buttons | Button row scrolls horizontally |

---

## Testing

### Tier 2b — App component

**`ShortcutDaoTest`** (extend):

| Test | What to verify |
|------|----------------|
| `getByStatus` | Returns only shortcuts matching the given status |
| `updateSyncStatus` | Changes status for the specified row; other rows unaffected |

**`ShortcutSyncServiceTest`** (new) — Robolectric + in-memory Room + `MockEngine`:

| Test | What to verify |
|------|----------------|
| `pushPending sends PENDING_CREATE shortcut and marks it SYNCED` | PUT called with correct body; all rows become SYNCED |
| `pushPending does nothing when no pending shortcuts` | PUT not called |
| `pull upserts server shortcuts into Room as SYNCED` | Shortcuts land in Room with correct fields and SYNCED status |
| `pushPending stays PENDING_CREATE on network error` | Status unchanged after network failure |

**`DrinkButtonsViewModelTest`** (new) — Robolectric + in-memory Room:

| Test | What to verify |
|------|----------------|
| `save inserts rows with PENDING_CREATE` | After save, all shortcuts have PENDING_CREATE status |
| `save assigns contiguous sortOrder` | sortOrder values match list position (0, 1, 2, …) |

**`DrinkButtonsScreenTest`** (new) — Compose test:

| Test | What to verify |
|------|----------------|
| Empty draft: Save disabled | Save button not enabled when no rows |
| Add dialog: Save confirm disabled when emoji empty | Confirm button disabled |
| Add dialog: Save confirm disabled when label empty | Confirm button disabled |
| Add dialog: Save confirm disabled when kcal ≤ 0 | Confirm button disabled |
| Add dialog: confirm enabled when all valid | Confirm button enabled |
| Configured rows rendered | Emoji + label + kcal visible |
| Delete removes row | Row no longer appears after delete |
| Edit makes Save enabled when all rows valid | Save becomes enabled |

**`LogScreenTest`** (extend):

| Test | What to verify |
|------|----------------|
| Empty shortcuts: setup button visible | "Set up drink buttons" outlined button exists |
| Tapping setup button calls `onSetUpDrinkButtons` | Callback fires |
| Configured shortcuts: button per shortcut | "🍺 Pils" button visible |
| Tapping shortcut calls `onLogShortcut` | Correct entity passed to callback |

**`SettingsScreenTest`** (extend):

| Test | What to verify |
|------|----------------|
| "Drink buttons" row present and tappable | Exists and calls `onNavigateDrinkButtons` |

**`SyncWorkerTest`** (extend):

| Test | What to verify |
|------|----------------|
| `pushPending wires ShortcutSyncService` | Shortcut in PENDING_CREATE → SYNCED after sync |

### Tier 2a — Server integration

No new test class. `ProfileAndShortcutsIntegrationTest` (UUID slot 2) already covers
`GET/PUT /shortcuts`.
