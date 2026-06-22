# OFD Import Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a daily-synced Open Food Facts NL mirror (`catalog.product`) to the server with `/food/search` and `/food/barcode` endpoints.

**Architecture:** A `catalog` Postgres schema holds `catalog.product` (~79 K NL rows, ~25-30 MB) and `catalog.import_state` (single bookkeeping row). `OfdImportService` handles two import paths: full JSONL download (cold start, `?mode=full`) and daily delta files (normal operation). Two authenticated endpoints expose FTS search and barcode lookup; barcode misses proxy live to the OFD API and cache the result.

**Tech Stack:** Ktor 3.4.3, Exposed 0.61.0, Postgres, `kotlinx-serialization-json`, `ktor-client-mock` (already in test deps), Java GZIPInputStream/BufferedReader.

## Global Constraints

- All new code in `org.branneman.health.food` package.
- Migration file: `V10__ofd_product.sql` (next in sequence after V9).
- `catalog` schema excluded from pg_dump with `--exclude-schema=catalog`.
- OFD admin endpoint only registered when `OFD_ADMIN_SECRET` env var is set (same pattern as `E2E_PASSWORD`).
- No new Gradle dependencies needed — all required libraries are already on the classpath.
- Run `./gradlew :server:test` after every task to catch regressions. Tests need the `health_test` Postgres DB running.
- Commit message format: `type(server): message`.

---

## File Map

| File | Status | Purpose |
|---|---|---|
| `server/src/main/resources/db/migration/V10__ofd_product.sql` | Create | `catalog` schema, `catalog.product`, `catalog.import_state`, GIN index |
| `server/src/main/kotlin/org/branneman/health/data/Tables.kt` | Modify | Add `Product` and `ImportState` Exposed table objects |
| `server/src/main/kotlin/org/branneman/health/food/OfdImportService.kt` | Create | Extraction logic, delta import, full import, single-row upsert for proxy cache |
| `server/src/main/kotlin/org/branneman/health/food/FoodRoutes.kt` | Create | `GET /food/search` and `GET /food/barcode` (with OFD live proxy) |
| `server/src/main/kotlin/org/branneman/health/food/OfdAdminRoute.kt` | Create | `POST /admin/ofd-import` |
| `server/src/main/kotlin/org/branneman/health/Application.kt` | Modify | Wire `OfdImportService`, `foodRoutes`, `ofdAdminRoute` |
| `server/src/test/kotlin/org/branneman/health/food/OfdImportServiceTest.kt` | Create | Pure unit tests for extraction and delta-index parsing |
| `server/src/test/kotlin/org/branneman/health/food/OfdImportIntegrationTest.kt` | Create | Integration tests: delta import and full import against test DB with mock HTTP |
| `server/src/apiTest/kotlin/org/branneman/health/FoodApiTest.kt` | Create | API tests for search and barcode endpoints |

---

## Task 1: Migration and Table Objects

**Files:**
- Create: `server/src/main/resources/db/migration/V10__ofd_product.sql`
- Modify: `server/src/main/kotlin/org/branneman/health/data/Tables.kt`

**Interfaces:**
- Produces: `Product` and `ImportState` Exposed table objects used by Tasks 2–6.

- [ ] **Step 1: Write the migration**

Create `server/src/main/resources/db/migration/V10__ofd_product.sql`:

```sql
CREATE SCHEMA catalog;

CREATE TABLE catalog.product (
    id               UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    barcode          TEXT         NOT NULL UNIQUE,
    name             TEXT         NOT NULL,
    kcal_per_100g    DECIMAL(7,2) NOT NULL,
    protein_per_100g DECIMAL(7,2),
    carbs_per_100g   DECIMAL(7,2),
    fat_per_100g     DECIMAL(7,2),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX catalog_product_fts_idx
    ON catalog.product
    USING GIN (to_tsvector('simple', name));

CREATE TABLE catalog.import_state (
    id                  BOOLEAN     PRIMARY KEY DEFAULT TRUE CHECK (id = TRUE),
    last_delta_end_ts   BIGINT,
    last_full_import_at TIMESTAMPTZ
);

INSERT INTO catalog.import_state (id) VALUES (TRUE);
```

- [ ] **Step 2: Add Exposed table objects to `Tables.kt`**

Append to the end of `server/src/main/kotlin/org/branneman/health/data/Tables.kt`:

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

- [ ] **Step 3: Verify migration and existing tests pass**

```bash
./gradlew :server:test
```

Expected: all existing tests pass (Flyway runs V10 on `health_test` DB as part of `TestDatabase.dataSource` lazy init).

- [ ] **Step 4: Commit**

```bash
git add server/src/main/resources/db/migration/V10__ofd_product.sql \
        server/src/main/kotlin/org/branneman/health/data/Tables.kt
git commit -m "feat(server): add catalog.product schema and Exposed table objects"
```

---

## Task 2: OfdImportService — Extraction Logic (Unit Tests)

**Files:**
- Create: `server/src/main/kotlin/org/branneman/health/food/OfdImportService.kt` (partial — data classes + `extractProduct` + `parseDeltaIndex` only)
- Create: `server/src/test/kotlin/org/branneman/health/food/OfdImportServiceTest.kt`

**Interfaces:**
- Produces:
  - `OfdImportService.ProductRow(barcode, name, kcalPer100g, proteinPer100g, carbsPer100g, fatPer100g)`
  - `OfdImportService.DeltaFile(filename, startTs, endTs)`
  - `internal fun OfdImportService.extractProduct(json: JsonObject): ProductRow?`
  - `internal fun OfdImportService.parseDeltaIndex(text: String): List<DeltaFile>`

- [ ] **Step 1: Write the failing tests**

Create `server/src/test/kotlin/org/branneman/health/food/OfdImportServiceTest.kt`:

