package org.branneman.health.db.entities

import androidx.room.Entity

@Entity(tableName = "daily_energy", primaryKeys = ["userId", "date"])
data class DailyEnergyEntity(
    val userId: String,
    val date: String,
    val bmrKcal: Int,
    val activeKcal: Int,
    val totalKcal: Int,
    val steps: Int?,
    val source: String,
)
