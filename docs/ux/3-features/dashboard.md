# Feature Spec — Dashboard & Widget

Builds on `1-principles.md` and `2-scenarios.md` (S02, S05, S13). Covers the read
surfaces: the homescreen **widget** (read-only status + drink shortcuts) and the in-app
**dashboard** (status + history + detail). This is where the "am I on track?" question
gets answered.

## What the user wants to know, in order

From the user's own ranking:

1. **Am I on track today?** (a verdict, not a number)
2. **The trend over days/weeks.**
3. **How much budget I have left to eat.**
4. A tip/nudge (lowest — see `3-features/insights.md`).

Design implication: lead with the **verdict**, put the **arithmetic underneath**.
"On track / behind" is the headline; "1,850 in · 2,300 out · 450 left" is the
supporting detail, not the lead.

## The two-speed rule applied here

(See principles.) The dashboard has a **daily** zone and a **weekly** zone, and they
look different on purpose:

- **Daily zone = calm.** Neutral colors, factual numbers, no red/green judgment. A bad
  single day shows as numbers, not alarm. This is the steering signal (calorie
  in-vs-out) — useful for adjusting *today*, not for self-judgment.
- **Weekly zone = the verdict.** This is where the colored verdict lives, because the
  weekly trend is stable and honest. Driven primarily by the **smoothed weight trend**,
  with calorie balance as secondary context. Three states:
    - **Green** — on pace; weight trend matches the target deficit.
    - **Amber/red** — behind; weight flat or rising despite a logged deficit.
    - **Amber-fast** — dropping too quickly (>~0.5 kg/week). Message: "Dropping quickly —
      make sure you're eating enough to perform." This is not a success signal; it protects
      against over-restriction.

If daily and weekly disagree, the weekly/weight signal is the one the UI treats as
"truth" (calorie math drifts; the scale doesn't).

## The widget (Glance, homescreen)

Mostly read-only. Instant. Offline (reads Room only).

Contents, top to bottom:

- **The on-track verdict**, as the dominant element — the weekly verdict color as the
  primary signal (that's the honest one), with today's in-vs-out as a small secondary
  line. Weekly color keeps the glance tied to the trend, not to a noisy single day.
- **A compact trend hint** — e.g. a tiny sparkline of the weight trend or the week's
  balance.
- **Drink shortcut row** (medium widget only) — one button per configured drink type.
  Each tap writes directly to Room (offline-safe, zero contextual input required). This
  is the one carve-out from widget read-only — see `1-principles.md` for rationale and
  S09 for the scenario.

The status section answers question #1 and gestures at #2. Detail is a tap away in
the app. Tapping anywhere on the status section opens the app dashboard.

Tapping the widget opens the app dashboard.

## The in-app dashboard

The landing surface when the app opens. Top to bottom:

1. **Today, calm.** In-vs-out for today: calories in, calories out (Polar), and budget
   remaining. Neutral styling. Includes:
    - A quick affordance to log weight if not done today.
    - A **sport-tonight toggle**: activity type (`Climbing` / `Rowing` / `Other`) ×
      intensity (`Light` / `Normal` / `Hard`). Tapping it bumps the day's budget by the
      estimated session expenditure and labels the budget: "X kcal remaining (includes
      planned climb ~600 kcal)". Settable or changeable any time during the day; cleared
      each morning. See S05.
2. **The weekly verdict.** The colored verdict. Driven by smoothed weight trend +
   calorie balance over the week. One honest line per state:
    - Green: "Down 0.3 kg this week — on track."
    - Amber/red: "Flat this week — slightly behind."
    - Amber-fast: "Down 0.8 kg this week — dropping quickly, watch your intake."
      Direct about the trend, never personal. See three-state rule in `1-principles.md`.
3. **Trend charts.** The real signal made visible:
    - **Weight**: smoothed trend line (e.g. 7-day moving average), *not* raw daily dots
      as the headline — raw points can be a faint underlay at most. Goal line toward target weight
      optional.
    - **Calorie balance**: daily in-vs-out bars over the week, so over/under days are
      visible without being individually judged.
4. **History access.** Drill into any past day (view/edit entries, per
   `3-features/logging.md`), and switch the trend window (week / month).

## Charts — conventions

- Use the in-app chart surface for any multi-point series (weight trend, weekly
  balance). Single numbers (today's budget) stay as plain text, no chart.
- Weight: always smoothed as the primary line. Never present a single day's weight as a
  standalone verdict.
- Honest axes — don't truncate the weight axis to exaggerate movement (that turns noise
  into drama, the opposite of "kind about the day").

## Tone on these surfaces

- Daily: pure data. "1,850 in · 2,300 out · 450 left." No adjectives.
- Weekly verdict: direct, trend-focused, impersonal. Good: "Down 0.3 kg — on track."
  Avoid: "Great job!" (hollow) and "You're failing" (causes app avoidance).
- Empty/missing data states are neutral: "No weight logged this week" — never a
  reprimand.

## Offline

Both widget and dashboard render fully from local storage. Server sync (Polar "out",
any cross-device state) updates in the background; stale-but-present beats blank.

## Vacation / pause mode

When activated (S14), the app enters a neutral out-of-band state:

- The weekly verdict shows "On pause" instead of a colored verdict.
- The daily zone still renders normally (and logging still works if the user wants).
- A subtle "Paused" indicator appears in the dashboard header; tapping it offers to
  resume.
- Pattern insights skip the paused period when computing trends.

The toggle lives in the dashboard header — accessible but not prominent.

## Maintenance mode

When the target deficit is set to 0 (S16), the dashboard adapts:

- **Budget label:** "X kcal remaining (balance)" instead of "X kcal remaining". No
  deficit deducted — budget equals calories out.
- **Weekly verdict:** measures stability, not loss rate. Three states:
    - "Weight stable — on track." (change within ±0.2 kg/week — success state, green)
    - "Weight creeping up — slight surplus this week." (amber)
    - "Dropping below target — consider eating a bit more." (amber, reverse direction)
- **Amber-fast guard:** still active. If weight drops faster than 0.5 kg/week in
  maintenance, same protective message applies.
- The app may surface a gentle suggestion banner when the smoothed weight trend is
  consistently within ~1 kg of the target: "You're close to your goal — ready to
  switch to maintenance?" User dismisses or taps to transition.
- Maintenance mode must look like a success state, not a reduced mode. No "inactive"
  styling, no prompts to resume weight loss.

## Open questions for implementation

- Widget size variants (small = verdict only; medium = verdict + sparkline + drink row).
- Exact smoothing window for weight (7-day MA vs exponential) — tune once real data
  exists.
