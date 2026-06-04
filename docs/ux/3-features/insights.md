# Feature Spec — Insights & Nudges

Builds on `1-principles.md` and `2-scenarios.md` (S13). Covers the *interpretation*
layer: tips, patterns, and nudges. This is deliberately the **least prominent** feature —
the user ranked "a tip/nudge right now" last of four. Restraint is the design.

## Guiding stance

Insights are **pull, not push**. They appear when the user opens the app and looks for
them; they do not interrupt. The one and only exception is the single gentle
"haven't logged today" reminder (owned by `3-features/logging.md`). No other notifications.

No gamification: no streaks, no badges, no points, no alarms. Pressure mechanics are a
backfire risk — they cause app avoidance, not behaviour change. The app's
job is to make patterns *visible* and let the user draw conclusions — not to cheer or
scold.

## What counts as an "insight" here

Plain-language observations grounded in the user's own logged patterns, surfaced
calmly. The three behaviours the user wanted to *see* are the primary material:

1. **Late-night snacking.** Make the pattern visible, e.g. "Late snack logged 5 of the
   last 7 evenings." Observation, not verdict. The data already exists from the one-tap
   snack button.
2. **Drinking on stressful/social weeks.** Surface clusters: "8 drinks this week vs 2
   last week." Factual. Never moralizing per drink.
3. **Forgetting to log.** Surface coverage gently: "Dinner unlogged 3 days this week —
   trends are less reliable when days are missing." Frames missing data as a *data
   quality* note, not a personal failing.

4. **Deficit too aggressive.** If the smoothed weight trend shows dropping faster than
   ~0.5 kg/week, surface a protective note: "Dropping quickly — make sure you're eating
   enough to perform." Frames the concern as performance, not vanity. Not a guilt insight
   — a safety net. (See `0-context-private.md` for context.)

Beyond these, insights can connect the two data sources the user actually cares about:

- Calorie-balance vs weight-trend agreement ("calories suggest a deficit; weight
  agrees" / "calories suggest a deficit but weight is flat — the 'in' estimate may be
  low, especially on home-cooked dinners").
- That second one is genuinely useful: it's the app teaching the user where its own
  estimates drift, which is honest and builds the "skill/knowledge" the user said they
  want from this project.

## Where insights live

- A quiet **section on the dashboard**, below the trend charts — reached by scrolling,
  not shoved at the top. At most **one or two** observations at a time. A wall of tips
  is noise.
- Optionally a small "patterns" detail view for the user who wants to dig — opt-in, not
  default.

## Tone rules (strict)

- **Observational, not prescriptive by default.** "Late snack on most evenings" beats
  "You should stop snacking." If a suggestion is offered, it's optional and soft:
  "Worth a look?" not "Do this."
- **Trend-framed, never person-framed.** Talk about the data, not the user's character.
- **No false cheer.** Don't celebrate noise. A good week's verdict (in the dashboard)
  is enough; insights don't need to high-five.
- **Honest about uncertainty.** When the calorie math and the scale disagree, say so
  plainly and attribute it to estimate drift, not to the user.

## Cadence

- Insights recompute quietly in the background and are *there when opened*. They don't
  announce themselves.
- Weekly is the natural rhythm for pattern observations (matches the "weekly is the
  real signal" principle). Daily insights are usually noise — avoid them.

## What NOT to build (anti-requirements)

- No push notifications for insights (only the one logging reminder exists).
- No streaks / badges / scores / leaderboards-of-one.
- No "eating too fast" feature — the user explicitly excluded it; an app can't slow a
  fork.
- No motivational quotes, no emoji-laden encouragement.
- Nothing that makes a single bad day feel like failure.

## Open questions for implementation

- Minimum data threshold before an insight is trustworthy enough to show (e.g. ≥5
  logged days in the window) — avoid confidently wrong observations on thin data.
- Whether the calorie-vs-weight reconciliation insight should also gently recalibrate
  the displayed budget over time, or stay purely informational for v1.
