package org.branneman.health

import kotlinx.serialization.Serializable

@Serializable
data class TokenRequest(
    val username: String,
    val password: String,
)
