package org.branneman.health.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "workout")
data class WorkoutEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val date: String,
    val type: String,
    val durationSecs: Int?,
    val avgHr: Int?,
    val kcal: Int?,
)
