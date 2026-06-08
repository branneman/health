package org.branneman.health.db.dao

import androidx.room.*
import org.branneman.health.db.SyncStatus
import org.branneman.health.db.entities.FoodItemEntity

@Dao
interface FoodItemDao {
    @Query("SELECT * FROM food_item WHERE syncStatus = :status")
    suspend fun getByStatus(status: SyncStatus): List<FoodItemEntity>

    @Upsert
    suspend fun upsertAll(entities: List<FoodItemEntity>)

    @Query("DELETE FROM food_item WHERE userId = :userId")
    suspend fun deleteAllForUser(userId: String)

    @Query("UPDATE food_item SET syncStatus = :status WHERE id = :id")
    suspend fun updateSyncStatus(id: String, status: SyncStatus)
}