```kotlin
package org.branneman.health.food

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.branneman.health.TestDatabase
import org.jetbrains.exposed.sql.Database
import kotlin.test.*

class OfdImportServiceTest {

    private fun service() = OfdImportService(
        dataSource = TestDatabase.dataSource,
        httpClient = HttpClient(MockEngine { error("should not be called") }),
    )

    private fun json(vararg pairs: String) = pairs.joinToString(",", "{", "}").let {
        Json.parseToJsonElement(it).jsonObject
    }

    private fun nlProduct(
        code: String = "1234567890123",
        nameFull: String = "Oatly Oat Milk",
        nameNl: String? = null,
        nameEn: String? = null,
        kcal: Double? = 47.0,
        kj: Double? = null,
        protein: Double? = 1.0,
        carbs: Double? = 6.7,
        fat: Double? = 1.5,
        countries: String = """["en:netherlands"]""",
    ): String {
        val nameNlField = if (nameNl != null) """"product_name_nl":"$nameNl",""" else ""
        val nameEnField = if (nameEn != null) """"product_name_en":"$nameEn",""" else ""
        val kcalField = if (kcal != null) """"energy-kcal_100g":$kcal,""" else ""
        val kjField = if (kj != null) """"energy_100g":$kj,""" else ""
        return """
        {
          "code":"$code",
          "product_name":"$nameFull",
          $nameNlField
          $nameEnField
          "countries_tags":$countries,
          "nutriments":{
            $kcalField
            $kjField
            "proteins_100g":$protein,
            "carbohydrates_100g":$carbs,
            "fat_100g":$fat
          }
        }
        """.trimIndent()
    }

    // --- extractProduct ---

    @Test fun `valid NL product is extracted with all fields`() {
        val row = service().extractProduct(
            Json.parseToJsonElement(nlProduct()).jsonObject
        )
        assertNotNull(row)
        assertEquals("1234567890123", row.barcode)
        assertEquals("Oatly Oat Milk", row.name)
        assertEquals(47.0, row.kcalPer100g.toDouble())
        assertEquals(1.0, row.proteinPer100g?.toDouble())
        assertEquals(6.7, row.carbsPer100g?.toDouble())
        assertEquals(1.5, row.fatPer100g?.toDouble())
    }

    @Test fun `non-NL product is skipped when requireNl is true`() {
        val row = service().extractProduct(
            Json.parseToJsonElement(nlProduct(countries = """["en:france"]""")).jsonObject
        )
        assertNull(row)
    }

    @Test fun `non-NL product is accepted when requireNl is false`() {
        val row = service().extractProduct(
            Json.parseToJsonElement(nlProduct(countries = """["en:france"]""")).jsonObject,
            requireNl = false
        )
        assertNotNull(row)
        assertEquals("Oatly Oat Milk", row.name)
    }

    @Test fun `product with blank name is skipped`() {
        val row = service().extractProduct(
            Json.parseToJsonElement(nlProduct(nameFull = "  ", nameNl = null, nameEn = null)).jsonObject
        )
        assertNull(row)
    }

    @Test fun `product missing kcal and kJ is skipped`() {
        val row = service().extractProduct(
            Json.parseToJsonElement(nlProduct(kcal = null, kj = null)).jsonObject
        )
        assertNull(row)
    }

    @Test fun `name fallback: product_name_nl preferred over product_name`() {
        val row = service().extractProduct(
            Json.parseToJsonElement(nlProduct(nameFull = "Oatly Generic", nameNl = "Haver Melk")).jsonObject
        )
        assertEquals("Haver Melk", row?.name)
    }

    @Test fun `name fallback: product_name_en used when nl absent`() {
        val row = service().extractProduct(
            Json.parseToJsonElement(nlProduct(nameFull = "Oatly Generic", nameEn = "Oatly English")).jsonObject
        )
        assertEquals("Oatly English", row?.name)
    }

    @Test fun `name fallback: product_name used when nl and en absent`() {
        val row = service().extractProduct(
            Json.parseToJsonElement(nlProduct(nameFull = "Oatly Fallback")).jsonObject
        )
        assertEquals("Oatly Fallback", row?.name)
    }

    @Test fun `kJ-only product is converted to kcal`() {
        // 418.4 kJ / 4.184 = 100.0 kcal
        val row = service().extractProduct(
            Json.parseToJsonElement(nlProduct(kcal = null, kj = 418.4)).jsonObject
        )
        assertNotNull(row)
        assertEquals(100.0, row.kcalPer100g.toDouble(), absoluteTolerance = 0.1)
    }

    // --- parseDeltaIndex ---

    @Test fun `parseDeltaIndex parses filenames correctly`() {
        val index = """
            openfoodfacts_products_1000_2000.json.gz
            openfoodfacts_products_2001_3000.json.gz
        """.trimIndent()
        val files = service().parseDeltaIndex(index)
        assertEquals(2, files.size)
        assertEquals("openfoodfacts_products_1000_2000.json.gz", files[0].filename)
        assertEquals(1000L, files[0].startTs)
        assertEquals(2000L, files[0].endTs)
        assertEquals(2001L, files[1].startTs)
        assertEquals(3000L, files[1].endTs)
    }

    @Test fun `parseDeltaIndex ignores blank lines`() {
        val index = "\nopenfoodfacts_products_1000_2000.json.gz\n\n"
        val files = service().parseDeltaIndex(index)
        assertEquals(1, files.size)
    }
}
```

- [ ] **Step 2: Run to confirm tests fail**

```bash
./gradlew :server:test --tests "org.branneman.health.food.OfdImportServiceTest"
```

Expected: compilation error — `OfdImportService` does not exist yet.

- [ ] **Step 3: Create OfdImportService with extraction logic**

