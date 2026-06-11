package org.branneman.health.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sport_tonight")
data class SportTonightEntity(
    @PrimaryKey val date: String,
    val activityType: String,
    val intensity: String,
    val estimatedKcal: Int,
)
