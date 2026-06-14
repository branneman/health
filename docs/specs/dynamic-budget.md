# Dynamic Calorie Budget Model

**Scope:** Replaces the static `budget_remaining = calories_out_today − D − calories_in_today`
formula in `docs/specs/math-model.md §2` with a time-aware model that answers
"how much can I still eat today, given how much of the day is left?" The number
updates every minute and is influenced by 30 days of Polar and food-logging history.

Replaces math-model.md §2.1–2.4. All other sections of math-model.md remain unchanged.

---

## 1. Motivation

The existing budget formula treats the day as a lump sum: eat 1,500 kcal anytime
before midnight and you're on target. This misses two things the user needs:

1. **Time pressure** — if it's 9pm and you've eaten nothing, you can't realistically
   consume your full day's budget before bed. The remaining eating window matters.
2. **Polar's partial-day problem** — Polar's `calories` field is a running cumulative
   as of the last sync, not a daily total. Using it directly causes jarring jumps
   mid-day when a sync arrives with a lower partial figure than yesterday's full total.

The dynamic model solves both by spreading the expected daily burn across waking
hours and tying the eating budget to the *remaining* portion of that burn.

---

## 2. Inputs

### 2.1 User-configured (onboarding)

| Field | Example | Purpose |
|---|---|---|
| `wake_time` | 07:00 | Start of awake window |
| `bedtime` | 23:00 | End of awake window |
| `D` | 300 kcal | Target daily deficit (0 in maintenance mode) |

```
total_awake_minutes = bedtime − wake_time (in minutes)
```

### 2.2 Polar history (last 30 calendar days, from `daily_energy` + `workout` tables)

A day is classified as a **sport day** if a `workout` row exists for that date.
Otherwise it is a **non-sport day**.

A **logged day** = a calendar date with both a `daily_energy` row (Polar synced)
and at least one `log_entry` row (food logged). Only logged days count toward
eating_fraction computation (§4).

```
expected_today("sport")     = avg(daily_energy.total_kcal on sport days, last 30 calendar days)
expected_today("non-sport") = avg(daily_energy.total_kcal on non-sport days, last 30 calendar days)
```

`expected_today` requires only Polar data — food logging is not required for
this average. This separates the burn estimate from the eating pattern estimate.

### 2.3 Today's bucket

**The app is the authority on today's bucket.** The sport-tonight toggle on the
dashboard determines the bucket — not Polar, not day-of-week heuristics. Toggling
immediately shifts `expected_today` and `eating_fraction` to the sport bucket.
Historical classification (for learning eating_fraction) uses the `workout` table.

### 2.4 Real-time inputs

| Input | Source |
|---|---|
| `elapsed_minutes` | `now − wake_time` (clamped to [0, total_awake_minutes]) |
| `burned_so_far` | `actual_burned_confirmed` if non-null; otherwise `expected_today × elapsed / total_awake_minutes` |
| `calories_in_today` | Sum of all `log_entry_item` rows for today |
| `actual_burned_confirmed` | Latest Polar daily `total_kcal` for today (null if not yet synced) |

---

## 3. Core formula

Let `bucket` = `"sport"` if sport-tonight toggled, else `"non-sport"`.

### 3.1 Normal mode (time-decay)

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

**At wake time** (elapsed = 0): `calories_left = expected_today × eating_fraction − D`
— the full daily budget. The number declines toward 0 at bedtime.

**After eating:** if `calories_in_today` is below `allowance_so_far` (under-eating
relative to pace), the overshoot term is 0 — undereating is not banked as bonus.
The number is purely constrained by remaining time. If `calories_in_today` exceeds
`allowance_so_far`, overshoot reduces the remaining budget.

### 3.2 Post-workout mode (simple budget)

Once Polar confirms that `actual_burned_confirmed ≥ 0.9 × expected_today(bucket)`,
the time-decay model stops being useful — the day's burn is essentially done and
the remaining time window is too small to be meaningful. Switch to:

```
calories_left = max(0, expected_today(bucket) × eating_fraction(bucket)
                       − D − calories_in_today)
```

This shows the honest remaining daily budget (e.g., "540 kcal left") rather than
a tiny time-constrained figure that would discourage the user from eating a
post-workout recovery meal.