Create `server/src/main/kotlin/org/branneman/health/food/OfdImportService.kt`:

```kotlin
package org.branneman.health.food

import io.ktor.client.*
import kotlinx.serialization.json.*
import org.branneman.health.data.ImportState
import org.branneman.health.data.Product
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

class OfdImportService(
    private val dataSource: DataSource,
    private val httpClient: HttpClient,
) {
    companion object {
        const val INDEX_URL      = "https://static.openfoodfacts.org/data/delta/index.txt"
        const val DELTA_BASE_URL = "https://static.openfoodfacts.org/data/delta/"
        const val FULL_JSONL_URL = "https://static.openfoodfacts.org/data/openfoodfacts-products.jsonl.gz"
        const val OFD_PRODUCT_URL = "https://world.openfoodfacts.org/api/v2/product"
        const val USER_AGENT     = "HealthApp/1.0 (github.com/branneman/health)"
        const val BATCH_SIZE     = 500
        const val NL_TAG         = "en:netherlands"
    }

    private val importing = AtomicBoolean(false)

    fun isImporting(): Boolean = importing.get()

    // Exposed once so FoodRoutes can reuse it for the OFD proxy response
    // requireNl = true for bulk imports (NL-only filter);
    // requireNl = false for the barcode proxy (accept any OFD product — EU fallback).
    internal fun extractProduct(json: JsonObject, requireNl: Boolean = true): ProductRow? {
        if (requireNl) {
            val countries = json["countries_tags"]?.jsonArray
                ?.map { it.jsonPrimitive.content } ?: emptyList()
            if (NL_TAG !in countries) return null
        }

        val barcode = json["code"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() } ?: return null

        val name = json["product_name_nl"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: json["product_name_en"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: json["product_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return null

        val nutriments = json["nutriments"]?.jsonObject ?: return null

        val kcal: BigDecimal = nutriments["energy-kcal_100g"]?.jsonPrimitive?.doubleOrNull
            ?.toBigDecimal()
            ?: nutriments["energy_100g"]?.jsonPrimitive?.doubleOrNull
                ?.let { (it / 4.184).toBigDecimal() }
            ?: return null

        return ProductRow(
            barcode        = barcode,
            name           = name,
            kcalPer100g    = kcal.setScale(2, RoundingMode.HALF_UP),
            proteinPer100g = nutriments["proteins_100g"]?.jsonPrimitive?.doubleOrNull?.toBigDecimal(),
            carbsPer100g   = nutriments["carbohydrates_100g"]?.jsonPrimitive?.doubleOrNull?.toBigDecimal(),
            fatPer100g     = nutriments["fat_100g"]?.jsonPrimitive?.doubleOrNull?.toBigDecimal(),
        )
    }

    internal fun parseDeltaIndex(text: String): List<DeltaFile> =
        text.lines()
            .filter { it.isNotBlank() }
            .mapNotNull { filename ->
                val base = filename.removeSuffix(".json.gz")
                val parts = base.split("_")
                val startTs = parts.getOrNull(parts.size - 2)?.toLongOrNull() ?: return@mapNotNull null
                val endTs   = parts.getOrNull(parts.size - 1)?.toLongOrNull() ?: return@mapNotNull null
                DeltaFile(filename.trim(), startTs, endTs)
            }

    fun upsertProduct(row: ProductRow) {
        transaction {
            Product.upsert(Product.barcode, onUpdateExclude = listOf(Product.id)) {
                it[id]             = UUID.randomUUID()
                it[barcode]        = row.barcode
                it[name]           = row.name
                it[kcalPer100g]    = row.kcalPer100g
                it[proteinPer100g] = row.proteinPer100g
                it[carbsPer100g]   = row.carbsPer100g
                it[fatPer100g]     = row.fatPer100g
                it[updatedAt]      = OffsetDateTime.now()
            }
        }
    }

    internal fun upsertBatch(batch: List<ProductRow>) {
        transaction {
            for (row in batch) {
                Product.upsert(Product.barcode, onUpdateExclude = listOf(Product.id)) {
                    it[id]             = UUID.randomUUID()
                    it[barcode]        = row.barcode
                    it[name]           = row.name
                    it[kcalPer100g]    = row.kcalPer100g
                    it[proteinPer100g] = row.proteinPer100g
                    it[carbsPer100g]   = row.carbsPer100g
                    it[fatPer100g]     = row.fatPer100g
                    it[updatedAt]      = OffsetDateTime.now()
                }
            }
        }
    }

    data class ProductRow(
        val barcode: String,
        val name: String,
        val kcalPer100g: BigDecimal,
        val proteinPer100g: BigDecimal?,
        val carbsPer100g: BigDecimal?,
        val fatPer100g: BigDecimal?,
    )

    data class DeltaFile(val filename: String, val startTs: Long, val endTs: Long)

    data class ImportResult(val upserted: Int, val skipped: Int)
}
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
./gradlew :server:test --tests "org.branneman.health.food.OfdImportServiceTest"
```

Expected: all 10 tests pass.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/org/branneman/health/food/OfdImportService.kt \
        server/src/test/kotlin/org/branneman/health/food/OfdImportServiceTest.kt
git commit -m "feat(server): add OFD product extraction and delta-index parsing"
```

---

## Task 3: OfdImportService — Delta Import

**Files:**
- Modify: `server/src/main/kotlin/org/branneman/health/food/OfdImportService.kt` (add `importDelta`, `processGzipBytes`)
- Create: `server/src/test/kotlin/org/branneman/health/food/OfdImportIntegrationTest.kt`

**Interfaces:**
- Consumes: `ProductRow`, `DeltaFile`, `parseDeltaIndex`, `upsertBatch`, `ImportState`, `Product` (from Tasks 1–2)
- Produces: `suspend fun OfdImportService.importDelta(): ImportResult`

- [ ] **Step 1: Write failing integration tests**

Create `server/src/test/kotlin/org/branneman/health/food/OfdImportIntegrationTest.kt`:

```kotlin
package org.branneman.health.food

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import org.branneman.health.TestDatabase
import org.branneman.health.data.ImportState
import org.branneman.health.data.Product
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.*

