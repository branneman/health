package org.branneman.health.db.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.branneman.health.db.entities.DailyEnergyEntity

@Dao
interface DailyEnergyDao {
    @Query("SELECT * FROM daily_energy ORDER BY date DESC")
    fun observeAll(): Flow<List<DailyEnergyEntity>>

    @Upsert
    suspend fun upsertAll(entities: List<DailyEnergyEntity>)

    @Query("DELETE FROM daily_energy WHERE userId = :userId")
    suspend fun deleteAllForUser(userId: String)
}
