package org.branneman.health.db.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.branneman.health.db.SyncStatus
import org.branneman.health.db.entities.MealTemplateEntity
import org.branneman.health.db.entities.MealTemplateItemEntity

@Dao
interface MealTemplateDao {
    @Query("SELECT * FROM meal_template WHERE syncStatus != 'PENDING_DELETE'")
    fun observeAll(): Flow<List<MealTemplateEntity>>

    @Query("SELECT * FROM meal_template WHERE sortOrder IS NOT NULL AND syncStatus != 'PENDING_DELETE' ORDER BY sortOrder ASC")
    fun observePinned(): Flow<List<MealTemplateEntity>>

    @Query("SELECT * FROM meal_template WHERE syncStatus = :status")
    suspend fun getByStatus(status: SyncStatus): List<MealTemplateEntity>

    @Query("SELECT * FROM meal_template WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MealTemplateEntity?

    @Query("DELETE FROM meal_template WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM meal_template_item WHERE templateId = :templateId")
    suspend fun getItems(templateId: String): List<MealTemplateItemEntity>

    @Upsert
    suspend fun upsert(entity: MealTemplateEntity)

    @Upsert
    suspend fun upsertItem(item: MealTemplateItemEntity)

    @Upsert
    suspend fun upsertAll(entities: List<MealTemplateEntity>)

    @Upsert
    suspend fun upsertAllItems(items: List<MealTemplateItemEntity>)

    @Query("UPDATE meal_template SET syncStatus = :status WHERE id = :id")
    suspend fun updateSyncStatus(id: String, status: SyncStatus)

    @Query("DELETE FROM meal_template WHERE userId = :userId")
    suspend fun deleteAllForUser(userId: String)

    @Query("DELETE FROM meal_template_item WHERE templateId IN (SELECT id FROM meal_template WHERE userId = :userId)")
    suspend fun deleteAllItemsForUser(userId: String)
}
