package org.branneman.health

import kotlinx.serialization.Serializable

@Serializable
data class FoodLogItemRequestDto(val foodItemId: String, val grams: Double)

@Serializable
data class FoodLogRequestDto(
    val id: String,
    val mealType: String,
    val loggedAt: String?,
    val items: List<FoodLogItemRequestDto>,
)
