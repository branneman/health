package org.branneman.health

import kotlinx.serialization.Serializable

@Serializable
data class MealTemplateItemDto(
    val foodItemId: String,
    val grams: Double,
    val sortOrder: Int = 0,
)

@Serializable
data class MealTemplateDto(
    val id: String,
    val name: String,
    val sortOrder: Int?,
    val quickAddKcal: Int?,
    val items: List<MealTemplateItemDto>,
)
