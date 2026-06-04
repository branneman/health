# Feature Spec — Logging

Builds on `1-principles.md` and `2-scenarios.md` (S03–S11). Covers how the user
records food, drink, and weight. Logging is the highest-traffic surface and the
make-or-break for whether the app gets used at all.

## Mental model

Logging is **"close out the meal"** — a discrete action taken *just after finishing*,
not a live race against eating. The user always knows the next tap. Speed matters less
than predictability. The screen's job: get the common case done in one tap, make the
rare case (a new dinner) possible without dread.

## The log screen — layout priority

The default logging surface, top to bottom, ordered by frequency of use:

1. **The one-tap buttons**, always visible, always in the same place:
    - `Usual breakfast` — logs the saved breakfast template as-is.
    - `Usual lunch` — logs the saved lunch template as-is.
    - `Usual late snack` — logs the saved late-night snack template as-is.
    - **Drink shortcuts** — one button per drink type configured by the user (see S15).
      Each is a single tap that logs that drink's preset kcal. No long-press, no "1 drink"
      ambiguity — calorie accuracy requires knowing the type. These same buttons appear on
      the homescreen widget (medium size) for one-tap logging without opening the app.
2. **`Log dinner`** — opens the dinner flow (below). Visually distinct because it's the
   one that takes real interaction.
3. **Today's entries** — a simple reverse-chronological list of what's been logged,
   each tappable to edit or delete. This is also where the day becomes *visible*:
   seeing "late snack" and "drink ×3" in the list is the gentle mirror, no
   commentary needed.

One-tap buttons must give **immediate, unmistakable confirmation** (the entry appears
in the list instantly, optimistic / offline-first) and must be **undoable in one tap**
— a misfire shouldn't cost anything.

## Dinner flow — the hard case

Dinner is the one genuinely variable meal. Tapping `Log dinner` presents three paths in
this order:

### Path 1: From template (most common)

- A `Dinner templates` list, most-recently/most-frequently used first.
- Tap a template → it logs immediately at the saved portion.
- A single **portion adjuster** (`Lighter` / `Normal` / `Heavier`) scales the whole
  meal without re-touching ingredients. Exact base, fuzzy scale.
- Editing individual ingredients is possible but never *required* for a repeat.

### Path 2: Quick-add by calories (common — when you know the number)

- A single number field: "How many kcal?"
- Logs immediately as "Dinner — ~X kcal (estimate)". No ingredients, no template needed.
- The primary path for any home-cooked meal where the user knows roughly what it was —
  from a recipe app, from memory, or from estimation. This is a first-class path, not a
  fallback.
- The "(estimate)" label is honest: no snapshotted nutrition detail, but it counts toward
  the daily calorie total.

### Path 3: Build from scratch (rare — first time, template-worthy dish)

- Build from ingredients via Open Food Facts search (barcode where packaged components
  exist, text search otherwise). Per-item quantity in grams.
- At save, prompt: **"Save as a dinner template?"** with a name (e.g. "chicken stir-fry").
  This is the moment exactness gets amortized.
- Snapshots nutrition values into the template so later Open Food Facts changes don't
  silently alter past logs or templates.
- **If the user bails mid-build**, fall through to Path 2 with the partial kcal total
  pre-filled. A rough log always beats no log.

### Design guardrails

- Never block a dinner log on complete data. Path 2 exists precisely for this.
- Templates are editable: dishes drift over time, the user should be able to update one
  in place.

## Weight logging

Optional input, treated gently (see principles — never nag, never punish).

- **One-tap morning entry**: a single number field, prefilled near the last value to
  minimize typing, in kg. Ideally reachable from the dashboard or a dedicated small
  card, not buried.
- Logging weight is *encouraged but never required*. No reminder if skipped, no "you
  missed your weigh-in" language.
- Daily raw value is stored but **never shown as the headline** — the dashboard shows a
  smoothed trend (see `3-features/dashboard.md`). A heavy-water morning must not read as
  failure.

## Forgetting-to-log reminder

- One **gentle** end-of-day local notification *only if nothing was logged that day*
  (or only breakfast/lunch auto-candidates are missing). Phrasing is neutral: a nudge,
  not guilt.
- User-disableable. Never escalates, never stacks.

## Correcting entries

Log entries are **immutable** — no in-place edit. To correct a mistake: delete the
entry and re-log using any logging path. This is an API-level constraint (snapshot
integrity), not a UX choice; the UI presents it as "delete to correct" without
surfacing the technical reason.

- Every entry is **deletable** from today's list in one tap. Deletion is immediate
  and updates the day's calorie total instantly.
- Past days are accessible from history (see `3-features/dashboard.md`). Same
  delete-and-relog flow applies.

## Offline behavior

- All logging writes to Room first and succeeds offline. Open Food Facts search is the
  only network-dependent part of the dinner flow; cache eaten items locally so repeat
  dinners and all templates work fully offline.

## Open questions for implementation

- Exact gesture for the portion adjuster (stepper vs slider vs presets) — prototype
  both, pick by feel.
- Auto-suggesting a dinner template based on day-of-week patterns (later, not v1).
