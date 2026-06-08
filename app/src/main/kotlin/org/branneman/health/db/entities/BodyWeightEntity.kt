package org.branneman.health.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.branneman.health.db.SyncStatus
import java.util.UUID

@Entity(tableName = "body_weight")
data class BodyWeightEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val date: String,
    val kg: Double,
    val syncStatus: SyncStatus = SyncStatus.PENDING_CREATE,
    val createdAt: Long = System.currentTimeMillis(),
)
