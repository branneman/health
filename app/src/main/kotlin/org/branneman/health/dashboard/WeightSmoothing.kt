package org.branneman.health.dashboard

import org.branneman.health.db.entities.BodyWeightEntity
import java.time.LocalDate

// Mean of the last ≤7 readings on or before endpoint (math-model §3.1).
// Shared by WeeklyVerdict (story 19) and WeightTrend (story 20) so both stay
// provably consistent — never reimplement this independently.
internal fun smoothed(sortedByDate: List<BodyWeightEntity>, endpoint: LocalDate): Double =
    sortedByDate
        .filter { LocalDate.parse(it.date) <= endpoint }
        .takeLast(7)
        .map { it.kg }
        .average()
