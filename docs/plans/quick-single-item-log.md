# Quick Single-Item Log — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add "Single item" and "Scan & log" to the log flow sheet — both lead to food search → grams input → immediate log, with no meal type prompt.

**Architecture:** Four independent tasks in dependency order: (1) new `LogViewModel.logSingleItem` method, (2) new `SingleItemLogScreen` composable, (3) `autoLaunchScan` parameter on `FoodSearchScreen`, (4) navigation wiring in `App.kt`. Tasks 1–3 are independent of each other; task 4 depends on all three.

**Tech Stack:** Kotlin, Jetpack Compose, Room (in-memory for tests), Robolectric, `kotlinx.coroutines.test`.

## Global Constraints

- IDs are always UUIDs — never `Int`, `Long`, or auto-increment
- No meal type prompt anywhere in this flow — `mealType = "unknown"` hardcoded
- No `log_entry_item` rows — store as `quickAddKcal` + `quickAddLabel` only (YAGNI)
- Run `./gradlew :app:test` before every commit; commit only on `BUILD SUCCESSFUL`
- Conventional commits: `type(scope): message` — scope is `app` throughout

---

## File Map

| File | Action | Purpose |
|------|--------|---------|
| `app/src/main/kotlin/org/branneman/health/log/LogViewModel.kt` | Modify | Add `logSingleItem(label, kcal)` |
| `app/src/test/kotlin/org/branneman/health/log/LogViewModelTest.kt` | Modify | Test `logSingleItem` |
| `app/src/main/kotlin/org/branneman/health/ui/SingleItemLogScreen.kt` | Create | Grams input + live kcal preview + log button |
| `app/src/test/kotlin/org/branneman/health/ui/SingleItemLogScreenTest.kt` | Create | Compose UI tests for `SingleItemLogContent` |
| `app/src/main/kotlin/org/branneman/health/ui/FoodSearchScreen.kt` | Modify | Add `autoLaunchScan: Boolean = false` parameter |
| `app/src/main/kotlin/org/branneman/health/App.kt` | Modify | New `LogPage` values, new nav state, new log sheet entries, wiring |

---

## Task 1: `LogViewModel.logSingleItem`

**Files:**
- Modify: `app/src/main/kotlin/org/branneman/health/log/LogViewModel.kt`
- Test: `app/src/test/kotlin/org/branneman/health/log/LogViewModelTest.kt`

**Interfaces:**
- Produces: `fun logSingleItem(label: String, kcal: Int)` on `LogViewModel` — used by Task 2

- [ ] **Step 1: Write the failing test**

In `LogViewModelTest.kt`, add after the existing tests:

```kotlin
@Test
fun `logSingleItem creates a log entry with food name and computed kcal`() = runTest {
    val farFuture = OffsetDateTime.now().plusDays(30).toString()
    tokenStore.save("test-token", farFuture, userId)

    viewModel.logSingleItem("Apple", 85)
    testDispatcher.scheduler.advanceUntilIdle()

    val entries = db.logEntryDao().observeAll().first { it.isNotEmpty() }
    val entry = entries.single()
    assertEquals(85, entry.quickAddKcal)
    assertEquals("Apple", entry.quickAddLabel)
    assertEquals("unknown", entry.mealType)
    assertEquals(SyncStatus.PENDING_CREATE, entry.syncStatus)
}
```

- [ ] **Step 2: Run test to confirm it fails**

```
./gradlew :app:test --tests "org.branneman.health.log.LogViewModelTest.logSingleItem*"
```

Expected: `FAILED` — `logSingleItem` not defined.

- [ ] **Step 3: Implement `logSingleItem` in `LogViewModel.kt`**

Add after `logFromShortcut`:

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

- [ ] **Step 4: Run tests to confirm they pass**

```
./gradlew :app:test --tests "org.branneman.health.log.LogViewModelTest"
```

Expected: `BUILD SUCCESSFUL`, all tests green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/log/LogViewModel.kt \
        app/src/test/kotlin/org/branneman/health/log/LogViewModelTest.kt
