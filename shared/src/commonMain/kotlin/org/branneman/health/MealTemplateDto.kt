package org.branneman.health

import kotlinx.serialization.Serializable

@Serializable
data class MealTemplateItemDto(
    val foodItemId: String,
    val grams: Double,
)

@Serializable
data class MealTemplateDto(
    val id: String,
    val name: String,
    val items: List<MealTemplateItemDto>,
)
