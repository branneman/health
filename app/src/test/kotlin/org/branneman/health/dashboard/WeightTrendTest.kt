package org.branneman.health.dashboard

import org.branneman.health.aBodyWeightEntry
import org.branneman.health.db.entities.BodyWeightEntity
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeightTrendTest {

    private val today = LocalDate.parse("2026-07-01")

    private fun readingsOverDays(count: Int, startDaysAgo: Int = 0, kg: Double = 80.0): List<BodyWeightEntity> =
        (0 until count).map {
            aBodyWeightEntry(date = today.minusDays((startDaysAgo + it).toLong()).toString(), kg = kg)
        }

    // --- Confidence gate (math-model §3.2, spec decision 3) ---

    @Test fun `two readings is NONE confidence with no points`() {
        val result = computeWeightTrend(readingsOverDays(2), today)
        assertEquals(TrendConfidence.NONE, result.confidence)
        assertTrue(result.points.isEmpty())
    }

    @Test fun `three readings is PARTIAL confidence`() {
        assertEquals(TrendConfidence.PARTIAL, computeWeightTrend(readingsOverDays(3), today).confidence)
    }

    @Test fun `six readings is still PARTIAL confidence`() {
        assertEquals(TrendConfidence.PARTIAL, computeWeightTrend(readingsOverDays(6), today).confidence)
    }

    @Test fun `seven readings is FULL confidence`() {
        assertEquals(TrendConfidence.FULL, computeWeightTrend(readingsOverDays(7), today).confidence)
    }

    // --- Points: one per reading date, using the shared SMA function ---

    @Test fun `one point per reading date with matching smoothed value`() {
        val readings = readingsOverDays(7, kg = 80.0)
        val result = computeWeightTrend(readings, today)
        assertEquals(7, result.points.size)
        result.points.forEach { assertEquals(80.0, it.smoothedKg, 0.0001) }
    }

    @Test fun `raw kg is the reading's own value even when smoothed differs`() {
        // 6 readings at 80.0 on days -6..-1, then one at 86.0 today:
        // smoothed(today) = mean of the last 7 = (6*80 + 86) / 7
        val readings = readingsOverDays(6, startDaysAgo = 1, kg = 80.0) +
            listOf(aBodyWeightEntry(date = today.toString(), kg = 86.0))
        val result = computeWeightTrend(readings, today)
        val last = result.points.last()
        assertEquals(today, last.date)
        assertEquals(86.0, last.rawKg, 0.0001)
        assertEquals((6 * 80.0 + 86.0) / 7, last.smoothedKg, 0.0001)
    }

    // --- availableRanges (spec decision 4) ---

    @Test fun `short history only offers week month and all`() {
        val result = computeWeightTrend(readingsOverDays(7), today)
        assertEquals(setOf(TrendRange.WEEK, TrendRange.MONTH, TrendRange.ALL), result.availableRanges)
    }

    @Test fun `history spanning exactly 90 days does not yet offer 3 months`() {
        val readings = listOf(aBodyWeightEntry(date = today.minusDays(90).toString(), kg = 80.0)) + readingsOverDays(7)
        val ranges = computeWeightTrend(readings, today).availableRanges
        assertEquals(setOf(TrendRange.WEEK, TrendRange.MONTH, TrendRange.ALL), ranges)
    }

    @Test fun `history spanning 91 days offers 3 months but not 6`() {
        val readings = listOf(aBodyWeightEntry(date = today.minusDays(91).toString(), kg = 80.0)) + readingsOverDays(7)
        val ranges = computeWeightTrend(readings, today).availableRanges
        assertTrue(TrendRange.THREE_MONTHS in ranges)
        assertTrue(TrendRange.SIX_MONTHS !in ranges)
    }

    @Test fun `history spanning 181 days offers 6 months but not a year`() {
        val readings = listOf(aBodyWeightEntry(date = today.minusDays(181).toString(), kg = 80.0)) + readingsOverDays(7)
        val ranges = computeWeightTrend(readings, today).availableRanges
        assertTrue(TrendRange.SIX_MONTHS in ranges)
        assertTrue(TrendRange.YEAR !in ranges)
    }

    @Test fun `history spanning 366 days offers a year`() {
        val readings = listOf(aBodyWeightEntry(date = today.minusDays(366).toString(), kg = 80.0)) + readingsOverDays(7)
        assertTrue(TrendRange.YEAR in computeWeightTrend(readings, today).availableRanges)
    }

    // --- filterToRange (spec decision 6) ---

    @Test fun `filterToRange excludes points before the window`() {
        val points = (0..10).map {
            WeightTrendPoint(date = today.minusDays(it.toLong()), smoothedKg = 80.0, rawKg = 80.0)
        }
        val filtered = filterToRange(points, TrendRange.WEEK, today)
        assertEquals(8, filtered.size) // today-0 .. today-7 inclusive
        assertTrue(filtered.all { !it.date.isBefore(today.minusDays(7)) })
    }

    @Test fun `filterToRange ALL returns every point`() {
        val points = (0..400).map {
            WeightTrendPoint(date = today.minusDays(it.toLong()), smoothedKg = 80.0, rawKg = 80.0)
        }
        assertEquals(points.size, filterToRange(points, TrendRange.ALL, today).size)
    }
}
