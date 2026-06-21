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
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
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
