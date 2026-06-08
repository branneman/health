package org.branneman.health.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.branneman.health.db.SyncStatus

@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey val userId: String,
    val heightCm: Int,
    val birthYear: Int,
    val sex: String,
    val goalWeightKg: Double,
    val activityLevel: String,
    val targetDeficit: Int,
    val phase: String,
    val vacationMode: Boolean,
    val syncStatus: SyncStatus = SyncStatus.SYNCED,
)
