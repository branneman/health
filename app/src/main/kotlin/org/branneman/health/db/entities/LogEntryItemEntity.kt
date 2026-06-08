package org.branneman.health.db.entities

import androidx.room.Entity

@Entity(tableName = "log_entry_item", primaryKeys = ["logEntryId", "foodItemId"])
data class LogEntryItemEntity(
    val logEntryId: String,
    val foodItemId: String,
    val grams: Double,
    val kcalPer100g: Double,
    val proteinPer100g: Double?,
    val carbsPer100g: Double?,
    val fatPer100g: Double?,
)
