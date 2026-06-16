package org.branneman.health.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "dynamic_budget_params")
data class DynamicBudgetParamsEntity(
    @PrimaryKey val date: String,
    val expectedTodaySport: Int?,
    val expectedTodayNonSport: Int?,
    val eatingFractionSport: Double?,
    val eatingFractionNonSport: Double?,
    val postWorkoutModeSport: Boolean,
    val postWorkoutModeNonSport: Boolean,
)
