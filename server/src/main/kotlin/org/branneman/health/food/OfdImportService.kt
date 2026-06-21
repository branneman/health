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
        const val USER_AGENT     = "HealthApp/1.0 (personal project; bran.van.der.meer@protonmail.com)"
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
