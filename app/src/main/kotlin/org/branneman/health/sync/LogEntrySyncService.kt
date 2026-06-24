package org.branneman.health.sync

import org.branneman.health.FoodLogItemRequestDto
import org.branneman.health.FoodLogRequestDto
import org.branneman.health.QuickAddRequestDto
import org.branneman.health.QuickAddUpdateRequestDto
import org.branneman.health.db.HealthDatabase
import org.branneman.health.db.SyncStatus
import org.branneman.health.network.HealthApiClient

class LogEntrySyncService(
    private val api: HealthApiClient,
    private val db: HealthDatabase,
) {
    suspend fun sync(token: String) {
        db.logEntryDao().getByStatus(SyncStatus.PENDING_CREATE).forEach { entity ->
            if (entity.quickAddKcal != null) {
                runCatching {
                    api.postQuickAdd(
                        token,
                        QuickAddRequestDto(
                            id            = entity.id,
                            quickAddKcal  = entity.quickAddKcal,
                            quickAddLabel = entity.quickAddLabel,
                            loggedAt      = entity.loggedAt,
                        )
                    )
                }.onSuccess {
                    db.logEntryDao().updateSyncStatus(entity.id, SyncStatus.SYNCED)
                }
            } else {
                val items = db.logEntryDao().getItemsForEntry(entity.id)
                if (items.isEmpty()) return@forEach
                runCatching {
                    api.postFoodLog(
                        token,
                        FoodLogRequestDto(
                            id       = entity.id,
                            mealType = entity.mealType,
                            loggedAt = entity.loggedAt,
                            items    = items.map { FoodLogItemRequestDto(it.foodItemId, it.grams) },
                        )
                    )
                }.onSuccess {
                    db.logEntryDao().updateSyncStatus(entity.id, SyncStatus.SYNCED)
                }
            }
        }

        db.logEntryDao().getByStatus(SyncStatus.PENDING_UPDATE).forEach { entity ->
            if (entity.quickAddKcal == null) return@forEach
            runCatching {
                api.patchQuickAdd(
                    token = token,
                    id    = entity.id,
                    dto   = QuickAddUpdateRequestDto(
                        kcal  = entity.quickAddKcal,
                        label = entity.quickAddLabel,
                    ),
                )
            }.onSuccess {
                db.logEntryDao().updateSyncStatus(entity.id, SyncStatus.SYNCED)
            }
        }

        db.logEntryDao().getByStatus(SyncStatus.PENDING_DELETE).forEach { entity ->
            runCatching { api.deleteLogEntry(token, entity.id) }
                .onSuccess { db.logEntryDao().deleteById(entity.id) }
        }
    }
}
