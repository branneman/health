package org.branneman.health.db.dao

import androidx.room.*
import org.branneman.health.db.SyncStatus
import org.branneman.health.db.entities.FoodItemEntity

@Dao
interface FoodItemDao {
    @Query("SELECT * FROM food_item WHERE syncStatus = :status")
    suspend fun getByStatus(status: SyncStatus): List<FoodItemEntity>

    @Query("SELECT * FROM food_item WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): FoodItemEntity?

    @Query("SELECT * FROM food_item WHERE barcode = :barcode LIMIT 1")
    suspend fun getByBarcode(barcode: String): FoodItemEntity?

    @Query("SELECT * FROM food_item WHERE name LIKE '%' || :query || '%'")
    suspend fun searchByName(query: String): List<FoodItemEntity>

    @Upsert
    suspend fun upsert(entity: FoodItemEntity)

    @Upsert
    suspend fun upsertAll(entities: List<FoodItemEntity>)

    @Query("DELETE FROM food_item WHERE userId = :userId")
    suspend fun deleteAllForUser(userId: String)

    @Query("UPDATE food_item SET syncStatus = :status WHERE id = :id")
    suspend fun updateSyncStatus(id: String, status: SyncStatus)
}
