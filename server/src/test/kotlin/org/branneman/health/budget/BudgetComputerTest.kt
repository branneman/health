package org.branneman.health.budget

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BudgetComputerTest {

    private val today = LocalDate.of(2026, 6, 10)

    private val profile = UserProfileInput(
        heightCm      = 177,
        birthYear     = 1986,
        sex           = "male",
        activityLevel = "lightly_active",
        targetDeficit = 300,
        goalWeightKg  = 74.0,
    )

    // --- BMR ---

    @Test fun `computeBmr male 84kg 177cm age 40`() {
        // 10×84 + 6.25×177 − 5×40 + 5 = 840 + 1106.25 − 200 + 5 = 1751.25
        assertEquals(1751.25, computeBmr("male", 84.0, 177, 40), 0.01)
    }

    @Test fun `computeBmr female uses minus161 constant`() {
        // 10×70 + 6.25×165 − 5×35 − 161 = 700 + 1031.25 − 175 − 161 = 1395.25
        assertEquals(1395.25, computeBmr("female", 70.0, 165, 35), 0.01)
    }

    // --- Activity multiplier ---

    @Test fun `activityMultiplier sedentary`() =
        assertEquals(1.20, activityMultiplier("sedentary"), 0.001)

    @Test fun `activityMultiplier lightly_active`() =
        assertEquals(1.375, activityMultiplier("lightly_active"), 0.001)

    @Test fun `activityMultiplier moderately_active`() =
        assertEquals(1.55, activityMultiplier("moderately_active"), 0.001)

    @Test fun `activityMultiplier unknown defaults to lightly_active`() =
        assertEquals(1.375, activityMultiplier("unknown"), 0.001)

    // --- caloriesOut source priority ---

    @Test fun `uses today Polar when available`() {
        val energy = listOf(
            EnergyRow(today, 2300),
            EnergyRow(today.minusDays(1), 2100),
        )
        val result = BudgetComputer.compute(today, profile, 84.0, energy, 0)
        assertEquals(2300, result.caloriesOut)
        assertEquals("polar_today", result.caloriesOutSource)
    }

    @Test fun `falls back to yesterday Polar when today absent`() {
        val energy = listOf(EnergyRow(today.minusDays(1), 2100))
        val result = BudgetComputer.compute(today, profile, 84.0, energy, 0)
        assertEquals(2100, result.caloriesOut)
        assertEquals("polar_yesterday", result.caloriesOutSource)
    }

    @Test fun `falls back to estimate when no Polar rows`() {
        val result = BudgetComputer.compute(today, profile, 84.0, emptyList(), 0)
        assertEquals("estimate", result.caloriesOutSource)
        assertEquals((computeBmr("male", 84.0, 177, 40) * 1.375).toInt(), result.caloriesOut)
    }

    @Test fun `uses goalWeightKg when no body weight provided`() {
        val result = BudgetComputer.compute(today, profile, null, emptyList(), 0)
        val expected = (computeBmr("male", 74.0, 177, 40) * 1.375).toInt()
        assertEquals(expected, result.caloriesOut)
        assertEquals("estimate", result.caloriesOutSource)
    }

    // --- budgetRemaining ---

    @Test fun `budgetRemaining = caloriesOut minus deficit minus caloriesIn`() {
        val energy = listOf(EnergyRow(today, 2300))
        val result = BudgetComputer.compute(today, profile, 84.0, energy, 850)
        // 2300 − 300 − 850 = 1150
        assertEquals(1150, result.budgetRemaining)
        assertEquals(850, result.caloriesIn)
        assertEquals(300, result.targetDeficit)
    }

    @Test fun `budgetRemaining is negative when over budget`() {
        val energy = listOf(EnergyRow(today, 2000))
        val result = BudgetComputer.compute(today, profile, 84.0, energy, 1800)
        // 2000 − 300 − 1800 = −100
        assertEquals(-100, result.budgetRemaining)
    }

    // --- computeDynamic ---

    private fun day(i: Int, out: Int, isSport: Boolean, caloriesIn: Int? = null) =
        HistoricalDay(date = today.minusDays(i.toLong()), caloriesOut = out, caloriesIn = caloriesIn, isSportDay = isSport)

    @Test fun `computeDynamic - no history returns null expected and fraction`() {
        val r = BudgetComputer.computeDynamic(emptyList(), targetDeficit = 300, actualBurnedToday = null)
        assertNull(r.expectedTodaySport)
        assertNull(r.expectedTodayNonSport)
        assertNull(r.eatingFractionSport)
        assertNull(r.eatingFractionNonSport)
        assertFalse(r.postWorkoutModeSport)
        assertFalse(r.postWorkoutModeNonSport)
    }

    @Test fun `computeDynamic - fewer than 5 logged sport days uses Approach 1`() {
        // 4 sport days, none food-logged → Approach 1: (expected - D) / expected
        val history = (1..4).map { day(it, out = 2400, isSport = true, caloriesIn = null) }
        val r = BudgetComputer.computeDynamic(history, targetDeficit = 300, actualBurnedToday = null)
        assertEquals(2400, r.expectedTodaySport)
        // (2400 - 300) / 2400 = 0.875
        assertEquals(0.875, r.eatingFractionSport!!, 0.001)
    }

    @Test fun `computeDynamic - 5 logged sport days uses Approach 2`() {
        val history = (1..5).map { day(it, out = 2400, isSport = true, caloriesIn = 2000) }
        val r = BudgetComputer.computeDynamic(history, targetDeficit = 300, actualBurnedToday = null)
        // avg_in / avg_out = 2000 / 2400
        assertEquals(2000.0 / 2400.0, r.eatingFractionSport!!, 0.001)
    }

    @Test fun `computeDynamic - 10 qualifying sport days uses Approach 3`() {
        // qualifying threshold = 2400 - 300 + 100 = 2200; 2100 qualifies, 2500 does not
        val qualifying    = (1..10).map { day(it,     out = 2400, isSport = true, caloriesIn = 2100) }
        val nonQualifying = (11..12).map { day(it,    out = 2400, isSport = true, caloriesIn = 2500) }
        val r = BudgetComputer.computeDynamic(qualifying + nonQualifying, 300, null)
        // avg of qualifying only → 2100 / 2400
        assertEquals(2100.0 / 2400.0, r.eatingFractionSport!!, 0.001)
    }

    @Test fun `computeDynamic - sport and non-sport buckets are independent`() {
        val sportDays    = (1..10).map { day(it,     out = 2400, isSport = true,  caloriesIn = 2100) }
        val nonSportDays = (11..15).map { day(it,    out = 2000, isSport = false, caloriesIn = 1700) }
        val r = BudgetComputer.computeDynamic(sportDays + nonSportDays, 300, null)
        // Sport: Approach 3 → 2100/2400
        assertEquals(2100.0 / 2400.0, r.eatingFractionSport!!, 0.001)
        // Non-sport: Approach 2 (5 logged, threshold 2000-300+100=1800 → none qualify from 5) → 1700/2000
        assertEquals(1700.0 / 2000.0, r.eatingFractionNonSport!!, 0.001)
    }

    @Test fun `computeDynamic - post-workout triggers at exactly 90 percent of expected`() {
        val history = (1..5).map { day(it, out = 2400, isSport = true, caloriesIn = 2000) }
        val r = BudgetComputer.computeDynamic(history, 300, actualBurnedToday = 2160) // 2400 * 0.9
        assertTrue(r.postWorkoutModeSport)
    }

    @Test fun `computeDynamic - post-workout does not trigger at 89 percent`() {
        val history = (1..5).map { day(it, out = 2400, isSport = true, caloriesIn = 2000) }
        val r = BudgetComputer.computeDynamic(history, 300, actualBurnedToday = 2159)
        assertFalse(r.postWorkoutModeSport)
    }

    @Test fun `computeDynamic - post-workout stays off when actualBurnedToday is null`() {
        val history = (1..5).map { day(it, out = 2400, isSport = true, caloriesIn = 2000) }
        val r = BudgetComputer.computeDynamic(history, 300, actualBurnedToday = null)
        assertFalse(r.postWorkoutModeSport)
        assertFalse(r.postWorkoutModeNonSport)
    }

    @Test fun `computeDynamic - non-sport expected is independent of sport history`() {
        val sportDays = (1..10).map { day(it, out = 2400, isSport = true) }
        val r = BudgetComputer.computeDynamic(sportDays, 300, null)
        // 10 sport days → sport expected = 2400; no non-sport days → null
        assertEquals(2400, r.expectedTodaySport)
        assertNull(r.expectedTodayNonSport)
    }
}
