package org.branneman.health.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import org.branneman.health.db.entities.SportTonightEntity

@Dao
interface SportTonightDao {
    @Query("SELECT * FROM sport_tonight WHERE date = :date LIMIT 1")
    suspend fun getForDate(date: String): SportTonightEntity?

    @Upsert
    suspend fun upsert(entity: SportTonightEntity)

    @Query("DELETE FROM sport_tonight WHERE date = :date")
    suspend fun deleteForDate(date: String)
}