class OfdImportIntegrationTest {

    companion object {
        private val ds = TestDatabase.dataSource

        init { Database.connect(ds) }
    }

    private fun gzip(vararg jsonLines: String): ByteArray {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gz ->
            jsonLines.forEach { gz.write((it + "\n").toByteArray(Charsets.UTF_8)) }
        }
        return baos.toByteArray()
    }

    private fun nlProductJson(
        code: String,
        name: String,
        kcal: Double = 100.0,
    ) = """{"code":"$code","product_name":"$name","countries_tags":["en:netherlands"],"nutriments":{"energy-kcal_100g":$kcal,"proteins_100g":5.0,"carbohydrates_100g":10.0,"fat_100g":2.0}}"""

    private fun deltaIndex(vararg files: String) = files.joinToString("\n")

    private fun resetState() {
        transaction {
            Product.deleteWhere { Product.barcode like "test-%" }
            ImportState.update({ ImportState.id eq true }) {
                it[ImportState.lastDeltaEndTs]   = null
                it[ImportState.lastFullImportAt] = null
            }
        }
    }

    @BeforeTest fun setUp() = resetState()

    // --- delta import ---

    @Test fun `delta import upserts NL products from delta file`() = runBlocking {
        val deltaBytes = gzip(
            nlProductJson("test-0001", "Almond Milk"),
            nlProductJson("test-0002", "Soy Milk"),
        )
        val mockEngine = MockEngine { request ->
            when (request.url.toString()) {
                OfdImportService.INDEX_URL ->
                    respond("openfoodfacts_products_1000_2000.json.gz", HttpStatusCode.OK)
                "${OfdImportService.DELTA_BASE_URL}openfoodfacts_products_1000_2000.json.gz" ->
                    respond(deltaBytes, HttpStatusCode.OK)
                else -> error("Unexpected URL: ${request.url}")
            }
        }
        val service = OfdImportService(ds, HttpClient(mockEngine))
        val result = service.importDelta()

        assertEquals(2, result.upserted)
        val names = transaction {
            Product.selectAll()
                .where { Product.barcode inList listOf("test-0001", "test-0002") }
                .map { it[Product.name] }
                .toSet()
        }
        assertEquals(setOf("Almond Milk", "Soy Milk"), names)
    }

    @Test fun `delta import only processes files newer than last_delta_end_ts`() = runBlocking {
        transaction {
            ImportState.update({ ImportState.id eq true }) {
                it[ImportState.lastDeltaEndTs] = 2000L
            }
        }
        val mockEngine = MockEngine { request ->
            when (request.url.toString()) {
                OfdImportService.INDEX_URL -> respond(
                    deltaIndex(
                        "openfoodfacts_products_1000_2000.json.gz",   // startTs=1000 <= 2000 — skip
                        "openfoodfacts_products_2001_3000.json.gz",   // startTs=2001 > 2000 — process
                    ),
                    HttpStatusCode.OK
                )
                "${OfdImportService.DELTA_BASE_URL}openfoodfacts_products_2001_3000.json.gz" ->
                    respond(gzip(nlProductJson("test-0003", "Rice Milk")), HttpStatusCode.OK)
                else -> error("Unexpected URL: ${request.url}")
            }
        }
        val service = OfdImportService(ds, HttpClient(mockEngine))
        val result = service.importDelta()

        assertEquals(1, result.upserted)
        val count = transaction {
            Product.selectAll().where { Product.barcode eq "test-0003" }.count()
        }
        assertEquals(1L, count)
    }

    @Test fun `delta import updates last_delta_end_ts to max END_TS`() = runBlocking {
        val mockEngine = MockEngine { request ->
            when (request.url.toString()) {
                OfdImportService.INDEX_URL -> respond(
                    deltaIndex(
                        "openfoodfacts_products_1000_2000.json.gz",
                        "openfoodfacts_products_2001_3000.json.gz",
                    ),
                    HttpStatusCode.OK
                )
                "${OfdImportService.DELTA_BASE_URL}openfoodfacts_products_1000_2000.json.gz" ->
                    respond(gzip(nlProductJson("test-0004", "Oat A")), HttpStatusCode.OK)
                "${OfdImportService.DELTA_BASE_URL}openfoodfacts_products_2001_3000.json.gz" ->
                    respond(gzip(nlProductJson("test-0005", "Oat B")), HttpStatusCode.OK)
                else -> error("Unexpected URL: ${request.url}")
            }
        }
        val service = OfdImportService(ds, HttpClient(mockEngine))
        service.importDelta()

        val endTs = transaction {
            ImportState.selectAll().where { ImportState.id eq true }
                .single()[ImportState.lastDeltaEndTs]
        }
        assertEquals(3000L, endTs)
    }

    @Test fun `upserting same barcode twice produces one row with updated name`() = runBlocking {
        val firstBytes  = gzip(nlProductJson("test-0006", "Original Name"))
        val secondBytes = gzip(nlProductJson("test-0006", "Updated Name"))
        var callCount = 0
        val mockEngine = MockEngine { request ->
            when {
                request.url.toString() == OfdImportService.INDEX_URL -> {
                    callCount++
                    if (callCount == 1)
                        respond("openfoodfacts_products_1000_2000.json.gz\n", HttpStatusCode.OK)
                    else
                        respond("openfoodfacts_products_2001_3000.json.gz\n", HttpStatusCode.OK)
                }
                request.url.toString().endsWith("1000_2000.json.gz") ->
                    respond(firstBytes, HttpStatusCode.OK)
                request.url.toString().endsWith("2001_3000.json.gz") ->
                    respond(secondBytes, HttpStatusCode.OK)
                else -> error("Unexpected URL: ${request.url}")
            }
        }
        val service = OfdImportService(ds, HttpClient(mockEngine))
        service.importDelta()
        // Reset state so second delta is processed
        transaction { ImportState.update({ ImportState.id eq true }) { it[ImportState.lastDeltaEndTs] = null } }
        service.importDelta()

        val rows = transaction {
            Product.selectAll().where { Product.barcode eq "test-0006" }.toList()
        }
        assertEquals(1, rows.size)
        assertEquals("Updated Name", rows[0][Product.name])
    }
}
```

- [ ] **Step 2: Run to confirm tests fail**

```bash
./gradlew :server:test --tests "org.branneman.health.food.OfdImportIntegrationTest"
```

Expected: compilation error — `importDelta()` does not exist yet.

- [ ] **Step 3: Add `importDelta` and `processGzipBytes` to OfdImportService**

Add the following to `OfdImportService.kt` (after the existing `upsertBatch` method and before the data classes):

```kotlin
import io.ktor.client.request.*
import io.ktor.client.statement.*
import java.io.ByteArrayInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream
```

Add these imports at the top of the file (after the existing imports), then add these methods to the class body:

```kotlin
suspend fun importDelta(): ImportResult {
    if (!importing.compareAndSet(false, true)) error("Import already in progress")
    return try {
        val indexText = httpClient.get(INDEX_URL).bodyAsText()
        val allFiles  = parseDeltaIndex(indexText)

        val lastEndTs = transaction {
            ImportState.selectAll().where { ImportState.id eq true }
                .single()[ImportState.lastDeltaEndTs]
        }

        val toProcess = allFiles
            .filter { it.startTs > (lastEndTs ?: Long.MIN_VALUE) }
            .sortedBy { it.startTs }

        var upserted = 0
        var skipped  = 0
        for (file in toProcess) {
            val bytes = httpClient.get("$DELTA_BASE_URL${file.filename}") {
                header("User-Agent", USER_AGENT)
            }.readBytes()
            val result = processGzipBytes(bytes)
            upserted += result.upserted
            skipped  += result.skipped
        }

        if (toProcess.isNotEmpty()) {
            val maxEndTs = toProcess.maxOf { it.endTs }
            transaction {
                ImportState.update({ ImportState.id eq true }) {
                    it[lastDeltaEndTs] = maxEndTs
                }
            }
        }

        ImportResult(upserted, skipped)
    } finally {
        importing.set(false)
    }
}

