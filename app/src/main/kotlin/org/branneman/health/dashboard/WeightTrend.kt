package org.branneman.health.dashboard

import org.branneman.health.db.entities.BodyWeightEntity
import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class TrendConfidence { NONE, PARTIAL, FULL }

enum class TrendRange(val days: Long?) {
    WEEK(7), MONTH(30), THREE_MONTHS(90), SIX_MONTHS(180), YEAR(365), ALL(null)
}

data class WeightTrendPoint(
    val date: LocalDate,
    val smoothedKg: Double,
    val rawKg: Double,
)

data class WeightTrendData(
    val points: List<WeightTrendPoint>,
    val confidence: TrendConfidence,
    val availableRanges: Set<TrendRange>,
)

// math-model §3.2 gate, evaluated once over the whole history (spec decision 3).
fun computeWeightTrend(readings: List<BodyWeightEntity>, today: LocalDate): WeightTrendData {
    val sorted = readings.sortedBy { it.date }

    if (sorted.size < 3) {
        return WeightTrendData(
            points = emptyList(),
            confidence = TrendConfidence.NONE,
            availableRanges = setOf(TrendRange.WEEK, TrendRange.MONTH, TrendRange.ALL),
        )
    }

    val confidence = if (sorted.size >= 7) TrendConfidence.FULL else TrendConfidence.PARTIAL

    val points = sorted.map { reading ->
        val date = LocalDate.parse(reading.date)
        WeightTrendPoint(
            date = date,
            smoothedKg = smoothed(sorted, date),
            rawKg = reading.kg,
        )
    }

    val earliest = LocalDate.parse(sorted.first().date)
    val spanDays = ChronoUnit.DAYS.between(earliest, today)
    val availableRanges = buildSet {
        add(TrendRange.WEEK)
        add(TrendRange.MONTH)
        add(TrendRange.ALL)
        if (spanDays > 90) add(TrendRange.THREE_MONTHS)
        if (spanDays > 180) add(TrendRange.SIX_MONTHS)
        if (spanDays > 365) add(TrendRange.YEAR)
    }

    return WeightTrendData(points, confidence, availableRanges)
}

// Range buttons crop the already-computed series; they never re-run the gate (spec decision 3/6).
fun filterToRange(points: List<WeightTrendPoint>, range: TrendRange, today: LocalDate): List<WeightTrendPoint> =
    if (range.days == null) points
    else points.filter { !it.date.isBefore(today.minusDays(range.days)) }
