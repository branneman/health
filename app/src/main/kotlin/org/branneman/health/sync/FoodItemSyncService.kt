package org.branneman.health.sync

import org.branneman.health.FoodItemRequestDto
import org.branneman.health.db.HealthDatabase
import org.branneman.health.db.SyncStatus
import org.branneman.health.network.HealthApiClient

class FoodItemSyncService(
    private val api: HealthApiClient,
    private val db: HealthDatabase,
) {
    suspend fun pushPending(token: String) {
        val pending = db.foodItemDao().getByStatus(SyncStatus.PENDING_CREATE)
        if (pending.isEmpty()) return
        pending.forEach { entity ->
            val result = runCatching {
                api.postFoodItem(
                    token,
                    FoodItemRequestDto(
                        id             = entity.id,
                        barcode        = entity.barcode,
                        name           = entity.name,
                        kcalPer100g    = entity.kcalPer100g,
                        proteinPer100g = entity.proteinPer100g,
                        carbsPer100g   = entity.carbsPer100g,
                        fatPer100g     = entity.fatPer100g,
                        source         = entity.source,
                    )
                )
            }
            // null return means 409 Conflict — already on server, mark synced
            if (result.isSuccess) {
                db.foodItemDao().updateSyncStatus(entity.id, SyncStatus.SYNCED)
            }
        }
    }
}
