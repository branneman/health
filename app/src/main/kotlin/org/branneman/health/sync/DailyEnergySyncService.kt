package org.branneman.health.sync

import org.branneman.health.db.HealthDatabase
import org.branneman.health.db.entities.DailyEnergyEntity
import org.branneman.health.network.HealthApiClient
import java.time.LocalDate

class DailyEnergySyncService(
    private val apiClient: HealthApiClient,
    private val db: HealthDatabase,
) {
    suspend fun sync(token: String, userId: String) {
        val from = LocalDate.now().minusDays(30).toString()
        runCatching { apiClient.getDailyEnergy(token, from) }
            .onSuccess { dtos ->
                db.dailyEnergyDao().upsertAll(dtos.map { dto ->
                    DailyEnergyEntity(
                        userId     = userId,
                        date       = dto.date,
                        bmrKcal    = dto.bmrKcal,
                        activeKcal = dto.activeKcal,
                        totalKcal  = dto.totalKcal,
                        steps      = dto.steps,
                        source     = dto.source,
                    )
                })
            }
    }
}
