package org.branneman.health.ai

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.branneman.health.AiConfigRequestDto
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.util.Base64
import java.util.UUID

private val log = LoggerFactory.getLogger("org.branneman.health.ai.AiRoutes")

fun Route.aiRoutes(configService: AiConfigService, estimateService: AiEstimateService) {
    authenticate("api") {
        route("/ai") {
            get("/config") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                call.respond(configService.getStatus(userId))
            }

            put("/config") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val dto = call.receive<AiConfigRequestDto>()
                if (dto.apiKey.isBlank() || dto.apiKey.length > 300) {
                    return@put call.respond(HttpStatusCode.BadRequest)
                }
                val expiresAt = dto.expiresAt?.let {
                    runCatching { LocalDate.parse(it) }.getOrNull()
                        ?: return@put call.respond(HttpStatusCode.BadRequest)
                }
                val status = configService.upsert(userId, dto.apiKey, expiresAt)
                call.respond(status)
            }

            delete("/config") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                configService.delete(userId)
                call.respond(HttpStatusCode.NoContent)
            }

            post("/estimate") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)

                var textPart: String? = null
                var imageBytes: ByteArray? = null
                var imageMimeType: String? = null

                val contentType = call.request.contentType()
                if (!contentType.match(ContentType.MultiPart.FormData)) {
                    return@post call.respond(HttpStatusCode.BadRequest)
                }

                val multipart = call.receiveMultipart()
                multipart.forEachPart { part ->
                    when {
                        part is PartData.FormItem && part.name == "text" -> {
                            textPart = part.value
                        }
                        part is PartData.FileItem && part.name == "image" -> {
                            imageMimeType = part.contentType?.toString()
                            @Suppress("DEPRECATION")
                            imageBytes = part.streamProvider().readBytes()
                        }
                    }
                    part.dispose()
                }

                if (textPart == null && imageBytes == null) {
                    return@post call.respond(HttpStatusCode.BadRequest)
                }
                if (textPart != null && textPart.length > 500) {
                    return@post call.respond(HttpStatusCode.BadRequest)
                }
                if (imageBytes != null && imageMimeType !in listOf("image/jpeg", "image/png")) {
                    return@post call.respond(HttpStatusCode.BadRequest)
                }

                when (val keyResult = configService.getDecryptedKey(userId)) {
                    is AiKeyResult.NotConfigured ->
                        return@post call.respond(
                            HttpStatusCode.UnprocessableEntity,
                            mapOf("error" to "ai_not_configured"),
                        )
                    is AiKeyResult.Expired ->
                        return@post call.respond(
                            HttpStatusCode.UnprocessableEntity,
                            mapOf("error" to "ai_key_expired"),
                        )
                    is AiKeyResult.Available -> {
                        val imageBase64 = imageBytes?.let { Base64.getEncoder().encodeToString(it) }
                        val response = try {
                            estimateService.estimate(
                                apiKey        = keyResult.apiKey,
                                text          = textPart,
                                imageBase64   = imageBase64,
                                imageMimeType = imageMimeType,
                            )
                        } catch (e: ClaudeEstimateException) {
                            log.warn("AI estimate failed for userId=$userId: ${e.message}")
                            return@post call.respond(
                                HttpStatusCode.UnprocessableEntity,
                                mapOf("error" to "ai_estimate_failed"),
                            )
                        }
                        call.respond(response)
                    }
                }
            }
        }
    }
}