The switch is one-way: once post-workout mode activates, it stays active until
the next `wake_time` (the model's day boundary, not calendar midnight).

---

## 4. Eating fraction

`eating_fraction(bucket)` is the historically observed ratio of calories consumed
to calories burned on days of that type. It encodes how this user actually eats
on sport vs. non-sport days.

Each bucket upgrades independently through two tiers.

### 4.1 Baseline (Approach 2)

```
eating_fraction(bucket) =
  avg(calories_in on logged days matching bucket, last 30 calendar days)
  / avg(daily_energy.total_kcal on those days)
```

Available once ≥ 5 logged days exist in the bucket.

### 4.2 Upgraded (Approach 3)

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

The upgraded fraction encodes "what does eating look like on days I nailed the
target?" rather than averaging in over-eating days. Each bucket upgrades and
falls back independently.

### 4.3 Fallback chain

| Condition | eating_fraction source |
|---|---|
| Approach 3 threshold met (≥10 qualifying days) | Approach 3 |
| ≥5 days in bucket with Polar + food data | Approach 2 |
| < 5 days in bucket | `(expected_today − D) / expected_today` (Approach 1, target-derived) |
| No Polar data yet | BMR × activity multiplier as `expected_today`; Approach 1 fraction |

---

## 5. Polar sync integration

When the hourly cron pulls Polar data and upserts into `daily_energy`:

1. `burned_so_far` is updated from the new `total_kcal` value for today.
2. `remaining_expected_burn` recalculates as `expected_today(bucket) − burned_so_far`.
3. `expected_today(bucket)` is **not** recalibrated mid-day — it remains the
   30-day historical average. Only `burned_so_far` changes on sync.
4. If `total_kcal ≥ 0.9 × expected_today(bucket)`, post-workout mode activates.

A sync showing less than estimated (e.g., quiet morning) slightly increases
`remaining_expected_burn` and thus `calories_left`. A sync showing more than
estimated decreases it. Both are small effects on typical days.

---

## 6. Display & architecture

### 6.1 Client-side tick

The formula changes every minute, making server-push impractical. Architecture:

- **Server computes parameters** (eating_fraction, expected_today per bucket,
  qualifying-day counts) and exposes them via the existing `/summary/today`
  endpoint additions.
- **Client stores parameters** in Room alongside `calories_in_today` and
  `actual_burned_so_far` (from the last Polar sync received).
- **Client runs the formula** locally every minute — presentation logic,
  not business logic. The widget reads the same Room data and does the same
  calculation offline.

This is a deliberate, narrow exception to the "no math on client" principle.
The eating_fraction and expected_today values (the business logic) are
server-computed and server-owned. Only the per-minute interpolation lives on device.

### 6.2 Display states

| State | Shown label |
|---|---|
| Normal | `X kcal left` |
| Over budget | `−X kcal` |
| Approach 1 or no Polar | `X kcal left (estimated)` |
| Post-workout mode | `X kcal left` (no qualifier) |
| Maintenance (D = 0) | `X kcal left (balance)` |

Sport vs. non-sport bucket is not surfaced as a label — the toggle already
communicates this to the user.

---

## 7. Onboarding additions

Two fields added to the profile/onboarding step (or a dedicated "your schedule"
step if the profile step is already crowded):

- **Wake time** — time picker, default 07:00
- **Bedtime** — time picker, default 23:00

These are the only new onboarding inputs this model requires.

---

## 8. Maintenance mode (D = 0)

The formula works without modification:

- `deficit_remaining = 0` always
- `allowance_so_far = burned_so_far × eating_fraction` (no deficit subtraction)
- Qualifying days for Approach 3: `calories_in ≤ expected_today + 100`
- Display label: `X kcal left (balance)` per existing dashboard spec

---

## 9. Relationship to existing specs

| Spec | Impact |
|---|---|
| `math-model.md §2.1–2.4` | Replaced by this spec. §1, §3–§8 unchanged. |
| `math-model.md §2.3` | Sport-tonight estimate still adjusts `expected_today` as before; post-session Polar actual replaces it. Compatible — the toggle now also shifts the eating fraction. |
| `ux/3-features/dashboard.md` | Display label vocabulary unchanged (`left`). Display states table extended by this spec. |
| `api-design.md` | `/summary/today` needs new fields: `expected_today`, `eating_fraction`, `bucket`, `post_workout_mode`, `actual_burned_so_far`. See math-model.md §7. |

---

## 10. Open questions

- **Minimum data warning:** below what data volume should the app show a
  "still learning your patterns" label alongside the budget? Proposed: show it
  while on Approach 1 (< 5 days per bucket).
- **Wake/bedtime drift:** if the user regularly stays up late, the configured
  bedtime diverges from reality. No special handling in v1 — the configured
  schedule is the model's clock; the user adjusts it in settings.
- **Multiple Polar syncs per day:** the model takes the latest `total_kcal`
  value for today on each sync. Idempotent — same as existing upsert behaviour.
