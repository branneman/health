package org.branneman.health

import kotlinx.serialization.Serializable

@Serializable
data class LogEntryItemDto(
    val foodItemId: String,
    val grams: Double,
    val kcalPer100g: Double,
    val proteinPer100g: Double?,
    val carbsPer100g: Double?,
    val fatPer100g: Double?,
)

@Serializable
data class LogEntryDto(
    val id: String,
    val loggedAt: String,
    val mealType: String,
    val quickAddKcal: Int?,
    val quickAddLabel: String?,
    val sortOrder: Int = 0,
    val items: List<LogEntryItemDto>,
)
