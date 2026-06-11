package org.branneman.health.db.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.branneman.health.db.SyncStatus
import org.branneman.health.db.entities.BodyWeightEntity

@Dao
interface BodyWeightDao {
    @Query("SELECT * FROM body_weight ORDER BY date DESC")
    fun observeAll(): Flow<List<BodyWeightEntity>>

    @Query("SELECT * FROM body_weight WHERE userId = :userId AND date = :date LIMIT 1")
    suspend fun getForDate(userId: String, date: String): BodyWeightEntity?

    @Query("SELECT * FROM body_weight WHERE syncStatus = :status")
    suspend fun getByStatus(status: SyncStatus): List<BodyWeightEntity>

    @Upsert
    suspend fun upsert(entity: BodyWeightEntity)

    @Upsert
    suspend fun upsertAll(entities: List<BodyWeightEntity>)

    @Query("DELETE FROM body_weight WHERE userId = :userId")
    suspend fun deleteAllForUser(userId: String)

    @Query("UPDATE body_weight SET syncStatus = :status WHERE id = :id")
    suspend fun updateSyncStatus(id: String, status: SyncStatus)

    @Query("DELETE FROM body_weight WHERE id = :id")
    suspend fun deleteById(id: String)
}
