# UX Scenarios

Bridges `1-principles.md` → `4-flows.md`. Each scenario describes a concrete
interaction from the user's perspective: the trigger, the steps, and the outcome.
No screen layouts here — those belong in `4-flows.md`.

The principles explain *why*; the scenarios explain *what happens*.

---

## Index

| ID  | Scenario                         | Context        |
|-----|----------------------------------|----------------|
| S01 | First launch — onboarding        | One-time       |
| S02 | Morning weigh-in + budget glance | Daily morning  |
| S03 | Log breakfast                    | Daily morning  |
| S04 | Log lunch                        | Daily midday   |
| S05 | Set sport tonight                | Morning/midday |
| S06 | Log — from template              | Anytime        |
| S07 | Log — quick-add by calories      | Anytime        |
| S08 | Log — build from scratch         | Anytime, rare  |
| S09 | Log a drink                      | Evening/night  |
| S10 | Log late-night snack             | Late night     |
| S11 | End-of-day logging reminder      | ~21:00         |
| S12 | Edit or delete a logged entry    | Anytime        |
| S13 | Weekly review                    | Weekly         |
| S14 | Activate / end vacation mode     | Anytime        |
| S15 | Configure shortcuts              | Settings       |
| S16 | Switch to maintenance mode       | After goal met |

---

## S01 — First launch: onboarding

**Trigger:** App opened for the first time; no local account data exists.

**Goal:** Log in, set up profile, connect Polar, and arrive at a working daily budget.

**Steps:**

1. **Login.** User enters email and password. Token is stored locally. If the account
   already has profile data saved server-side (e.g. reinstall on a new phone), steps 2
   and 3 are pre-filled; user can confirm or adjust.
2. **Biometrics.** Height, current weight, goal weight, age, biological sex. App
   computes a BMR estimate (Mifflin-St Jeor formula). Goal weight ≤ current weight
   (or equal, for maintenance from day one).
3. **Target deficit.** User sets a target daily deficit (0–600 kcal/day). 0 = maintain
   current weight. With goal weight known, the screen shows: "≈ 0.27 kg/week — goal
   reached in ~8 months." Both figures update live as the slider moves. A warning
   appears above 500 kcal/day.
4. **Connect Polar.** The app explains: "Polar data makes calorie budgets accurate —
   without it, everything is estimated." User taps "Connect Polar" → Polar OAuth flow
   in browser → returns to app with token. Skippable with "Skip for now — I'll use
   estimates", but that label communicates what's being traded away.
5. User lands on the dashboard. Drink shortcut configuration is offered as a dismissible
   banner (pre-populated with suggested defaults; can be customised in settings later).

**Outcome:** Dashboard shows today's budget. User can start logging immediately.

**Pre-filled from account:** If the server account already holds a completed profile
(returning user, reinstall), onboarding steps 2–3 show pre-filled values and can be
confirmed in one tap per step. Drink shortcuts configured on a previous install are
also restored automatically.

**Polar not connected / skipped:** Dashboard shows "calories out: ~X kcal (estimated)".
Budget uses BMR × activity-level multiplier. Once Polar is connected, actual daily
expenditure replaces the estimate and the label disappears.

**Notes:**

- Login requires a network connection. Steps 2–4 work offline once logged in.
- The starting budget is a first approximation. It will be refined over weeks as the
  weight trend gives feedback (see `3-features/insights.md` — calorie-vs-weight
  calibration).

---

## S02 — Morning weigh-in + budget glance

**Trigger:** User wakes up, uses the toilet, opens the app

**Goal:** Log today's weight; see what the day looks like.

**Steps:**

1. Dashboard opens. Weight entry is prominent — a single field pre-filled with the last
   logged value (e.g. 84.0 kg).
2. User adjusts the value if needed and confirms. One tap to log.
3. Dashboard shows today's state: calories in so far (zero), budget remaining, and the
   sport-tonight toggle (cleared to off each morning).

**Outcome:** Weight logged; day's budget visible.

**Edge cases:**

- Skipped weigh-in: nothing happens, no prompt, no penalty. Dashboard shows last known
  weight with a neutral "last weighed N days ago" note.
- Scale-upgrade day (new decimal precision): same field, just more digits available.

---

## S03 — Log breakfast

**Trigger:** After eating breakfast.

**Goal:** Record breakfast in one tap.

**Steps:**

