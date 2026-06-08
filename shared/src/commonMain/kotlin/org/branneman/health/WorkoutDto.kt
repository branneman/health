package org.branneman.health

import kotlinx.serialization.Serializable

@Serializable
data class WorkoutDto(
    val id: String,
    val date: String,
    val type: String,
    val durationSecs: Int?,
    val avgHr: Int?,
    val kcal: Int?,
)
