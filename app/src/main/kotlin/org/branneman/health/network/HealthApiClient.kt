package org.branneman.health.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import org.branneman.health.AiConfigRequestDto
import org.branneman.health.AiConfigStatusDto
import org.branneman.health.AiEstimateResponseDto
import org.branneman.health.BuildConfig
import org.branneman.health.QuickAddRequestDto
import org.branneman.health.DailyEnergyDto
import org.branneman.health.TodaySummaryDto
import org.branneman.health.FoodItemDto
import org.branneman.health.LogEntryDto
import org.branneman.health.MealTemplateDto
import org.branneman.health.ShortcutDto
import org.branneman.health.TokenRequest
import org.branneman.health.TokenResponse
import org.branneman.health.UserProfileDto
import org.branneman.health.WeightEntryDto
import org.branneman.health.WorkoutDto
import org.branneman.health.PolarStatusDto

@kotlinx.serialization.Serializable
private data class PolarConnectUrlResponse(val url: String)

sealed interface AiEstimateApiResult {
    data class Success(val dto: AiEstimateResponseDto) : AiEstimateApiResult
    data object NotConfigured : AiEstimateApiResult
    data object KeyExpired : AiEstimateApiResult
    data object EstimateFailed : AiEstimateApiResult
    data object NetworkError : AiEstimateApiResult
}

class HealthApiClient(
    private val baseUrl: String = BuildConfig.SERVER_BASE_URL,
    private val client: HttpClient = HttpClient(Android) {
        install(ContentNegotiation) { json() }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 15_000
        }
    },
) {
    suspend fun isServerReachable(): Boolean = runCatching {
        client.get("$baseUrl/server-health").status.isSuccess()
    }.getOrDefault(false)

    suspend fun login(username: String, password: String): TokenResponse =
        client.post("$baseUrl/auth/token") {
            contentType(ContentType.Application.Json)
            setBody(TokenRequest(username, password))
        }.body()

    suspend fun refresh(token: String): TokenResponse =
        client.post("$baseUrl/auth/refresh") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.body()

    suspend fun logout(token: String) {
        client.post("$baseUrl/auth/logout") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
    }

    suspend fun getProfile(token: String): UserProfileDto? {
        val response = client.get("$baseUrl/profile") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        return if (response.status == HttpStatusCode.NotFound) null else response.body()
    }

    suspend fun putProfile(token: String, profile: UserProfileDto) {
        val response = client.put("$baseUrl/profile") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(profile)
        }
        check(response.status.isSuccess()) { "PUT /profile failed: ${response.status}" }
    }

    suspend fun getShortcuts(token: String): List<ShortcutDto> =
        client.get("$baseUrl/shortcuts") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.body()

    suspend fun putShortcuts(token: String, shortcuts: List<ShortcutDto>) {
        client.put("$baseUrl/shortcuts") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(shortcuts)
        }
    }

    suspend fun getBodyWeight(token: String): List<WeightEntryDto> =
        client.get("$baseUrl/body/weight") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.body()

    suspend fun postBodyWeight(token: String, dto: WeightEntryDto) {
        val response = client.post("$baseUrl/body/weight") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(dto)
        }
        if (!response.status.isSuccess() && response.status != HttpStatusCode.Conflict) {
            throw Exception("POST /body/weight failed: ${response.status}")
        }
    }

    suspend fun getDailyEnergy(token: String, from: String): List<DailyEnergyDto> =
        client.get("$baseUrl/out/energy") {
            header(HttpHeaders.Authorization, "Bearer $token")
            parameter("from", from)
        }.body()

    suspend fun getWorkouts(token: String, from: String): List<WorkoutDto> =
        client.get("$baseUrl/out/workouts") {
            header(HttpHeaders.Authorization, "Bearer $token")
            parameter("from", from)
        }.body()

    suspend fun getFoodItems(token: String): List<FoodItemDto> =
        client.get("$baseUrl/in/food-items") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.body()

    suspend fun getTemplates(token: String): List<MealTemplateDto> =
        client.get("$baseUrl/in/templates") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.body()

    suspend fun putTemplates(token: String, templates: List<MealTemplateDto>): List<MealTemplateDto> =
        client.put("$baseUrl/in/templates") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(templates)
        }.body()

    suspend fun getLogEntries(token: String, from: String): List<LogEntryDto> =
        client.get("$baseUrl/in/log") {
            header(HttpHeaders.Authorization, "Bearer $token")
            parameter("from", from)
        }.body()

    suspend fun postQuickAdd(token: String, dto: QuickAddRequestDto): LogEntryDto? {
        val response = client.post("$baseUrl/in/log/quick-add") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(dto)
        }
        if (response.status == HttpStatusCode.Conflict) return null
        return response.body()
    }

    suspend fun deleteLogEntry(token: String, id: String) {
        val response = client.delete("$baseUrl/in/log/$id") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        if (!response.status.isSuccess() && response.status != HttpStatusCode.NotFound) {
            throw Exception("DELETE /in/log/$id failed: ${response.status}")
        }
    }

    suspend fun getTodaySummary(token: String, date: String): TodaySummaryDto =
        client.get("$baseUrl/summary/today") {
            header(HttpHeaders.Authorization, "Bearer $token")
            parameter("date", date)
        }.body()

    suspend fun getPolarConnectUrl(token: String): String {
        val response = client.get("$baseUrl/polar/connect-url") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        check(response.status.isSuccess()) { "GET /polar/connect-url failed: ${response.status}" }
        return response.body<PolarConnectUrlResponse>().url
    }

    suspend fun getPolarStatus(token: String): PolarStatusDto =
        client.get("$baseUrl/polar/status") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.body()

    suspend fun triggerPolarSync(token: String) {
        client.post("$baseUrl/polar/sync") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
    }

    suspend fun getAiConfig(token: String): AiConfigStatusDto =
        client.get("$baseUrl/ai/config") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.body()

    suspend fun putAiConfig(token: String, dto: AiConfigRequestDto): AiConfigStatusDto =
        client.put("$baseUrl/ai/config") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(dto)
        }.body()

    suspend fun deleteAiConfig(token: String) {
        client.delete("$baseUrl/ai/config") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
    }

    suspend fun postAiEstimate(
        token: String,
        text: String?,
        imageBytes: ByteArray?,
    ): AiEstimateApiResult {
        return try {
            val response = client.post("$baseUrl/ai/estimate") {
                header(HttpHeaders.Authorization, "Bearer $token")
                setBody(MultiPartFormDataContent(
                    formData {
                        text?.let { append("text", it) }
                        imageBytes?.let { bytes ->
                            append("image", bytes, Headers.build {
                                append(HttpHeaders.ContentType, "image/jpeg")
                                append(HttpHeaders.ContentDisposition, "filename=\"photo.jpg\"")
                            })
                        }
                    }
                ))
            }
            when (response.status) {
                HttpStatusCode.OK -> AiEstimateApiResult.Success(response.body())
                HttpStatusCode.UnprocessableEntity -> {
                    val errorBody = runCatching { response.body<Map<String, String>>() }.getOrNull()
                    when (errorBody?.get("error")) {
                        "ai_not_configured" -> AiEstimateApiResult.NotConfigured
                        "ai_key_expired"    -> AiEstimateApiResult.KeyExpired
                        else                -> AiEstimateApiResult.EstimateFailed
                    }
                }
                else -> AiEstimateApiResult.EstimateFailed
            }
        } catch (e: Exception) {
            AiEstimateApiResult.NetworkError
        }
    }
}
