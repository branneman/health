package org.branneman.health

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.branneman.health.auth.Users
import org.branneman.health.data.FoodItem
import org.branneman.health.data.LogEntry
import org.branneman.health.data.LogEntryItem
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Before
import org.junit.Test
import org.mindrot.jbcrypt.BCrypt
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FoodCatalogIntegrationTest {

    companion object {
        private val ds = TestDatabase.dataSource
        val testUserId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000012")
        private const val TEST_EMAIL = "foodcatalog-test@test.local"
        private const val TEST_PASSWORD = "testpassword"
        private val TEST_HASH = BCrypt.hashpw(TEST_PASSWORD, BCrypt.gensalt(4))

        init {
            Database.connect(ds)
            transaction {
                Users.deleteWhere { username eq TEST_EMAIL }
                Users.deleteWhere { id eq testUserId }
                Users.insert {
                    it[id]           = testUserId
                    it[username]     = TEST_EMAIL
                    it[passwordHash] = TEST_HASH
                }
            }
        }
    }

    @Before fun cleanMutableRows() {
        transaction {
            val logEntryIds = LogEntry.select(LogEntry.id)
                .where { LogEntry.userId eq testUserId }
                .map { it[LogEntry.id] }
            if (logEntryIds.isNotEmpty()) {
                LogEntryItem.deleteWhere { Op.build { logEntryId inList logEntryIds } }
            }
            LogEntry.deleteWhere { userId eq testUserId }
            FoodItem.deleteWhere { userId eq testUserId }
        }
    }

    private fun appTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(ds) }
        block()
    }

    private suspend fun ApplicationTestBuilder.login(): String {
        val r = client.post("/auth/token") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$TEST_EMAIL","password":"$TEST_PASSWORD"}""")
        }
        return Json.parseToJsonElement(r.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content
    }

    // ─── GET /in/food-items?q= ────────────────────────────────────────────────

    @Test fun `GET food-items with q returns matching items`() = appTest {
        val token = login()
        val foodId = UUID.randomUUID()
        transaction {
            FoodItem.insert {
                it[FoodItem.id]          = foodId
                it[FoodItem.userId]      = testUserId
                it[FoodItem.barcode]     = null
                it[FoodItem.name]        = "Peanut Butter"
                it[FoodItem.kcalPer100g] = 589.0.toBigDecimal()
                it[FoodItem.dataSource]  = "manual"
            }
        }
        val r = client.get("/in/food-items?q=peanut") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val arr = Json.parseToJsonElement(r.bodyAsText()).jsonArray
        assertEquals(1, arr.size)
        assertEquals("Peanut Butter", arr[0].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test fun `GET food-items with q returns empty list when no match`() = appTest {
        val token = login()
        val r = client.get("/in/food-items?q=xyz_nonexistent_food") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(Json.parseToJsonElement(r.bodyAsText()).jsonArray.isEmpty())
    }

    // ─── GET /in/food-items?barcode= ─────────────────────────────────────────

    @Test fun `GET food-items with barcode returns matching item`() = appTest {
        val token = login()
        val foodId = UUID.randomUUID()
        transaction {
            FoodItem.insert {
                it[FoodItem.id]          = foodId
                it[FoodItem.userId]      = testUserId
                it[FoodItem.barcode]     = "1234567890"
                it[FoodItem.name]        = "Scanned Product"
                it[FoodItem.kcalPer100g] = 150.0.toBigDecimal()
                it[FoodItem.dataSource]  = "openfoodfacts"
            }
        }
        val r = client.get("/in/food-items?barcode=1234567890") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val arr = Json.parseToJsonElement(r.bodyAsText()).jsonArray
        assertEquals(1, arr.size)
        assertEquals("1234567890", arr[0].jsonObject["barcode"]!!.jsonPrimitive.content)
    }

    @Test fun `GET food-items with barcode returns empty list when no match`() = appTest {
        val token = login()
        val r = client.get("/in/food-items?barcode=0000000000") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(Json.parseToJsonElement(r.bodyAsText()).jsonArray.isEmpty())
    }

    // ─── POST /in/food-items ─────────────────────────────────────────────────

    @Test fun `POST food-items creates item and returns 201`() = appTest {
        val token  = login()
        val foodId = UUID.randomUUID()
        val r = client.post("/in/food-items") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"id":"$foodId","barcode":null,"name":"Oatmeal","kcalPer100g":68.0,"proteinPer100g":2.4,"carbsPer100g":12.0,"fatPer100g":1.4,"source":"manual"}""")
        }
        assertEquals(HttpStatusCode.Created, r.status)
        val json = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals(foodId.toString(), json["id"]!!.jsonPrimitive.content)
        assertEquals("Oatmeal", json["name"]!!.jsonPrimitive.content)
    }

    @Test fun `POST food-items returns 409 on duplicate id`() = appTest {
        val token  = login()
        val foodId = UUID.randomUUID()
        val body = """{"id":"$foodId","barcode":null,"name":"Oatmeal","kcalPer100g":68.0,"proteinPer100g":null,"carbsPer100g":null,"fatPer100g":null,"source":"manual"}"""
        client.post("/in/food-items") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val r2 = client.post("/in/food-items") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.Conflict, r2.status)
    }

    // ─── POST /in/log/food ────────────────────────────────────────────────────

    @Test fun `POST log food snapshots nutrition and returns 201`() = appTest {
        val token  = login()
        val foodId = UUID.randomUUID()
        transaction {
            FoodItem.insert {
                it[FoodItem.id]             = foodId
                it[FoodItem.userId]         = testUserId
                it[FoodItem.barcode]        = null
                it[FoodItem.name]           = "Chicken Breast"
                it[FoodItem.kcalPer100g]    = 165.0.toBigDecimal()
                it[FoodItem.proteinPer100g] = 31.0.toBigDecimal()
                it[FoodItem.carbsPer100g]   = 0.0.toBigDecimal()
                it[FoodItem.fatPer100g]     = 3.6.toBigDecimal()
                it[FoodItem.dataSource]     = "manual"
            }
        }
        val logId = UUID.randomUUID()
        val r = client.post("/in/log/food") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"id":"$logId","mealType":"lunch","loggedAt":null,"items":[{"foodItemId":"$foodId","grams":200.0}]}""")
        }
        assertEquals(HttpStatusCode.Created, r.status)
        val json = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals(logId.toString(), json["id"]!!.jsonPrimitive.content)
        assertEquals("lunch", json["mealType"]!!.jsonPrimitive.content)
        val items = json["items"]!!.jsonArray
        assertEquals(1, items.size)
        assertEquals(165.0, items[0].jsonObject["kcalPer100g"]!!.jsonPrimitive.content.toDouble())
    }

    @Test fun `POST log food returns 409 on duplicate log id`() = appTest {
        val token  = login()
        val foodId = UUID.randomUUID()
        transaction {
            FoodItem.insert {
                it[FoodItem.id]          = foodId
                it[FoodItem.userId]      = testUserId
                it[FoodItem.name]        = "Rice"
                it[FoodItem.kcalPer100g] = 130.0.toBigDecimal()
                it[FoodItem.dataSource]  = "manual"
            }
        }
        val logId = UUID.randomUUID()
        val body = """{"id":"$logId","mealType":"dinner","loggedAt":null,"items":[{"foodItemId":"$foodId","grams":150.0}]}"""
        client.post("/in/log/food") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val r2 = client.post("/in/log/food") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.Conflict, r2.status)
    }

    @Test fun `POST log food returns 404 when food item unknown`() = appTest {
        val token = login()
        val logId = UUID.randomUUID()
        val r = client.post("/in/log/food") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"id":"$logId","mealType":"breakfast","loggedAt":null,"items":[{"foodItemId":"${UUID.randomUUID()}","grams":100.0}]}""")
        }
        assertEquals(HttpStatusCode.NotFound, r.status)
    }

    @Test fun `POST log food returns 401 without token`() = appTest {
        val r = client.post("/in/log/food") {
            contentType(ContentType.Application.Json)
            setBody("""{"id":"${UUID.randomUUID()}","mealType":"lunch","loggedAt":null,"items":[]}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }
}
