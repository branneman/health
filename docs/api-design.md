# API Design

**Date:** 2026-06-03  
**Scope:** Server API — endpoint structure, HTTP methods, and DTO shapes. Excludes `/out/`
(Polar) DTO shapes, which will be specified once Polar integration is proven.

---

## Design Principles

- **Domain-namespaced URLs** — `/in/`, `/out/`, `/body/`, `/summary/` reflect the domain
  model. The in/out split is visible in the URL, not just in docs.
- **No math on the client** — totals, aggregations, and deficits are computed server-side.
  UI logic (ordering, filtering, string formatting) is the client's responsibility.
- **Log entries are immutable** — `POST` + `DELETE` only, no update. Nutrition is
  snapshotted at log time so history is correct even if catalog data changes later.
- **`/out/` is read-only from the API** — Polar cron writes calories-out data server-side.
  Android only reads.
- **Food items have no `DELETE`** — snapshots protect history. Stale catalog data can be
  corrected without affecting log history.
- **Template `PUT` is full replacement** — `meal_template_item` is a join table; partial
  patching would require complex diff logic. Server replaces atomically in a transaction.

---

## Endpoints

### System

| Method | Path             | Auth | Description               |
|--------|------------------|------|---------------------------|
| `GET`  | `/`              | No   | API reference docs (HTML) |
| `GET`  | `/server-health` | No   | Server health check       |
| `GET`  | `/api/update`    | No   | Current version + APK URL |
| `POST` | `/auth/token`     | No  | Issue bearer token              |
| `POST` | `/auth/refresh`   | Yes | Rotate token (30-day extension) |
| `POST` | `/auth/logout`    | Yes | Revoke session                  |

### Body metrics

| Method   | Path                | Auth | Description                         |
|----------|---------------------|------|-------------------------------------|
| `GET`    | `/body/weight`      | Yes  | List all entries, newest first      |
| `POST`   | `/body/weight`      | Yes  | Log a weight entry                  |
| `DELETE` | `/body/weight/{id}` | Yes  | Delete entry (correction mechanism) |

### Calories in — food catalog

| Method | Path                  | Auth | Description                    |
|--------|-----------------------|------|--------------------------------|
| `GET`  | `/in/food-items`      | Yes  | Search by `?barcode=` or `?q=` |
| `GET`  | `/in/food-items/{id}` | Yes  | Get single food item           |
| `POST` | `/in/food-items`      | Yes  | Add food item to catalog       |

### Calories in — meal templates

| Method   | Path                 | Auth | Description                    |
|----------|----------------------|------|--------------------------------|
| `GET`    | `/in/templates`      | Yes  | List all templates with items  |
| `GET`    | `/in/templates/{id}` | Yes  | Get single template with items |
| `POST`   | `/in/templates`      | Yes  | Create template                |
| `PUT`    | `/in/templates/{id}` | Yes  | Replace template atomically    |
| `DELETE` | `/in/templates/{id}` | Yes  | Delete template                |

### Calories in — food log

| Method   | Path                    | Auth | Description                              |
|----------|-------------------------|------|------------------------------------------|
| `POST`   | `/in/log/quick-add`     | Yes  | Log kcal + optional label (7 (Quick-add logging))      |
| `POST`   | `/in/log/food`          | Yes  | Log a custom meal (15 (Build from scratch))             |
| `POST`   | `/in/log/template`      | Yes  | Log from a meal template (13 (Meal templates))      |
| `GET`    | `/in/log`               | Yes  | List entries for `?date=YYYY-MM-DD`      |
| `GET`    | `/in/log/{id}`          | Yes  | Get single log entry                     |
| `DELETE` | `/in/log/{id}`          | Yes  | Delete entry (correction mechanism)      |

### Calories out — Polar (read-only; not yet implemented)

Endpoint shapes reserved. DTOs will be specified after Polar integration is proven.

| Method | Path                 | Auth | Description                         |
|--------|----------------------|------|-------------------------------------|
| `GET`  | `/out/energy`        | Yes  | List daily energy (`?from=` `?to=`) |
| `GET`  | `/out/energy/{date}` | Yes  | Get one day's energy summary        |
| `GET`  | `/out/workouts`      | Yes  | List workouts (`?from=` `?to=`)     |
| `GET`  | `/out/workouts/{id}` | Yes  | Get single workout                  |

