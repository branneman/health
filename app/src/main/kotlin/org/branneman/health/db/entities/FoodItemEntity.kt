package org.branneman.health.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.branneman.health.db.SyncStatus

@Entity(tableName = "food_item")
data class FoodItemEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val barcode: String?,
    val name: String,
    val kcalPer100g: Double,
    val proteinPer100g: Double?,
    val carbsPer100g: Double?,
    val fatPer100g: Double?,
    val source: String,
    val syncStatus: SyncStatus = SyncStatus.SYNCED,
)
