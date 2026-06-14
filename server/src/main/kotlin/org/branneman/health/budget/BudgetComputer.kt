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

data class HistoricalDay(
    val date: LocalDate,
    val caloriesOut: Int,
    val caloriesIn: Int?,   // null if not food-logged that day
    val isSportDay: Boolean,
)

data class BudgetResult(
    val caloriesIn: Int,
    val caloriesOut: Int,
    val budgetRemaining: Int,
    val targetDeficit: Int,
    val caloriesOutSource: String,
)

data class DynamicBudgetParams(
    val expectedTodaySport: Int?,
    val expectedTodayNonSport: Int?,
    val eatingFractionSport: Double?,
    val eatingFractionNonSport: Double?,
    val actualBurnedSoFar: Int?,
    val postWorkoutModeSport: Boolean,
    val postWorkoutModeNonSport: Boolean,
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

    fun computeDynamic(
        history: List<HistoricalDay>,
        targetDeficit: Int,
        actualBurnedToday: Int?,
    ): DynamicBudgetParams {
        val expectedSport    = computeExpected(history, isSport = true)
        val expectedNonSport = computeExpected(history, isSport = false)

        val fractionSport    = computeFraction(history, isSport = true,  targetDeficit, expectedSport)
        val fractionNonSport = computeFraction(history, isSport = false, targetDeficit, expectedNonSport)

        val postWorkoutSport = actualBurnedToday != null && expectedSport != null &&
            actualBurnedToday >= (expectedSport * 0.9).toInt()
        val postWorkoutNonSport = actualBurnedToday != null && expectedNonSport != null &&
            actualBurnedToday >= (expectedNonSport * 0.9).toInt()

        return DynamicBudgetParams(
            expectedTodaySport      = expectedSport,
            expectedTodayNonSport   = expectedNonSport,
            eatingFractionSport     = fractionSport,
            eatingFractionNonSport  = fractionNonSport,
            actualBurnedSoFar       = actualBurnedToday,
            postWorkoutModeSport    = postWorkoutSport,
            postWorkoutModeNonSport = postWorkoutNonSport,
        )
    }

    internal fun computeExpected(history: List<HistoricalDay>, isSport: Boolean): Int? {
        val days = history.filter { it.isSportDay == isSport }
        if (days.isEmpty()) return null
        return days.sumOf { it.caloriesOut } / days.size
    }

    internal fun computeFraction(
        history: List<HistoricalDay>,
        isSport: Boolean,
        targetDeficit: Int,
        expected: Int?,
    ): Double? {
        val logged = history.filter { it.isSportDay == isSport && it.caloriesIn != null }

        // Approach 3 — qualifying days (≥10 logged days with ≥10 qualifying)
        if (expected != null && logged.size >= 10) {
            val qualifying = logged.filter { (it.caloriesIn ?: Int.MAX_VALUE) <= expected - targetDeficit + 100 }
            if (qualifying.size >= 10) {
                val avgIn  = qualifying.sumOf { it.caloriesIn!! }.toDouble() / qualifying.size
                val avgOut = qualifying.sumOf { it.caloriesOut }.toDouble() / qualifying.size
                if (avgOut > 0) return avgIn / avgOut
            }
        }

        // Approach 2 — any logged days (≥5)
        if (logged.size >= 5) {
            val avgIn  = logged.sumOf { it.caloriesIn!! }.toDouble() / logged.size
            val avgOut = logged.sumOf { it.caloriesOut }.toDouble() / logged.size
            if (avgOut > 0) return avgIn / avgOut
        }

        // Approach 1 — target-derived fallback
        if (expected != null && expected > 0) {
            return maxOf(0.0, (expected - targetDeficit).toDouble() / expected)
        }

        return null
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
