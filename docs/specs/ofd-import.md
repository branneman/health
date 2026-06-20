# OFD Import Pipeline — Design Spec

**Story:** 14 (OFD import pipeline)  
**Scope:** Server-only. No Android changes in this story.

---

## What we're building

A pipeline that keeps a local mirror of Open Food Facts NL products in a `catalog.product`
Postgres table, and exposes two search endpoints the app will use in story 15 (Build from
scratch).

---

## Size and sync cadence

- **NL product count:** ~79 000 (confirmed via OFD API, June 2026)
- **Postgres footprint:** ~25–30 MB including GIN full-text index — negligible
- **Cold-start import:** Download the full world JSONL (~3–5 GB compressed, 3.7 M products
  total), stream-filter to NL, upsert 79 K rows. Takes ~5–10 min on the VPS. One-time
  developer-triggered operation.
- **Daily delta:** OFD publishes daily delta files covering the previous 14 days. Each file
  is ~10–50 MB compressed; ~100–500 NL products change per day. Processes in seconds.
- **Cadence:** **Daily at 2 AM** via systemd timer. The 14-day delta retention window means
  the server can be offline for up to two weeks without losing coverage. Beyond that, a
  developer triggers a full re-import manually.

---

## Schema

### Postgres schema: `catalog`

A dedicated schema keeps OFD data completely separate from user data. The backup script
excludes it with `--exclude-schema=catalog`, keeping pg_dump output small since this data
is re-importable from OFD at any time.

### Migration: `V10__ofd_product.sql`

```sql
CREATE SCHEMA catalog;

CREATE TABLE catalog.product (
    id            UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    barcode       TEXT        NOT NULL UNIQUE,   -- OFD 'code' field; used as upsert key
    name          TEXT        NOT NULL,
    kcal_per_100g DECIMAL(7,2) NOT NULL,
    protein_per_100g DECIMAL(7,2),
    carbs_per_100g   DECIMAL(7,2),
    fat_per_100g     DECIMAL(7,2),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- GIN index for full-text search on product name.
-- 'simple' dictionary: no language-specific stemming — product names are proper nouns.
CREATE INDEX catalog_product_fts_idx
    ON catalog.product
    USING GIN (to_tsvector('simple', name));

-- Single-row state table for import bookkeeping.
CREATE TABLE catalog.import_state (
    id                  BOOLEAN     PRIMARY KEY DEFAULT TRUE CHECK (id = TRUE),
    last_delta_end_ts   BIGINT,      -- Unix timestamp of last processed delta file's END_TS
    last_full_import_at TIMESTAMPTZ  -- When the last full JSONL import completed
);

INSERT INTO catalog.import_state (id) VALUES (TRUE);
```

**Notes:**
- `barcode` stores OFD's `code` field (EAN/UPC for real products; OFD synthetic ID for
  unscanned products). It is always set for OFD imports and serves as the natural upsert key.
  Story 15 may add food items with `barcode = null` via `food_item`, which is a separate table.
- No `source` column needed — every row in `catalog.product` is from OFD by definition.
- No `user_id` — this is a shared reference table, not per-user data.

---

## OFD field mapping

| OFD JSON field | catalog.product column | Notes |
|---|---|---|
| `code` | `barcode` | Always present in OFD |
| `product_name_nl` ?? `product_name_en` ?? `product_name` | `name` | Prefer Dutch; skip product if all three are blank |
| `nutriments.energy-kcal_100g` | `kcal_per_100g` | If absent, try `energy_100g / 4.184` (kJ → kcal); skip product if still absent |
| `nutriments.proteins_100g` | `protein_per_100g` | Nullable |
| `nutriments.carbohydrates_100g` | `carbs_per_100g` | Nullable |
| `nutriments.fat_100g` | `fat_per_100g` | Nullable |
| `countries_tags` | filter only | Must contain `"en:netherlands"` — confirmed via OFD API (`countries_tags_en=netherlands` returns 79 K products); verify against a real NL product record during implementation |
| `last_modified_t` | not stored | Used only to determine which delta files to process |

