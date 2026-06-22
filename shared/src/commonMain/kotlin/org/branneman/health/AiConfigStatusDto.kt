package org.branneman.health

import kotlinx.serialization.Serializable

@Serializable
data class AiConfigStatusDto(
    val configured: Boolean,
    val expiresAt: String?
)
