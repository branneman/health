package org.branneman.health

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
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
        // 0000000000000 is synthetic and not in OFD
        val resp = client.get("$serverUrl/food/barcode?barcode=0000000000000") { bearerAuth(token) }
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
}
