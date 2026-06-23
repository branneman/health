# Spec — 15 (Build from scratch)

Ingredient builder UI: text search and barcode scan against the OFD catalog, manual
entry fallback, per-ingredient gram quantities, save as ingredient template, log
directly. Ingredient templates also editable from Settings → Templates.

---

## Delivery Order

Server-first, then app. The server changes are additive and backwards-compatible (new
endpoints, one additive schema column). Deploy and manually verify in production before
touching any app code. The app ships as a complete vertical slice once both phases are
done — no partial app release mid-story.

**Phase 1:** shared DTOs + Flyway migration + server endpoints → deploy → manual
production verification.
**Phase 2:** Room migration + DAOs + sync services + screens + navigation → app release.

---

## Scope

- **In:** OFD text search, barcode scan (ML Kit bundled), manual food item creation,
  ingredient list assembly (insertion order), meal type selection at log time,
  "save as template?" prompt after logging, ingredient template creation and editing
  from Settings → Templates.
- **Out:** drag-to-reorder ingredients (YAGNI — insertion order is sufficient for v1),
  editing existing log entries (immutable by design), multi-device sync (single device
  assumed).

---

## User Flows

### Flow 1 — Log from scratch

1. Log tab → **Log** button → log flow sheet → **Build from scratch**
2. `BuildFromScratchScreen` — starts with an empty ingredient list
3. **Add ingredient** → `FoodSearchScreen`:
   - Text search (debounced 300 ms): queries `/food/search?q=` (OFD) and personal
     catalog (`FoodItemDao.searchByName`) simultaneously; personal catalog results
     shown first
   - **Barcode** button: opens ML Kit scanner; result queries `/food/barcode?barcode=`
     (OFD) and `FoodItemDao.getByBarcode` simultaneously
   - **No match**: "Not found — enter manually" expands an inline form (name required;
     kcal/100g required; protein/carbs/fat per 100g optional)
   - Selecting any result (OFD, personal catalog, manual form):
     - If barcode matches an existing personal catalog item, use that entity (no
       duplicate)
     - Otherwise write `FoodItemEntity` to Room with `syncStatus = PENDING_CREATE`
     - Return the entity to `BuildFromScratchScreen`
4. Grams input dialog → item appended to the bottom of the list
5. Repeat until done
6. **Log** button (enabled once ≥ 1 ingredient) → meal type picker sheet (Breakfast /
   Lunch / Dinner / Snack) → `LogEntryEntity` + `LogEntryItemEntity` rows written to
   Room with `syncStatus = PENDING_CREATE` → `SyncWorker.syncNow()` triggered
7. **"Save as template?"** bottom sheet (name field, Save / Skip):
   - Save: `MealTemplateEntity` + `MealTemplateItemEntity` rows written to Room →
     `SyncWorker.syncNow()`
   - Skip: dismiss, return to `LogPage.Main`
8. **Abandon mid-build** (back with ≥ 1 ingredient accumulated): confirm dialog →
   option to fall through to Quick Add with the partial kcal total pre-filled (matching
   the UX spec bail-out behaviour)

### Flow 2 — Create ingredient template from Settings

1. Settings → Templates → **Add** → sheet: "Kcal total" / "From ingredients"
2. "From ingredients" → `EditIngredientTemplate` (empty list, name field at top)
3. Same `FoodSearchScreen` and grams dialog as Flow 1
4. **Save**: `MealTemplateEntity` + `MealTemplateItemEntity` written to Room → sync

### Flow 3 — Edit existing ingredient template

1. Settings → Templates → tap an ingredient template (distinguished from kcal-total
   templates by showing "N ingredients" as the secondary line)
2. `EditIngredientTemplate` — ingredient list pre-populated from Room
3. Add / delete ingredients, adjust grams inline
4. **Save**: full-replace PUT via `MealTemplateSyncService` (existing pattern)

---

## Server Changes

All in `Application.kt`. New endpoints follow the existing `transaction { }` +
existence-check idempotency pattern (same as `POST /in/log/quick-add`).

### Fix `GET /in/food-items`

Add query parameter handling:
- `?barcode=` — return the matching item or empty list
- `?q=` — `ILIKE '%query%'` search on `name`
- No parameter — return all (existing behaviour)

### New `POST /in/food-items`

Request: `FoodItemRequestDto` (client-generated `id` — same pattern as quick-add).
Server wraps insert in `transaction { }`, checks for existing id, returns `409 Conflict`
if found, otherwise inserts and returns `FoodItemDto`.