### Summary — computed

| Method | Path             | Auth | Description           |
|--------|------------------|------|-----------------------|
| `GET`  | `/summary/today` | Yes  | Today's full picture  |
| `GET`  | `/summary/week`  | Yes  | 7-day rolling summary |

---

## DTO Shapes

All field names: camelCase (kotlinx.serialization default).  
Timestamps: ISO 8601 with UTC offset. Dates: `YYYY-MM-DD`.

### `GET /server-health`

```json
{
  "status": "ok"
}
```

---

### `GET /api/update`

```json
{
  "versionCode": 32,
  "versionName": "32-2f765b5",
  "apkUrl": "https://github.com/branneman/health/releases/download/32-2f765b5/app-release.apk",
  "releaseNotes": "Fixed calorie widget not refreshing"
}
```

`versionCode` is the git commit count; `versionName` is `{count}-{short-hash}`. Both are
derived at build time — see `docs/specs/auto-update.md` for the full versioning
scheme. The app compares `versionCode` against `BuildConfig.VERSION_CODE` to decide whether
to prompt for an update.

---

### Body weight

**`POST /body/weight` request:**

```json
{
  "date": "2026-06-03",
  "kg": 82.05
}
```

**Response — `GET /body/weight` list item and `POST /body/weight` created entry:**

```json
{
  "id": "uuid",
  "date": "2026-06-03",
  "kg": 82.05
}
```

`GET /body/weight` returns an array ordered by date descending.  
`DELETE /body/weight/{id}` → `204 No Content`.

---

### Food catalog

**Response shape — `GET /in/food-items` and `GET /in/food-items/{id}`:**

```json
{
  "id": "uuid",
  "barcode": "8710522957487",
  "name": "Oatly Oat Milk",
  "kcalPer100g": 47.0,
  "proteinPer100g": 1.0,
  "carbsPer100g": 6.7,
  "fatPer100g": 1.5,
  "source": "openfoodfacts"
}
```

Nullable: `barcode`, `proteinPer100g`, `carbsPer100g`, `fatPer100g`.  
`?barcode=` returns an array (0 or 1 items — barcode is unique).  
`?q=` returns an array of matches.

**`POST /in/food-items` request:**

```json
{
  "barcode": "8710522957487",
  "name": "Oatly Oat Milk",
  "kcalPer100g": 47.0,
  "proteinPer100g": 1.0,
  "carbsPer100g": 6.7,
  "fatPer100g": 1.5,
  "source": "openfoodfacts"
}
```

`barcode` optional. `source` defaults to `"openfoodfacts"`; use `"manual"` for hand-entered
items. Returns the created food item (same shape as GET).

---

### Meal templates

**Response shape — `GET /in/templates` list item and `GET /in/templates/{id}`:**

```json
{
  "id": "uuid",
  "name": "Usual Breakfast",
  "totalKcal": 520.0,
  "items": [
    {
      "foodItemId": "uuid",
      "grams": 80.0,
      "kcal": 312.0
    },
    {
      "foodItemId": "uuid",
      "grams": 300.0,
      "kcal": 141.0
    }
  ]
}
```

`totalKcal` and per-item `kcal` are computed server-side (`grams × kcalPer100g / 100`).  
Client resolves `foodItemId` to a display name from its local Room cache.

**`POST /in/templates` and `PUT /in/templates/{id}` request (same shape):**

```json
{
  "name": "Usual Breakfast",
  "items": [
    {
      "foodItemId": "uuid",
      "grams": 80.0
    },
    {
      "foodItemId": "uuid",
      "grams": 300.0
    }
  ]
}
```

`PUT` replaces the template and all its items atomically.  
Returns the created/updated template (same shape as GET).  
`DELETE /in/templates/{id}` → `204 No Content`.

---

### Food log

**`POST /in/log/food` request:**

```json
{
  "mealType": "dinner",
  "loggedAt": "2026-06-03T19:30:00+02:00",
  "items": [
    {
      "foodItemId": "uuid",
      "grams": 150.0
    }
  ]
}
```

**`POST /in/log/template` request:**

