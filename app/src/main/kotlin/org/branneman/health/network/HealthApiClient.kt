package org.branneman.health.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import org.branneman.health.BuildConfig
import org.branneman.health.TokenRequest
import org.branneman.health.TokenResponse

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
}