### New `POST /in/log/food`

Request: `FoodLogRequestDto` (`id`, `mealType`, optional `loggedAt`, `items:
[{foodItemId, grams}]`). Server:
1. Checks log entry id not already present (409 on duplicate)
2. For each item: looks up `food_item` to snapshot nutrition values (404 if unknown)
3. Inserts `log_entry` + `log_entry_item` rows in one transaction
4. Returns `LogEntryDto` with snapshotted totals

`mealType` values: `breakfast | lunch | dinner | snack`. `loggedAt` defaults to server
`now()` if absent.

---

## Shared DTOs (new)

```kotlin
// POST /in/food-items request
@Serializable
data class FoodItemRequestDto(
    val id: String,
    val barcode: String?,
    val name: String,
    val kcalPer100g: Double,
    val proteinPer100g: Double?,
    val carbsPer100g: Double?,
    val fatPer100g: Double?,
    val source: String,
)

// POST /in/log/food request
@Serializable
data class FoodLogItemRequestDto(val foodItemId: String, val grams: Double)

@Serializable
data class FoodLogRequestDto(
    val id: String,
    val mealType: String,
    val loggedAt: String?,
    val items: List<FoodLogItemRequestDto>,
)
```

`MealTemplateItemDto` — add `sortOrder: Int = 0`. Order is implicit in list position;
server assigns 0, 1, 2… on PUT and returns items `ORDER BY sort_order` on GET.

---

## Data Layer (App)

### Room — version 6 → 7

`MealTemplateItemEntity`: add `sortOrder: Int = 0`.

Migration:
```sql
ALTER TABLE meal_template_item ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0;
```

No other entity changes. `LogEntryEntity`, `LogEntryItemEntity`, and `FoodItemEntity`
already have the right shape.

**Local nutrition snapshot:** when writing `LogEntryItemEntity` rows to Room at log
time, the app copies `kcalPer100g`, `proteinPer100g`, `carbsPer100g`, and `fatPer100g`
from the `FoodItemEntity` already in Room. This is the same snapshot invariant the
server applies — it ensures the local calorie total is correct before sync completes,
and that local history remains accurate even if the food item is later corrected.

### `FoodItemDao` additions

```kotlin
@Query("SELECT * FROM food_item WHERE id = :id")
suspend fun getById(id: String): FoodItemEntity?

@Query("SELECT * FROM food_item WHERE barcode = :barcode LIMIT 1")
suspend fun getByBarcode(barcode: String): FoodItemEntity?

@Query("SELECT * FROM food_item WHERE name LIKE '%' || :query || '%'")
suspend fun searchByName(query: String): List<FoodItemEntity>

@Upsert
suspend fun upsert(entity: FoodItemEntity)
```

### `MealTemplateDao` addition

```kotlin
@Query("SELECT * FROM meal_template_item WHERE templateId = :templateId ORDER BY sortOrder ASC")
suspend fun getItemsForTemplate(templateId: String): List<MealTemplateItemEntity>
```

### New `FoodItemSyncService`

Push-pending pattern (same as `MealTemplateSyncService`). For each `PENDING_CREATE`
food item: calls `POST /in/food-items`, marks `SYNCED` on success.

### `LogEntrySyncService` extension

Current code skips entries where `quickAddKcal == null`. Extend: if `quickAddKcal` is
null, fetch `LogEntryItemEntity` rows and call `POST /in/log/food`.

### `SyncWorker` order

```
FoodItemSyncService.pushPending(token)   // new — must run before log entries
LogEntrySyncService.sync(token)          // extended
MealTemplateSyncService.pushPending(token)
...
```

Food items must be on the server before log entries that reference them.

### `HealthApiClient` additions

```kotlin
suspend fun postFoodItem(token: String, dto: FoodItemRequestDto): FoodItemDto
suspend fun searchFoodItems(token: String, q: String): List<FoodItemDto>
suspend fun lookupFoodByBarcode(token: String, barcode: String): FoodItemDto?
suspend fun postFoodLog(token: String, dto: FoodLogRequestDto): LogEntryDto?
```

---

## App — Screens & Navigation

### Navigation additions

`LogPage` enum: add `BuildFromScratch`, `FoodSearch`.

`SettingsPage` enum: add `EditIngredientTemplate`, `TemplatesFoodSearch`.

