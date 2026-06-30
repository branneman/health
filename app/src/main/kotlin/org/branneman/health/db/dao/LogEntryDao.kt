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
abstract class LogEntryDao {
    @Query("SELECT * FROM log_entry WHERE syncStatus != 'PENDING_DELETE' ORDER BY loggedAt DESC")
    abstract fun observeAll(): Flow<List<LogEntryEntity>>

    @Query("""
        SELECT le.*, COALESCE((
            SELECT CAST(SUM(lei.grams * lei.kcalPer100g / 100.0) AS INTEGER)
            FROM log_entry_item lei WHERE lei.logEntryId = le.id
        ), 0) AS itemKcal
        FROM log_entry le
        WHERE le.syncStatus != 'PENDING_DELETE'
        ORDER BY le.loggedAt DESC
    """)
    abstract fun observeAllWithKcal(): Flow<List<LogEntryWithKcal>>

    @Query("""
        SELECT le.*, COALESCE((
            SELECT CAST(SUM(lei.grams * lei.kcalPer100g / 100.0) AS INTEGER)
            FROM log_entry_item lei WHERE lei.logEntryId = le.id
        ), 0) AS itemKcal
        FROM log_entry le
        WHERE le.userId = :userId AND le.loggedAt LIKE :datePrefix AND le.syncStatus != 'PENDING_DELETE'
        ORDER BY le.sortOrder ASC
    """)
    abstract fun observeForDate(userId: String, datePrefix: String): Flow<List<LogEntryWithKcal>>

    @Query("SELECT COALESCE(SUM(quickAddKcal), 0) FROM log_entry WHERE userId = :userId AND loggedAt LIKE :datePattern AND syncStatus != 'PENDING_DELETE'")
    abstract suspend fun sumQuickAddKcalForDate(userId: String, datePattern: String): Int

    @Query("""
        SELECT COALESCE(CAST(SUM(lei.grams * lei.kcalPer100g / 100.0) AS INTEGER), 0)
        FROM log_entry_item lei
        INNER JOIN log_entry le ON lei.logEntryId = le.id
        WHERE le.userId = :userId AND le.loggedAt LIKE :datePattern AND le.syncStatus != 'PENDING_DELETE'
    """)
    abstract suspend fun sumItemKcalForDate(userId: String, datePattern: String): Int

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
    abstract fun observeTotalKcalForDate(userId: String, datePattern: String): Flow<Int>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM log_entry WHERE userId = :userId AND loggedAt LIKE :datePrefix AND syncStatus != 'PENDING_DELETE'")
    abstract suspend fun maxSortOrderForDate(userId: String, datePrefix: String): Int

    @Query("SELECT * FROM log_entry WHERE syncStatus = :status")
    abstract suspend fun getByStatus(status: SyncStatus): List<LogEntryEntity>

    @Query("SELECT * FROM log_entry_item WHERE logEntryId = :entryId")
    abstract suspend fun getItemsForEntry(entryId: String): List<LogEntryItemEntity>

    @Upsert
    abstract suspend fun upsert(entity: LogEntryEntity)

    @Upsert
    abstract suspend fun upsertItem(item: LogEntryItemEntity)

    @Upsert
    abstract suspend fun upsertAll(entries: List<LogEntryEntity>)

    @Upsert
    abstract suspend fun upsertAllItems(items: List<LogEntryItemEntity>)

    @Query("UPDATE log_entry SET syncStatus = :status WHERE id = :id")
    abstract suspend fun updateSyncStatus(id: String, status: SyncStatus)

    @Query("""
        UPDATE log_entry
        SET quickAddKcal = :kcal, quickAddLabel = :label, syncStatus = 'PENDING_UPDATE'
        WHERE id = :id
    """)
    abstract suspend fun updateQuickAdd(id: String, kcal: Int, label: String?)

    @Query("UPDATE log_entry SET sortOrder = :order, syncStatus = 'PENDING_UPDATE' WHERE id = :id")
    abstract suspend fun updateSortOrder(id: String, order: Int)

    @Transaction
    open suspend fun updateSortOrders(updates: List<Pair<String, Int>>) {
        updates.forEach { (id, order) -> updateSortOrder(id, order) }
    }

    @Query("DELETE FROM log_entry WHERE id = :id")
    abstract suspend fun deleteById(id: String)

    @Query("DELETE FROM log_entry WHERE userId = :userId")
    abstract suspend fun deleteAllForUser(userId: String)

    @Query("DELETE FROM log_entry_item WHERE logEntryId IN (SELECT id FROM log_entry WHERE userId = :userId)")
    abstract suspend fun deleteAllItemsForUser(userId: String)
}
