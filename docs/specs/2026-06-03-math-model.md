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

### 2.1 Formula

```
budget_remaining = calories_out_today − D − calories_in_today
```

Where:

- `D` = target daily deficit in kcal, configured at onboarding (recommended: 250–400)
- `calories_in_today` = sum of all logged food and drink entries for today
- `calories_out_today` = see §2.2

A negative result means over budget. Displayed neutrally as `−X kcal` — no red
color, no alarm. The daily zone is always calm (see `docs/ux/1-principles.md`).

### 2.2 calories_out_today — source priority

1. **Today's Polar total** (if synced for today) — used as-is, most accurate
2. **Yesterday's Polar total** (if today's sync not yet received) — used as proxy,
   shown with note "based on yesterday's activity"
3. **BMR × activity_multiplier** — bootstrap fallback, shown with note "Polar not
   connected — using estimate"

### 2.3 Sport-tonight adjustment

When the sport-tonight toggle is active:

```
calories_out_today += sport_estimate_kcal
```

Budget label: `X kcal remaining (includes planned climb ~600 kcal)`

After Polar syncs the actual session data, the estimate is silently replaced by the
real figure and `budget_remaining` recalculates. If the actual is higher than the
estimate (harder session), the user gains remaining budget; if lower, it decreases.

### 2.4 Display states

| State                    | Label                                               |
|--------------------------|-----------------------------------------------------|
| Normal (Polar connected) | `X kcal remaining`                                  |
| Over budget              | `−X kcal over budget`                               |
| Sport toggle active      | `X kcal remaining (includes planned climb ~N kcal)` |
| Using yesterday's Polar  | `X kcal remaining (based on yesterday)`             |
| Bootstrap (no Polar)     | `X kcal remaining (estimated)`                      |

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

The current `docs/specs/2026-06-03-api-design.md` `/summary/today` and
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
  total (e.g. after a morning run, before an evening climb) is handled by §2.3
  (sport-tonight adds on top), but the exact Polar sync model may need revisiting once
  the Polar integration is implemented.
