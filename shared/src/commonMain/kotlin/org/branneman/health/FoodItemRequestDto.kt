package org.branneman.health

import kotlinx.serialization.Serializable

@Serializable
data class FoodItemRequestDto(
    val id: String,
    val barcode: String?,
    val name: String,
    val kcalPer100g: Double,
    val proteinPer100g: Double?,
    val carbsPer100g: Double?,
    val fatPer100g: Double?,
    val source: String,
)
