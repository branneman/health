package org.branneman.health.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import org.branneman.health.BuildConfig
import org.branneman.health.DailyEnergyDto
import org.branneman.health.FoodItemDto
import org.branneman.health.LogEntryDto
import org.branneman.health.MealTemplateDto
import org.branneman.health.ShortcutDto
import org.branneman.health.TokenRequest
import org.branneman.health.TokenResponse
import org.branneman.health.UserProfileDto
import org.branneman.health.WeightEntryDto
import org.branneman.health.WorkoutDto

class HealthApiClient(
    private val baseUrl: String = BuildConfig.SERVER_BASE_URL,
    private val client: HttpClient = HttpClient(Android) {
        install(ContentNegotiation) { json() }
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

    suspend fun getLogEntries(token: String, from: String): List<LogEntryDto> =
        client.get("$baseUrl/in/log") {
            header(HttpHeaders.Authorization, "Bearer $token")
            parameter("from", from)
        }.body()
}
