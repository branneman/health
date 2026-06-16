package org.branneman.health.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import org.branneman.health.db.entities.DynamicBudgetParamsEntity

@Dao
interface DynamicBudgetParamsDao {
    @Upsert
    suspend fun upsert(params: DynamicBudgetParamsEntity)

    @Query("SELECT * FROM dynamic_budget_params WHERE date = :date")
    suspend fun getForDate(date: String): DynamicBudgetParamsEntity?
}
