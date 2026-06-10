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
)
