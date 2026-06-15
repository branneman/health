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

Time-aware model that answers "how much can I still eat today, given how much of the day is left?" Updates every minute, informed by 30 days of Polar and food-logging history.

### 2.1 Motivation

The day-as-lump-sum approach misses two things:

1. **Time pressure** — if it's 9pm and you've eaten nothing, you can't realistically consume your full day's budget before bed. The remaining eating window matters.
2. **Polar's partial-day problem** — Polar's `calories` field is a running cumulative as of the last sync, not a daily total. Using it directly causes jarring jumps mid-day when a sync arrives with a lower partial figure than yesterday's full total.

The dynamic model solves both by spreading the expected daily burn across waking hours and tying the eating budget to the *remaining* portion of that burn.

### 2.2 Inputs

#### User-configured (onboarding)

| Field | Example | Purpose |
|---|---|---|
| `wake_time` | 07:00 | Start of awake window |
| `bedtime` | 23:00 | End of awake window |
| `D` | 300 kcal | Target daily deficit (0 in maintenance mode) |

```
total_awake_minutes = bedtime − wake_time (in minutes)
```

#### Polar history (last 30 calendar days, from `daily_energy` + `workout` tables)

A day is classified as a **sport day** if a `workout` row exists for that date. Otherwise it is a **non-sport day**.

A **logged day** = a calendar date with both a `daily_energy` row (Polar synced) and at least one `log_entry` row (food logged). Only logged days count toward eating_fraction computation.

```
expected_today("sport")     = avg(daily_energy.total_kcal on sport days, last 30 calendar days)
expected_today("non-sport") = avg(daily_energy.total_kcal on non-sport days, last 30 calendar days)
```

`expected_today` requires only Polar data — food logging is not required for this average. This separates the burn estimate from the eating pattern estimate.

#### Today's bucket

**The app is the authority on today's bucket.** The sport-tonight toggle on the dashboard determines the bucket — not Polar, not day-of-week heuristics. Toggling immediately shifts `expected_today` and `eating_fraction` to the sport bucket. Historical classification (for learning eating_fraction) uses the `workout` table.

#### Real-time inputs

| Input | Source |
|---|---|
| `elapsed_minutes` | `now − wake_time` (clamped to [0, total_awake_minutes]) |
| `burned_so_far` | `actual_burned_confirmed` if non-null; otherwise `expected_today × elapsed / total_awake_minutes` |
| `calories_in_today` | Sum of all `log_entry_item` rows for today |
| `actual_burned_confirmed` | Latest Polar daily `total_kcal` for today (null if not yet synced) |

### 2.3 Core formula

Let `bucket` = `"sport"` if sport-tonight toggled, else `"non-sport"`.

#### Normal mode (time-decay)

```
remaining_expected_burn = expected_today(bucket) − burned_so_far

allowance_so_far = burned_so_far × eating_fraction(bucket)
                   − D × (elapsed_minutes / total_awake_minutes)

overshoot = max(0, calories_in_today − allowance_so_far)

deficit_remaining = D × ((total_awake_minutes − elapsed_minutes) / total_awake_minutes)

calories_left = remaining_expected_burn × eating_fraction(bucket)
                − deficit_remaining
                − overshoot
```

`calories_left` is floored at 0 for display (negative is shown as `−X kcal`).

**At wake time** (elapsed = 0): `calories_left = expected_today × eating_fraction − D` — the full daily budget. The number declines toward 0 at bedtime.

**After eating:** if `calories_in_today` is below `allowance_so_far` (under-eating relative to pace), the overshoot term is 0 — undereating is not banked as bonus. The number is purely constrained by remaining time. If `calories_in_today` exceeds `allowance_so_far`, overshoot reduces the remaining budget.

#### Post-workout mode (simple budget)

Once Polar confirms that `actual_burned_confirmed ≥ 0.9 × expected_today(bucket)`, the time-decay model stops being useful — the day's burn is essentially done and the remaining time window is too small to be meaningful. Switch to:

```
calories_left = max(0, expected_today(bucket) × eating_fraction(bucket)
                       − D − calories_in_today)
```

This shows the honest remaining daily budget (e.g., "540 kcal left") rather than a tiny time-constrained figure that would discourage a post-workout recovery meal.

