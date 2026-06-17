# Math Model

**Date:** 2026-06-03
**Scope:** Algorithms behind the daily budget, weight trend, weekly verdict, sport
estimates, and insight trigger conditions. Validates that the UX design in `docs/ux/`
is computationally feasible and pins down where the math must be honest about
uncertainty.

All computation lives server-side (per API design principle: no math on client).
Client receives computed values and displays them.

---

## 1. Constants and reference values

### 1.1 Energy density of body fat

**7,700 kcal ≈ 1 kg of body mass change**

Standard approximation for adipose tissue (~87% lipid + water + connective tissue).
Real range is 7,000–8,000 kcal/kg depending on individual body composition, but 7,700
is the established dietician figure. All predictions from deficit to expected weight
change flow through this constant. Results are directional estimates, not measurements.

### 1.2 BMR — Mifflin-St Jeor formula

Used during onboarding and as fallback before Polar data is available.

```
Men:   BMR = 10 × weight_kg + 6.25 × height_cm − 5 × age_years + 5
Women: BMR = 10 × weight_kg + 6.25 × height_cm − 5 × age_years − 161
```

Accuracy: ±10–15% vs a measured metabolic rate. Superseded by Polar's actual daily
expenditure as soon as that data is available. Formula executes server-side at
onboarding and is not re-run during normal operation once Polar is connected.

### 1.3 Activity multipliers (bootstrap only)

Multiplied by BMR to estimate TDEE before Polar is connected. Retired once Polar
takes over.

| Level             | Multiplier | When to use                   |
|-------------------|------------|-------------------------------|
| Sedentary         | 1.20       | Desk job, no regular exercise |
| Lightly active    | 1.375      | 1–2 sport sessions/week       |
| Moderately active | 1.55       | 3–5 sport sessions/week       |

User selects level during onboarding. The app displays estimated daily expenditure
with a visible "estimated" label until Polar is connected.

### 1.4 MET defaults for sport-tonight estimates

MET (Metabolic Equivalent of Task) measures exercise intensity relative to rest.

```
kcal = MET × body_weight_kg × duration_hrs
```

`body_weight_kg` uses the user's most recently logged body weight. Server computes
this at the time the sport-tonight toggle is set.

**Bouldering** (stop-and-go, rest-heavy — effective METs are lower than continuous
climbing):

| Intensity | MET | Default duration | ≈ kcal (80 kg) |
|-----------|-----|------------------|----------------|
| Light     | 4.0 | 75 min           | ~400           |
| Normal    | 5.0 | 90 min           | ~600           |
| Hard      | 6.5 | 90 min           | ~780           |

**Indoor rowing** (continuous effort):

| Intensity | MET | Default duration | ≈ kcal (80 kg) |
|-----------|-----|------------------|----------------|
| Light     | 7.0 | 45 min           | ~420           |
| Normal    | 7.5 | 60 min           | ~600           |
| Hard      | 9.0 | 60 min           | ~720           |

All duration and kcal values are user-configurable defaults per activity/intensity
combination. Polar's post-session actual always overwrites the estimate — these
numbers only affect the daytime budget preview.

---

## 2. Daily calorie budget

Simple running balance: "how much can I eat today, given what I've burned and what I've already eaten?"

### 2.1 Motivation

An earlier time-decay model spread the expected daily burn across waking hours and gave a shrinking eating window as bedtime approached. This caused a confusing "0 kcal left" display late in the evening even when the user was well under their budget — the model treated end-of-eating-window as equivalent to hitting the target, which destroys the signal.

The simpler model is clearer: `budget = calories_out_today − D` and `calories_left = budget − calories_in_today`. The number reflects how far you are from your target, regardless of clock time.

### 2.2 Inputs

#### User-configured

| Field | Example | Purpose |
|---|---|---|
| `D` | 300 kcal | Target daily deficit (0 in maintenance mode) |

#### Polar history (last 30 calendar days, from `daily_energy` + `workout` tables)

A day is classified as a **sport day** if a `workout` row exists for that date. Otherwise it is a **non-sport day**.

```
expected_today("sport")     = avg(daily_energy.total_kcal on sport days, last 30 calendar days)
expected_today("non-sport") = avg(daily_energy.total_kcal on non-sport days, last 30 calendar days)
```

When no Polar history is available: `expected_today` falls back to BMR × activity multiplier (§1.2/§1.3).

**Today's bucket:** the sport-tonight toggle on the dashboard determines which average to use — not Polar, not day-of-week heuristics. Historical classification uses the `workout` table.

#### Real-time inputs