private fun processGzipBytes(bytes: ByteArray): ImportResult {
    var upserted = 0
    var skipped  = 0
    GZIPInputStream(ByteArrayInputStream(bytes)).use { gz ->
        BufferedReader(InputStreamReader(gz, Charsets.UTF_8)).use { reader ->
            val batch = mutableListOf<ProductRow>()
            var line = reader.readLine()
            while (line != null) {
                val row = runCatching {
                    extractProduct(Json.parseToJsonElement(line).jsonObject)
                }.getOrNull()
                if (row != null) batch.add(row) else skipped++
                if (batch.size >= BATCH_SIZE) {
                    upsertBatch(batch)
                    upserted += batch.size
                    batch.clear()
                }
                line = reader.readLine()
            }
            if (batch.isNotEmpty()) {
                upsertBatch(batch)
                upserted += batch.size
            }
        }
    }
    return ImportResult(upserted, skipped)
}
```

Add the missing imports to the top of the file:

```kotlin
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
./gradlew :server:test --tests "org.branneman.health.food.OfdImportIntegrationTest"
```

Expected: all 4 integration tests pass.

- [ ] **Step 5: Run full test suite to catch regressions**

```bash
./gradlew :server:test
```

Expected: all tests pass.

- [ ] **Step 6: Commit**

```bash
git add server/src/main/kotlin/org/branneman/health/food/OfdImportService.kt \
        server/src/test/kotlin/org/branneman/health/food/OfdImportIntegrationTest.kt
git commit -m "feat(server): add OFD delta import"
```

---

## Task 4: OfdImportService — Full Import

**Files:**
- Modify: `server/src/main/kotlin/org/branneman/health/food/OfdImportService.kt` (add `importFull`)
- Modify: `server/src/test/kotlin/org/branneman/health/food/OfdImportIntegrationTest.kt` (add full-import tests)

**Interfaces:**
- Consumes: `processGzipBytes`, `parseDeltaIndex`, `upsertBatch`, `ImportState` (all from Task 3)
- Produces: `suspend fun OfdImportService.importFull(): ImportResult`

- [ ] **Step 1: Write the failing tests**

Append to `OfdImportIntegrationTest.kt` (inside the class body, after the existing tests):

```kotlin
// --- full import ---

@Test fun `full import upserts NL products and skips non-NL`() = runBlocking {
    val fullBytes = gzip(
        nlProductJson("test-0010", "NL Product"),
        """{"code":"test-0011","product_name":"FR Product","countries_tags":["en:france"],"nutriments":{"energy-kcal_100g":200.0}}""",
    )
    val mockEngine = MockEngine { request ->
        when (request.url.toString()) {
            OfdImportService.INDEX_URL ->
                respond("openfoodfacts_products_9000_9999.json.gz", HttpStatusCode.OK)
            OfdImportService.FULL_JSONL_URL ->
                respond(fullBytes, HttpStatusCode.OK)
            else -> error("Unexpected: ${request.url}")
        }
    }
    val service = OfdImportService(ds, HttpClient(mockEngine))
    val result = service.importFull()

    assertEquals(1, result.upserted)
    assertNotNull(
        transaction { Product.selectAll().where { Product.barcode eq "test-0010" }.singleOrNull() }
    )
    assertNull(
        transaction { Product.selectAll().where { Product.barcode eq "test-0011" }.singleOrNull() }
    )
}

