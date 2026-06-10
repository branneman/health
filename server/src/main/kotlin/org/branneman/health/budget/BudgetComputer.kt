package org.branneman.health.budget

import java.time.LocalDate

data class UserProfileInput(
    val heightCm: Int,
    val birthYear: Int,
    val sex: String,
    val activityLevel: String,
    val targetDeficit: Int,
    val goalWeightKg: Double,
)

data class EnergyRow(val date: LocalDate, val totalKcal: Int)

data class BudgetResult(
    val caloriesIn: Int,
    val caloriesOut: Int,
    val budgetRemaining: Int,
    val targetDeficit: Int,
    val caloriesOutSource: String,
)

fun computeBmr(sex: String, weightKg: Double, heightCm: Int, age: Int): Double {
    val base = 10.0 * weightKg + 6.25 * heightCm - 5.0 * age
    return if (sex == "male") base + 5.0 else base - 161.0
}

fun activityMultiplier(level: String): Double = when (level) {
    "sedentary"         -> 1.20
    "lightly_active"    -> 1.375
    "moderately_active" -> 1.55
    else                -> 1.375
}

object BudgetComputer {
    fun compute(
        today: LocalDate,
        profile: UserProfileInput,
        latestWeightKg: Double?,
        energyRows: List<EnergyRow>,
        caloriesIn: Int,
    ): BudgetResult {
        val (caloriesOut, source) = resolveCaloriesOut(today, profile, latestWeightKg, energyRows)
        return BudgetResult(
            caloriesIn        = caloriesIn,
            caloriesOut       = caloriesOut,
            budgetRemaining   = caloriesOut - profile.targetDeficit - caloriesIn,
            targetDeficit     = profile.targetDeficit,
            caloriesOutSource = source,
        )
    }

    private fun resolveCaloriesOut(
        today: LocalDate,
        profile: UserProfileInput,
        latestWeightKg: Double?,
        energyRows: List<EnergyRow>,
    ): Pair<Int, String> {
        energyRows.firstOrNull { it.date == today }
            ?.let { return it.totalKcal to "polar_today" }
        energyRows.firstOrNull { it.date == today.minusDays(1) }
            ?.let { return it.totalKcal to "polar_yesterday" }
        val weightKg = latestWeightKg ?: profile.goalWeightKg
        val age = today.year - profile.birthYear
        val tdee = (computeBmr(profile.sex, weightKg, profile.heightCm, age) * activityMultiplier(profile.activityLevel)).toInt()
        return tdee to "estimate"
    }
}
