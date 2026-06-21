package org.branneman.health.food

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import org.branneman.health.FoodItemDto
import org.branneman.health.data.Product
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

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
                ) { rs ->
                    val list = mutableListOf<FoodItemDto>()
                    while (rs.next()) {
                        list.add(FoodItemDto(
                            id             = rs.getString("id"),
                            barcode        = rs.getString("barcode"),
                            name           = rs.getString("name"),
                            kcalPer100g    = rs.getDouble("kcal_per_100g"),
                            proteinPer100g = rs.getObject("protein_per_100g")?.let { (it as java.math.BigDecimal).toDouble() },
                            carbsPer100g   = rs.getObject("carbs_per_100g")?.let { (it as java.math.BigDecimal).toDouble() },
                            fatPer100g     = rs.getObject("fat_per_100g")?.let { (it as java.math.BigDecimal).toDouble() },
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

            // 3. Cache and return
            importService.upsertProduct(row)

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