@Test fun `full import sets last_full_import_at and last_delta_end_ts`() = runBlocking {
    val mockEngine = MockEngine { request ->
        when (request.url.toString()) {
            OfdImportService.INDEX_URL ->
                respond("openfoodfacts_products_5000_5999.json.gz", HttpStatusCode.OK)
            OfdImportService.FULL_JSONL_URL ->
                respond(gzip(nlProductJson("test-0012", "Bread")), HttpStatusCode.OK)
            else -> error("Unexpected: ${request.url}")
        }
    }
    val service = OfdImportService(ds, HttpClient(mockEngine))
    service.importFull()

    val state = transaction {
        ImportState.selectAll().where { ImportState.id eq true }.single()
    }
    assertNotNull(state[ImportState.lastFullImportAt])
    assertEquals(5999L, state[ImportState.lastDeltaEndTs])
}
```

- [ ] **Step 2: Run to confirm tests fail**

```bash
./gradlew :server:test --tests "org.branneman.health.food.OfdImportIntegrationTest.full*"
```

Expected: compilation error — `importFull()` does not exist yet.

- [ ] **Step 3: Add `importFull` to OfdImportService**

Add the following method to `OfdImportService.kt` (after `importDelta`):

```kotlin
import io.ktor.utils.io.jvm.javaio.toInputStream
```

Add this import at the top, then add the method:

```kotlin
suspend fun importFull(): ImportResult {
    if (!importing.compareAndSet(false, true)) error("Import already in progress")
    return try {
        val indexText = httpClient.get(INDEX_URL).bodyAsText()
        val maxEndTs  = parseDeltaIndex(indexText).maxOfOrNull { it.endTs }

        var upserted = 0
        var skipped  = 0
        httpClient.prepareGet(FULL_JSONL_URL) {
            header("User-Agent", USER_AGENT)
        }.execute { response ->
            GZIPInputStream(response.bodyAsChannel().toInputStream()).use { gz ->
                BufferedReader(InputStreamReader(gz, Charsets.UTF_8)).use { reader ->
                    val batch = mutableListOf<ProductRow>()
                    var line = reader.readLine()
                    while (line != null) {
                        val row = runCatching {
                            extractProduct(Json.parseToJsonElement(line).jsonObject)
                        }.getOrNull()
                        if (row != null) batch.add(row) else skipped++
                        if (batch.size >= BATCH_SIZE) {
                            upsertBatch(batch)
                            upserted += batch.size
                            batch.clear()
                        }
                        line = reader.readLine()
                    }
                    if (batch.isNotEmpty()) {
                        upsertBatch(batch)
                        upserted += batch.size
                    }
                }
            }
        }

        transaction {
            ImportState.update({ ImportState.id eq true }) {
                it[lastFullImportAt] = OffsetDateTime.now()
                if (maxEndTs != null) it[lastDeltaEndTs] = maxEndTs
            }
        }

        ImportResult(upserted, skipped)
    } finally {
        importing.set(false)
    }
}
```

- [ ] **Step 4: Run tests**

```bash
./gradlew :server:test --tests "org.branneman.health.food.OfdImportIntegrationTest"
```

Expected: all tests pass (both delta and full import).

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/org/branneman/health/food/OfdImportService.kt \
        server/src/test/kotlin/org/branneman/health/food/OfdImportIntegrationTest.kt
git commit -m "feat(server): add OFD full JSONL import"
```

---

## Task 5: Admin Route

**Files:**
- Create: `server/src/main/kotlin/org/branneman/health/food/OfdAdminRoute.kt`
- Modify: `server/src/main/kotlin/org/branneman/health/Application.kt`

**Interfaces:**
- Consumes: `OfdImportService.importDelta()`, `OfdImportService.importFull()`, `OfdImportService.isImporting()` (Task 3–4)
- Produces: `POST /admin/ofd-import?mode=delta|full` endpoint, gated by `OFD_ADMIN_SECRET` header.

- [ ] **Step 1: Create OfdAdminRoute.kt**

Create `server/src/main/kotlin/org/branneman/health/food/OfdAdminRoute.kt`:

```kotlin
package org.branneman.health.food

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("OfdAdminRoute")

fun Route.ofdAdminRoute(secret: String, importService: OfdImportService) {
    post("/admin/ofd-import") {
        val provided = call.request.headers["X-Admin-Secret"]
        if (provided != secret) {
            call.respond(HttpStatusCode.Unauthorized)
            return@post
        }
        if (importService.isImporting()) {
            call.respond(HttpStatusCode.Conflict, "Import already in progress")
            return@post
        }
        val mode = call.request.queryParameters["mode"] ?: "delta"
        call.respond(HttpStatusCode.Accepted)
        call.application.launch {
            runCatching {
                if (mode == "full") importService.importFull() else importService.importDelta()
            }.onSuccess { result ->
                log.info("OFD $mode import complete: upserted=${result.upserted} skipped=${result.skipped}")
            }.onFailure { e ->
                log.error("OFD $mode import failed", e)
            }
        }
    }
}
```

- [ ] **Step 2: Wire into Application.kt**

In `Application.module()` in `Application.kt`, after the `polarCipher` line and before `module(dataSource, ...)`:

```kotlin
val ofdAdminSecret = System.getenv("OFD_ADMIN_SECRET")
```

In `Application.module(dataSource, polarApiClient, polarCipher)`, add a new parameter (and the corresponding call-site update):