| Input | Source |
|---|---|
| `actual_burned_today` | Latest Polar daily `total_kcal` for today (null if not yet synced) |
| `calories_in_today` | Sum of all `log_entry_item` rows for today |

### 2.3 Core formula

Let `bucket` = `"sport"` if sport-tonight toggled, else `"non-sport"`.

```
calories_out_today = actual_burned_today        if actual_burned_today ≥ 0.9 × expected_today(bucket)
                   = expected_today(bucket)      otherwise

calories_left = calories_out_today − D − calories_in_today
```

**While Polar has only a partial daily reading** (actual is null or < 90% of expected): `expected_today(bucket)` is the stable proxy. It avoids budget jumps mid-day when Polar sends a partial cumulative total.

**Once Polar confirms the day is essentially done** (actual ≥ 90% of expected): `actual_burned_today` is used directly — the measured reading supersedes the historical average.

`calories_left` can be negative (over budget) or positive (under budget). Display: negative shown as `−X kcal`.

### 2.4 Polar sync integration

When the hourly cron pulls Polar data and upserts into `daily_energy`:

1. `actual_burned_today` is updated from the new `total_kcal` for today.
2. If `actual_burned_today ≥ 0.9 × expected_today(bucket)`, the formula switches to using `actual_burned_today` directly.
3. `expected_today(bucket)` is **not** recalibrated mid-day — it remains the 30-day historical average.

### 2.5 Display and architecture

#### No per-minute tick

The formula no longer changes with clock time — only with food log events, Polar syncs, and sport toggle changes. There is no client-side per-minute tick.

- **Server computes** `expected_today` per bucket and exposes it via `/summary/today`.
- **Client stores** these params in Room alongside `actual_burned_today` and `calories_in_today`.
- **Client recalculates** `calories_left` on: food log event, Polar sync, sport toggle change. The widget reads the same Room data offline.

#### Display states

| State | Shown label |
|---|---|
| Normal | `X kcal left` |
| Over budget | `−X kcal` |
| No Polar history (BMR fallback) | `X kcal left (estimated)` |
| Maintenance (D = 0) | `X kcal left (balance)` |

### 2.6 Maintenance mode (D = 0)

The formula works without modification: `calories_left = calories_out_today − 0 − calories_in_today`. Display label: `X kcal left (balance)`.

### 2.7 Wake/bedtime in profile

Wake time and bedtime are stored in the user profile but are **not used in the budget formula**. They serve other features: the late-night snacking insight (§5.1) and the end-of-day notification (story 23).

### 2.8 Note on Polar calibration

Polar's absolute daily calorie figures may be systematically offset from actual expenditure. This means `expected_today` carries the same bias, inflating the budget proportionally. Polar's *relative* variation (sport vs. non-sport days, heavy vs. light days) is not affected by this offset and remains useful.

**Deferred.** After ≥ 30 days of concurrent Polar + food + weight data, a calibration pass will determine the correction approach. Leading candidate: weight-trend feedback — if weight is flat while eating X kcal/day, actual TDEE ≈ X kcal. See §8 open questions.

---

## 3. Weight trend smoothing

### 3.1 Method

**7-day Simple Moving Average (SMA)** over the last 7 weight readings, regardless of
calendar date.

```
smoothed_weight = mean(last_7_readings)
```

