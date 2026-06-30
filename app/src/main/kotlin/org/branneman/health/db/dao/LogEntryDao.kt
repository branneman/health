package org.branneman.health.db.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.branneman.health.db.SyncStatus
import org.branneman.health.db.entities.LogEntryEntity
import org.branneman.health.db.entities.LogEntryItemEntity

data class LogEntryWithKcal(
    @Embedded val entry: LogEntryEntity,
    val itemKcal: Int,
) {
    val totalKcal: Int get() = (entry.quickAddKcal ?: 0) + itemKcal
    val displayLabel: String get() = entry.quickAddLabel
        ?: entry.mealType.replaceFirstChar { it.uppercase() }
}

@Dao
interface LogEntryDao {
    @Query("SELECT * FROM log_entry WHERE syncStatus != 'PENDING_DELETE' ORDER BY loggedAt DESC")
    fun observeAll(): Flow<List<LogEntryEntity>>

    @Query("""
        SELECT le.*, COALESCE((
            SELECT CAST(SUM(lei.grams * lei.kcalPer100g / 100.0) AS INTEGER)
            FROM log_entry_item lei WHERE lei.logEntryId = le.id
        ), 0) AS itemKcal
        FROM log_entry le
        WHERE le.syncStatus != 'PENDING_DELETE'
        ORDER BY le.loggedAt DESC
    """)
    fun observeAllWithKcal(): Flow<List<LogEntryWithKcal>>

    @Query("""
        SELECT le.*, COALESCE((
            SELECT CAST(SUM(lei.grams * lei.kcalPer100g / 100.0) AS INTEGER)
            FROM log_entry_item lei WHERE lei.logEntryId = le.id
        ), 0) AS itemKcal
        FROM log_entry le
        WHERE le.userId = :userId AND le.loggedAt LIKE :datePrefix AND le.syncStatus != 'PENDING_DELETE'
        ORDER BY le.loggedAt ASC
    """)
    fun observeForDate(userId: String, datePrefix: String): Flow<List<LogEntryWithKcal>>

    @Query("SELECT COALESCE(SUM(quickAddKcal), 0) FROM log_entry WHERE userId = :userId AND loggedAt LIKE :datePattern AND syncStatus != 'PENDING_DELETE'")
    suspend fun sumQuickAddKcalForDate(userId: String, datePattern: String): Int

    @Query("""
        SELECT COALESCE(CAST(SUM(lei.grams * lei.kcalPer100g / 100.0) AS INTEGER), 0)
        FROM log_entry_item lei
        INNER JOIN log_entry le ON lei.logEntryId = le.id
        WHERE le.userId = :userId AND le.loggedAt LIKE :datePattern AND le.syncStatus != 'PENDING_DELETE'
    """)
    suspend fun sumItemKcalForDate(userId: String, datePattern: String): Int

    @Query("""
        SELECT COALESCE(SUM(le.quickAddKcal), 0) + COALESCE((
            SELECT CAST(SUM(lei.grams * lei.kcalPer100g / 100.0) AS INTEGER)
            FROM log_entry_item lei
            INNER JOIN log_entry le2 ON lei.logEntryId = le2.id
            WHERE le2.userId = :userId AND le2.loggedAt LIKE :datePattern
              AND le2.syncStatus != 'PENDING_DELETE'
        ), 0)
        FROM log_entry le
        WHERE le.userId = :userId AND le.loggedAt LIKE :datePattern AND le.syncStatus != 'PENDING_DELETE'
    """)
    fun observeTotalKcalForDate(userId: String, datePattern: String): Flow<Int>

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

    @Query("""
        UPDATE log_entry
        SET quickAddKcal = :kcal, quickAddLabel = :label, syncStatus = 'PENDING_UPDATE'
        WHERE id = :id
    """)
    suspend fun updateQuickAdd(id: String, kcal: Int, label: String?)

    @Query("DELETE FROM log_entry WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM log_entry WHERE userId = :userId")
    suspend fun deleteAllForUser(userId: String)

    @Query("DELETE FROM log_entry_item WHERE logEntryId IN (SELECT id FROM log_entry WHERE userId = :userId)")
    suspend fun deleteAllItemsForUser(userId: String)
}