Find this signature in Application.kt:
```kotlin
fun Application.module(
    dataSource: javax.sql.DataSource,
    polarApiClient: PolarApiClient? = null,
    polarCipher: TokenCipher? = null,
) {
```

Change to:
```kotlin
fun Application.module(
    dataSource: javax.sql.DataSource,
    polarApiClient: PolarApiClient? = null,
    polarCipher: TokenCipher? = null,
    ofdAdminSecret: String? = null,
) {
```

Update the call in `Application.module()` (the no-arg version):
```kotlin
module(dataSource, polarApiClient, polarCipher, ofdAdminSecret)
```

Inside the module function, after `Database.connect(dataSource)`, add:
```kotlin
val ofdHttpClient = buildOfdHttpClient()
val ofdImportService = OfdImportService(dataSource, ofdHttpClient)
```

Inside the `routing { }` block, after the Polar routes block, add:
```kotlin
ofdAdminSecret?.let { secret ->
    ofdAdminRoute(secret, ofdImportService)
}
```

At the bottom of Application.kt add:
```kotlin
private fun buildOfdHttpClient(): io.ktor.client.HttpClient {
    return io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO) {
        install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
            json()
        }
    }
}
```

Add the required imports:
```kotlin
import org.branneman.health.food.OfdImportService
import org.branneman.health.food.ofdAdminRoute
```

- [ ] **Step 3: Run server tests to confirm nothing is broken**

```bash
./gradlew :server:test
```

Expected: all tests pass.

- [ ] **Step 4: Commit**

```bash
git add server/src/main/kotlin/org/branneman/health/food/OfdAdminRoute.kt \
        server/src/main/kotlin/org/branneman/health/Application.kt
git commit -m "feat(server): add POST /admin/ofd-import endpoint"
```

---

## Task 6: Food Search and Barcode Routes

**Files:**
- Create: `server/src/main/kotlin/org/branneman/health/food/FoodRoutes.kt`
- Modify: `server/src/main/kotlin/org/branneman/health/Application.kt`
- Create: `server/src/apiTest/kotlin/org/branneman/health/FoodApiTest.kt`

**Interfaces:**
- Consumes: `OfdImportService.extractProduct()`, `OfdImportService.upsertProduct()`, `Product` table, `FoodItemDto` from `shared`
- Produces: `GET /food/search?q=&limit=`, `GET /food/barcode?barcode=`

- [ ] **Step 1: Write the failing API tests**

Create `server/src/apiTest/kotlin/org/branneman/health/FoodApiTest.kt`:

```kotlin
package org.branneman.health

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.branneman.health.FoodItemDto
import org.junit.Test
import kotlin.test.*

class FoodApiTest : ApiTestBase() {

    @Test fun `search returns 200 with list`() = runTest {
        val token = login()
        val resp = client.get("$serverUrl/food/search?q=melk") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, resp.status)
        // List may be empty if catalog not yet seeded — shape is what matters here
        assertNotNull(resp.body<List<FoodItemDto>>())
    }

    @Test fun `search without q returns 400`() = runTest {
        val token = login()
        val resp = client.get("$serverUrl/food/search") { bearerAuth(token) }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test fun `search with q longer than 100 chars returns 400`() = runTest {
        val token = login()
        val q = "a".repeat(101)
        val resp = client.get("$serverUrl/food/search?q=$q") { bearerAuth(token) }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test fun `search without auth returns 401`() = runTest {
        val resp = client.get("$serverUrl/food/search?q=melk")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test fun `barcode without param returns 400`() = runTest {
        val token = login()
        val resp = client.get("$serverUrl/food/barcode") { bearerAuth(token) }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test fun `barcode for unknown code returns 404`() = runTest {
        val token = login()
        // 0000000000000 is a synthetic non-real barcode; OFD returns 404 or status=0
        val resp = client.get("$serverUrl/food/barcode?barcode=0000000000000") { bearerAuth(token) }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test fun `barcode without auth returns 401`() = runTest {
        val resp = client.get("$serverUrl/food/barcode?barcode=3017620422003")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test fun `barcode proxy returns FoodItemDto for Nutella (live OFD)`() = runTest {
        // 3017620422003 = Nutella; confirmed present in OFD (worldwide product, always available)
        // First call proxies to OFD and caches; subsequent calls hit local catalog
        val token = login()
        val resp = client.get("$serverUrl/food/barcode?barcode=3017620422003") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, resp.status)
        val item = resp.body<FoodItemDto>()
        assertEquals("3017620422003", item.barcode)
        assertEquals("openfoodfacts", item.source)
        assertTrue(item.kcalPer100g > 0.0, "Expected positive kcal value")
    }
}
```

Note: the `barcode proxy` test hits the live OFD API on first run and caches the result. It is idempotent on subsequent runs (local DB hit). No seeding required.

- [ ] **Step 2: Create FoodRoutes.kt**

Create `server/src/main/kotlin/org/branneman/health/food/FoodRoutes.kt`:

