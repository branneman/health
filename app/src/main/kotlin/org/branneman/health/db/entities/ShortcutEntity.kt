package org.branneman.health.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.branneman.health.db.SyncStatus

@Entity(tableName = "shortcut")
data class ShortcutEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val emoji: String,
    val label: String,
    val kcal: Int,
    val sortOrder: Int,
    val syncStatus: SyncStatus = SyncStatus.SYNCED,
)