1. From the log screen (or widget), user taps `Usual breakfast`.
2. Entry appears in today's list immediately (optimistic local write — offline-first).
3. Confirmation is visible: the entry with its kcal total.
4. A one-tap undo is available for a few seconds.

**Outcome:** Breakfast logged. Budget updates.

**Edge case:** Breakfast was different today — user skips the one-tap button and uses
quick-add by calories (or builds a custom entry). The "usual" button is never forced.

---

## S04 — Log lunch

**Trigger:** After eating lunch.

**Goal:** Record lunch in one tap.

**Steps:** Identical to S03, using the `Usual lunch` button.

**Outcome:** Lunch logged. Budget updates.

---

## S05 — Set sport tonight

**Trigger:** Morning when planning the day, or mid-afternoon when the climbing plan is confirmed
with friends. Sometimes as late as 30 minutes before leaving.

**Goal:** Bump today's calorie budget to account for a planned evening session, so food
choices during the day reflect actual expenditure.

**Steps:**

1. On the dashboard, user taps the sport-tonight toggle (visible and accessible, not buried).
2. A picker: activity type (`Climbing` / `Rowing` / `Other`) × intensity (`Light` /
   `Normal` / `Hard`), with an estimated kcal burn for each combination.
3. User picks — e.g. "Climbing, Normal — est. 600 kcal".
4. Budget updates immediately and shows the adjustment: "X kcal remaining (includes
   planned climb ~600 kcal)".
5. The active toggle stays visible on the dashboard for the rest of the day.

**Outcome:** Daytime budget reflects planned expenditure. User can eat appropriately
before the session.

**Late or last-minute:** User can set or change the toggle at any point during the day
— including 30 minutes before leaving. Polar's actual post-session figure settles the
real number at day's end.

**Edge cases:**

- Session cancelled: tap the toggle again to clear. Budget reverts.
- Rowing (always last-minute): same flow, user picks "Rowing" when the decision is made.
- Calorie variance: climbing burns 300–900 kcal depending on session intensity. The
  Light/Normal/Hard picker lets the user express this rather than locking in one number.
  Over time, Polar data accumulates and the app can suggest better personal defaults for
  each intensity level.

---

## S06 — Log: from template

**Trigger:** Just after any meal where a saved template matches (dinner most often, but
also a meal out, a known snack, or any food logged before).

**Goal:** Log in 2–3 taps from a saved template.

**Steps:**

1. User taps `Log food` on the log screen.
2. A list of food templates appears, sorted alphabetically. Pinned templates appear at
   the top in configured order.
3. User taps the matching template (e.g. "Chicken stir-fry").
4. A portion adjuster appears: `Lighter` / `Normal` / `Heavier`. User picks or skips
   (defaults to Normal, which logs the template at 1×).
5. Entry logs immediately. Confirmation visible in today's list.

**Outcome:** Meal logged in 2–3 taps, with accurate nutrition from the saved template.

**Edge case:** Template exists but portions were very different — use the adjuster for
`Lighter` or `Heavier`. If it was a completely different food, fall through to S07 or S08.

---

## S07 — Log: quick-add by calories

**Trigger:** Any time the user knows roughly how many calories something was — a meal, a
snack, a glass of whole milk, a recipe looked up in RM5K — but doesn't want to log
ingredients.

**Goal:** Log anything as a calorie estimate with a number and an optional label.

**Steps:**

1. User taps `Log` → `Quick-add`.
2. A kcal number field opens. User types the number.
3. An optional label field below it: "What was it?" User can type a short note
   (e.g. "Pasta at work", "Whole milk 250ml", "Dinner at Marco's") or skip it.
4. User confirms. Entry appears in today's list as "Pasta at work — ~800 kcal (est.)"
   or just "~800 kcal (est.)" if no label was given.
5. One-tap undo available for a few seconds.

**Outcome:** Entry logged with a number and an optional memory aid. No template, no
ingredients, no friction.

**Notes:**

- "(est.)" label is always shown — this entry has no snapshotted nutrition detail.
  It contributes to the daily calorie total but not to macro breakdown.
- This is the primary path for anything the user can roughly quantify. It is
  *not* a fallback — it is a first-class logging option.

---

## S08 — Log: build from scratch

**Trigger:** Any food the user wants to log accurately and save for future reuse — most
commonly a new dinner dish, but applicable to any food with identifiable ingredients.

**Goal:** Build a detailed entry from ingredients and optionally save it as a reusable template.

