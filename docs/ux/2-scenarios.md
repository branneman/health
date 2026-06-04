# UX Scenarios

Bridges `1-principles.md` → `3-flows.md`. Each scenario describes a concrete
interaction from the user's perspective: the trigger, the steps, and the outcome.
No screen layouts here — those belong in `ux-flow.md`.

The principles explain *why*; the scenarios explain *what happens*.

---

## Index

| ID  | Scenario                           | Context        |
|-----|------------------------------------|----------------|
| S01 | First launch — onboarding          | One-time       |
| S02 | Morning weigh-in + budget glance   | Daily morning  |
| S03 | Log breakfast                      | Daily morning  |
| S04 | Log lunch                          | Daily midday   |
| S05 | Set sport tonight                  | Morning/midday |
| S06 | Log dinner — from template         | Evening        |
| S07 | Log dinner — quick-add by calories | Evening        |
| S08 | Log dinner — build from scratch    | Evening, rare  |
| S09 | Log a drink                        | Evening/night  |
| S10 | Log late-night snack               | Late night     |
| S11 | End-of-day logging reminder        | Evening        |
| S12 | Edit or delete a logged entry      | Anytime        |
| S13 | Weekly review                      | Weekly         |
| S14 | Activate / end vacation mode       | Anytime        |
| S15 | Configure drink shortcuts          | Settings       |

---

## S01 — First launch: onboarding

**Trigger:** App opened for the first time; no data exists.

**Goal:** Set up enough to show a meaningful daily budget.

**Steps:**

1. App prompts for personal data: height, weight, age, biological sex.
2. App computes a BMR estimate (Mifflin-St Jeor formula) and briefly explains it.
3. User sets a target daily deficit. App suggests a sustainable range (250–400 kcal/day)
   with a visible warning against aggressive deficits. User confirms or adjusts.
4. App shows the resulting starting budget: "you'll aim to eat roughly X kcal/day."
5. User lands on the dashboard. Polar connection and drink-shortcut configuration are
   offered as "finish setting up" nudges — not blockers. Both can be done later.

**Outcome:** Dashboard shows today's budget. User can start logging immediately.

**Empty state / Polar not yet connected:** Dashboard shows "calories out: estimated
(Polar not connected)" — budget uses BMR × activity-level multiplier. Once Polar is
connected, actual daily expenditure replaces the estimate and the label disappears.

**Notes:**

- Onboarding must work fully offline — no network required for BMR calculation.
- The starting budget is a first approximation. It will be refined over weeks as the
  weight trend gives feedback (see `3-features/insights.md` — calorie-vs-weight calibration).

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

## S06 — Log dinner: from template

**Trigger:** Just after dinner. User ate a familiar dish.

**Goal:** Log dinner in 2–3 taps from a saved template.

**Steps:**

1. User taps `Log dinner` on the log screen.
2. A list of dinner templates appears, ordered by most-recently-used / most-frequently-used.
3. User taps the matching template (e.g. "Chicken stir-fry").
4. A portion adjuster appears: `Lighter` / `Normal` / `Heavier`. User picks or skips
   (defaults to Normal, which logs the template at 1×).
5. Entry logs immediately. Confirmation visible in today's list.

**Outcome:** Dinner logged in 2–3 taps, with accurate nutrition from the saved template.

**Edge case:** Template exists but portions were very different tonight — use the adjuster
for `Lighter` or `Heavier`. If it was a completely different meal, fall through to S07 or S08.

---

## S07 — Log dinner: quick-add by calories

**Trigger:** Just after dinner. User knows roughly how many calories the meal was — from a
recipe in RM5K, from memory, or from estimation.

**Goal:** Log dinner as a calorie estimate without specifying ingredients.

**Steps:**

1. User taps `Log dinner` → `Quick-add`.
2. A single number field: "How many kcal?" User types the number.
3. Meal type is pre-set to "dinner". Timestamp defaults to now.
4. User confirms. Entry appears in today's list as "Dinner — ~X kcal (estimate)".
5. One-tap undo available for a few seconds.

**Outcome:** Dinner logged with one number. No template, no ingredients, no friction.

**Notes:**

- "(estimate)" label is honest — this entry has no snapshotted nutrition detail.
  It contributes to the daily calorie total but not to macro breakdown.
- This is the primary dinner path for any meal the user can roughly quantify. It is
  *not* a fallback — it is a first-class logging option.

---

## S08 — Log dinner: build from scratch

**Trigger:** Just after dinner. New dish the user wants to log accurately and save for future reuse.

**Goal:** Build a detailed entry and save it as a reusable template.

**Steps:**

1. User taps `Log dinner` → `New dish`.
2. For each ingredient: search Open Food Facts by name or scan a barcode. Set quantity
   in grams. Running kcal total updates live.
3. At any point the user can bail: they're dropped to S07 (quick-add) with the partial
   kcal total pre-filled as a starting point. A rough log always beats no log.
4. On save: "Save as a dinner template?" prompt. User names it (e.g. "Chicken stir-fry").
5. Template is saved for future use (S06 path). Entry is logged immediately.

**Outcome:** Detailed dinner entry logged. Template saved so future instances cost 2–3 taps.

**Notes:**

- This path is genuinely rare — only needed for new dishes worth precise tracking.
- The bail-to-quick-add fallback is critical: never block a log on complete data.

---

## S09 — Log a drink

**Trigger:** At the bouldering gym bar after a session, at home on a social evening, or
on a Friday/Saturday night out. User has a drink.

**Goal:** Log the drink instantly, with minimum taps — ideally without opening the app.

**Steps:**

1. From the homescreen widget (medium size), user taps the relevant drink shortcut
   (e.g. `Weizen 0.5L`, `Pils 330ml`, `Wine`, `Scotch`).
2. Entry writes to Room instantly. No app-open required.
3. Confirmation is visible the next time the app is opened (entry in today's list).
4. Undo is available from the log screen (not the widget).

**Outcome:** Drink logged in one tap from the homescreen.

**Same shortcuts on log screen:** When the user is already in the app, the same buttons
are available on the log screen — no need to go back to the homescreen.

**Edge case:** Unknown drink (e.g. a cocktail) — use quick-add by calories from the
log screen.

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

## S15 — Configure drink shortcuts

**Trigger:** First-time setup, or when usual drinks change.

**Goal:** Set the drink shortcut buttons shown on the widget and log screen.

**Steps:**

1. Settings → `Drink shortcuts`.
2. For each shortcut: label (e.g. "Weizen 0.5L") and kcal per unit (e.g. 220).
3. User can add, edit, reorder, or delete shortcuts.
4. Shortcuts appear on the widget (medium size, in configured order) and on the log screen.

**Outcome:** Widget and log screen show the right drink options for the user's actual habits.

**Suggested defaults on first run:**

- Pils 330ml — 140 kcal
- Weizen 0.5L — 220 kcal
- Wine 150ml — 120 kcal
- Scotch 30ml — 65 kcal

User can keep, edit, or replace these. No hardcoded drink types exist in the app.
