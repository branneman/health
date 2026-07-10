package org.branneman.health.dashboard

import org.branneman.health.db.entities.BodyWeightEntity
import java.time.LocalDate

sealed interface WeeklyVerdict {
    data class Green(val deltaKg: Double) : WeeklyVerdict
    data class AmberBehind(val deltaKg: Double) : WeeklyVerdict
    data class AmberFast(val deltaKg: Double) : WeeklyVerdict
    data object GracePeriod : WeeklyVerdict
    data object NotEnoughData : WeeklyVerdict
}

// math-model §4: gates first (grace before data sufficiency), then thresholds.
// The grace gate guarantees a reading exists on or before today−14, so both
// SMA endpoints are always computable once gates pass.
fun computeWeeklyVerdict(
    readings: List<BodyWeightEntity>,
    targetDeficit: Int,
    today: LocalDate,
): WeeklyVerdict {
    val sorted = readings.sortedBy { it.date }
    val earliest = sorted.firstOrNull()?.let { LocalDate.parse(it.date) }
    if (earliest == null || earliest.isAfter(today.minusDays(14))) return WeeklyVerdict.GracePeriod
    val inWindow = sorted.count { LocalDate.parse(it.date) >= today.minusDays(14) }
    if (inWindow < 5) return WeeklyVerdict.NotEnoughData

    val actual = smoothed(sorted, today) - smoothed(sorted, today.minusDays(7))
    val expectedWeekly = targetDeficit * 7 / 7700.0
    return when {
        actual < -0.5                     -> WeeklyVerdict.AmberFast(actual)
        actual <= -(expectedWeekly * 0.4) -> WeeklyVerdict.Green(actual)
        else                              -> WeeklyVerdict.AmberBehind(actual)
    }
}

fun verdictMessage(verdict: WeeklyVerdict): String = when (verdict) {
    is WeeklyVerdict.Green       -> "${deltaPhrase(verdict.deltaKg)} — on track."
    is WeeklyVerdict.AmberBehind -> "${deltaPhrase(verdict.deltaKg)} — slightly behind."
    is WeeklyVerdict.AmberFast   -> "${deltaPhrase(verdict.deltaKg)} — dropping quickly, watch your intake."
    WeeklyVerdict.GracePeriod    -> "Building baseline — keep logging."
    WeeklyVerdict.NotEnoughData  -> "Not enough weigh-ins for a reliable verdict this week."
}

// |delta| < 0.05 counts as flat (spec §Messages), so a flat trend never reads "Down 0.0 kg".
private fun deltaPhrase(deltaKg: Double): String = when {
    kotlin.math.abs(deltaKg) < 0.05 -> "Flat this week"
    deltaKg < 0                     -> "Down %.1f kg this week".format(-deltaKg)
    else                            -> "Up %.1f kg this week".format(deltaKg)
}
