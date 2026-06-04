package org.branneman.health.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import org.branneman.health.BuildConfig

class HealthApiClient(
    private val baseUrl: String = BuildConfig.SERVER_BASE_URL,
    private val httpClient: HttpClient = HttpClient(Android)
) {
    suspend fun isServerReachable(): Boolean = runCatching {
        httpClient.get("$baseUrl/server-health").status.isSuccess()
    }.getOrDefault(false)
}
