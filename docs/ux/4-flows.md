# Screen Flows

Translates `2-scenarios.md` and `3-features/` into exact screen definitions: layout,
navigation, and state variants per screen. This is the last design layer before the
Compose implementation.

Each screen covers:

- **Layout** — what's on screen, top to bottom, in priority order
- **Navigation** — what opens this screen and what taps lead out
- **States** — empty, partial-data, error, and loading variants

Cross-references: scenario IDs (S01–S16) and feature specs (`3-features/`).

---

## Navigation map

```
Homescreen widget ──────────────────────────────► tap → Dashboard
                                                              │
Login (F01, step 0) → Onboarding (F01) ────────► Dashboard (F03)
                                                      │      │     │
                                              Log screen  Past-day  Settings
                                                (F04)     view      (F06)
                                                  │       (F07)
                                           Food log flow
                                        (F05a / F05b / F05c)
```

Bottom navigation (always visible): `[ Dashboard ]  [ Log ]  [ Settings ]`

---

## F01 — Onboarding

**Triggered by:** first app launch; no local account token exists  
**Scenarios:** S01

### Step 0 — Login

```
┌──────────────────────────────┐
│  Health                      │
│  Sign in to your account     │
├──────────────────────────────┤
│  Email   [ _______________ ] │
│  Password[ _______________ ] │
├──────────────────────────────┤
│          [ Sign in ]         │
└──────────────────────────────┘
```

- No "create account" — single-user app; account is pre-provisioned
- Sign in requires a network connection; all subsequent steps work offline
- On success: token stored in secure local storage
- If the server account already holds a completed profile (reinstall / new phone),
  steps 1–3 are pre-filled and each can be confirmed in one tap
- If the account already has drink shortcuts saved, F06 is pre-populated automatically

### Step 1 — Biometrics (1 of 3)

```
┌──────────────────────────────┐
│  Set up your profile   1/3   │
├──────────────────────────────┤
│  Sex         ○ Male ○ Female │
│  Height      [ 177 ] cm      │
│  Current weight [ 84.0 ] kg  │
│  Goal weight    [ 74.0 ] kg  │
│  Age         [ 39  ] years   │
├──────────────────────────────┤
│          [ Continue ]        │
└──────────────────────────────┘
```

- Sex: 2-option toggle, not a dropdown
- Keyboard: numeric with decimal for weights; integer for height and age
- Goal weight must be ≤ current weight (or equal, for maintenance from day one)
- Continue is disabled until all fields are filled
- No Back on step 1

### Step 2 — Activity level (2 of 3)

```
┌──────────────────────────────┐
│  How active are you?   2/3   │
├──────────────────────────────┤
│  ○ Mostly sitting            │
│    Desk job, ≤1 sport/week   │
│  ● Lightly active  ← default │
│    2–4 sport sessions/week   │
│  ○ Moderately active         │
│    5+ sessions/week          │
├──────────────────────────────┤
│  Estimated output:           │
│  ~2,400 kcal/day (estimated) │ ← updates live as selection changes
├──────────────────────────────┤
│  [ Back ]      [ Continue ]  │
└──────────────────────────────┘
```

- "estimated" label always visible — sets expectations before Polar is connected
- Default: lightly active (middle option)

### Step 3 — Target deficit (3 of 3)

```
┌──────────────────────────────┐
│  How fast?              3/3  │
├──────────────────────────────┤
│  ◄────────●──────────────►   │ ← slider, range 0–600
│          300 kcal/day        │
│                              │
│  Recommended range:          │
│  250–400 kcal/day            │ ← zone highlighted on slider track
│                              │
│  ≈ 0.27 kg/week              │
│  Goal reached in ~8 months   │ ← (current − goal weight) × 7700 / deficit
│                              │   updates live as slider moves
│                              │   "Maintain weight" when slider = 0
├──────────────────────────────┤
│  ⚠ Above 500 kcal/day risks  │
│    muscle loss.              │ ← visible only when slider > 500
├──────────────────────────────┤
│  [ Back ]          [ Done ]  │
└──────────────────────────────┘
```

- Slider range: 0–600 kcal. 0 = "Maintain weight" (maintenance from day one). 250–400
  zone is visually highlighted — user sees where "sensible" is without being forced into it.
- "Goal reached in ~N months" = `(current_weight − goal_weight) × 7700 / deficit_kcal_per_day / 30`.
  Requires both current weight and goal weight from step 1.
