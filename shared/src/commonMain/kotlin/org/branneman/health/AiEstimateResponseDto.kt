package org.branneman.health

import kotlinx.serialization.Serializable

@Serializable
data class AiEstimateResponseDto(
    val kcal: Int,
    val explanation: String? = null,
)
