# Meal Templates — Story 13

**Scope:** Create and manage named kcal-total templates; browse and log from a template
list with a portion adjuster; migrate quick-add from the inline log-screen form to a
dedicated screen. No ingredient-based templates — those are story 15 (Build from scratch).

Cross-references: `docs/ux/4-flows.md` (F04, F05, F05a, F05b), `docs/ux/3-features/logging.md`,
`docs/api-design.md` (`/in/templates`), `docs/testing-manifesto.md`.

---

## What is already built

| Layer | What exists |
|---|---|
| Server | `GET /in/templates`, `PUT /in/templates` (bulk replace) |
| Shared DTO | `MealTemplateDto` (`id`, `name`, `sortOrder`, `quickAddKcal`, `items`) |
| Room entity | `MealTemplateEntity` (`id`, `userId`, `name`, `sortOrder`, `quickAddKcal`, `syncStatus`, `updatedAt`) |
| Room DAO | `MealTemplateDao` — `observeAll()`, `observePinned()`, `getByStatus()`, `upsert()`, `upsertAll()`, `updateSyncStatus()`, `deleteAllForUser()` |
| Sync | `MealTemplateSyncService.push()` — pushes SYNCED + PENDING_CREATE to server via bulk replace |
| App UI | `MealButtonsScreen` / `MealButtonsViewModel` — manages pinned templates (those with `sortOrder`) |
| Log screen | Pinned templates shown as one-tap buttons; `LogViewModel.logFromTemplate()` logs from `quickAddKcal` |

Nothing changes on the server or in the shared module.

---

## Sync strategy

Bulk push, no individual CRUD endpoints. Any local change (create / edit / delete)
is queued as a sync status; on next sync the full active set is pushed to the server
via `PUT /in/templates`, atomically replacing all server-side templates for this user.

**SyncStatus transitions for templates:**

| Action | Status set locally | After successful push |
|---|---|---|
| Create | `PENDING_CREATE` | `SYNCED` |
| Edit (name or kcal) | `PENDING_CREATE` | `SYNCED` |
| Delete | `PENDING_DELETE` | row hard-deleted from Room |

**Bug fix in `MealTemplateSyncService.pushPending()`:** currently bails early if no
`PENDING_CREATE` rows exist, so a delete-only change never reaches the server. Fix:
trigger the push whenever any of `PENDING_CREATE` or `PENDING_DELETE` exist. After a
successful push, hard-delete all `PENDING_DELETE` rows.

---

## Room DAO additions

Two new methods on `MealTemplateDao`:

```kotlin
@Query("SELECT * FROM meal_template WHERE id = :id LIMIT 1")
suspend fun getById(id: String): MealTemplateEntity?

@Query("DELETE FROM meal_template WHERE id = :id")
suspend fun deleteById(id: String)
```

`deleteById` is called by the sync service after a successful push to clean up
`PENDING_DELETE` rows. It is not called directly from the UI (the UI uses
`updateSyncStatus(id, PENDING_DELETE)` to soft-delete).

---

## App — new screens and changes

### F04 — Log screen (change)

Remove the inline quick-add form (kcal field, label field, Add button). Add a
`[ Log › ]` button below the drink shortcuts row, visually distinct from the one-tap
buttons (outlined or filled, full-width). Tapping it opens the F05 bottom sheet.

`LogViewModel.addEntry()` is removed. `logFromTemplate()` stays (still used by pinned
one-tap buttons).

### F05 — Log flow (new, bottom sheet)

A `ModalBottomSheet` that appears over F04. Two options:

```
[ From template  › ]
[ Quick-add calories › ]
```

"New dish" is not shown — that is story 15. Tapping either option dismisses the sheet
and navigates to the corresponding screen (F05a or F05b).

F05 is local UI state on F04 (a `var showLogSheet by remember { mutableStateOf(false) }`),
not a navigation destination.

### F05a — Template list screen (new)

Route: `template_list`

