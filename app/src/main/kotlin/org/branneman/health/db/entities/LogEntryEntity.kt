package org.branneman.health.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.branneman.health.db.SyncStatus
import java.util.UUID

@Entity(tableName = "log_entry")
data class LogEntryEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val loggedAt: String,
    val mealType: String,
    val quickAddKcal: Int?,
    val quickAddLabel: String?,
    val sortOrder: Int = 0,
    val syncStatus: SyncStatus = SyncStatus.PENDING_CREATE,
    val createdAt: Long = System.currentTimeMillis(),
)