git commit -m "feat(app): add LogViewModel.logSingleItem for quick single-item log"
```

---

## Task 2: `SingleItemLogScreen`

**Files:**
- Create: `app/src/main/kotlin/org/branneman/health/ui/SingleItemLogScreen.kt`
- Create: `app/src/test/kotlin/org/branneman/health/ui/SingleItemLogScreenTest.kt`

**Interfaces:**
- Consumes: `LogViewModel.logSingleItem(label: String, kcal: Int)` from Task 1; `LogViewModel.undoAdd()` (already exists)
- Produces:
  - `fun SingleItemLogScreen(item: FoodItemEntity, logViewModel: LogViewModel, onLogged: (() -> Unit) -> Unit, onBack: () -> Unit)` — used by Task 4
  - `fun SingleItemLogContent(foodName: String, kcalPer100g: Double, onLog: (kcal: Int) -> Unit, onBack: () -> Unit)` — testable inner composable

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/org/branneman/health/ui/SingleItemLogScreenTest.kt`:

```kotlin
package org.branneman.health.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SingleItemLogScreenTest {

    @get:Rule val compose = createComposeRule()

    private fun render(
        foodName: String = "Apple",
        kcalPer100g: Double = 200.0,
        onLog: (Int) -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                SingleItemLogContent(
                    foodName    = foodName,
                    kcalPer100g = kcalPer100g,
                    onLog       = onLog,
                    onBack      = onBack,
                )
            }
        }
    }

    @Test fun `Log button is disabled when grams field is empty`() {
        render()
        compose.onNodeWithTag("single_item_log_button").assertIsNotEnabled()
    }

    @Test fun `Log button is disabled when grams is zero`() {
        render()
        compose.onNodeWithTag("single_item_grams_field").performTextInput("0")
        compose.onNodeWithTag("single_item_log_button").assertIsNotEnabled()
    }

    @Test fun `kcal preview appears and is correct when grams are entered`() {
        render(kcalPer100g = 200.0)
        compose.onNodeWithTag("single_item_grams_field").performTextInput("150")
        // 150g * 200 kcal/100g = 300 kcal
        compose.onNodeWithTag("single_item_kcal_preview").assertTextContains("300 kcal")
    }

    @Test fun `kcal preview is not shown when grams field is empty`() {
        render()
        compose.onNodeWithTag("single_item_kcal_preview").assertDoesNotExist()
    }

    @Test fun `tapping Log calls onLog with correct kcal`() {
        var logged: Int? = null
        render(kcalPer100g = 200.0, onLog = { logged = it })
        compose.onNodeWithTag("single_item_grams_field").performTextInput("150")
        compose.onNodeWithTag("single_item_log_button").performClick()
        kotlin.test.assertEquals(300, logged)
    }

    @Test fun `back button calls onBack`() {
        var called = false
        render(onBack = { called = true })
        compose.onNodeWithText("← Back").performClick()
        assertTrue(called)
    }

    @Test fun `food name is shown as title`() {
        render(foodName = "Banana")
        compose.onNodeWithText("Banana").assertIsDisplayed()
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```
./gradlew :app:test --tests "org.branneman.health.ui.SingleItemLogScreenTest"
```

Expected: `FAILED` — `SingleItemLogContent` not defined.

- [ ] **Step 3: Implement `SingleItemLogScreen.kt`**

Create `app/src/main/kotlin/org/branneman/health/ui/SingleItemLogScreen.kt`:

```kotlin
package org.branneman.health.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.branneman.health.db.entities.FoodItemEntity
import org.branneman.health.log.LogViewModel
import kotlin.math.roundToInt

@Composable
fun SingleItemLogScreen(
    item: FoodItemEntity,
    logViewModel: LogViewModel,
    onLogged: (undoAction: () -> Unit) -> Unit,
    onBack: () -> Unit,
) {
    SingleItemLogContent(
        foodName    = item.name,
        kcalPer100g = item.kcalPer100g,
        onLog       = { kcal ->
            logViewModel.logSingleItem(item.name, kcal)
            onLogged { logViewModel.undoAdd() }
        },
        onBack      = onBack,
    )
}