Products missing both `name` and `kcal_per_100g` after extraction are silently skipped — they
are not usable for food logging.

---

## Import pipeline

### Admin trigger endpoint

```
POST /admin/ofd-import
Header: X-Admin-Secret: {OFD_ADMIN_SECRET}
Query:  ?mode=delta   (default)
        ?mode=full
```

- Protected by `OFD_ADMIN_SECRET` env var (absent → endpoint not registered, same pattern
  as `E2E_PASSWORD` in the existing codebase).
- Returns `202 Accepted` immediately. Import runs in a background coroutine so the HTTP
  response is not held open.
- A `409 Conflict` is returned if an import is already in progress (tracked by an in-memory
  `AtomicBoolean`).
- Logs progress at INFO level (files downloaded, rows upserted, duration).

The systemd timer on the VPS runs:
```
curl -s -X POST https://server/admin/ofd-import \
     -H "X-Admin-Secret: ${OFD_ADMIN_SECRET}"
```

A developer triggers a full re-import with:
```
curl -s -X POST https://server/admin/ofd-import?mode=full \
     -H "X-Admin-Secret: ${OFD_ADMIN_SECRET}"
```

### Delta import (mode=delta)

1. Fetch `https://static.openfoodfacts.org/data/delta/index.txt` — list of available delta
   files, one per line.
2. Parse filenames: `openfoodfacts_products_{START_TS}_{END_TS}.json.gz`. Extract `START_TS`
   and `END_TS` as Long (Unix seconds).
3. Filter to files where `START_TS > last_delta_end_ts` from `catalog.import_state` (or all
   files if `last_delta_end_ts` is null — first-ever delta run after a full import).
4. Sort ascending by `START_TS`. Process in order.
5. For each file: download from `https://static.openfoodfacts.org/data/delta/{filename}`,
   decompress (gzip), parse JSONL line by line. For each line:
   - Filter: `countries_tags` must contain `"en:netherlands"`.
   - Extract fields per mapping table above.
   - Skip if `name` or `kcal_per_100g` is absent.
   - Accumulate into a batch of 500, then upsert the batch:
     ```sql
     INSERT INTO catalog.product (...) VALUES (...)
     ON CONFLICT (barcode) DO UPDATE SET
       name = EXCLUDED.name,
       kcal_per_100g = EXCLUDED.kcal_per_100g,
       protein_per_100g = EXCLUDED.protein_per_100g,
       carbs_per_100g = EXCLUDED.carbs_per_100g,
       fat_per_100g = EXCLUDED.fat_per_100g,
       updated_at = NOW()
     ```
6. After processing all files, update `import_state.last_delta_end_ts` to the maximum
   `END_TS` seen.

**If `last_delta_end_ts` is older than 14 days:** the delta window has lapsed. Log a warning
and skip — a developer should trigger `mode=full` to reseed.

### Full import (mode=full)

1. Download `https://static.openfoodfacts.org/data/openfoodfacts-products.jsonl.gz`.
2. Stream-decompress and parse JSONL line by line — never buffer the full file in memory.
   The file is ~3–5 GB compressed; line-by-line streaming keeps heap usage flat.
3. Apply the same filter + extraction + batch-upsert logic as delta import (batches of 500).
4. Also fetch `index.txt` during the full import to find the `END_TS` of the most recent
   available delta file.
5. On completion, set `import_state.last_full_import_at = NOW()` and
   `import_state.last_delta_end_ts = {max END_TS from index.txt}`. This ensures the next
   daily delta run picks up changes from the point the full JSONL snapshot was generated,
   with no gap and no double-processing.

---

## Search and barcode endpoints

Both endpoints are **authenticated** (bearer token required). They return the same DTO shape
as `FoodItemDto` (already in `shared`), with `source = "openfoodfacts"`. No new DTO needed.

### `GET /food/search?q={query}&limit={n}`