Result passing between parent and `FoodSearchScreen`: when the user selects an item,
`FoodSearchScreen` calls an `onItemSelected(FoodItemEntity)` callback. The App.kt
navigation layer routes this back to the appropriate parent ViewModel
(`BuildFromScratchViewModel` or `EditIngredientTemplateViewModel`). Implementation
detail of how to wire this is left to the plan.

### `FoodSearchScreen` + `FoodSearchViewModel`

- Text search field (debounced 300 ms), results list (personal catalog first, then OFD)
- Barcode button — ML Kit `com.google.mlkit:barcode-scanning` (bundled variant, no Play
  Services dependency)
- "Not found — enter manually" inline form: name + kcal/100g (required), macros
  (optional)
- On any selection: dedup by barcode against personal catalog; create `FoodItemEntity`
  in Room if not already present; emit via `selectedItem`
- Offline: OFD search/barcode unavailable → inline notice; personal catalog still works

### `BuildFromScratchScreen` + `BuildFromScratchViewModel`

- Ingredient list (insertion order, swipe-to-delete rows), running kcal total
- Add ingredient → `LogPage.FoodSearch`; grams dialog on return
- Log button (≥ 1 ingredient) → meal type sheet → Room write → sync trigger →
  save-as-template sheet
- Back with ≥ 1 ingredient: confirm dialog with Quick Add bail-out option

### `EditIngredientTemplateViewModel`

- Loads existing template + items from Room (or starts empty for new)
- Ingredient list with same add/delete/grams logic as `BuildFromScratchViewModel`
- Name field (pre-filled for edits)
- Save: full-replace via `MealTemplateSyncService`; no meal type, no log

### `LogFlowSheet` change

Add **Build from scratch** as the fourth option below Quick add.

### `TemplatesScreen` changes

- Distinguish template types: kcal-total shows the kcal value; ingredient template shows
  "N ingredients"
- Tapping an ingredient template → `SettingsPage.EditIngredientTemplate`
- **Add** button → sheet: "Kcal total" / "From ingredients"

---

## Offline Behaviour

| Action | Offline behaviour |
|---|---|
| OFD text search | Unavailable — inline notice; personal catalog results still shown |
| Barcode scan | OFD proxy unavailable — personal catalog check still runs |
| Manual food item creation | Room-first — always succeeds; syncs when connected |
| Logging | Room-first — always succeeds; syncs when connected |
| Save as template | Room-first — always succeeds; syncs when connected |

---

## Ingredient Ordering

Ingredients are stored and displayed in **insertion order** (append to bottom). No
reordering UI in v1. `sort_order` is persisted in `meal_template_item` (Postgres) and
`MealTemplateItemEntity` (Room) so order is deterministic across edits and syncs.
`log_entry_item` has no `sort_order` — log entries are immutable and display order does
not matter.

---

## Schema Changes

### Postgres — Flyway `V12__build_from_scratch.sql`

```sql
ALTER TABLE meal_template_item ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0;
```

### Room — version bump to 7

```sql
ALTER TABLE meal_template_item ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0;
```

---

## Testing

### Tier 1 — Unit

`BuildFromScratchViewModelTest`: running kcal total, bail-out prefill value.

### Tier 2a — Server Integration

New class `FoodCatalogIntegrationTest` — UUID slot **#12**,
email `foodcatalog-test@test.local`:
- `GET /in/food-items?q=` search, `?barcode=` lookup
- `POST /in/food-items` happy path; 409 on duplicate id
- `POST /in/log/food` happy path (nutrition snapshotted correctly); 409 on duplicate id;
  404 on unknown food item id

### Tier 2b — App Component

| Test class | What |
|---|---|
| `FoodItemDaoTest` (extend) | `getById`, `getByBarcode`, `searchByName` |
| `MealTemplateDaoTest` (extend) | `getItemsForTemplate` respects `sortOrder` |
| `FoodItemSyncServiceTest` | PENDING_CREATE items pushed + marked SYNCED; idempotent on retry |
| `LogEntrySyncServiceTest` (extend) | Food-item entry branch calls `POST /in/log/food` |
| `FoodSearchScreenTest` | Empty state, results list, manual form visibility, barcode button |
| `BuildFromScratchScreenTest` | Add flow, running total, meal type picker, save-as-template sheet, bail-out prefill |

### Tier 3 — API

Extend `FoodApiTest`: `POST /in/food-items` and `POST /in/log/food` against live server.

### Tier 4 — E2E

No new E2E test. Build-from-scratch is the rare path; core journey already covered.
