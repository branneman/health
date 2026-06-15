package org.branneman.health.sync

import org.branneman.health.db.HealthDatabase
import org.branneman.health.db.SyncStatus
import org.branneman.health.db.entities.*
import org.branneman.health.network.HealthApiClient
import java.time.LocalDate

class LoginSyncService(
    private val api: HealthApiClient,
    private val db: HealthDatabase,
) {
    // Returns true if the user has a profile (onboarding done), false otherwise.
    suspend fun sync(token: String, userId: String): Boolean {
        val from = LocalDate.now().minusDays(90).toString()

        val profile = api.getProfile(token)

        ShortcutSyncService(api, db).pull(token, userId)

        val foodItems = api.getFoodItems(token)
        db.foodItemDao().upsertAll(foodItems.map { dto ->
            FoodItemEntity(
                id = dto.id, userId = userId, barcode = dto.barcode, name = dto.name,
                kcalPer100g = dto.kcalPer100g, proteinPer100g = dto.proteinPer100g,
                carbsPer100g = dto.carbsPer100g, fatPer100g = dto.fatPer100g,
                source = dto.source, syncStatus = SyncStatus.SYNCED,
            )
        })

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
        db.mealTemplateDao().upsertAllItems(templates.flatMap { dto ->
            dto.items.map { item ->
                MealTemplateItemEntity(
                    templateId = dto.id, foodItemId = item.foodItemId,
                    grams = item.grams
                )
            }
        })

        val weights = api.getBodyWeight(token)
        db.bodyWeightDao().upsertAll(weights.map { dto ->
            BodyWeightEntity(
                id = dto.date, userId = userId, date = dto.date,
                kg = dto.kg, syncStatus = SyncStatus.SYNCED
            )
        })

        val logEntries = api.getLogEntries(token, from)
        db.logEntryDao().upsertAll(logEntries.map { dto ->
            LogEntryEntity(
                id = dto.id, userId = userId, loggedAt = dto.loggedAt,
                mealType = dto.mealType, quickAddKcal = dto.quickAddKcal,
                quickAddLabel = dto.quickAddLabel, syncStatus = SyncStatus.SYNCED
            )
        })
        db.logEntryDao().upsertAllItems(logEntries.flatMap { dto ->
            dto.items.map { item ->
                LogEntryItemEntity(
                    logEntryId = dto.id, foodItemId = item.foodItemId,
                    grams = item.grams, kcalPer100g = item.kcalPer100g,
                    proteinPer100g = item.proteinPer100g, carbsPer100g = item.carbsPer100g,
                    fatPer100g = item.fatPer100g
                )
            }
        })

        val energy = api.getDailyEnergy(token, from)
        db.dailyEnergyDao().upsertAll(energy.map { dto ->
            DailyEnergyEntity(
                userId = userId, date = dto.date, bmrKcal = dto.bmrKcal,
                activeKcal = dto.activeKcal, totalKcal = dto.totalKcal,
                steps = dto.steps, source = dto.source
            )
        })

        val workouts = api.getWorkouts(token, from)
        db.workoutDao().upsertAll(workouts.map { dto ->
            WorkoutEntity(
                id = dto.id, userId = userId, date = dto.date, type = dto.type,
                durationSecs = dto.durationSecs, avgHr = dto.avgHr, kcal = dto.kcal
            )
        })

        if (profile != null) {
            db.userProfileDao().upsert(
                UserProfileEntity(
                    userId = userId, heightCm = profile.heightCm, birthYear = profile.birthYear,
                    sex = profile.sex, goalWeightKg = profile.goalWeightKg,
                    activityLevel = profile.activityLevel, targetDeficit = profile.targetDeficit,
                    phase = profile.phase, vacationMode = profile.vacationMode,
                    syncStatus = SyncStatus.SYNCED,
                )
            )
        }

        return profile != null
    }
}