@Composable
fun SingleItemLogContent(
    foodName: String,
    kcalPer100g: Double,
    onLog: (kcal: Int) -> Unit,
    onBack: () -> Unit,
) {
    var gramsText by remember { mutableStateOf("") }
    val grams = gramsText.toDoubleOrNull()
    val kcal  = grams?.takeIf { it > 0.0 }?.let { (it / 100.0 * kcalPer100g).roundToInt() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text("← Back") }

        Text(foodName, style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(
            value           = gramsText,
            onValueChange   = { gramsText = it },
            label           = { Text("Grams") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine      = true,
            modifier        = Modifier
                .fillMaxWidth()
                .testTag("single_item_grams_field"),
        )

        if (kcal != null) {
            Text(
                text     = "${gramsText}g → $kcal kcal",
                style    = MaterialTheme.typography.bodyMedium,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("single_item_kcal_preview"),
            )
        }

        Button(
            onClick  = { kcal?.let { onLog(it) } },
            enabled  = kcal != null,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("single_item_log_button"),
        ) { Text("Log") }
    }
}
```

- [ ] **Step 4: Run tests to confirm they pass**

```
./gradlew :app:test --tests "org.branneman.health.ui.SingleItemLogScreenTest"
```

Expected: `BUILD SUCCESSFUL`, all 7 tests green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/ui/SingleItemLogScreen.kt \
        app/src/test/kotlin/org/branneman/health/ui/SingleItemLogScreenTest.kt
git commit -m "feat(app): add SingleItemLogScreen with grams input and live kcal preview"
```

---

## Task 3: `FoodSearchScreen` — `autoLaunchScan` parameter

**Files:**
- Modify: `app/src/main/kotlin/org/branneman/health/ui/FoodSearchScreen.kt`

**Interfaces:**
- Produces: `autoLaunchScan: Boolean = false` parameter on `FoodSearchScreen` — consumed by Task 4. Default `false` keeps all existing call sites unchanged.

- [ ] **Step 1: Add `autoLaunchScan` parameter and `LaunchedEffect` to `FoodSearchScreen`**

In `FoodSearchScreen.kt`, change the signature and add a `LaunchedEffect`:

```kotlin
@Composable
fun FoodSearchScreen(
    onItemSelected: (FoodItemEntity) -> Unit,
    onBack: () -> Unit,
    autoLaunchScan: Boolean = false,           // ← new parameter
    viewModel: FoodSearchViewModel = viewModel(),
) {
    val context = LocalContext.current
    val query           by viewModel.query.collectAsStateWithLifecycle()
    val results         by viewModel.results.collectAsStateWithLifecycle()
    val selectedItem    by viewModel.selectedItem.collectAsStateWithLifecycle()
    val isOffline       by viewModel.isOffline.collectAsStateWithLifecycle()
    val barcodeNotFound by viewModel.barcodeNotFound.collectAsStateWithLifecycle()
    var showScanner      by remember { mutableStateOf(false) }
    var scanNoResult     by remember { mutableStateOf(false) }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) showScanner = true }

    // ← new: auto-launch scanner when requested
    LaunchedEffect(autoLaunchScan) {
        if (autoLaunchScan) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                showScanner = true
            } else {
                cameraPermission.launch(Manifest.permission.CAMERA)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.resetSearch() }
    }

    // ... rest of the function unchanged
```

The rest of `FoodSearchScreen` (from `LaunchedEffect(selectedItem)` onward) is unchanged.

- [ ] **Step 2: Run existing `FoodSearchScreenTest` to confirm nothing broke**

```
./gradlew :app:test --tests "org.branneman.health.ui.FoodSearchScreenTest"
```

Expected: `BUILD SUCCESSFUL`, all tests green.

- [ ] **Step 3: Run full app test suite**

```
./gradlew :app:test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/ui/FoodSearchScreen.kt
git commit -m "feat(app): add autoLaunchScan parameter to FoodSearchScreen"
```

---

## Task 4: Wire navigation in `App.kt`

**Files:**
- Modify: `app/src/main/kotlin/org/branneman/health/App.kt`

**Interfaces:**
- Consumes:
  - `SingleItemLogScreen(item, logViewModel, onLogged, onBack)` from Task 2
  - `FoodSearchScreen(..., autoLaunchScan = true)` from Task 3
  - `LogViewModel.logSingleItem` is called inside `SingleItemLogScreen` — no direct call here

- [ ] **Step 1: Extend `LogPage` enum with two new values**

In `App.kt`, change:

```kotlin
private enum class LogPage { Main, TemplateList, QuickAdd, AskAi, BuildFromScratch, FoodSearch }
```

to:

```kotlin
private enum class LogPage { Main, TemplateList, QuickAdd, AskAi, BuildFromScratch, FoodSearch, SingleItemSearch, SingleItemGrams }
```

- [ ] **Step 2: Add new nav state variables in `MainNav`**

Inside `MainNav`, after the existing `var loadIngredientTemplateId` declaration, add:

```kotlin
var selectedFoodItemForSingleLog by remember { mutableStateOf<FoodItemEntity?>(null) }
var singleItemAutoLaunchScan     by remember { mutableStateOf(false) }
```

- [ ] **Step 3: Clear new state in the cleanup `LaunchedEffect`**

In the `LaunchedEffect(currentTab)` block, inside the `if (currentTab != Tab.Log)` branch, add two lines after the existing resets:

```kotlin
if (currentTab != Tab.Log) {
    logPage = LogPage.Main
    showLogSheet = false
    pendingLogUndoAction = null
    quickAddPrefill = null
    loadIngredientTemplateId = null
    selectedFoodItemForSingleLog = null   // ← new
    singleItemAutoLaunchScan = false      // ← new
}
```

- [ ] **Step 4: Add `SingleItemSearch` and `SingleItemGrams` cases to the `LogPage` `when` block**

After the existing `LogPage.FoodSearch -> ...` case (and before `if (showLogSheet)`), add:

```kotlin
LogPage.SingleItemSearch -> FoodSearchScreen(
    onItemSelected = { item ->
        selectedFoodItemForSingleLog = item
        logPage = LogPage.SingleItemGrams
    },
    onBack         = {
        selectedFoodItemForSingleLog = null
        singleItemAutoLaunchScan = false
        logPage = LogPage.Main
    },
    autoLaunchScan = singleItemAutoLaunchScan,
)
LogPage.SingleItemGrams -> {
    val item = selectedFoodItemForSingleLog
    if (item != null) {
        SingleItemLogScreen(
            item         = item,
            logViewModel = logVm,
            onLogged     = { undoAction ->
                pendingLogUndoAction = undoAction
                selectedFoodItemForSingleLog = null
                singleItemAutoLaunchScan = false
                logPage = LogPage.Main
            },
            onBack       = {
                selectedFoodItemForSingleLog = null
                logPage = LogPage.SingleItemSearch
            },
        )
    }
}
```

- [ ] **Step 5: Add "Single item" and "Scan & log" to `LogFlowSheet`**

Change the `LogFlowSheet` signature to include two new callbacks:

```kotlin
@Composable
private fun LogFlowSheet(
    onFromTemplate: () -> Unit,
    onQuickAdd: () -> Unit,
    onAskAi: () -> Unit,
    onBuildFromScratch: () -> Unit,
    onSingleItem: () -> Unit,     // ← new
    onScanAndLog: () -> Unit,     // ← new
    onDismiss: () -> Unit,
)
```

Inside `LogFlowSheet`, after the existing "Build from scratch" `ListItem` + `HorizontalDivider`, append:

```kotlin
HorizontalDivider()
ListItem(
    headlineContent = { Text("Single item") },
    trailingContent = { Text("›", style = MaterialTheme.typography.titleLarge) },
    modifier = Modifier
        .clickable(onClick = onSingleItem)
        .testTag("log_single_item"),
)
HorizontalDivider()
ListItem(
    headlineContent   = { Text("Scan & log") },
    supportingContent = { Text("Scan barcode directly") },
    trailingContent   = { Text("›", style = MaterialTheme.typography.titleLarge) },
    modifier = Modifier
        .clickable(onClick = onScanAndLog)
        .testTag("log_scan_and_log"),
)
```

- [ ] **Step 6: Update the `LogFlowSheet` call site in `MainNav`**

The existing call to `LogFlowSheet` in the `if (showLogSheet)` block needs two new arguments. Change:

```kotlin
LogFlowSheet(
    onFromTemplate   = { showLogSheet = false; logPage = LogPage.TemplateList },
    onQuickAdd       = { showLogSheet = false; logPage = LogPage.QuickAdd },
    onAskAi          = { showLogSheet = false; logPage = LogPage.AskAi },
    onBuildFromScratch = { showLogSheet = false; buildVm.reset(); logPage = LogPage.BuildFromScratch },
    onDismiss        = { showLogSheet = false },
)
```

to:

```kotlin
LogFlowSheet(
    onFromTemplate     = { showLogSheet = false; logPage = LogPage.TemplateList },
    onQuickAdd         = { showLogSheet = false; logPage = LogPage.QuickAdd },
    onAskAi            = { showLogSheet = false; logPage = LogPage.AskAi },
    onBuildFromScratch = { showLogSheet = false; buildVm.reset(); logPage = LogPage.BuildFromScratch },
    onSingleItem       = { showLogSheet = false; singleItemAutoLaunchScan = false; logPage = LogPage.SingleItemSearch },
    onScanAndLog       = { showLogSheet = false; singleItemAutoLaunchScan = true;  logPage = LogPage.SingleItemSearch },
    onDismiss          = { showLogSheet = false },
)
```

- [ ] **Step 7: Run the full app test suite**

```
./gradlew :app:test
```

Expected: `BUILD SUCCESSFUL`, all tests green.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/App.kt
git commit -m "feat(app): wire Single item and Scan & log entries into log flow sheet"
```
