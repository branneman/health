# Settings Screen Redesign

Redesigns the Settings screen to expose all onboarding-configurable data as editable
sub-pages, organise the screen into clear sections, and fix typography inconsistencies
in the sync/server area.

---

## Problem

The current Settings screen is a flat, unsectioned column that:

- Has no way to edit any profile data set during onboarding (sex, height, age, goal
  weight, activity level, calorie deficit, wake/bedtime).
- Mixes raw status text with navigation buttons and destructive actions, all at the
  same visual weight.
- Has a typography bug: `Text("Server: …")` has no explicit style and renders at the
  ambient default (`bodyLarge`), while adjacent rows use explicit `bodyMedium`.

---

## Design

### Main screen structure

Four sections separated by dividers, with section header labels:

**Profile**
- Profile → (sex, height, age, goal weight)
- Goal → (activity level, calorie deficit)
- Schedule → (wake time, bedtime)

**Quick buttons**
- Meal buttons →
- Drink buttons →

**Connections**
- Polar watch — shows "Connected" (green chip) or "Not connected" (muted text +
  inline Connect outlined button). No disconnect option; tokens don't expire.

**Sync**
- Server — single `ListItem` row: title "Server", supporting text
  "Online · synced 4 Jun, 12:30" (or "Offline" when unreachable), trailing
  "Sync now" `TextButton`.

Footer (below final divider): version string, then Sign out (error colour).

All rows use `ListItem` with `headlineContent` (`bodyMedium`) and `supportingContent`
(`bodySmall`, `onSurfaceVariant`). No bare `Text` with implicit style.

---

### Sub-page template

All three new sub-pages (Profile, Goal, Schedule) share the same shell:

- Top bar: back chevron + page title (reuses the existing `SettingsPage` enum pattern
  in `App.kt`).
- Scrollable content area.
- Save area pinned at the bottom:
  - When no changes: `Save` button disabled (grey).
  - When dirty: small amber note "⚠ Unsaved changes" appears above the `Save` button;
    `Save` button becomes enabled.
- On back navigation while dirty (both the top-bar back button and the Android
  system back gesture): show a discard-changes confirmation dialog
  ("Discard unsaved changes? Your changes will be lost.") with Discard / Keep editing
  actions. Popping only happens after the user confirms Discard.
- On save success: pop back to main Settings. On save failure: show inline error
  (same pattern as onboarding's `saveError`).

---

### Profile sub-page

Fields (pre-populated from the server on open):

| Field | Input | Notes |
|---|---|---|
| Sex | `FilterChip` pair (Male / Female) | |
| Height (cm) | `OutlinedTextField`, numeric | |
| Age | `OutlinedTextField`, numeric | |
| Goal weight (kg) | `OutlinedTextField`, decimal | If the entered value exceeds the most recent logged weight, show a supporting-text warning ("Goal is above your current weight") but do **not** block saving — the user may be updating goal weight independently of today's weigh-in. |

Save POSTs to the existing profile endpoint. Current weight is **not** shown here;
it is managed via the dashboard weigh-in widget.

---

### Goal sub-page

Fields (pre-populated from the server on open):

| Field | Input | Notes |
|---|---|---|
| Activity level | Radio button list (same 3 options as onboarding step 2) | |
| Target deficit | `Slider` 0–600 kcal, 25 kcal steps (same as onboarding step 3) | |

Live feedback below the slider (updates as the user drags):
- `≈ X.XX kg/week`
- `Goal reached in ~N months` (omitted when deficit = 0)
- Warning if deficit > 500: "⚠ Above 500 kcal/day risks muscle loss."

Save POSTs to the existing profile endpoint.

---

### Schedule sub-page

Fields (pre-populated from the server on open):

| Field | Input | Notes |
|---|---|---|
| Wake time | `TimeAdjustRow` ±30 min (same as onboarding step 4) | |
| Bedtime | `TimeAdjustRow` ±30 min | |

Brief subtitle: "Used to calculate how much eating time is left in your day."

Save POSTs to the existing profile endpoint.

---

## Navigation

`App.kt` already has a `SettingsPage` enum. Extend it:

```
enum class SettingsPage { Main, MealButtons, DrinkButtons, Profile, Goal, Schedule }
```

Back navigation from any sub-page resets to `SettingsPage.Main` (existing pattern).
The dirty-state discard dialog intercepts the back action only when there are unsaved
changes.

---

## Data loading

Each sub-page loads its current values from the server every time it is navigated
to (same auth token pattern used elsewhere). This ensures edits made elsewhere
(e.g. a future web UI) are reflected without requiring an app restart. Show a
loading state while fetching; show an error with a retry option if the fetch fails.

---

## Out of scope

- Disconnect Polar (tokens don't expire; no user need identified).
- Notification settings (23 (End-of-day notification), not yet built).
- Vacation / maintenance mode toggles (25 (Vacation mode) and 26 (Maintenance mode), not yet built).
- Any change to Meal buttons or Drink buttons sub-pages.
