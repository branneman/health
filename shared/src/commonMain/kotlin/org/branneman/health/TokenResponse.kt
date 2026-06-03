package org.branneman.health

import kotlinx.serialization.Serializable

@Serializable
data class TokenResponse(
    val token: String,
    val expiresAt: String,
)
