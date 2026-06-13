package org.branneman.health.polar

import org.branneman.health.data.DailyEnergy
import org.branneman.health.data.PolarAuth
import org.branneman.health.data.PolarConnectState
import org.branneman.health.data.Workout
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

private data class PolarAuthRow(
    val healthUserId: UUID,
    val polarUserId: String,
    val encryptedToken: String,
)

class PolarSyncService(
    private val polarClient: PolarApiClient,
    @Suppress("UnusedPrivateMember") private val dataSource: DataSource,
    private val cipher: TokenCipher,
) {
    suspend fun syncAll() {
        transaction {
            PolarConnectState.deleteWhere { PolarConnectState.expiresAt less OffsetDateTime.now() }
        }

        val users = transaction {
            PolarAuth.selectAll()
                .where { PolarAuth.healthUserId.isNotNull() }
                .mapNotNull { row ->
                    val huid = row[PolarAuth.healthUserId] ?: return@mapNotNull null
                    PolarAuthRow(huid, row[PolarAuth.userId], row[PolarAuth.accessToken])
                }
        }

        users.forEach { row ->
            try {
                val token = cipher.decrypt(row.encryptedToken)
                syncUser(row.healthUserId, token)
            } catch (_: PolarRateLimitException) {
                // skip this cycle for this user
            } catch (_: Exception) {
                // log would go here; skip user, do not rethrow
            }
        }
    }

    suspend fun syncForUser(healthUserId: UUID) {
        val row = transaction {
            PolarAuth.selectAll()
                .where { PolarAuth.healthUserId eq healthUserId }
                .singleOrNull()
                ?.let { PolarAuthRow(healthUserId, it[PolarAuth.userId], it[PolarAuth.accessToken]) }
        } ?: return

        val token = cipher.decrypt(row.encryptedToken)
        syncUser(healthUserId, token)
    }

    private suspend fun syncUser(healthUserId: UUID, accessToken: String) {
        val today = LocalDate.now()

        val activities = polarClient.getActivities(accessToken, today.minusDays(2), today)
        transaction {
            activities.forEach { a ->
                DailyEnergy.upsert {
                    it[DailyEnergy.userId]     = healthUserId
                    it[DailyEnergy.date]       = a.date
                    it[DailyEnergy.bmrKcal]    = a.totalKcal - a.activeKcal
                    it[DailyEnergy.activeKcal] = a.activeKcal
                    it[DailyEnergy.totalKcal]  = a.totalKcal
                    it[DailyEnergy.steps]      = a.steps
                    it[DailyEnergy.dataSource] = "polar"
                }
            }
        }

        val exercises = polarClient.getExercises(accessToken)
        transaction {
            exercises.forEach { e ->
                val existing = Workout.selectAll()
                    .where { (Workout.userId eq healthUserId) and (Workout.polarExerciseId eq e.polarId) }
                    .singleOrNull()
                if (existing != null) {
                    Workout.update({ (Workout.userId eq healthUserId) and (Workout.polarExerciseId eq e.polarId) }) {
                        it[Workout.date]         = e.date
                        it[Workout.type]         = e.sport
                        it[Workout.durationSecs] = e.durationSecs
                        it[Workout.avgHr]        = e.avgHr
                        it[Workout.kcal]         = e.kcal
                    }
                } else {
                    Workout.insert {
                        it[Workout.id]              = UUID.randomUUID()
                        it[Workout.userId]          = healthUserId
                        it[Workout.date]            = e.date
                        it[Workout.type]            = e.sport
                        it[Workout.durationSecs]    = e.durationSecs
                        it[Workout.avgHr]           = e.avgHr
                        it[Workout.kcal]            = e.kcal
                        it[Workout.polarExerciseId] = e.polarId
                    }
                }
            }
        }
    }
}
