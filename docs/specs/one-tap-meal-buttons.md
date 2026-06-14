# Spec — One-tap meal buttons (story 9)

One-tap buttons on the log screen that let the user log a named meal in a single tap.
Each button has a user-defined name and a kcal total. No ingredients, no OFD — just a
number. The same `meal_template` entity will carry ingredient-based templates in later
stories; this story sets up the mechanism.

---

## Scope

- User can configure any number of one-tap buttons (name + kcal each) via a Settings
  sub-page.
- Buttons appear on the log screen in configured order.
- Tapping a button creates a `log_entry` instantly (offline-first, same undo pattern
  as quick-add).
- Unconfigured state: a single "Set up meal buttons →" outlined button navigates to
  the Settings sub-page.
- `mealType` on the created log entry is `"unknown"` — same as existing quick-add
  entries; the template carries no fixed meal-type slot.

Out of scope: ingredient-based templates, portion adjusters, the template browse list
(story 12).

---

## Data model

### Postgres — V8 migration

```sql
ALTER TABLE meal_template
  ADD COLUMN quick_add_kcal INT NULL,
  ADD COLUMN sort_order INT NULL;

CREATE UNIQUE INDEX ON meal_template (user_id, sort_order)
  WHERE sort_order IS NOT NULL;
```

`sort_order IS NOT NULL` marks a template as a one-tap button and defines its position
in the row. `sort_order IS NULL` means browseable-only (future story 12). The partial
unique index enforces no duplicate positions per user.

### Shared DTO

```kotlin
@Serializable
data class MealTemplateDto(
    val id: String,
    val name: String,
    val sortOrder: Int?,       // non-null → one-tap button
    val quickAddKcal: Int?,    // null when ingredient-based (future)
    val items: List<MealTemplateItemDto>,
)
```

### Room entity

```kotlin
@Entity(tableName = "meal_template")
data class MealTemplateEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val name: String,
    val sortOrder: Int?,
    val quickAddKcal: Int?,
    val syncStatus: SyncStatus = SyncStatus.PENDING_CREATE,
    val updatedAt: Long = System.currentTimeMillis(),
)
```

Room schema version bumps from 2 → 3 via a manual `MIGRATION_2_3` object (the database uses `exportSchema = false`, which precludes `@AutoMigration`).

---

## Server endpoints

### Existing — unchanged except DTO gains new fields

```
GET /in/templates
```
Returns `List<MealTemplateDto>` including `sortOrder` and `quickAddKcal`. Already
present; adding the new fields is a non-breaking addition.

### New

```
PUT /in/templates
```
Body: `List<MealTemplateDto>`. Replaces the user's full template list atomically
(delete-all then re-insert in one transaction). Returns `200` with the saved list
(IDs assigned server-side as UUIDs). Follows the same replace-all pattern as
`PUT /shortcuts`.

Tapping a one-tap button on the log screen uses the **existing**
`POST /in/log/quick-add` — no new log endpoint needed. The app passes
`quickAddKcal` from the template and `name` as the label.

---

## App architecture

### MealTemplateSyncService (new)

Follows the shape of `LogEntrySyncService`:

- `pushPending()` — collects templates with `PENDING_CREATE` or `PENDING_UPDATE`,
  calls `PUT /in/templates` with the full list, marks all `SYNCED`.
- `pull()` — called from `LoginSyncService` on first login / reinstall; calls
  `GET /in/templates` and upserts all into Room.

`SyncWorker` wires in `MealTemplateSyncService` alongside existing services.

### MealButtonsViewModel (new)

For the Settings sub-page:

- Exposes the ordered list of one-tap templates (`sortOrder != null`) as `StateFlow`.
- `save(templates: List<MealTemplateEntity>)` — upserts all into Room with
  `PENDING_UPDATE`, triggers sync.
- Add / remove / reorder mutations update the list in memory; `save` persists.

### LogViewModel — addition

New `logFromTemplate(template: MealTemplateEntity)`:
- Creates `LogEntryEntity` with `quickAddKcal = template.quickAddKcal`,
  `quickAddLabel = template.name`, `mealType = "unknown"`.
- Sets `_undoPending` the same way as `addEntry` — same snackbar + undo flow.

### UI

