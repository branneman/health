package org.branneman.health.db.entities

import androidx.room.Entity

@Entity(tableName = "meal_template_item", primaryKeys = ["templateId", "foodItemId"])
data class MealTemplateItemEntity(
    val templateId: String,
    val foodItemId: String,
    val grams: Double,
    val sortOrder: Int = 0,
)
