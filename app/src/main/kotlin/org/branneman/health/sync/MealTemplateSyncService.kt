package org.branneman.health.sync

import org.branneman.health.MealTemplateDto
import org.branneman.health.db.HealthDatabase
import org.branneman.health.db.SyncStatus
import org.branneman.health.db.entities.MealTemplateEntity
import org.branneman.health.network.HealthApiClient

class MealTemplateSyncService(
    private val api: HealthApiClient,
    private val db: HealthDatabase,
) {
    suspend fun pushPending(token: String, userId: String) {
        val pending = db.mealTemplateDao().getByStatus(SyncStatus.PENDING_CREATE)
        if (pending.isEmpty()) return

        val allActive = pending +
                        db.mealTemplateDao().getByStatus(SyncStatus.SYNCED)

        runCatching {
            api.putTemplates(token, allActive.map { it.toDto() })
        }.onSuccess {
            allActive.forEach { db.mealTemplateDao().updateSyncStatus(it.id, SyncStatus.SYNCED) }
        }
    }

    suspend fun pull(token: String, userId: String) {
        val templates = api.getTemplates(token)
        db.mealTemplateDao().upsertAll(templates.map { dto ->
            MealTemplateEntity(
                id           = dto.id,
                userId       = userId,
                name         = dto.name,
                sortOrder    = dto.sortOrder,
                quickAddKcal = dto.quickAddKcal,
                syncStatus   = SyncStatus.SYNCED,
            )
        })
    }

    private fun MealTemplateEntity.toDto() = MealTemplateDto(
        id           = id,
        name         = name,
        sortOrder    = sortOrder,
        quickAddKcal = quickAddKcal,
        items        = emptyList(),
    )
}
