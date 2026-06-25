# Quick Single-Item Log — Spec

Story 17 (Quick single-item log). Two new entries in the log flow sheet: "Single item"
(food search → grams → log) and "Scan & log" (barcode scan → grams → log). No template,
no builder, no meal type prompt — ideal for snacks where the user knows the food but not
the kcal number upfront.

---

## Log Sheet Changes

Two entries appended to `LogFlowSheet` in `App.kt`:

```
From template          ›
Quick-add calories     ›
Ask AI                 ›
Build from scratch     ›
Single item            ›   ← new
Scan & log             ›   ← new
```

"Single item" opens food search (text search + scan button).  
"Scan & log" opens the same food search screen but with the barcode scanner launched
immediately.

---

## Navigation

`LogPage` enum gains two new values: `SingleItemSearch` and `SingleItemGrams`.

`MainNav` gains one new piece of state:

```kotlin
var selectedFoodItemForSingleLog by remember { mutableStateOf<FoodItemEntity?>(null) }
```

Cleared when `logPage` returns to `Main`.

**"Single item" tap:**
```
logPage = SingleItemSearch, autoLaunchScan = false
```

**"Scan & log" tap:**
```
logPage = SingleItemSearch, autoLaunchScan = true
```

**Food item selected (from either path):**
```
selectedFoodItemForSingleLog = item
logPage = SingleItemGrams
```

**Log confirmed:**
```
selectedFoodItemForSingleLog = null
logPage = Main   (with pendingLogUndoAction set)
```

**Back from SingleItemGrams:**
```
selectedFoodItemForSingleLog = null
logPage = SingleItemSearch   (user can search again)
```

**Back from SingleItemSearch:**
```
logPage = Main
```

---

## `FoodSearchScreen` — `autoLaunchScan` Parameter

```kotlin
fun FoodSearchScreen(
    onItemSelected: (FoodItemEntity) -> Unit,
    onBack: () -> Unit,
    autoLaunchScan: Boolean = false,
    viewModel: FoodSearchViewModel = viewModel(),
)
```

When `autoLaunchScan = true`, a `LaunchedEffect(Unit)` fires once and runs the same
logic as the scan button: check camera permission, request if absent, open scanner when
granted.

Failure paths (scan cancelled, barcode not detected, product not found) all fall back to
the normal search UI. The existing `barcodeNotFound` state already shows "Product not
found — search by name or enter manually", which covers the fallback adequately.

---

## `SingleItemLogScreen`

New composable, no ViewModel of its own — grams live in local `remember` state.

```kotlin
@Composable
fun SingleItemLogScreen(
    item: FoodItemEntity,
    logViewModel: LogViewModel,
    onLogged: (() -> Unit) -> Unit,   // passes back the undo lambda
    onBack: () -> Unit,
)
```

UI elements:
- `← Back` button
- Food name as screen title
- Grams `OutlinedTextField` (numeric keyboard, auto-focused)
- Live kcal preview: `"150g → 285 kcal"` — blank until `grams > 0`
- "Log" `Button` — enabled only when `grams > 0`

On "Log":
```kotlin
val kcal = (grams / 100.0 * item.kcalPer100g).roundToInt()
logViewModel.logSingleItem(item.name, kcal)
onLogged { logViewModel.undoAdd() }
```

The screen is stateless (local `remember` for grams only), so no `DisposableEffect` or
`reset()` is needed. Navigation back to `Main` is handled by the caller.

---

## `LogViewModel.logSingleItem`

New method on the existing `LogViewModel`:

```kotlin
fun logSingleItem(label: String, kcal: Int) {
    viewModelScope.launch {
        val userId = tokenStore.tokenFlow.first()?.userId ?: return@launch
        val entity = LogEntryEntity(
            userId        = userId,
            loggedAt      = OffsetDateTime.now().toString(),
            mealType      = "unknown",
            quickAddKcal  = kcal,
            quickAddLabel = label,
        )
        db.logEntryDao().upsert(entity)
        _undoPending.value = entity to SyncStatus.PENDING_CREATE
    }
}
```

Identical pattern to `logFromShortcut`. No `log_entry_item` row — macro tracking is out
of scope for this story.

The resulting log entry:
- Displays as `{food name} — {kcal} kcal` in the log list
- Is editable by tapping (same edit dialog as quick-add entries, since `quickAddKcal` is set)
- Supports undo via snackbar

---

## Data Storage

No schema change. No Room migration. No server changes.

Stored as a `log_entry` row with:
- `quickAddKcal` = `round(grams / 100.0 * kcalPer100g)`
- `quickAddLabel` = food item name
- `mealType` = `"unknown"`
- No `log_entry_item` row

---

## Error Handling

| Scenario | Behaviour |
|----------|-----------|
| Camera permission denied | Scanner never opens; search field shown |
| Scan cancelled | Falls back to search field |
| Barcode not found in catalog | Existing `barcodeNotFound` message; search field available |
| No internet during search | Existing `isOffline` notice; personal catalog still searchable |
| Grams field empty or zero | Log button disabled |

---

## Testing

**Tier 1 — unit (`LogViewModelTest`):**
- `logSingleItem` inserts a `LogEntryEntity` with `quickAddKcal = round(grams/100 * kcalPer100g)`
  and `quickAddLabel = item.name`

**Tier 2b — app component (`SingleItemLogScreenTest`):**
- Log button disabled when grams field is empty
- Kcal preview appears and updates correctly as grams are typed
- Tapping Log fires `onLogged`

No server integration tests, API tests, or E2E changes needed.