- `q`: required, 1–100 chars (reject with 400 outside this range).
- `limit`: optional, default 20, max 50.
- Queries `catalog.product` using Postgres full-text search:
  ```sql
  SELECT * FROM catalog.product
  WHERE to_tsvector('simple', name) @@ plainto_tsquery('simple', :q)
  ORDER BY ts_rank(to_tsvector('simple', name), plainto_tsquery('simple', :q)) DESC
  LIMIT :limit
  ```
- Returns `List<FoodItemDto>`. Empty list if no results; never 404.

### `GET /food/barcode?barcode={code}`

1. Look up `catalog.product` by `barcode` column (exact match).
2. If found: return `FoodItemDto`. `200 OK`.
3. If not found: **proxy to OFD API**:
   ```
   GET https://world.openfoodfacts.org/api/v2/product/{code}.json
   User-Agent: HealthApp/1.0 (personal project; bran.van.der.meer@protonmail.com)
   ```
   - OFD requires a meaningful `User-Agent` for API access.
   - If OFD returns 404 or missing nutrition data: respond `404 Not Found`.
   - If OFD returns a usable product: upsert it into `catalog.product` (caches it for
     future lookups), then return `FoodItemDto`. `200 OK`.
   - OFD proxy errors (timeouts, 5xx): propagate as `502 Bad Gateway`.
4. The `id` in the returned `FoodItemDto` is the `catalog.product.id` UUID. Story 15 uses
   this UUID when the user saves a product to their personal `food_item` catalog.

---

## Exposed Table objects

Two new `Table` objects in `data/Tables.kt`:

```kotlin
object Product : Table("catalog.product") {
    val id             = uuid("id")
    val barcode        = text("barcode")
    val name           = text("name")
    val kcalPer100g    = decimal("kcal_per_100g", 7, 2)
    val proteinPer100g = decimal("protein_per_100g", 7, 2).nullable()
    val carbsPer100g   = decimal("carbs_per_100g", 7, 2).nullable()
    val fatPer100g     = decimal("fat_per_100g", 7, 2).nullable()
    val updatedAt      = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object ImportState : Table("catalog.import_state") {
    val id               = bool("id")
    val lastDeltaEndTs   = long("last_delta_end_ts").nullable()
    val lastFullImportAt = timestampWithTimeZone("last_full_import_at").nullable()
    override val primaryKey = PrimaryKey(id)
}
```

---

## Backup exclusion

The VPS backup script (Ansible) must pass `--exclude-schema=catalog` to `pg_dump`. This is
a deployment concern, not part of this story's code, but it must be done before the first
production import.

---

## New environment variable

| Variable | Purpose |
|---|---|
| `OFD_ADMIN_SECRET` | Bearer secret for `POST /admin/ofd-import`. If absent, the endpoint is not registered. |

Add to `.env.example` and Ansible vault.

---

## Error handling

- Download failures (HTTP error, timeout): log at ERROR, abort the current import run.
  `import_state` is not updated, so the next run retries from the same checkpoint.
- Malformed JSONL lines: log at WARN and skip the line. Do not abort the whole run.
- Database errors during upsert: let the exception propagate, log at ERROR, abort run.
- OFD API proxy errors on `/food/barcode`: return appropriate HTTP status to the app
  (404 if product not found, 502 on OFD server error). Never expose OFD error bodies
  verbatim to the client.

---

## Testing

- **Unit tests** (`OfdImportServiceTest`): parse a fixture JSONL with known records; verify
  NL filter, field extraction, name fallback logic (nl → en → generic), kcal kJ conversion,
  and skipping of products with missing required fields. No real HTTP calls.
- **Integration test** (`OfdImportIntegrationTest`): spin up test DB, call the service with
  a small fixture file, assert rows in `catalog.product` and that `import_state` is updated
  correctly. Reuse the existing `TestDatabase` helper.
- **API tests** (`FoodApiTest`): call `GET /food/search` and `GET /food/barcode` against a
  seeded test DB. Assert response shape, 400 on missing/overlong `q`, 404 on unknown
  barcode.
- The `POST /admin/ofd-import` endpoint is not covered by API tests (it requires a real OFD
  connection). A smoke test after the first production deployment is sufficient.
