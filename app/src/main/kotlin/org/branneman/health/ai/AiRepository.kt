package org.branneman.health.ai

import org.branneman.health.AiConfigRequestDto
import org.branneman.health.AiConfigStatusDto
import org.branneman.health.network.AiEstimateApiResult
import org.branneman.health.network.HealthApiClient

interface AiRepository {
    suspend fun getStatus(): AiConfigStatusDto?
    suspend fun saveKey(apiKey: String, expiresAt: String?): AiConfigStatusDto?
    suspend fun removeKey()
    suspend fun estimate(text: String?, imageBytes: ByteArray?): AiEstimateApiResult
}

class NetworkAiRepository(
    private val client: HealthApiClient,
    private val getToken: suspend () -> String?,
) : AiRepository {

    override suspend fun getStatus(): AiConfigStatusDto? {
        val token = getToken() ?: return null
        return runCatching { client.getAiConfig(token) }.getOrNull()
    }

    override suspend fun saveKey(apiKey: String, expiresAt: String?): AiConfigStatusDto? {
        val token = getToken() ?: return null
        return runCatching {
            client.putAiConfig(token, AiConfigRequestDto(apiKey, expiresAt))
        }.getOrNull()
    }

    override suspend fun removeKey() {
        val token = getToken() ?: return
        runCatching { client.deleteAiConfig(token) }
    }

    override suspend fun estimate(text: String?, imageBytes: ByteArray?): AiEstimateApiResult {
        val token = getToken() ?: return AiEstimateApiResult.NetworkError
        return client.postAiEstimate(token, text, imageBytes)
    }
}
