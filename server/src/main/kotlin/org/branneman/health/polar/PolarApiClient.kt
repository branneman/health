package org.branneman.health.polar

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.LocalDate
import java.util.Base64
import java.util.UUID

data class PolarTokenResponse(val accessToken: String, val xUserId: Long)
data class PolarActivity(val date: LocalDate, val totalKcal: Int, val activeKcal: Int, val steps: Int?)
data class PolarExercise(
    val polarId: String, val date: LocalDate, val sport: String,
    val durationSecs: Int?, val kcal: Int?, val avgHr: Int?,
)

class PolarRateLimitException : Exception("Polar API rate limit exceeded")

interface PolarApiClient {
    fun buildAuthorizationUrl(state: String): String
    suspend fun exchangeCode(code: String): PolarTokenResponse
    suspend fun registerUser(accessToken: String, memberIdUuid: UUID)
    suspend fun getActivities(accessToken: String, from: LocalDate, to: LocalDate): List<PolarActivity>
    suspend fun getExercises(accessToken: String): List<PolarExercise>
}

@Serializable
internal data class PolarTokenJson(
    @SerialName("access_token") val accessToken: String,
    @SerialName("x_user_id")    val xUserId: Long,
)

@Serializable
internal data class PolarActivitiesJson(
    @SerialName("activities") val activities: List<PolarActivityJson> = emptyList(),
)

@Serializable
internal data class PolarActivityJson(
    @SerialName("start_time")       val startTime: String,
    @SerialName("calories")         val calories: Int,
    @SerialName("active_calories")  val activeCalories: Int,
    @SerialName("steps")            val steps: Int? = null,
)

@Serializable
internal data class PolarExercisesJson(
    @SerialName("exercises") val exercises: List<PolarExerciseJson> = emptyList(),
)

@Serializable
internal data class PolarExerciseJson(
    @SerialName("id")         val id: String,
    @SerialName("start_time") val startTime: String,
    @SerialName("sport")      val sport: String,
    @SerialName("duration")   val duration: String? = null,
    @SerialName("calories")   val calories: Int? = null,
    @SerialName("heart_rate") val heartRate: HeartRateJson? = null,
)

@Serializable
internal data class HeartRateJson(@SerialName("average") val average: Int? = null)

private val lenientJson = Json { ignoreUnknownKeys = true }

class HttpPolarApiClient(
    private val httpClient: HttpClient,
    private val clientId: String,
    private val clientSecret: String,
    private val redirectUri: String,
) : PolarApiClient {

    override fun buildAuthorizationUrl(state: String): String {
        val encodedUri = java.net.URLEncoder.encode(redirectUri, "UTF-8")
        return "https://flow.polar.com/oauth2/authorization" +
               "?response_type=code&client_id=$clientId" +
               "&redirect_uri=$encodedUri&scope=accesslink.read_all&state=$state"
    }

    override suspend fun exchangeCode(code: String): PolarTokenResponse {
        val credentials = Base64.getEncoder().encodeToString("$clientId:$clientSecret".toByteArray())
        val response = httpClient.post("https://polarremote.com/v2/oauth2/token") {
            header(HttpHeaders.Authorization, "Basic $credentials")
            header(HttpHeaders.Accept, "application/json;charset=UTF-8")
            setBody(FormDataContent(Parameters.build {
                append("grant_type", "authorization_code")
                append("code", code)
                append("redirect_uri", redirectUri)
            }))
        }
        if (response.status == HttpStatusCode.TooManyRequests) throw PolarRateLimitException()
        check(response.status.isSuccess()) { "Token exchange failed: ${response.status.value}" }
        val body = lenientJson.decodeFromString<PolarTokenJson>(response.bodyAsText())
        return PolarTokenResponse(body.accessToken, body.xUserId)
    }

    override suspend fun registerUser(accessToken: String, memberIdUuid: UUID) {
        val response = httpClient.post("https://www.polaraccesslink.com/v3/users") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody("""{"member-id":"$memberIdUuid"}""")
        }
        if (response.status != HttpStatusCode.Conflict) {
            check(response.status.isSuccess()) { "Register user failed: ${response.status.value}" }
        }
    }

    override suspend fun getActivities(accessToken: String, from: LocalDate, to: LocalDate): List<PolarActivity> {
        val response = httpClient.get("https://www.polaraccesslink.com/v3/users/activities") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            parameter("from", from.toString())
            parameter("to", to.toString())
        }
        if (response.status == HttpStatusCode.TooManyRequests) throw PolarRateLimitException()
        if (response.status == HttpStatusCode.NoContent) return emptyList()
        check(response.status.isSuccess()) { "getActivities failed: ${response.status.value}" }
        val body = lenientJson.decodeFromString<PolarActivitiesJson>(response.bodyAsText())
        return body.activities.map {
            PolarActivity(
                date       = LocalDate.parse(it.startTime.take(10)),
                totalKcal  = it.calories,
                activeKcal = it.activeCalories,
                steps      = it.steps,
            )
        }
    }

    override suspend fun getExercises(accessToken: String): List<PolarExercise> {
        val response = httpClient.get("https://www.polaraccesslink.com/v3/exercises") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
        }
        if (response.status == HttpStatusCode.TooManyRequests) throw PolarRateLimitException()
        if (response.status == HttpStatusCode.NoContent) return emptyList()
        check(response.status.isSuccess()) { "getExercises failed: ${response.status.value}" }
        val body = lenientJson.decodeFromString<PolarExercisesJson>(response.bodyAsText())
        return body.exercises.map {
            PolarExercise(
                polarId      = it.id,
                date         = LocalDate.parse(it.startTime.take(10)),
                sport        = it.sport,
                durationSecs = it.duration?.let { d -> parseIso8601DurationSecs(d) },
                kcal         = it.calories,
                avgHr        = it.heartRate?.average,
            )
        }
    }

    private fun parseIso8601DurationSecs(d: String): Int =
        runCatching { Duration.parse(d).seconds.toInt() }.getOrDefault(0)
}