Layout (top to bottom):
1. Back navigation + "Templates" title
2. Scrollable list: pinned templates first (📌 indicator, in configured order), then
   all remaining templates alphabetically
3. Empty state: "No templates yet — add one in Settings → Templates."

Only templates with a non-null `quickAddKcal` are shown in the list (all templates
created in this story will have one; ingredient-only templates from story 15 will not).

Tapping a template opens the **portion adjuster** as a `ModalBottomSheet`:

```
┌──────────────────────────────┐
│ Chicken stir-fry             │
│ 620 kcal · normal portion    │
├──────────────────────────────┤
│  ○ Lighter  496 kcal  (×0.8) │
│  ● Normal   620 kcal  (×1.0) │
│  ○ Heavier  744 kcal  (×1.2) │
├──────────────────────────────┤
│           [ Log ]            │
└──────────────────────────────┘
```

Multipliers: Lighter = ×0.8, Normal = ×1.0, Heavier = ×1.2. Kcal preview updates live.
Final kcal = `round(template.quickAddKcal * multiplier)`.

`[ Log ]` writes a `LogEntryEntity` immediately (optimistic, offline-safe), dismisses the
sheet, pops back to F04, and shows undo snackbar ("Logged · Undo").

### F05b — Quick-add screen (new)

Route: `quick_add`

Migrated from the inline form on F04.

Layout:
- "← Quick-add" title with back navigation
- kcal number field (numeric keyboard opens immediately on arrival)
- Optional label field ("What was it?")
- Live preview: "Pasta at work — 800 kcal" or "800 kcal" if no label
- `[ Log ]` button (disabled until kcal > 0)

On Log: writes `LogEntryEntity`, pops back to F04, undo snackbar.

### Templates settings screen (new)

Route: `settings/templates`  
Reached from: Settings screen → "Templates"

Layout:
- "← Templates" title
- List of all templates. Pinned templates shown with a 📌 indicator (read-only here —
  pinning is managed in the existing Meal Buttons screen)
- FAB or header "+" to add a new template
- Tap any row → edit dialog

**Add / edit dialog** (same form for both):
- Name field (required, non-blank)
- Kcal field (required, integer > 0)
- Save button (disabled until both fields valid)
- Delete button (edit only) → confirmation snackbar/dialog → marks `PENDING_DELETE`

Changes take effect immediately in the local Room DB. Sync pushes on next background
sync cycle.

---

## ViewModels

### `TemplateListViewModel`

Exposes:
- `allTemplates: StateFlow<List<MealTemplateEntity>>` — sorted: pinned (by `sortOrder`)
  first, then non-pinned alphabetically by `name`

Actions:
- `logFromTemplate(template: MealTemplateEntity, multiplier: Float)` — computes
  `round(template.quickAddKcal!! * multiplier)`, writes a `LogEntryEntity` with
  `mealType = "unknown"` and `quickAddLabel = template.name`, marks `PENDING_CREATE`
- `undoLog()` — deletes the last-written entry

### `TemplatesViewModel`

Exposes:
- `templates: StateFlow<List<MealTemplateEntity>>` — all non-deleted templates,
  alphabetical

Actions:
- `create(name: String, kcal: Int)` — inserts new `MealTemplateEntity` with
  `PENDING_CREATE`, no `sortOrder` (not pinned)
- `update(id: String, name: String, kcal: Int)` — updates name and `quickAddKcal`,
  sets `PENDING_CREATE`
- `delete(id: String)` — sets `PENDING_DELETE`; `observeAll()` already filters these out

### `QuickAddViewModel`

Extracted from `LogViewModel.addEntry()`. Same logic, new home.

Exposes: `canLog: StateFlow<Boolean>` (true when kcal > 0)

Actions:
- `log(kcalStr: String, label: String)` — writes `LogEntryEntity`, sets `PENDING_CREATE`
- `undoLog()` — deletes last entry

---

## Navigation

New routes added to the existing nav graph:

| Route | Screen |
|---|---|
| `template_list` | F05a — template list + portion adjuster |
| `quick_add` | F05b — quick-add |
| `settings/templates` | Templates CRUD settings screen |

