package org.branneman.health.db.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.branneman.health.db.SyncStatus
import org.branneman.health.db.entities.ShortcutEntity

@Dao
interface ShortcutDao {
    @Query("SELECT * FROM shortcut ORDER BY sortOrder ASC")
    fun observeAll(): Flow<List<ShortcutEntity>>

    @Upsert
    suspend fun upsertAll(entities: List<ShortcutEntity>)

    @Query("DELETE FROM shortcut WHERE userId = :userId")
    suspend fun deleteAllForUser(userId: String)

    @Query("SELECT * FROM shortcut WHERE syncStatus = :status")
    suspend fun getByStatus(status: SyncStatus): List<ShortcutEntity>

    @Query("UPDATE shortcut SET syncStatus = :status WHERE id = :id")
    suspend fun updateSyncStatus(id: String, status: SyncStatus)
}
