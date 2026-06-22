# UX Principles

Shared design philosophy for the health app. The feature specs
(`3-features/logging.md`, `3-features/dashboard.md`, `3-features/insights.md`) all build on
this — read this first. Read `2-scenarios.md` next for concrete interaction scenarios.
Small group of invited users, admin-provisioned. Android, Kotlin + Compose, Glance widget.

These are *interface principles*, derived from how the user actually behaves, not
generic best practices. Where a principle exists to counter a specific habit or
risk, that's noted — don't "clean it up" without understanding why it's there.

## The core tension this app must resolve

The user wants **exact** tracking but knows exactness is **painful**, and dinner —
the only meal that needs real logging — is **fresh-cooked from ingredients**, so
barcode scanning (the planned low-friction path) mostly doesn't apply to the hardest
case. There is no way to make nightly per-ingredient logging both exact and
frictionless. The design resolves this by **amortizing exactness**: pay the cost
once (define a meal carefully), reuse it cheaply forever (templates + portion tweak).
Every logging decision flows from this.

## Two-speed feedback: gentle day, blunt week

This is the single most important principle. The user wants blunt accountability and a
red/green verdict, but identifies the *weekly* trend as the real signal — not individual
days. Resolution:

- **The day is shown calmly and neutrally.** No red verdict on a single day. Home-
  cooked dinners and stressful-week drinking *will* spike individual days; a daily red
  light on a normal day teaches the user to avoid the app. Daily = neutral status +
  numbers, no judgment color.
- **The week earns directness.** The weekly trend is stable, it's what moves the
  scale, and it's where blunt + red/green is motivating rather than punishing. Weekly
  = the colored verdict, allowed to be direct.

Rule of thumb: *be honest about the trend, kind about the day.* This honesty runs in
both directions — the verdict also flags *over-restriction*. If the weight trend shows
dropping faster than ~0.5 kg/week, an amber warning surfaces: "Dropping quickly — make
sure you're eating enough to perform." The app must not silently celebrate an aggressive
deficit. (See `0-context-private.md` for background.)

## Two phases: loss and maintenance

The app has two distinct operating modes, and the design must handle both:

- **Loss phase** (default): target deficit > 0. The weekly verdict measures whether
  the weight trend is consistent with the target loss rate. Most of the design
  decisions in these specs assume this phase.
- **Maintenance phase**: target deficit = 0. Goal achieved — now the job is avoiding
  regress. The weekly verdict flips: it now measures *stability*, not loss rate.
  "Stable" is success; "creeping up" is the amber signal; "dropping too fast" still
  applies. The daily budget becomes "eat to balance" rather than "eat at a deficit."

Transitioning to maintenance is an explicit user action (settings), not automatic.
The app may surface a gentle suggestion when the weight trend is consistently within
~1 kg of the target, but never switches mode silently. Maintenance should feel like
a success state — the UI must not treat it as a degraded or inactive mode.

## Two sources of truth, two jobs

- **Calorie in-vs-out = the daily steering signal.** Fast feedback so the user can
  adjust today. But it's built from two estimates (Polar's "out", hand-logged "in" on
  home-cooked food), so it drifts.
- **Body weight = the weekly reality check.** Slow but honest ground truth. Shown as a
  *smoothed trend line*, never raw daily numbers — a +1kg water-weight morning must not
  read as failure.
- When they disagree over weeks, **the scale wins** and the calorie math recalibrates.

## App vs widget: workspace vs dashboard

The user opens the app *deliberately* to log and review; they do not live in the
widget. Therefore:

- **The widget is mostly read-only status.** A glance: on-track verdict + the trend.
  It must be instant and work offline (reads local storage only).
- **One carve-out: quick-log shortcuts.** These are allowed on the widget because
  they require zero contextual input, write directly to local storage (offline-safe), and
  the moment of use (e.g. at a bar after climbing) makes opening the app a genuine
  friction barrier. No other logging belongs on the widget.
- **The app is the workspace.** All food logging, history, and detail live here.

## Logging is "close out the meal", not a race

The user logs *just after finishing* a meal, not while eating. Logging is a discrete
"done, record it" action. It tolerates a few seconds of interaction — it does **not**
need to be sub-second — but it must be *predictable and never punishing*. Optimize for
"I always know how to do this in 3 taps" over raw speed.

## Friction budget by meal

Friction should match how variable the meal is. Spend the friction where the calories
are uncertain, nowhere else.

- **Breakfast & lunch:** fixed → **one tap** (log saved template as-is).
- **Late-night snack** (a recurring post-sport snack): **one tap** ("log usual late
  snack"). Surfacing it as a button also makes the pattern *visible*, which the user
  asked for.
- **Configurable shortcuts** — one-tap buttons for any frequently-consumed
  calorie-containing item (typically alcoholic drinks, but not restricted to them).
  Each shortcut has a user-set **emoji icon + short label + kcal value**. The icon
  makes the buttons scannable at small size. Available on the widget and the log screen.
  No tally ambiguity — calorie accuracy requires knowing the specific item.
- **Everything else** (dinner, a one-off snack, a glass of whole milk, a meal out,
  anything not covered by the presets above) → a generic **Log** flow with three paths:
  from a saved template, quick-add by calories (with an optional short label to remember
  what it was), or build from ingredients.

## What to surface, what to leave alone

The user named three things to *see/catch*: late-night snacking, drinking on
stressful weeks, and forgetting to log. They deliberately did **not** pick "eating
fast" — an app can't slow a fork, so don't try.

- **Forgetting to log** → one gentle end-of-day reminder. Not guilt, just a nudge.
- **Late-night snacking** → the one-tap button doubles as making the habit visible in
  history.
- **Drinking** → per-type shortcuts make weekly clusters visible in the log (total kcal,
  drink count) without moralizing each drink.

## Tips and nudges are subtle, not pushy

The user ranked "a tip/nudge right now" **last** of four things to see. So:

- Insights are *pull*, not push — shown when the user opens the app, not as
  notifications. The single exception is the one gentle "haven't logged today"
  reminder.
- No streak pressure, no badges, no alarms, no guilt language. Gamified pressure is a
  backfire risk — it causes app avoidance, not behaviour change.

## Tone & language

- Daily/neutral surfaces: factual, no adjectives of judgment. "1,850 in · 2,300 out · 450 left."
- **Calorie label vocabulary:** use **in · out · left** throughout the app. "In" = food/drink
  consumed; "out" = energy expended; "left" = budget remaining. Never "eaten/burned" (verb-based,
  gym-culture register) or "eaten/out" (asymmetric registers). The in/out pair names the direction
  of calorie flow — neutral accounting language, not performance language.
- Weekly/verdict surfaces: direct but never personal. Talk about the *trend*, not the
  person. "Down 0.3 kg this week — on track" not "Good job!" or "You failed."
- Never punish missing data (a skipped weigh-in, an unlogged day). Degrade gracefully.

## Offline & performance

Basic functionality works without internet; the app writes locally first, syncs to
the server later. The widget reads only local data. Logging must succeed offline — it's
the one thing that can't wait for a connection.

## Accessibility / locale

- Metric throughout (kg, kcal, g, cm). No imperial.
- UI language: English.