```json
{
  "templateId": "uuid",
  "mealType": "breakfast",
  "loggedAt": "2026-06-03T08:00:00+02:00"
}
```

`loggedAt` is optional on both — defaults to server `now()`.  
`mealType` is required on `POST /in/log/template` — the meal slot may differ from the
template's name.  
`mealType` values: `breakfast | lunch | dinner | snack`.

**Log entry response — both POST endpoints, `GET /in/log` list item, `GET /in/log/{id}`:**

```json
{
  "id": "uuid",
  "mealType": "breakfast",
  "loggedAt": "2026-06-03T08:00:00+02:00",
  "totalKcal": 520.0,
  "totalProteinG": 18.5,
  "totalCarbsG": 72.0,
  "totalFatG": 14.2,
  "items": [
    {
      "foodItemId": "uuid",
      "grams": 80.0,
      "kcal": 312.0,
      "proteinG": 10.0,
      "carbsG": 52.0,
      "fatG": 7.0
    }
  ]
}
```

All totals and per-item values are computed server-side from snapshotted nutrition data.  
`GET /in/log?date=2026-06-03` returns an array ordered by `loggedAt`.  
`DELETE /in/log/{id}` → `204 No Content`. No update — log entries are immutable once created.

---

### Summary

**`GET /summary/today`:**

```json
{
  "date": "2026-06-03",
  "caloriesIn": 1850,
  "caloriesOut": 2200,
  "budgetRemaining": 50,
  "targetDeficit": 300,
  "caloriesOutSource": "estimate",
  "expectedTodaySport": 2387,
  "expectedTodayNonSport": 2100,
  "actualBurnedSoFar": null
}
```

`caloriesOutSource` values: `"polar_today"` / `"polar_yesterday"` / `"estimate"`.  
`caloriesIn` reflects all log entries synced to the server for the date. The Android
app computes `caloriesIn` locally from Room (reactive) and ignores this field; it is
retained for completeness and future multi-client use.

`expectedTodaySport` / `expectedTodayNonSport`: 30-day rolling average of Polar
`total_kcal` for sport and non-sport days respectively. Null if fewer than 1 day of
Polar history exists for that bucket. Used by the client as the stable daily burn
proxy for the budget formula.

`actualBurnedSoFar`: today's Polar `total_kcal` if synced, else null. When this value
reaches ≥ 90% of the active bucket's expected, the client uses it directly instead of
the historical average.

**`GET /summary/week`:**

```json
{
  "from": "2026-05-27",
  "to": "2026-06-03",
  "avgCaloriesIn": 1920,
  "avgCaloriesOut": null,
  "avgDeficit": null,
  "weightChangeKg": -0.5,
  "days": [
    {
      "date": "2026-06-03",
      "caloriesIn": 1850,
      "caloriesOut": null,
      "deficit": null
    },
    {
      "date": "2026-06-02",
      "caloriesIn": 1900,
      "caloriesOut": null,
      "deficit": null
    }
  ]
}
```

`weightChangeKg`: latest minus earliest weight entry in the 7-day window. Negative means weight
lost. `null` if fewer than 2 entries in the window.  
`avgCaloriesOut` and `avgDeficit` are `null` until Polar is wired.

---

## HTTP Status Codes

| Code  | Usage                                                              |
|-------|--------------------------------------------------------------------|
| `200` | Successful GET, PUT                                                |
| `201` | Successful POST (resource created)                                 |
| `204` | Successful DELETE                                                  |
| `400` | Malformed request body                                             |
| `401` | Missing or expired bearer token                                    |
| `404` | Resource not found                                                 |
| `409` | Unique constraint violated (e.g. duplicate date on `/body/weight`) |
| `429` | Rate limit exceeded (`POST /token` only)                           |

---

## Out of Scope

- `/out/` DTO shapes — specified after Polar integration is proven
- Open Food Facts proxy — server may proxy OFFs on `GET /in/food-items?barcode=` cache miss;
  decision deferred to implementation
- Food item `DELETE` — not exposed; snapshot semantics make it safe to correct without removal
- Polar OAuth endpoints (`GET /polar/auth`, `GET /polar/callback`) — server-side only, not
  part of the Android-facing API
