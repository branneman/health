package org.branneman.health

import kotlinx.serialization.Serializable

@Serializable
data class TodaySummaryDto(
    val date: String,
    val caloriesIn: Int,
    val caloriesOut: Int,
    val budgetRemaining: Int,
    val targetDeficit: Int,
    val caloriesOutSource: String,
    val expectedTodaySport: Int? = null,
    val expectedTodayNonSport: Int? = null,
    val eatingFractionSport: Double? = null,
    val eatingFractionNonSport: Double? = null,
    val actualBurnedSoFar: Int? = null,
    val postWorkoutModeSport: Boolean = false,
    val postWorkoutModeNonSport: Boolean = false,
    val wakeTime: String = "07:00",
    val bedtime: String = "23:00",
)
