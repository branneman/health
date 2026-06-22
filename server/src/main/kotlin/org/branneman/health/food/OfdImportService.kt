package org.branneman.health.food

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.serialization.json.*
import org.branneman.health.data.ImportState
import org.branneman.health.data.Product
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import javax.sql.DataSource

class OfdImportService(
    private val dataSource: DataSource,
    private val httpClient: HttpClient,
) {
    private val log = org.slf4j.LoggerFactory.getLogger(OfdImportService::class.java)

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
                val base = filename.trim().removeSuffix(".json.gz")
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

    suspend fun importDelta(): ImportResult {
        if (!importing.compareAndSet(false, true)) error("Import already in progress")
        return try {
            val indexText = httpClient.get(INDEX_URL) {
                header("User-Agent", USER_AGENT)
            }.bodyAsText()
            val allFiles  = parseDeltaIndex(indexText)

            val lastEndTs = transaction {
                ImportState.selectAll().where { ImportState.id eq true }
                    .single()[ImportState.lastDeltaEndTs]
            }

            if (lastEndTs != null) {
                val ageSeconds = java.time.Instant.now().epochSecond - lastEndTs
                if (ageSeconds > 14L * 24 * 60 * 60) {
                    log.warn("OFD delta checkpoint is older than 14 days (last_delta_end_ts=$lastEndTs). Run ?mode=full to reseed.")
                    return ImportResult(0, 0)
                }
            }

            val toProcess = allFiles
                .filter { it.startTs > (lastEndTs ?: Long.MIN_VALUE) }
                .sortedBy { it.startTs }

            var upserted = 0
            var skipped  = 0
            for (file in toProcess) {
                val deltaResponse = httpClient.get("$DELTA_BASE_URL${file.filename}") {
                    header("User-Agent", USER_AGENT)
                }
                if (!deltaResponse.status.isSuccess())
                    error("HTTP ${deltaResponse.status.value} fetching delta file ${file.filename}")
                val bytes = deltaResponse.readRawBytes()
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

    suspend fun importFull(): ImportResult {
        if (!importing.compareAndSet(false, true)) error("Import already in progress")
        return try {
            val indexText = httpClient.get(INDEX_URL) {
                header("User-Agent", USER_AGENT)
            }.bodyAsText()
            val maxEndTs = parseDeltaIndex(indexText).maxOfOrNull { it.endTs }

            var upserted = 0
            var skipped  = 0
            httpClient.prepareGet(FULL_JSONL_URL) {
                header("User-Agent", USER_AGENT)
            }.execute { response ->
                if (!response.status.isSuccess())
                    error("HTTP ${response.status.value} fetching full JSONL")
                GZIPInputStream(response.bodyAsChannel().toInputStream()).use { gz ->
                    BufferedReader(InputStreamReader(gz, Charsets.UTF_8)).use { reader ->
                        val batch = mutableListOf<ProductRow>()
                        var line = reader.readLine()
                        while (line != null) {
                            val row = try {
                                extractProduct(Json.parseToJsonElement(line).jsonObject)
                            } catch (e: Exception) {
                                log.warn("Skipping malformed JSONL line: ${e.message}")
                                null
                            }
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

    private fun processGzipBytes(bytes: ByteArray): ImportResult {
        var upserted = 0
        var skipped  = 0
        GZIPInputStream(ByteArrayInputStream(bytes)).use { gz ->
            BufferedReader(InputStreamReader(gz, Charsets.UTF_8)).use { reader ->
                val batch = mutableListOf<ProductRow>()
                var line = reader.readLine()
                while (line != null) {
                    val row = try {
                        extractProduct(Json.parseToJsonElement(line).jsonObject)
                    } catch (e: Exception) {
                        log.warn("Skipping malformed JSONL line: ${e.message}")
                        null
                    }
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
