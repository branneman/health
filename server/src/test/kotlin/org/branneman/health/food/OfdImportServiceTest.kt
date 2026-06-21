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

    @Test fun `name fallback - product_name_nl preferred over product_name`() {
        val row = service().extractProduct(
            Json.parseToJsonElement(nlProduct(nameFull = "Oatly Generic", nameNl = "Haver Melk")).jsonObject
        )
        assertEquals("Haver Melk", row?.name)
    }

    @Test fun `name fallback - product_name_en used when nl absent`() {
        val row = service().extractProduct(
            Json.parseToJsonElement(nlProduct(nameFull = "Oatly Generic", nameEn = "Oatly English")).jsonObject
        )
        assertEquals("Oatly English", row?.name)
    }

    @Test fun `name fallback - product_name used when nl and en absent`() {
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
