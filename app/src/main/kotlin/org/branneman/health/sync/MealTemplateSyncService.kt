package org.branneman.health.sync

import org.branneman.health.MealTemplateDto
import org.branneman.health.MealTemplateItemDto
import org.branneman.health.db.HealthDatabase
import org.branneman.health.db.SyncStatus
import org.branneman.health.db.entities.MealTemplateEntity
import org.branneman.health.db.entities.MealTemplateItemEntity
import org.branneman.health.network.HealthApiClient

class MealTemplateSyncService(
    private val api: HealthApiClient,
    private val db: HealthDatabase,
) {
    suspend fun pushPending(token: String) {
        val pendingCreate = db.mealTemplateDao().getByStatus(SyncStatus.PENDING_CREATE)
        val pendingDelete = db.mealTemplateDao().getByStatus(SyncStatus.PENDING_DELETE)
        if (pendingCreate.isEmpty() && pendingDelete.isEmpty()) return

        val allActive = pendingCreate + db.mealTemplateDao().getByStatus(SyncStatus.SYNCED)
        val dtos = allActive.map { template ->
            val items = db.mealTemplateDao().getItemsForTemplate(template.id)
            template.toDto(items)
        }
        runCatching {
            api.putTemplates(token, dtos)
        }.onSuccess {
            allActive.forEach { db.mealTemplateDao().updateSyncStatus(it.id, SyncStatus.SYNCED) }
            pendingDelete.forEach { db.mealTemplateDao().deleteById(it.id) }
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
        templates.forEach { dto ->
            db.mealTemplateDao().deleteItemsForTemplate(dto.id)
            db.mealTemplateDao().upsertAllItems(
                dto.items.mapIndexed { index, item ->
                    MealTemplateItemEntity(
                        templateId = dto.id,
                        foodItemId = item.foodItemId,
                        grams      = item.grams,
                        sortOrder  = item.sortOrder.takeIf { it != 0 } ?: index,
                    )
                }
            )
        }
    }

    private fun MealTemplateEntity.toDto(items: List<MealTemplateItemEntity> = emptyList()) = MealTemplateDto(
        id           = id,
        name         = name,
        sortOrder    = sortOrder,
        quickAddKcal = quickAddKcal,
        items        = items.map { MealTemplateItemDto(foodItemId = it.foodItemId, grams = it.grams, sortOrder = it.sortOrder) },
    )
}