The switch is one-way: once post-workout mode activates, it stays active until the next `wake_time` (the model's day boundary, not calendar midnight).

### 2.4 Eating fraction

`eating_fraction(bucket)` is the historically observed ratio of calories consumed to calories burned on days of that type. It encodes how this user actually eats on sport vs. non-sport days. Each bucket upgrades independently through two tiers.

#### Baseline (Approach 2)

```
eating_fraction(bucket) =
  avg(calories_in on logged days matching bucket, last 30 calendar days)
  / avg(daily_energy.total_kcal on those days)
```

Available once ≥ 5 logged days exist in the bucket.

#### Upgraded (Approach 3)

Uses only **qualifying days** — days where the deficit was actually hit:

```
qualifying_day(bucket) = day where:
  - bucket classification matches
  - calories_in ≤ expected_today(bucket) − D + 100   [one-sided; never disqualifies undereating]
```

In maintenance mode (D = 0): qualifying = `calories_in ≤ expected_today + 100`.

```
IF count(qualifying_days(bucket), last 30) ≥ 10:
  eating_fraction(bucket) =
    avg(calories_in on qualifying days) / avg(total_kcal on qualifying days)
```

The upgraded fraction encodes "what does eating look like on days I nailed the target?" rather than averaging in over-eating days. Each bucket upgrades and falls back independently.

#### Fallback chain

| Condition | eating_fraction source |
|---|---|
| Approach 3 threshold met (≥10 qualifying days) | Approach 3 |
| ≥5 days in bucket with Polar + food data | Approach 2 |
| < 5 days in bucket | `(expected_today − D) / expected_today` (Approach 1, target-derived) |
| No Polar data yet | BMR × activity multiplier as `expected_today`; Approach 1 fraction |

### 2.5 Polar sync integration

When the hourly cron pulls Polar data and upserts into `daily_energy`:

1. `burned_so_far` is updated from the new `total_kcal` value for today.
2. `remaining_expected_burn` recalculates as `expected_today(bucket) − burned_so_far`.
3. `expected_today(bucket)` is **not** recalibrated mid-day — it remains the 30-day historical average. Only `burned_so_far` changes on sync.
4. If `total_kcal ≥ 0.9 × expected_today(bucket)`, post-workout mode activates.

A sync showing less than estimated slightly increases `remaining_expected_burn` and thus `calories_left`. A sync showing more decreases it. Both are small effects on typical days.

### 2.6 Display and architecture

#### Client-side tick

The formula changes every minute, making server-push impractical:

- **Server computes parameters** (eating_fraction, expected_today per bucket, qualifying-day counts) and exposes them via the existing `/summary/today` endpoint additions.
- **Client stores parameters** in Room alongside `calories_in_today` and `actual_burned_so_far` (from the last Polar sync received).
- **Client runs the formula** locally every minute — presentation logic, not business logic. The widget reads the same Room data and does the same calculation offline.

This is a deliberate, narrow exception to the "no math on client" principle. The eating_fraction and expected_today values (the business logic) are server-computed and server-owned. Only the per-minute interpolation lives on device.

#### Display states

| State | Shown label |
|---|---|
| Normal | `X kcal left` |
| Over budget | `−X kcal` |
| Approach 1 or no Polar | `X kcal left (estimated)` |
| Post-workout mode | `X kcal left` (no qualifier) |
| Maintenance (D = 0) | `X kcal left (balance)` |

Sport vs. non-sport bucket is not surfaced as a label — the toggle already communicates this to the user.

### 2.7 Onboarding additions

Two fields added to the profile/onboarding step:

- **Wake time** — time picker, default 07:00
- **Bedtime** — time picker, default 23:00

### 2.8 Maintenance mode (D = 0)

The formula works without modification:

- `deficit_remaining = 0` always
- `allowance_so_far = burned_so_far × eating_fraction` (no deficit subtraction)
- Qualifying days for Approach 3: `calories_in ≤ expected_today + 100`
- Display label: `X kcal left (balance)` per existing dashboard spec

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

- **Budget recalibration:** if the calorie-vs-weight disagreement insight fires
  persistently over 4+ weeks (same direction), should the app suggest adjusting the
  target deficit D? Recommend: purely informational in v1. Flag as a v2 feature.
- **Non-standard weigh-in times:** the model assumes morning post-toilet weighing
  (lowest daily weight, most consistent). If the user sometimes weighs at other times,
  this adds noise. No special handling in v1 — document the convention; handle
  inconsistency through the SMA's noise tolerance.
- **Polar partial-day sync:** behaviour when Polar syncs mid-day with a partial daily
  total (e.g. after a morning run, before an evening climb) is handled by §2 (sport-tonight
  adds on top), but the exact Polar sync model may need revisiting once the Polar
  integration is implemented.
- **Minimum data warning:** below what data volume should the app show a "still learning
  your patterns" label alongside the budget? Proposed: show it while on Approach 1
  (< 5 days per bucket, §2.4).
- **Wake/bedtime drift:** if the user regularly stays up late, the configured bedtime
  diverges from reality. No special handling in v1 — the configured schedule is the
  model's clock; the user adjusts it in settings.
- **Multiple Polar syncs per day:** the model takes the latest `total_kcal` value for
  today on each sync. Idempotent — same as existing upsert behaviour.
