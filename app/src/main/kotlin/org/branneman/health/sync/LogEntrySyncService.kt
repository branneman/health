package org.branneman.health.sync

import org.branneman.health.QuickAddRequestDto
import org.branneman.health.db.HealthDatabase
import org.branneman.health.db.SyncStatus
import org.branneman.health.network.HealthApiClient

class LogEntrySyncService(
    private val api: HealthApiClient,
    private val db: HealthDatabase,
) {
    suspend fun sync(token: String) {
        db.logEntryDao().getByStatus(SyncStatus.PENDING_CREATE).forEach { entity ->
            val kcal = entity.quickAddKcal ?: return@forEach
            runCatching {
                api.postQuickAdd(
                    token,
                    QuickAddRequestDto(
                        id            = entity.id,
                        quickAddKcal  = kcal,
                        quickAddLabel = entity.quickAddLabel,
                        loggedAt      = entity.loggedAt,
                    )
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
