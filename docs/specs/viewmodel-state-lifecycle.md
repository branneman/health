# Tech debt: ViewModel state lifecycle fixes

## Background

Because this app uses enum-based navigation (no NavHost), all ViewModels are
Activity-scoped and persist across every navigation event. A bug class was identified
where screens display stale state from a previous session on re-entry.

The root cause and the correct patterns are documented in CLAUDE.md. This spec
covers the concrete fixes needed to bring existing screens in line with those
patterns.

## Why "reset on exit", not "reset on entry"

`LaunchedEffect(Unit) { viewModel.reset() }` — the original pattern — fires *after*
the first composition. By definition, stale state is always visible for at least one
frame. It treats the symptom rather than the cause.

`DisposableEffect(Unit) { onDispose { viewModel.reset() } }` fires when the
composable *leaves* composition. The ViewModel is clean before re-entry. No flash.

The only risk with reset-on-exit is a running coroutine that completes after `reset()`
fires and writes state back. The fix is to cancel in-flight coroutines inside
`reset()`. Each fix below notes where this applies.

## Fixes

### 1. `AskAiViewModel` — cancel in-flight coroutine in `reset()`

`AskAiScreen` already uses `DisposableEffect` + `onDispose { viewModel.reset() }`,
so the exit-reset pattern is correct. The bug is that `estimate()` launches a
coroutine without tracking the job, so `reset()` cannot cancel it. If the user
navigates away mid-estimate, the coroutine completes after reset and writes
`AskAiState.Result` back into the ViewModel. The next entry to `AskAiScreen`
shows the stale result.

**Fix in `AskAiViewModel`:**
- Track the estimate job: `private var estimateJob: Job? = null`
- In `estimate()`: `estimateJob = viewModelScope.launch { ... }`
- In `reset()`: `estimateJob?.cancel(); estimateJob = null` before clearing state

### 2. `FoodSearchScreen` — switch from `LaunchedEffect` to `DisposableEffect`

`FoodSearchScreen` currently uses:
```kotlin
LaunchedEffect(Unit) { viewModel.resetSearch() }
```
This is the wrong pattern — stale query and results flash on re-entry.

**Fix in `FoodSearchScreen`:**
```kotlin
DisposableEffect(Unit) { onDispose { viewModel.resetSearch() } }
```

`resetSearch()` has no in-flight coroutine to cancel (the search coroutine
in `init` is driven by the `_query` flow; clearing `_query.value = ""` stops
it from emitting new searches). No additional cancellation needed.

### 3. `EditIngredientTemplateViewModel` — clear state in `loadTemplate()`

`loadTemplate(null)` (called when opening a new template) exits early without
clearing `_name` or `_ingredients`. Opening a new template after editing an
existing one shows the previous template's data.

Additionally, when switching between two existing templates, the coroutine
loads the new data asynchronously without first clearing state, causing a
flash of the previous template.

**Fix in `EditIngredientTemplateViewModel.loadTemplate()`:**
```kotlin
fun loadTemplate(id: String?) {
    templateId = id
    _name.value = ""               // clear synchronously, before any async work
    _ingredients.value = emptyList()
    if (id == null) return
    viewModelScope.launch { ... }  // existing load logic unchanged
}
```

No coroutine cancellation concern here: each call to `loadTemplate` starts a
fresh coroutine, and since `_name`/`_ingredients` are cleared first, a
late-completing previous coroutine would overwrite the blank state with stale
data. If this races in practice, track the load job and cancel it on each call.

### 4. `AiConfigScreen` — clear `saveError` on exit

`AiConfigViewModel.saveError` is never cleared when the screen is left. If a
previous key-save failed, re-entering the AI config screen shows the error
banner immediately, even though no save is in progress.

**Fix in `AiConfigScreen`:**
```kotlin
DisposableEffect(Unit) { onDispose { viewModel.saveError.value = false } }
```

## Screens confirmed clean (no fix needed)

| Screen | Reason |
|---|---|
| `BuildFromScratchScreen` | Session-aware: `reset()` called at session boundary in `MainNav` |
| `QuickAddScreen` | Form state is local `remember` |
| `ProfileSettingsScreen` | Calls `viewModel.load()` on entry; shows "Loading…" during async load |
| `GoalSettingsScreen` | Same |
| `ScheduleSettingsScreen` | Same |
| `MealButtonsScreen` / `DrinkButtonsScreen` | Draft from DB flow; local rows use `remember(draft)` |
| `TemplatesScreen` | All UI state local; templates DB-observed |
| `TemplateListScreen` | All local `remember` |
| `LogScreen` | All local `remember`; data DB-observed |
| `DashboardScreen` | Read-only, DB-observed |
| `SettingsScreen` | `serverReachable` is local + re-checked on every entry via `LaunchedEffect(Unit)` |
