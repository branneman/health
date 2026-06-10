package org.branneman.health.onboarding

import org.branneman.health.UserProfileDto
import org.branneman.health.WeightEntryDto
import org.branneman.health.db.HealthDatabase
import org.branneman.health.db.SyncStatus
import org.branneman.health.db.entities.BodyWeightEntity
import org.branneman.health.db.entities.UserProfileEntity
import org.branneman.health.network.HealthApiClient
import java.time.LocalDate

fun computeBmr(sex: String, weightKg: Double, heightCm: Int, age: Int): Double {
    val base = 10.0 * weightKg + 6.25 * heightCm - 5.0 * age
    return if (sex == "male") base + 5.0 else base - 161.0
}

fun activityMultiplier(level: String): Double = when (level) {
    "sedentary"          -> 1.20
    "lightly_active"     -> 1.375
    "moderately_active"  -> 1.55
    else                 -> 1.375
}

class OnboardingRepository(
    private val apiClient: HealthApiClient,
    private val db: HealthDatabase,
) {
    suspend fun save(
        token: String,
        userId: String,
        sex: String,
        heightCm: Int,
        currentWeightKg: Double,
        goalWeightKg: Double,
        birthYear: Int,
        activityLevel: String,
        targetDeficit: Int,
    ): Result<Unit> = runCatching {
        val profileDto = UserProfileDto(
            heightCm      = heightCm,
            birthYear     = birthYear,
            sex           = sex,
            goalWeightKg  = goalWeightKg,
            activityLevel = activityLevel,
            targetDeficit = targetDeficit,
            phase         = "loss",
            vacationMode  = false,
        )
        apiClient.putProfile(token, profileDto)

        val today = LocalDate.now().toString()
        apiClient.postBodyWeight(token, WeightEntryDto(date = today, kg = currentWeightKg))

        db.userProfileDao().upsert(
            UserProfileEntity(
                userId        = userId,
                heightCm      = heightCm,
                birthYear     = birthYear,
                sex           = sex,
                goalWeightKg  = goalWeightKg,
                activityLevel = activityLevel,
                targetDeficit = targetDeficit,
                phase         = "loss",
                vacationMode  = false,
                syncStatus    = SyncStatus.SYNCED,
            )
        )
        db.bodyWeightDao().upsert(
            BodyWeightEntity(
                id         = today,
                userId     = userId,
                date       = today,
                kg         = currentWeightKg,
                syncStatus = SyncStatus.SYNCED,
            )
        )
    }
}