```kotlin
package org.branneman.health.food

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.branneman.health.FoodItemDto
import org.branneman.health.data.Product
import org.jetbrains.exposed.sql.IntegerColumnType
import org.jetbrains.exposed.sql.VarCharColumnType
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

fun Route.foodRoutes(httpClient: HttpClient, importService: OfdImportService) {
    authenticate("api") {
        get("/food/search") {
            val q = call.request.queryParameters["q"]
                ?: return@get call.respond(HttpStatusCode.BadRequest)
            if (q.isBlank() || q.length > 100)
                return@get call.respond(HttpStatusCode.BadRequest)
            val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 50)

            val results = transaction {
                exec(
                    """
                    SELECT id, barcode, name, kcal_per_100g, protein_per_100g, carbs_per_100g, fat_per_100g
                    FROM catalog.product
                    WHERE to_tsvector('simple', name) @@ plainto_tsquery('simple', ?)
                    ORDER BY ts_rank(to_tsvector('simple', name), plainto_tsquery('simple', ?)) DESC
                    LIMIT ?
                    """.trimIndent(),
                    args = listOf(
                        VarCharColumnType() to q,
                        VarCharColumnType() to q,
                        IntegerColumnType() to limit,
                    )
                ) {
                    val list = mutableListOf<FoodItemDto>()
                    while (next()) {
                        list.add(FoodItemDto(
                            id             = getString("id"),
                            barcode        = getString("barcode"),
                            name           = getString("name"),
                            kcalPer100g    = getDouble("kcal_per_100g"),
                            proteinPer100g = getObject("protein_per_100g")?.let { (it as java.math.BigDecimal).toDouble() },
                            carbsPer100g   = getObject("carbs_per_100g")?.let { (it as java.math.BigDecimal).toDouble() },
                            fatPer100g     = getObject("fat_per_100g")?.let { (it as java.math.BigDecimal).toDouble() },
                            source         = "openfoodfacts",
                        ))
                    }
                    list
                } ?: emptyList()
            }
            call.respond(results)
        }

        get("/food/barcode") {
            val barcode = call.request.queryParameters["barcode"]
                ?: return@get call.respond(HttpStatusCode.BadRequest)

            // 1. Local lookup
            val local = transaction {
                Product.selectAll().where { Product.barcode eq barcode }.singleOrNull()
                    ?.let { row ->
                        FoodItemDto(
                            id             = row[Product.id].toString(),
                            barcode        = row[Product.barcode],
                            name           = row[Product.name],
                            kcalPer100g    = row[Product.kcalPer100g].toDouble(),
                            proteinPer100g = row[Product.proteinPer100g]?.toDouble(),
                            carbsPer100g   = row[Product.carbsPer100g]?.toDouble(),
                            fatPer100g     = row[Product.fatPer100g]?.toDouble(),
                            source         = "openfoodfacts",
                        )
                    }
            }
            if (local != null) return@get call.respond(local)

            // 2. Proxy to OFD
            val ofdResponse = try {
                httpClient.get("${OfdImportService.OFD_PRODUCT_URL}/$barcode.json") {
                    header(HttpHeaders.UserAgent, OfdImportService.USER_AGENT)
                }
            } catch (e: Exception) {
                return@get call.respond(HttpStatusCode.BadGateway)
            }

            if (ofdResponse.status == HttpStatusCode.NotFound)
                return@get call.respond(HttpStatusCode.NotFound)

            if (!ofdResponse.status.isSuccess())
                return@get call.respond(HttpStatusCode.BadGateway)

            val body = ofdResponse.bodyAsText()
            val root = Json.parseToJsonElement(body).jsonObject
            val status = root["status"]?.jsonPrimitive?.intOrNull
            if (status != 1) return@get call.respond(HttpStatusCode.NotFound)

            val productJson = root["product"]?.jsonObject
                ?: return@get call.respond(HttpStatusCode.NotFound)

            val row = importService.extractProduct(productJson, requireNl = false)
                ?: return@get call.respond(HttpStatusCode.NotFound)

            // 3. Cache result
            importService.upsertProduct(row)

            // 4. Return — read back the id we just wrote
            val cached = transaction {
                Product.selectAll().where { Product.barcode eq row.barcode }.single()
            }
            call.respond(FoodItemDto(
                id             = cached[Product.id].toString(),
                barcode        = cached[Product.barcode],
                name           = cached[Product.name],
                kcalPer100g    = cached[Product.kcalPer100g].toDouble(),
                proteinPer100g = cached[Product.proteinPer100g]?.toDouble(),
                carbsPer100g   = cached[Product.carbsPer100g]?.toDouble(),
                fatPer100g     = cached[Product.fatPer100g]?.toDouble(),
                source         = "openfoodfacts",
            ))
        }
    }
}
```

- [ ] **Step 3: Wire foodRoutes into Application.kt**

Inside the `routing { }` block in Application.kt, after the `ofdAdminSecret` block, add:

```kotlin
foodRoutes(ofdHttpClient, ofdImportService)
```

Add the import:
```kotlin
import org.branneman.health.food.foodRoutes
```

- [ ] **Step 4: Run server unit + integration tests**

```bash
./gradlew :server:test
```

Expected: all tests pass.

- [ ] **Step 5: Run API tests against local server**

Start the server in one terminal:
```bash
./gradlew :server:run
```

In another terminal, trigger a seed + run API tests:
```bash
API_TEST_SERVER_URL=http://localhost:8080 ./gradlew :server:apiTest --tests "org.branneman.health.FoodApiTest"
```

Expected: all 8 API tests pass. If barcode tests fail due to missing seed data, verify the E2E seed route inserted the Oatly product row.

- [ ] **Step 6: Commit**

```bash
git add server/src/main/kotlin/org/branneman/health/food/FoodRoutes.kt \
        server/src/main/kotlin/org/branneman/health/Application.kt \
        server/src/apiTest/kotlin/org/branneman/health/FoodApiTest.kt
git commit -m "feat(server): add /food/search and /food/barcode endpoints"
```

---

## Post-Implementation Checklist

- [ ] Add `OFD_ADMIN_SECRET` to `.env.example` with a placeholder value
- [ ] Add `OFD_ADMIN_SECRET` to Ansible vault for the VPS
- [ ] Update VPS backup script to pass `--exclude-schema=catalog` to pg_dump
- [ ] Add systemd timer on VPS: daily at 02:00, calls `POST /admin/ofd-import` with `X-Admin-Secret` header
- [ ] Trigger first production import: `curl -X POST https://<server>/admin/ofd-import?mode=full -H "X-Admin-Secret: $OFD_ADMIN_SECRET"` and watch server logs