- At slider = 0: months line replaced with "Maintain weight — no active deficit."
- Warning at >500 kcal/day: always visible when triggered, never blocks Done.
- Default: 300 kcal/day.

### Step 4 — Connect Polar

```
┌──────────────────────────────┐
│  Connect your Polar watch    │
├──────────────────────────────┤
│  The app tracks how many     │
│  calories you burn each day. │
│  Without Polar, everything   │
│  is estimated.               │
│                              │
│  [ Connect Polar ]           │ ← opens Polar OAuth in browser
│                              │
│  [ Skip for now —            │
│    I'll use estimates ]      │ ← label communicates the trade-off
└──────────────────────────────┘
```

- "Connect Polar" opens the Polar OAuth flow in the device browser; on completion the
  browser redirects back to the app with the token
- The skip label is honest: "I'll use estimates" — not a neutral "Skip"
- This step is not labelled "optional" — Polar is the core of the calorie-out data

### After step 4 — landing

App navigates to Dashboard (F03). A dismissible banner at the top covers drink shortcuts:

```
┌──────────────────────────────┐
│ One more thing             × │
│ Set up drink shortcuts for   │
│ quick logging from the widget│
│ [ Set up now ]               │
└──────────────────────────────┘
```

Dismissible with ×. If shortcuts were already restored from the server account,
this banner does not appear.

### Onboarding states

| State                           | Behaviour                                                                   |
|---------------------------------|-----------------------------------------------------------------------------|
| Login — wrong credentials       | Inline error under password field; no modal                                 |
| Login — no network              | "Check your connection — login requires the internet"                       |
| Steps 1–3 — offline after login | Fully functional; BMR is computed locally                                   |
| Pre-filled from account         | Each step shows existing values; Continue confirms without changes          |
| App killed mid-flow             | Restarts from step 0 (login); token check skips step 0 if already signed in |
| Polar OAuth cancelled/failed    | Returns to step 4 with an error note; skip is still available               |

---

## F02 — Widget

**Scenarios:** S09  
**Feature spec:** `3-features/dashboard.md`

Glance composition. Two supported sizes.

### Small widget

```
┌──────────────┐
│ ●  On track  │
│ 450 kcal left│
└──────────────┘
```

- Verdict dot: green / amber / grey (grace period / vacation)
- One short verdict label: "On track" / "Slightly behind" / "Watch intake" / "On pause"
- Today's remaining budget in kcal
- Tap anywhere → opens app to Dashboard (F03)

### Medium widget

```
┌─────────────────────────────┐
│ ●  On track   ╌╌╌╌╌╌╌╌╌╱   │ ← verdict + weight sparkline (7 readings)
│ 450 kcal left               │
├─────────────────────────────┤
│ [🍺 Pils] [🍺 Weizen] [🍷 Wine] │ ← shortcuts (configured in S15)
│ [🥃 Scotch]                     │
└─────────────────────────────────┘
```

- Sparkline: weight trend direction over the last 7 readings; no axis labels, no numbers
- Shortcut buttons show emoji icon + short label; icon gives instant visual recognition
- Each button: single tap writes an entry to local storage immediately (offline-safe)
- No confirmation on the widget itself; entry is visible next time the app opens
- Shortcuts row is absent if none are configured (never an empty row)

### Widget states

| State                    | Behaviour                                                     |
|--------------------------|---------------------------------------------------------------|
| No weight data yet       | Sparkline omitted; verdict still shows if calorie data exists |
| Grace period (< 14 days) | Grey dot, "Building baseline"                                 |
| Vacation mode            | Grey dot, "On pause"                                          |
| Polar not connected      | Budget shows, labelled "(estimated)" in smaller text          |
| No data at all           | "Open app to get started"                                     |

---

## F03 — Dashboard

**Scenarios:** S02, S05, S13, S14  
**Feature spec:** `3-features/dashboard.md`

Primary landing screen. Scrollable. Sections are always in this order.