Rationale for SMA over EMA: SMA is explainable to the user ("your last 7 weigh-ins,
averaged") and robust to single outliers. EMA weights recent readings more but
requires tuning a decay parameter and is harder to explain. LOESS is overkill for
daily personal data at this volume.

### 3.2 Minimum data gates

| Readings available | Behaviour                                                             |
|--------------------|-----------------------------------------------------------------------|
| < 3                | No trend line. Message: "Log a few more weigh-ins to see your trend." |
| 3–6                | Partial SMA shown as dashed/faint line — low-confidence signal        |
| ≥ 7                | Full SMA line                                                         |

### 3.3 Chart representation

- Smoothed SMA line is the primary visual — bold, always shown
- Raw daily readings shown as faint dots behind the line — context, not headline
- Axes are not truncated — full honest scale even if movement is small. Truncation
  turns noise into drama; that's the opposite of what this app is for.

---

## 4. Weekly verdict

### 4.1 Expected weekly loss

```
E = (D × 7) / 7700   [kg/week]
```

Examples:

- D = 250 kcal/day → E ≈ 0.23 kg/week
- D = 300 kcal/day → E ≈ 0.27 kg/week
- D = 400 kcal/day → E ≈ 0.36 kg/week

### 4.2 Actual smoothed weekly change

```
actual = smoothed_weight_today − smoothed_weight_7_days_ago
```

Rolling 7-day window — not a fixed calendar week. Uses SMA values on both ends to
avoid single noisy weigh-ins swinging the verdict.

Negative = weight lost. Positive = weight gained.

### 4.3 Verdict thresholds

| State          | Condition                                 | Example message                                                 |
|----------------|-------------------------------------------|-----------------------------------------------------------------|
| **Green**      | `actual ≤ −(E × 0.4)` and `actual ≥ −0.5` | "Down 0.3 kg this week — on track."                             |
| **Amber-red**  | `actual > −(E × 0.4)`                     | "Flat this week — slightly behind."                             |
| **Amber-fast** | `actual < −0.5`                           | "Dropping quickly — make sure you're eating enough to perform." |

**On the 0.4× tolerance:** Green requires losing at ≥40% of target pace. Tighter
thresholds (e.g. 80%) would generate false amber-red on normal weeks — weight loss
is inherently noisy and a 7-day window is short. The 40% floor accepts "heading in
the right direction" as success, which is honest given the imprecision of the inputs.

**On the 0.5 kg/week ceiling:** This is a hard threshold, not relative to the target.
It is a safety guard against over-restriction (muscle loss territory) regardless of
what deficit the user has set. The app must not treat fast weight loss as a success.

### 4.4 Minimum data gates

The verdict is shown only when:

- ≥ 5 weight readings exist in the last 14 calendar days
- App has been in use ≥ 14 days (**grace period**)

**Why a grace period:** The first 1–2 weeks of any diet reliably show rapid weight
loss from glycogen and water depletion — not fat. This is often 0.5–1.5 kg in week 1
alone. Showing amber-fast during this period would be a false alarm and undermine
trust in the verdict permanently. The grace period absorbs this.

During grace period: neutral message, "Building baseline — keep logging."
Insufficient weigh-ins: "Not enough weigh-ins for a reliable verdict this week."

### 4.5 Verdict without Polar data

The weight-trend verdict works fully without Polar — the scale is ground truth and
doesn't depend on calories-out data. Calorie balance context (used to explain *why*
the verdict is what it is) is omitted or shown as "Polar not connected" when unavailable.

---

## 5. Insight trigger conditions

Insights are pull, not push. They are computed in the background and shown when the
user opens the app. At most 1–2 are shown at a time; a wall of observations is noise.

### 5.1 Late-night snacking

**Trigger:** ≥ 3 snack entries logged after 21:00 in the last 7 logged days
**Message:** "Late snack logged N of the last M evenings."
**Minimum data:** ≥ 5 logged days in the 7-day window

### 5.2 Drinking cluster

**Trigger:** ≥ 5 drinks logged in the rolling 7 days
**Context:** compare to previous 7 days if available
**Message:** "N drinks this week." / "N drinks this week — up from M last week."
**Minimum data:** ≥ 3 logged days in current 7-day window

### 5.3 Logging coverage

**Trigger:** ≥ 2 days in the last 7 with no dinner entry (or no entries at all)
**Message:** "Dinner unlogged N days this week — trends are less reliable when days
are missing."
**Framing:** data quality note, not personal failing

### 5.4 Deficit too aggressive

**Trigger:** Amber-fast verdict on ≥ 2 consecutive rolling-7 windows
**Why 2 consecutive:** a single amber-fast window can be noise or measurement artifact;
two in a row indicates a real trend.
**Message:** "Dropping quickly — make sure you're eating enough to perform."
**Minimum data:** same as verdict gate (§4.4)

### 5.5 Calorie-vs-weight disagreement

The most valuable and most data-hungry insight. Connects the two sources of truth.

**Requires Polar.** `calories_out` must come from Polar measured data, not BMR
estimates — the bootstrap estimate is too inaccurate for this comparison to mean
anything. This insight is suppressed until ≥ 3 weeks of Polar-sourced data exist.

**Definitions:**

- *Logged day*: a calendar day with ≥ 1 food log entry AND a Polar calories-out value.
- *Smoothed weight start/end*: the 7-day SMA as of the first and last day of the window.

**Algorithm:**

```
logged_deficit = sum over logged days of (polar_calories_out − calories_in)
predicted_loss = logged_deficit / 7700           [kg]
actual_loss    = smoothed_weight_start − smoothed_weight_end
disagreement   = predicted_loss − actual_loss    [positive = calories predict more loss than scale shows]
```

**Triggers:**

