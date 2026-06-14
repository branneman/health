package org.branneman.health.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.branneman.health.db.SyncStatus
import java.util.UUID

@Entity(tableName = "meal_template")
data class MealTemplateEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val name: String,
    val sortOrder: Int? = null,
    val quickAddKcal: Int? = null,
    val syncStatus: SyncStatus = SyncStatus.PENDING_CREATE,
    val updatedAt: Long = System.currentTimeMillis(),
)
