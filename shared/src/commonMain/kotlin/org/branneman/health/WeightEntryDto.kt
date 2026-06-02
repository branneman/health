package org.branneman.health

import kotlinx.serialization.Serializable

@Serializable
data class WeightEntryDto(
    val date: String,
    val kg: Double,
)