```
┌──────────────────────────────┐
│ Health             ···       │ ← overflow: contains Pause/Resume, settings link
├──────────────────────────────┤
│ Thursday, 5 Jun              │
│                              │
│ ─────────── Today ────────── │
│  2,150 out  ·  1,380 in      │
│  770 kcal remaining          │ ← neutral style; no color
│  (includes planned climb     │
│   ~600 kcal)                 │ ← shown only when sport-tonight is active
│                              │
│  [ 84.0 kg ✓ ] tap to edit  │ ← weight entry; pre-filled from last log
│  [ ⚡ Sport tonight: off ]   │ ← toggle; tap opens picker (below)
│                              │
│ ──── This week ─────────────  │
│ ●  Down 0.3 kg — on track.  │ ← verdict; color matches state
│                              │
│ [Weight trend chart]         │ ← 7-day SMA line + faint raw dots
│ [Calorie balance bars]       │ ← in vs out per day, this week
│                              │
│ Insights                     │ ← section absent if no insights to show
│ · Late snack 5 of 7 evenings │
│                              │
│ History          [wk] [mo]   │
│  Mon Tue Wed Thu Fri Sat Sun │ ← tap any day → past-day view (F07)
└──────────────────────────────┘
```

### Sport-tonight picker (bottom sheet)

Opens when the sport-tonight toggle is tapped.

```
┌──────────────────────────────┐
│ Sport tonight                │
├──────────────────────────────┤
│  Activity:  [ Climbing  ▼ ]  │ ← Climbing / Rowing / Other
│                              │
│  Intensity:                  │
│  ○ Light  ●Normal  ○ Hard   │
│                              │
│  Estimate: ~600 kcal         │ ← updates live; shown before confirming
├──────────────────────────────┤
│  [ Cancel ]      [ Set ]     │
└──────────────────────────────┘
```

- After Set: toggle label updates to "⚡ Climbing, Normal — ~600 kcal"
- Tapping the active toggle re-opens the picker (to change or clear)
- A "Clear" option appears inside the picker when already set

### Weight entry interaction

- Tap the weight field → becomes an inline editable field (no separate screen)
- Pre-filled with last logged value
- Confirm with keyboard Done or by tapping elsewhere
- If not logged today: field shows last value + "(last: N days ago)" in muted style

### Dashboard states

| State                          | Behaviour                                                                                                        |
|--------------------------------|------------------------------------------------------------------------------------------------------------------|
| Polar not connected            | `calories_out` shows "~2,200 kcal (estimated)" in muted style                                                    |
| Using yesterday's Polar        | Budget shows "(based on yesterday)" note                                                                         |
| No weight logged today         | Field shows last known value + muted "(N days ago)"                                                              |
| First 14 days (grace period)   | Weekly section: "Building baseline — keep logging." No verdict dot.                                              |
| Insufficient weigh-ins         | "Not enough weigh-ins for a verdict this week."                                                                  |
| Vacation mode                  | Header shows "Paused" in muted style; weekly verdict → "On pause"                                                |
| No entries this week           | Charts show axes + "No data logged this week."                                                                   |
| Deficit too fast (amber-fast)  | Amber verdict: "Dropping quickly — make sure you're eating enough to perform."                                   |
| Maintenance mode (deficit = 0) | Budget label → "X kcal remaining (balance)"; weekly verdict uses stability states from `3-features/dashboard.md` |

---

## F04 — Log screen

**Scenarios:** S03, S04, S09, S10, S12  
**Feature spec:** `3-features/logging.md`

Reached via the Log tab in bottom nav, or by tapping a notification.

```
┌──────────────────────────────┐
│ Log                          │
├──────────────────────────────┤
│ [ Usual breakfast  430 kcal ]│
│ [ Usual lunch      560 kcal ]│
│ [ Late snack       210 kcal ]│
│                              │
│ [🍺 Pils] [🍺 Weizen]        │ ← shortcuts; icon + short label per item
│ [🍷 Wine] [🥃 Scotch]        │   (count varies per user config)
│                              │
│ [ Log                      › ]│ ← visually distinct; leads to F05
├──────────────────────────────┤
│ Today                        │
│  08:14  Breakfast    430 kcal│ ← tap → delete sheet
│  13:02  Lunch        560 kcal│
│  20:47  Pasta at work 800 kcal│ ← quick-add with label
│  21:15  🍺 Pils      140 kcal│ ← shortcut entry uses icon + label
└──────────────────────────────┘
```

### One-tap button behaviour

Each tap:

1. Writes to local storage immediately (optimistic, offline-safe)
2. Entry appears at top of today's list instantly
3. Snackbar: "Breakfast logged · Undo" — visible for ~4 seconds
4. Undo removes the entry and recalculates the day's total

