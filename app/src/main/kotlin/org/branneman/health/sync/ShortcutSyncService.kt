package org.branneman.health.sync

import org.branneman.health.ShortcutDto
import org.branneman.health.db.HealthDatabase
import org.branneman.health.db.SyncStatus
import org.branneman.health.db.entities.ShortcutEntity
import org.branneman.health.network.HealthApiClient

class ShortcutSyncService(
    private val api: HealthApiClient,
    private val db: HealthDatabase,
) {
    suspend fun pushPending(token: String) {
        val pending = db.shortcutDao().getByStatus(SyncStatus.PENDING_CREATE)
        if (pending.isEmpty()) return
        val allActive = pending + db.shortcutDao().getByStatus(SyncStatus.SYNCED)
        runCatching {
            api.putShortcuts(token, allActive.map { it.toDto() })
        }.onSuccess {
            allActive.forEach { db.shortcutDao().updateSyncStatus(it.id, SyncStatus.SYNCED) }
        }
    }

    suspend fun pull(token: String, userId: String) {
        val shortcuts = api.getShortcuts(token)
        db.shortcutDao().deleteAllForUser(userId)
        db.shortcutDao().upsertAll(shortcuts.map { dto ->
            ShortcutEntity(
                id         = dto.id,
                userId     = userId,
                emoji      = dto.emoji,
                label      = dto.label,
                kcal       = dto.kcal,
                sortOrder  = dto.sortOrder,
                syncStatus = SyncStatus.SYNCED,
            )
        })
    }

    private fun ShortcutEntity.toDto() = ShortcutDto(
        id        = id,
        emoji     = emoji,
        label     = label,
        kcal      = kcal,
        sortOrder = sortOrder,
    )
}
