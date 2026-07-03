package org.branneman.health.dashboard

import org.branneman.health.aBodyWeightEntry
import org.branneman.health.db.entities.BodyWeightEntity
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WeeklyVerdictTest {

    private val today = LocalDate.parse("2026-07-01")

    /**
     * 8 readings today−14 … today−7 at [oldKg], 7 readings today−6 … today at [newKg].
     * So smoothed(today) = newKg, smoothed(today − 7) = oldKg, actual = newKg − oldKg,
     * and both data gates pass (earliest = today − 14; 15 readings in window).
     */
    private fun twoPlateaus(oldKg: Double, newKg: Double): List<BodyWeightEntity> =
        (7..14).map { aBodyWeightEntry(date = today.minusDays(it.toLong()).toString(), kg = oldKg) } +
        (0..6).map { aBodyWeightEntry(date = today.minusDays(it.toLong()).toString(), kg = newKg) }

    // --- Verdict thresholds (math-model §4.3) ---

    @Test fun `loss at target pace is Green`() {
        val v = computeWeeklyVerdict(twoPlateaus(80.3, 80.0), targetDeficit = 300, today = today)
        assertIs<WeeklyVerdict.Green>(v)
        assertEquals(-0.3, v.deltaKg, 0.0001)
    }

    @Test fun `flat week with a deficit target is AmberBehind`() {
        assertIs<WeeklyVerdict.AmberBehind>(computeWeeklyVerdict(twoPlateaus(80.0, 80.0), 300, today))
    }

    @Test fun `weight gain is AmberBehind`() {
        val v = computeWeeklyVerdict(twoPlateaus(80.0, 80.4), 300, today)
        assertIs<WeeklyVerdict.AmberBehind>(v)
        assertEquals(0.4, v.deltaKg, 0.0001)
    }

    @Test fun `dropping more than half a kilo is AmberFast`() {
        val v = computeWeeklyVerdict(twoPlateaus(80.8, 80.0), 300, today)
        assertIs<WeeklyVerdict.AmberFast>(v)
        assertEquals(-0.8, v.deltaKg, 0.0001)
    }

    @Test fun `exactly minus 0 point 5 is Green because the ceiling is inclusive`() {
        // deficit 1375 → E = 1.25, 0.4E = 0.5: −0.5 sits exactly on both boundaries at once
        assertIs<WeeklyVerdict.Green>(computeWeeklyVerdict(twoPlateaus(80.5, 80.0), 1375, today))
    }

    @Test fun `losing slower than 40 percent of target pace is AmberBehind`() {
        // deficit 1375 → 0.4E = 0.5; a 0.4 kg drop misses the green floor
        assertIs<WeeklyVerdict.AmberBehind>(computeWeeklyVerdict(twoPlateaus(80.4, 80.0), 1375, today))
    }

    @Test fun `ceiling beats green floor on a huge deficit`() {
        // deficit 2750 → 0.4E = 1.0 > 0.5 ceiling: Green is unreachable, fast loss stays AmberFast
        assertIs<WeeklyVerdict.AmberFast>(computeWeeklyVerdict(twoPlateaus(80.7, 80.0), 2750, today))
    }

    @Test fun `zero deficit makes flat Green`() {
        assertIs<WeeklyVerdict.Green>(computeWeeklyVerdict(twoPlateaus(80.0, 80.0), 0, today))
    }

    // --- Gates (math-model §4.4, spec decisions 2) ---

    @Test fun `no readings is GracePeriod`() {
        assertIs<WeeklyVerdict.GracePeriod>(computeWeeklyVerdict(emptyList(), 300, today))
    }

    @Test fun `earliest reading 13 days ago is GracePeriod`() {
        val readings = (0..13).map { aBodyWeightEntry(date = today.minusDays(it.toLong()).toString(), kg = 80.0) }
        assertIs<WeeklyVerdict.GracePeriod>(computeWeeklyVerdict(readings, 300, today))
    }

    @Test fun `earliest reading exactly 14 days ago passes the grace gate`() {
        val readings = (0..14).map { aBodyWeightEntry(date = today.minusDays(it.toLong()).toString(), kg = 80.0) }
        assertIs<WeeklyVerdict.AmberBehind>(computeWeeklyVerdict(readings, 300, today))
    }

    @Test fun `four readings in the last 14 days is NotEnoughData`() {
        val readings = listOf(aBodyWeightEntry(date = today.minusDays(20).toString(), kg = 81.0)) +
            (0..3).map { aBodyWeightEntry(date = today.minusDays(it.toLong()).toString(), kg = 80.0) }
        assertIs<WeeklyVerdict.NotEnoughData>(computeWeeklyVerdict(readings, 300, today))
    }

    @Test fun `five readings in the last 14 days is enough`() {
        val readings = listOf(aBodyWeightEntry(date = today.minusDays(20).toString(), kg = 81.0)) +
            (0..4).map { aBodyWeightEntry(date = today.minusDays(it.toLong()).toString(), kg = 80.0) }
        // smoothed(today−7) = 81.0 (only the day-20 reading), smoothed(today) = 481/6 ≈ 80.1667
        val v = computeWeeklyVerdict(readings, 300, today)
        assertIs<WeeklyVerdict.AmberFast>(v)
        assertEquals(-0.8333, v.deltaKg, 0.001)
    }

    @Test fun `in grace with too few readings GracePeriod wins`() {
        val readings = listOf(
            aBodyWeightEntry(date = today.minusDays(5).toString(), kg = 80.0),
            aBodyWeightEntry(date = today.toString(), kg = 80.0),
        )
        assertIs<WeeklyVerdict.GracePeriod>(computeWeeklyVerdict(readings, 300, today))
    }

    // --- SMA endpoints (math-model §3.1, §4.2) ---

    @Test fun `sparse endpoint averages only the readings that exist`() {
        val readings = listOf(
            aBodyWeightEntry(date = today.minusDays(14).toString(), kg = 82.0),
            aBodyWeightEntry(date = today.minusDays(7).toString(), kg = 81.0),
        ) + (0..4).map { aBodyWeightEntry(date = today.minusDays(it.toLong()).toString(), kg = 80.0) }
        // smoothed(today−7) = mean(82.0, 81.0) = 81.5; smoothed(today) = 563/7 ≈ 80.4286
        val v = computeWeeklyVerdict(readings, 300, today)
        assertIs<WeeklyVerdict.AmberFast>(v)
        assertEquals(-1.0714, v.deltaKg, 0.001)
    }

    @Test fun `smoothing window is capped at 7 readings`() {
        // the 90.0 outlier is the 8th-oldest reading on or before today−7 — must be excluded
        val readings = listOf(aBodyWeightEntry(date = today.minusDays(14).toString(), kg = 90.0)) +
            (7..13).map { aBodyWeightEntry(date = today.minusDays(it.toLong()).toString(), kg = 82.0) } +
            (0..6).map { aBodyWeightEntry(date = today.minusDays(it.toLong()).toString(), kg = 80.0) }
        val v = computeWeeklyVerdict(readings, 300, today)
        assertIs<WeeklyVerdict.AmberFast>(v)
        assertEquals(-2.0, v.deltaKg, 0.0001)
    }

    @Test fun `input order does not matter`() {
        assertIs<WeeklyVerdict.Green>(computeWeeklyVerdict(twoPlateaus(80.3, 80.0).shuffled(), 300, today))
    }

    // --- Messages (spec §Messages) ---

    @Test fun `green message shows delta and on track`() {
        assertEquals("Down 0.3 kg this week — on track.", verdictMessage(WeeklyVerdict.Green(-0.3)))
    }

    @Test fun `flat green reads Flat not Down 0 kg`() {
        assertEquals("Flat this week — on track.", verdictMessage(WeeklyVerdict.Green(0.0)))
        assertEquals("Flat this week — on track.", verdictMessage(WeeklyVerdict.Green(-0.04)))
    }

    @Test fun `amber behind messages cover flat up and down`() {
        assertEquals("Flat this week — slightly behind.", verdictMessage(WeeklyVerdict.AmberBehind(0.0)))
        assertEquals("Up 0.2 kg this week — slightly behind.", verdictMessage(WeeklyVerdict.AmberBehind(0.2)))
        assertEquals("Down 0.1 kg this week — slightly behind.", verdictMessage(WeeklyVerdict.AmberBehind(-0.1)))
    }

    @Test fun `amber fast message warns about intake`() {
        assertEquals(
            "Down 0.8 kg this week — dropping quickly, watch your intake.",
            verdictMessage(WeeklyVerdict.AmberFast(-0.8)),
        )
    }

    @Test fun `delta rounds to one decimal`() {
        assertEquals("Down 0.9 kg this week — dropping quickly, watch your intake.",
            verdictMessage(WeeklyVerdict.AmberFast(-0.86)))
    }

    @Test fun `gate state messages`() {
        assertEquals("Building baseline — keep logging.", verdictMessage(WeeklyVerdict.GracePeriod))
        assertEquals("Not enough weigh-ins for a reliable verdict this week.", verdictMessage(WeeklyVerdict.NotEnoughData))
    }
}