### Entry tap — delete sheet

```
┌──────────────────────────────┐
│ Breakfast — 430 kcal — 08:14 │
├──────────────────────────────┤
│     [ Delete ]               │
│     [ Cancel ]               │
└──────────────────────────────┘
```

- Tap a quick-add entry → edit dialog (kcal + label)
- Tap a food-item entry → delete-confirm dialog (immutable — see `3-features/logging.md`)
- Delete is immediate; no second confirmation
- Budget recalculates as soon as entry is removed

### Log screen states

| State                           | Behaviour                                                                                            |
|---------------------------------|------------------------------------------------------------------------------------------------------|
| No entries today                | List shows "Nothing logged today." (neutral)                                                         |
| One-tap template not configured | Button is greyed + tap opens "Set up your usual breakfast first" prompt with link to template editor |
| No shortcuts configured         | Shortcuts row is absent (not shown as empty)                                                         |
| Offline                         | All logging works normally                                                                           |

---

## F05 — Log flow

**Scenarios:** S06, S07, S08  
**Feature spec:** `3-features/logging.md`

Tapping `Log` on F04 opens a bottom sheet with three paths.

```
┌──────────────────────────────┐
│ Log                          │
├──────────────────────────────┤
│ [ From template           › ]│
│ [ Quick-add calories      › ]│
│ [ New dish                › ]│
└──────────────────────────────┘
```

### F05a — From template (S06)

```
┌──────────────────────────────┐
│ ← Templates                  │
├──────────────────────────────┤
│ 📌 Chicken stir-fry 620 kcal │ ← pinned templates first
│ ─────────────────────────── │
│  Lentil soup        480 kcal │ ← then alphabetical
│  Pasta bolognese    720 kcal │
│  Salmon + veg       540 kcal │
└──────────────────────────────┘
```

Sorting: pinned templates at top (user-configured), then remaining templates
alphabetically. Rationale: "most recently used" surfaces exactly the items just eaten —
the ones least likely to be wanted again, since variety is the typical goal. Alphabetical
is predictable; pinning covers go-to dishes.

Tapping a template:

```
┌──────────────────────────────┐
│ Chicken stir-fry             │
│ 620 kcal · normal portion    │
├──────────────────────────────┤
│  Portion:                    │
│  [ Lighter −20% ]            │
│  [● Normal       ]           │
│  [ Heavier +20% ]            │
├──────────────────────────────┤
│           [ Log ]            │
└──────────────────────────────┘
```

- Lighter = ×0.8, Normal = ×1.0, Heavier = ×1.2 (multipliers tunable)
- "Log" writes immediately; undo snackbar appears

Template list states:

- No templates yet: "No templates saved. Log a new dish (New dish) or use Quick-add to get started."
- Template list is scrollable; no search in v1

### F05b — Quick-add by calories (S07)

```
┌──────────────────────────────┐
│ ← Quick-add                  │
├──────────────────────────────┤
│  How many kcal?              │
│  ┌──────────────────┐        │
│  │  800             │        │
│  └──────────────────┘        │
│                              │
│  What was it? (optional)     │
│  ┌──────────────────┐        │
│  │  Pasta at work   │        │
│  └──────────────────┘        │
│                              │
│  "Pasta at work — 800 kcal"  │
│                              │ ← preview updates live; no label → "800 kcal"
├──────────────────────────────┤
│           [ Log ]            │
└──────────────────────────────┘
```

- Numeric keyboard opens immediately on arrival; label field is next in tab order
- "Log" enabled as soon as a kcal value is entered; label is always optional
- Label is freeform, short — a memory aid, not a categorisation field
- Undo snackbar on confirm

### F05c — Build from scratch (S08)

```
┌──────────────────────────────┐
│ ← New dish                   │
│                              │
│  🔍 [Search ingredient...  ] │ ← Open Food Facts; camera icon for barcode
│                              │
│  Ingredients:                │
│  · Chicken breast   150 g    │ ← tap to edit grams
│  · Soy sauce         20 ml   │
│                              │
│  Running total: 320 kcal     │ ← live
│                              │
│  [ Bail — log ~320 kcal  ]   │ ← escape to F05b, pre-filled with total
│          [ Done ]            │ ← disabled until ≥ 1 ingredient added
└──────────────────────────────┘
```

Tapping Done:

