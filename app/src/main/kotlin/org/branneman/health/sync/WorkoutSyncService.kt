package org.branneman.health.sync

import org.branneman.health.db.HealthDatabase
import org.branneman.health.db.entities.WorkoutEntity
import org.branneman.health.network.HealthApiClient
import java.time.LocalDate

class WorkoutSyncService(
    private val apiClient: HealthApiClient,
    private val db: HealthDatabase,
) {
    suspend fun sync(token: String, userId: String) {
        val from = LocalDate.now().minusDays(30).toString()
        runCatching { apiClient.getWorkouts(token, from) }
            .onSuccess { dtos ->
                db.workoutDao().upsertAll(dtos.map { dto ->
                    WorkoutEntity(
                        id           = dto.id,
                        userId       = userId,
                        date         = dto.date,
                        type         = dto.type,
                        durationSecs = dto.durationSecs,
                        avgHr        = dto.avgHr,
                        kcal         = dto.kcal,
                    )
                })
            }
    }
}