**Steps:**

1. User taps `Log food` → `New dish`.
2. For each ingredient: search Open Food Facts by name or scan a barcode. Set quantity
   in grams. Running kcal total updates live.
3. At any point the user can bail: they're dropped to S07 (quick-add) with the partial
   kcal total pre-filled as a starting point. A rough log always beats no log.
4. On save: "Save as a template?" prompt. User names it (e.g. "Chicken stir-fry").
5. Template is saved for future use (S06 path). Entry is logged immediately.

**Outcome:** Detailed entry logged. Template saved so future instances cost 2–3 taps.

**Notes:**

- This path is genuinely rare — only needed for new foods worth precise tracking.
- The bail-to-quick-add fallback is critical: never block a log on complete data.

---

## S09 — Log a drink

**Trigger:** At the bouldering gym bar after a session, at home on a social evening, or
on a Friday/Saturday night out. User has a drink.

**Goal:** Log the drink instantly, with minimum taps — ideally without opening the app.

**Steps:**

1. From the homescreen widget (medium size), user taps the relevant shortcut button
   (e.g. 🍺 Pils, 🍺 Weizen, 🍷 Wine, 🥃 Scotch).
2. Entry writes to local storage instantly. No app-open required.
3. Confirmation is visible the next time the app is opened (entry in today's list).
4. Undo is available from the log screen (not the widget).

**Outcome:** Item logged in one tap from the homescreen.

**Same shortcuts on log screen:** When the user is already in the app, the same buttons
are available on the log screen — no need to go back to the homescreen.

**Edge case:** Unknown item (e.g. a cocktail, an unfamiliar drink) — use quick-add by
calories from the log screen.

---

## S10 — Log late-night snack

**Trigger:** After a heavy session, user is hungry and reaches for a habitual late-night snack.

**Goal:** Record the snack without friction; make the pattern visible in history.

**Steps:**

1. From the log screen (or widget), user taps `Usual late snack`.
2. Entry logged immediately. Timestamp marks the late-night hour.
3. Confirmation visible in today's list.

**Outcome:** Snack logged. The pattern becomes visible over time — "Late snack"
in the list is a gentle mirror; the insights surface frequency ("5 of last 7 evenings")
without commenting on it.

**Edge case:** Different snack tonight — quick-add by calories.

---

## S11 — End-of-day logging reminder

**Trigger:** Evening, if dinner has not been logged and the day otherwise looks light.

**Goal:** Gently prompt the user without guilt.

**Steps:**

1. A local notification fires. Neutral phrasing: "Haven't logged dinner yet today."
2. Tapping it opens the app to the log screen.
3. User logs, or dismisses. No follow-up, no escalation, no stacking.

**Outcome:** User either logs the missing entry or consciously skips. No penalty either way.

**Notes:**

- Never fires during vacation mode (S14).
- User-disableable in settings.
- Does not fire if dinner is genuinely not planned (e.g. a light day).

---

## S12 — Edit or delete a logged entry

**Trigger:** User notices a mistake — wrong meal, wrong amount, duplicate tap.

**Goal:** Correct the log.

**Steps:**

1. User taps the entry in today's list (or in history via the dashboard).
2. Option: `Delete`. Log entries are immutable — no in-place edit. To correct, delete
   and re-log using any path (S03–S10).
3. Deletion removes the entry and updates the day's total immediately.

**Outcome:** Corrected entry in the log.

**Past days:** Accessible from the history section of the dashboard. Same delete-and-relog
flow applies.

**Notes:** The immutability is an API-level constraint (snapshot integrity). The UX
presents it as "delete to correct" without exposing the technical reason.

---

## S13 — Weekly review

**Trigger:** User opens the app and scrolls the dashboard — typically a weekend morning
or any time they want to see the bigger picture.

**Goal:** Understand honestly whether the week was on track.

**Steps:**

1. Dashboard opens to the daily zone (calm, neutral — today's numbers).
2. User scrolls to the weekly zone: the verdict line, then trend charts.
3. Weekly verdict: green / amber / red, driven by smoothed weight trend + calorie balance.
   Direct but impersonal: "Down 0.3 kg — on track." Never personal praise or blame.
4. Trend charts: smoothed weight line + daily calorie-in/out bars for the week.
5. Insights section (below charts, pull not push): at most 1–2 observations.
   Examples: "Late snack logged 5 of 7 evenings", "8 drinks this week vs 2 last week."

**Outcome:** Clear honest picture of the week. No number-by-number interrogation required.

**Special verdict cases:**

- Deficit too large (dropping faster than ~0.5 kg/week): amber verdict with a note —
  "Dropping quickly — make sure you're eating enough to perform." Protects against the
  over-restriction pattern.
- Fewer than 2 weight entries: verdict shows as neutral / "not enough data." No false
  confidence from a single measurement.
- Calorie balance and weight disagree: insight surfaces this plainly and attributes it
  to estimate drift, not to the user (see `ux-insights.md`).

---

## S14 — Activate / end vacation mode

**Trigger:** About to go on holiday, ill, or any period the user wants to treat as out-of-band.

**Goal:** Put the app in a neutral state so the pause period doesn't skew patterns or verdicts.

**Steps (activate):**

1. Dashboard header → settings/overflow → `Pause tracking`.
2. Brief confirmation: "While paused, logging still works but weekly verdicts are
   suspended and pattern insights skip this period."
3. User confirms. A subtle "Paused" indicator appears in the dashboard header.

**Steps (end):**

1. User taps the "Paused" indicator → `Resume tracking`.
2. Normal verdict and pattern logic resume. The paused period is excluded from all
   trend and insight calculations going forward.

**Outcome:** Clean break in the data. The app does not read a holiday week as a
regression or a failure.

**Notes:**

- Logging during a pause is optional but still works.
- Polar sync continues; the data is stored but flagged so it doesn't feed the
  calorie-vs-weight calibration insight.

---

## S15 — Configure shortcuts

**Trigger:** First-time setup, or when frequently-logged items change.

**Goal:** Set the shortcut buttons shown on the widget and log screen.

**Steps:**

1. Settings → `Shortcuts`.
2. For each shortcut: emoji icon (e.g. 🍺), short label (e.g. "Pils"), and kcal per
   unit (e.g. 140). All three fields are required.
3. User can add, edit, reorder, or delete shortcuts.
4. Shortcuts appear on the widget (medium size, in configured order) and on the log screen.

**Outcome:** Widget and log screen show the right one-tap buttons for the user's habits.

**Notes:**

- Shortcuts are not limited to alcoholic drinks — any frequently-consumed
  calorie-containing item can be a shortcut (e.g. 🥛 Whole milk 250ml — 150 kcal).
- The emoji provides the visual anchor for small-button readability; the short label
  distinguishes similar-looking icons (two different beers would both be 🍺).

**Suggested defaults on first run:**

- 🍺 Pils 330ml — 140 kcal
- 🍺 Weizen 0.5L — 220 kcal
- 🍷 Wine 150ml — 120 kcal
- 🥃 Scotch 30ml — 65 kcal

User can keep, edit, or replace these. No hardcoded items exist in the app.

---

## S16 — Switch to maintenance mode

**Trigger:** User has reached (or is approaching) their target weight and wants to stop
actively losing weight. Or they want to hold at current weight without a deficit goal.

**Goal:** Transition the app from weight-loss mode to weight-maintenance mode without
losing any history or logging capability.

**Steps:**

1. User goes to Settings → `Goal` (or the app surfaces a gentle suggestion banner on
   the dashboard when the smoothed weight trend comes within ~1 kg of the target weight:
   "You're close to your goal — ready to switch to maintenance?").
2. User sets target deficit to 0, or taps "Switch to maintenance".
3. The app confirms: "Maintenance mode — your budget will now aim to balance calories
   in and out, rather than run a deficit." One-tap confirm.
4. Dashboard updates: budget label changes from "X kcal remaining (deficit)" to
   "X kcal remaining (balance)". Weekly verdict logic adapts (see below).

**Outcome:** App is in maintenance mode. Logging, weight tracking, and Polar sync all
continue unchanged. The verdict now measures weight stability, not loss rate.

**Maintenance verdict states:**

- "Weight stable — on track." (weight change within ±0.2 kg/week — success)
- "Weight creeping up — slight surplus this week." (amber — gaining > 0.2 kg/week)
- "Dropping below target — consider eating a bit more." (amber-low — losing > 0.2 kg/week)

**Notes:**

- Maintenance mode should feel like a success state, not a reduced mode. The UI must
  not look degraded or inactive.
- The user can switch back to loss mode at any time from settings — no data is lost.
- Vacation mode (S14) and maintenance mode are independent: both can be active at once.
