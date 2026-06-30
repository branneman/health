package org.branneman.health

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.test.runTest
import org.branneman.health.FoodItemRequestDto
import org.branneman.health.FoodLogItemRequestDto
import org.branneman.health.FoodLogRequestDto
import org.junit.Test
import kotlin.test.*

class FoodApiTest : ApiTestBase() {

    @Test fun `search returns 200 with list`() = runTest {
        val token = login()
        val resp = client.get("$serverUrl/food/search?q=melk") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, resp.status)
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
        // 0000000000001 has an invalid EAN-13 check digit (correct is 0), so OFD will never store it
        val resp = client.get("$serverUrl/food/barcode?barcode=0000000000001") { bearerAuth(token) }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test fun `barcode without auth returns 401`() = runTest {
        val resp = client.get("$serverUrl/food/barcode?barcode=3017620422003")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test fun `barcode proxy returns FoodItemDto for Nutella (live OFD)`() = runTest {
        // 3017620422003 = Nutella; confirmed in OFD (worldwide, always available)
        // First call proxies to OFD and caches; subsequent calls hit local catalog — idempotent
        val token = login()
        val resp = client.get("$serverUrl/food/barcode?barcode=3017620422003") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, resp.status)
        val item = resp.body<FoodItemDto>()
        assertEquals("3017620422003", item.barcode)
        assertEquals("openfoodfacts", item.source)
        assertTrue(item.kcalPer100g > 0.0, "Expected positive kcal value")
    }

    @Test fun `POST in food-items creates item and returns 201`() = runTest {
        val token  = login()
        val foodId = java.util.UUID.randomUUID()
        val resp = client.post("$serverUrl/in/food-items") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(FoodItemRequestDto(
                id             = foodId.toString(),
                barcode        = null,
                name           = "Api Test Food",
                kcalPer100g    = 200.0,
                proteinPer100g = 10.0,
                carbsPer100g   = 25.0,
                fatPer100g     = 5.0,
                source         = "manual",
            ))
        }
        assertEquals(HttpStatusCode.Created, resp.status)
        val item = resp.body<FoodItemDto>()
        assertEquals(foodId.toString(), item.id)
        assertEquals("Api Test Food", item.name)
    }

    @Test fun `POST in food-items returns 409 on duplicate id`() = runTest {
        val token  = login()
        val foodId = java.util.UUID.randomUUID()
        val dto = FoodItemRequestDto(foodId.toString(), null, "Dup Food", 100.0, null, null, null, "manual")
        client.post("$serverUrl/in/food-items") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(dto)
        }
        val r2 = client.post("$serverUrl/in/food-items") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(dto)
        }
        assertEquals(HttpStatusCode.Conflict, r2.status)
    }

    @Test fun `POST in log food creates entry with nutrition snapshot`() = runTest {
        val token  = login()
        // First create the food item
        val foodId = java.util.UUID.randomUUID()
        client.post("$serverUrl/in/food-items") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(FoodItemRequestDto(foodId.toString(), null, "Log Test Food", 150.0, 5.0, 20.0, 3.0, "manual"))
        }
        // Then log it
        val logId = java.util.UUID.randomUUID()
        val resp = client.post("$serverUrl/in/log/food") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(FoodLogRequestDto(logId.toString(), "dinner", null, listOf(FoodLogItemRequestDto(foodId.toString(), 200.0))))
        }
        assertEquals(HttpStatusCode.Created, resp.status)
        val entry = resp.body<LogEntryDto>()
        assertEquals(logId.toString(), entry.id)
        assertEquals("dinner", entry.mealType)
        assertEquals(1, entry.items.size)
        assertEquals(150.0, entry.items[0].kcalPer100g)
    }
}
