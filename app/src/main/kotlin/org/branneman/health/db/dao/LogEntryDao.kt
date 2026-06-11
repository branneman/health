package org.branneman.health.db.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.branneman.health.db.SyncStatus
import org.branneman.health.db.entities.LogEntryEntity
import org.branneman.health.db.entities.LogEntryItemEntity

@Dao
interface LogEntryDao {
    @Query("SELECT * FROM log_entry WHERE syncStatus != 'PENDING_DELETE' ORDER BY loggedAt DESC")
    fun observeAll(): Flow<List<LogEntryEntity>>

    @Query("SELECT COALESCE(SUM(quickAddKcal), 0) FROM log_entry WHERE userId = :userId AND loggedAt LIKE :datePattern")
    suspend fun sumQuickAddKcalForDate(userId: String, datePattern: String): Int

    @Query("SELECT * FROM log_entry WHERE syncStatus = :status")
    suspend fun getByStatus(status: SyncStatus): List<LogEntryEntity>

    @Query("SELECT * FROM log_entry_item WHERE logEntryId = :entryId")
    suspend fun getItemsForEntry(entryId: String): List<LogEntryItemEntity>

    @Upsert
    suspend fun upsert(entity: LogEntryEntity)

    @Upsert
    suspend fun upsertItem(item: LogEntryItemEntity)

    @Upsert
    suspend fun upsertAll(entries: List<LogEntryEntity>)

    @Upsert
    suspend fun upsertAllItems(items: List<LogEntryItemEntity>)

    @Query("UPDATE log_entry SET syncStatus = :status WHERE id = :id")
    suspend fun updateSyncStatus(id: String, status: SyncStatus)

    @Query("DELETE FROM log_entry WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM log_entry WHERE userId = :userId")
    suspend fun deleteAllForUser(userId: String)

    @Query("DELETE FROM log_entry_item WHERE logEntryId IN (SELECT id FROM log_entry WHERE userId = :userId)")
    suspend fun deleteAllItemsForUser(userId: String)
}
