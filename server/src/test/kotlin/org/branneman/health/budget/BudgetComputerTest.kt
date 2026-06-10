package org.branneman.health.budget

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