**LogScreen** — one-tap button row inserted above the existing quick-add form.
- If no templates have `sortOrder != null`: single outlined "Set up meal buttons →"
  button (clearly tappable, not styled as disabled) navigates to `MealButtonsScreen`.
- If configured: one chip/button per template in sort order, each tappable.
- Tapping a configured button calls `logFromTemplate`; snackbar + undo appears.

**MealButtonsScreen (new)** — Settings sub-page.
- List of rows, each with a name field and a kcal field.
- Add / delete / reorder supported.
- Save button enabled only when all rows have a non-empty name and kcal > 0.
- On save: calls `MealButtonsViewModel.save`.
- Navigated to from `SettingsScreen` ("Meal buttons" row) and deep-linked from the
  log screen empty state button.

**SettingsScreen** — adds a "Meal buttons →" row that navigates to `MealButtonsScreen`.

---

## Navigation

The app uses a simple enum-based tab switcher in `App.kt` (no `NavHost`). A new
`SettingsPage` enum (`Main`, `MealButtons`) drives Settings sub-pages:

```kotlin
private enum class SettingsPage { Main, MealButtons }
var settingsPage by remember { mutableStateOf(SettingsPage.Main) }
```

The log screen empty-state button sets `currentTab = Tab.Settings` and
`settingsPage = SettingsPage.MealButtons` in one step. Back from `MealButtonsScreen`
resets `settingsPage` to `SettingsPage.Main`.

---

## Edge cases

| Case | Behaviour |
|------|-----------|
| No templates configured | Log screen shows "Set up meal buttons →" outlined button |
| Offline when saving config | Room write succeeds immediately; sync retries in background |
| Offline when tapping a button | Log entry written to Room; synced when online |
| Undo after tap | Same snackbar + undo pattern as quick-add |
| Empty name or kcal ≤ 0 in config | Save button disabled; no partial saves |
| Many buttons | Button row scrolls horizontally; no artificial cap |

---

## Testing

### Tier 1 — Unit tests

| Test | What to verify |
|------|----------------|
| `MealButtonsViewModelTest` | `add` appends with next sort_order; `remove` collapses gaps; `reorder` reassigns sort_order values contiguously; `save` sets `PENDING_UPDATE` on all |
| `LogViewModelTest` (extend) | `logFromTemplate` creates a `LogEntryEntity` with `quickAddKcal`, `quickAddLabel = name`, `mealType = "unknown"`, and sets `_undoPending` |

Use `TestFactories` — add `aMealTemplateEntity(...)` factory helper.

### Tier 2a — Server integration

**`MealTemplatesIntegrationTest`** — claims UUID slot #9 (`00000000-0000-0000-0000-000000000009`,
email `mealtemplates-test@test.local`).

| Case | What to verify |
|------|----------------|
| `PUT /in/templates` then `GET /in/templates` | Returns the saved list with correct `sortOrder` and `quickAddKcal` |
| `PUT /in/templates` twice | Second call replaces first (atomic delete-all + re-insert) |
| `GET /in/templates` unauthenticated | Returns `401` |
| `PUT /in/templates` with another user's token | Returns empty list for that user; original user's data unchanged |

### Tier 2b — App component tests

| Test | What to verify |
|------|----------------|
| `MealTemplateDaoTest` (extend) | New fields (`sortOrder`, `quickAddKcal`) persist; filter by `sortOrder != null` returns only pinned templates; unique index rejects duplicate sort_order for same user |
| `MealTemplateSyncServiceTest` (new) | `pushPending` calls `PUT /in/templates` with pending rows; `pull` upserts server response into Room; follows `MockEngine` pattern from `LogEntrySyncServiceTest` |
| `LogScreenTest` (extend) | Empty state: "Set up meal buttons" button visible; configured state: template buttons visible in order; tap creates entry + snackbar with undo |
| `MealButtonsScreenTest` (new) | Renders configured templates; add/delete/reorder updates list; Save disabled when name empty or kcal ≤ 0; Save enabled triggers `PENDING_UPDATE` |
| `SettingsScreenTest` (new) | "Meal buttons" row is present and navigates to `MealButtonsScreen` — open this test class now per manifesto rule; full Settings coverage to follow in story 15 |
