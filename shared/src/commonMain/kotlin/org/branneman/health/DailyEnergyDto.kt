package org.branneman.health

import kotlinx.serialization.Serializable

@Serializable
data class DailyEnergyDto(
    val date: String,
    val bmrKcal: Int,
    val activeKcal: Int,
    val totalKcal: Int,
    val steps: Int?,
    val source: String,
)