| Condition                | Message                                                                                                                          |
|--------------------------|----------------------------------------------------------------------------------------------------------------------------------|
| `disagreement > 0.5 kg`  | "Calories suggest more loss than the scale shows — food intake may be slightly underestimated, especially on home-cooked meals." |
| `disagreement < −0.5 kg` | "Weight dropping faster than the calorie math suggests — the numbers are working in your favour."                                |
| `disagreement ≤ 0.5 kg`  | No insight (expected agreement — nothing to surface)                                                                             |

**Minimum data:** ≥ 3 rolling weeks with ≥ 5 logged days each (logged day defined above). Without
this the
insight would be confidently wrong.
**Cadence:** shown at most once per 2 weeks even when persistently triggered — avoids
repeating the same observation every time the app is opened.

---

## 6. Limitations

These are documented honestly because the app should communicate uncertainty rather
than project false precision.

**BMR estimates are rough (±10–15%).** A person whose actual BMR is 10% higher than
calculated would see a systematically optimistic budget in the bootstrap period. Polar's
measured expenditure removes this error — connect it early.

**Calorie-in estimates are inherently approximate.** Quick-add calories are the user's
estimate. Template-based entries are more accurate but still depend on the accuracy of
the original ingredient data. The calorie-vs-weight insight (§5.5) surfaces systematic
under-estimation over time — this is one of the most useful things the app can do.

**Weight fluctuates for non-fat reasons.** A single weigh-in can vary ±1–2 kg from
glycogen stores, water retention, food mass in the gut, and hormonal cycles. The 7-day
SMA absorbs most of this. The first 2 weeks of any new routine are especially noisy
(glycogen depletion causes fast early loss that does not represent fat loss). The grace
period in §4.4 exists for this reason.

**The verdict is a signal, not a measurement.** Green means "your trend is consistent
with your goal." It does not mean "you lost exactly X grams of fat this week." The
inputs (BMR estimate, hand-logged food, Polar expenditure) all carry uncertainty; the
output should be read as a direction, not a precise figure.

**Sport estimates are defaults, not measurements.** MET-based estimates for bouldering
are especially rough because session style varies more than duration. Polar's
post-session actual is the reliable number; the estimate exists only to make the
daytime budget useful before the session.

---

## 7. API spec gap

The current `docs/api-design.md` `/summary/today` and
`/summary/week` endpoints do not yet expose the computed values this model produces:
smoothed weight series, verdict state + message, budget remaining, and insight
payloads. The API spec will need a revision pass once implementation begins to add
these fields. The math here defines what must be computed; the API spec defines how
it is exposed to the client.

---

## 8. Open questions

- **Polar absolute calibration:** Polar's daily calorie totals are observed to be
  400–600 kcal too high in absolute terms, inflating `expected_today` and thus the
  budget. Revisit after ≥ 30 days of concurrent Polar + food + weight data. Leading
  approach: weight-trend feedback to infer actual TDEE (if weight is flat while eating
  X kcal/day, TDEE ≈ X). Polar's relative sport/non-sport variation is still valid
  even with an absolute offset; only the anchor shifts.
- **Budget recalibration:** if the calorie-vs-weight disagreement insight fires
  persistently over 4+ weeks (same direction), should the app suggest adjusting the
  target deficit D? Recommend: purely informational in v1. Flag as a v2 feature.
- **Non-standard weigh-in times:** the model assumes morning post-toilet weighing
  (lowest daily weight, most consistent). If the user sometimes weighs at other times,
  this adds noise. No special handling in v1 — document the convention; handle
  inconsistency through the SMA's noise tolerance.
- **Minimum data warning:** below what data volume should the app show a "still learning
  your patterns" label alongside the budget? Proposed: show it while no Polar history
  exists (BMR fallback active).
- **Multiple Polar syncs per day:** the model takes the latest `total_kcal` value for
  today on each sync. Idempotent — same as existing upsert behaviour.
- **"Out" display vs formula divergence:** the dashboard shows `caloriesOut` resolved
  by `resolveCaloriesOut()` (polar_today → polar_yesterday → estimate), while
  `calories_left` is computed from `expected_today(bucket)` or `actual_burned_today`.
  When only yesterday's Polar is available, these two numbers can differ — e.g. "out:
  2350 (yesterday)" but "left" computed from `expected_today = 2400`. The three numbers
  on screen (`in · out · left`) are then not arithmetically consistent. No fix in v1 —
  acceptable given how rarely this state occurs. Candidate v2 fix: use `expected_today`
  as the displayed "out" figure when polar_today is unavailable.