```
┌──────────────────────────────┐
│ Save as a template?          │
├──────────────────────────────┤
│  [ ________________________ ]│ ← name field
│                              │
│  [ Skip ]   [ Save & log ]   │
└──────────────────────────────┘
```

- Skip: logs without saving a template
- Save & log: saves template then logs; both are immediate
- Undo snackbar on both paths

F05c states:

- No ingredients yet: Done button disabled
- Offline: search row shows "Search unavailable offline — use Quick-add instead"; bail button always
  enabled
- Unknown barcode: "Product not found. Enter details manually."

---

## F06 — Settings: shortcuts

**Scenarios:** S15  
**Feature spec:** `3-features/logging.md`

Reached from: Settings screen → "Shortcuts", or tapping a contextual "+" on F04.

```
┌──────────────────────────────┐
│ ← Shortcuts                  │
├──────────────────────────────┤
│  🍺 Pils    140 kcal      ≡  │ ← ≡ drag handle for reorder
│  🍺 Weizen  220 kcal      ≡  │
│  🍷 Wine    120 kcal      ≡  │
│  🥃 Scotch   65 kcal      ≡  │
├──────────────────────────────┤
│  [ + Add shortcut ]          │
└──────────────────────────────┘
```

Tapping any item or "+ Add shortcut":

```
┌──────────────────────────────┐
│ Edit shortcut                │
├──────────────────────────────┤
│  Icon   [ 🍺 ]               │ ← emoji picker; tap to change
│  Label  [ Weizen           ] │ ← short; shown next to icon on button
│  Kcal   [ 220              ] │
├──────────────────────────────┤
│  [ Delete ]      [ Save ]    │
└──────────────────────────────┘
```

- Icon: tap opens the system emoji keyboard; no custom icon set needed
- Label is short — fits beside the icon on a small button (4–10 chars)
- Two different items with the same icon (e.g. two beers) are distinguished by label
- Delete only shown when editing an existing shortcut
- Changes take effect immediately in F04 and on the next widget refresh
- Order here matches display order on F04 and the widget
- Widget shows the first 4 configured shortcuts (practical size limit)

First-run state: pre-populated with suggested defaults:
🍺 Pils 140 kcal · 🍺 Weizen 220 kcal · 🍷 Wine 120 kcal · 🥃 Scotch 65 kcal.
User can edit, delete, or reorder before first use.

---

## F07 — Past-day view

**Scenarios:** S12, S13  
**Feature spec:** `3-features/dashboard.md`

Reached by tapping a day in the history row of F03.

```
┌──────────────────────────────┐
│ ← Wednesday, 1 Jun           │ ← swipe left/right to navigate days
├──────────────────────────────┤
│  2,200 out  ·  1,850 in      │
│  350 kcal under budget       │ ← neutral label, no colour judgment
├──────────────────────────────┤
│  08:05  Breakfast   430 kcal │ ← tap → delete sheet (same as F04)
│  13:10  Lunch       560 kcal │
│  19:30  Pasta at work 750 kcal│
│  21:20  Late snack  210 kcal │
├──────────────────────────────┤
│  Weight: 83.5 kg             │ ← shown only if logged that day
└──────────────────────────────┘
```

- Swipe left/right or tap ← › to navigate to adjacent days
- Entries are deletable (same sheet as F04); budget recalculates for that day
- No "add entry" for past days in v1 — delete-only; re-log on the current day if needed
- Days in a vacation period show a muted "Paused period" banner at top

Past-day states:

| State            | Behaviour                            |
|------------------|--------------------------------------|
| No entries       | "Nothing logged this day."           |
| No weight logged | Weight row absent (not "not logged") |
| Vacation period  | Muted "Paused period" banner at top  |

---

## Cross-cutting

### Bottom navigation

```
[ Dashboard ]    [ Log ]    [ Settings ]
```

Always visible. Dashboard is the default landing tab.

### Undo timing

All one-tap logging actions offer a 4-second undo window via snackbar.
After 4 seconds the entry is committed. Delete is the correction path thereafter (S12).

### Offline behaviour

App is offline-first. No disruptive banners for connectivity state.
The only surface with a contextual offline note is F05c (build from scratch search).
Polar sync lag is indicated inline on F03 (see dashboard states table).

### Notifications

End-of-day reminder (S11): local, ~21:00, only when no food has been logged since lunch.

- Tap → opens F04 directly
- User-disableable in Settings
- Never fires during vacation mode
