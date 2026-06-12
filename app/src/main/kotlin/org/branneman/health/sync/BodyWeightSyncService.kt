package org.branneman.health.sync

import org.branneman.health.WeightEntryDto
import org.branneman.health.db.HealthDatabase
import org.branneman.health.db.SyncStatus
import org.branneman.health.network.HealthApiClient

class BodyWeightSyncService(
    private val api: HealthApiClient,
    private val db: HealthDatabase,
) {
    suspend fun sync(token: String) {
        db.bodyWeightDao().getByStatus(SyncStatus.PENDING_CREATE).forEach { entity ->
            runCatching {
                api.postBodyWeight(token, WeightEntryDto(entity.date, entity.kg))
            }.onSuccess {
                db.bodyWeightDao().updateSyncStatus(entity.id, SyncStatus.SYNCED)
            }
        }
    }
}