F05 bottom sheet is local UI state on F04, not a route.

---

## Testing

### Tier 1 — Unit tests

**`TemplateListViewModelTest`** (new)
- Pinned templates appear before non-pinned regardless of insertion order
- Non-pinned templates are sorted alphabetically
- Multiplier math: ×0.8, ×1.0, ×1.2 each produce correctly rounded kcal
- Empty template list → empty state

**`TemplatesViewModelTest`** (new)
- `create()` inserts entity with `PENDING_CREATE` and no `sortOrder`
- `update()` changes name and kcal, sets `PENDING_CREATE`
- `delete()` sets `PENDING_DELETE`; template no longer appears in `templates` flow

**`QuickAddViewModelTest`** (new)
- `canLog` is false when kcal is empty or zero
- `canLog` is true when kcal > 0
- `log()` writes correct `LogEntryEntity` (label null when blank, kcal matches input)
- `undoLog()` removes the entry

**`MealTemplateSyncServiceTest`** (update existing)
- PENDING_CREATE only → push fires, all templates marked SYNCED
- PENDING_DELETE only → push fires, PENDING_DELETE rows are hard-deleted from Room after success
- PENDING_CREATE + PENDING_DELETE mix → push fires with correct active set
- Nothing pending → push does not fire

### Tier 2b — App component tests

**`MealTemplateDaoTest`** (update existing)
- `getById()` returns correct entity; returns null for unknown id
- `deleteById()` removes the row

**`MealTemplateSyncServiceTest`** (update existing — Robolectric + in-memory Room + MockEngine)
- Covers the PENDING_DELETE cycle end-to-end (soft delete → push → hard delete)

**`TemplateListScreenTest`** (new — Robolectric + ComposeTestRule)
- Pinned templates appear above non-pinned
- Tapping a template opens the portion adjuster sheet
- Selecting Lighter/Normal/Heavier updates the kcal preview
- Tapping Log calls back with the correct computed kcal

**`TemplatesScreenTest`** (new — Robolectric + ComposeTestRule)
- Add dialog: Save disabled until both name and kcal are valid
- Edit dialog: pre-fills existing values
- Delete: confirmation step, template removed from list

**`QuickAddScreenTest`** (new — Robolectric + ComposeTestRule)
- Log button disabled until kcal > 0
- Label field is optional (Log works without it)
- Entering kcal + label updates the live preview

**`LogScreenTest`** (update existing)
- `[ Log › ]` button is present
- Inline kcal/label/Add form is absent

### Tier 2a — Server integration

No server changes. Existing `MealTemplatesIntegrationTest` (UUID slot 9) covers the
`GET`/`PUT /in/templates` endpoints and requires no changes.

### Tier 3 — API tests

No new server endpoints. No new API tests.

### Tier 4 — E2E

Update `loginViewDashboardLogEntryAndSignOut` in `E2ESmokeTest`: the inline quick-add
form is removed from F04. The test must navigate `[ Log › ]` → "Quick-add calories" →
enter kcal → `[ Log ]` instead of using the old inline form.

### Test data

Add to `TestFactories.kt`:

```kotlin
fun aMealTemplate(
    id: String = uuid(),
    userId: String = uuid(),
    name: String = "Test meal",
    sortOrder: Int? = null,
    quickAddKcal: Int? = 500,
    syncStatus: SyncStatus = SyncStatus.SYNCED,
) = MealTemplateEntity(
    id = id, userId = userId, name = name,
    sortOrder = sortOrder, quickAddKcal = quickAddKcal,
    syncStatus = syncStatus,
)
```

---

## Out of scope

- Ingredient-based templates (story 15 — Build from scratch)
- "Save as template" from quick-add (considered; deferred to a later story)
- Pinning / unpinning templates from the Templates settings screen (managed via existing Meal Buttons screen)
- Template search (no search in v1 per `docs/ux/4-flows.md`)
- "New dish" path in F05 (story 15)
