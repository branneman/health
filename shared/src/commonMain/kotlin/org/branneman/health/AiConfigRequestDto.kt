package org.branneman.health

import kotlinx.serialization.Serializable

@Serializable
data class AiConfigRequestDto(
    val apiKey: String,
    val expiresAt: String? = null
)
