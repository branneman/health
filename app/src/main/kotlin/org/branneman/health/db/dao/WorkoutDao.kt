package org.branneman.health.db.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.branneman.health.db.entities.WorkoutEntity

@Dao
interface WorkoutDao {
    @Query("SELECT * FROM workout ORDER BY date DESC")
    fun observeAll(): Flow<List<WorkoutEntity>>

    @Query("SELECT * FROM workout WHERE userId = :userId ORDER BY date DESC")
    suspend fun getAll(userId: String): List<WorkoutEntity>

    @Upsert
    suspend fun upsertAll(entities: List<WorkoutEntity>)

    @Query("DELETE FROM workout WHERE userId = :userId")
    suspend fun deleteAllForUser(userId: String)
}
