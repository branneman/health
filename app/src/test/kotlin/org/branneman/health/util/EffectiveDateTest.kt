package org.branneman.health.util

import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.assertEquals

class EffectiveDateTest {

    @Test fun `midnight returns previous day`() {
        assertEquals(
            LocalDate.of(2026, 6, 27),
            effectiveDate(LocalDateTime.of(2026, 6, 28, 0, 0))
        )
    }

    @Test fun `03h59 returns previous day`() {
        assertEquals(
            LocalDate.of(2026, 6, 27),
            effectiveDate(LocalDateTime.of(2026, 6, 28, 3, 59))
        )
    }

    @Test fun `04h00 returns current day`() {
        assertEquals(
            LocalDate.of(2026, 6, 28),
            effectiveDate(LocalDateTime.of(2026, 6, 28, 4, 0))
        )
    }

    @Test fun `noon returns current day`() {
        assertEquals(
            LocalDate.of(2026, 6, 28),
            effectiveDate(LocalDateTime.of(2026, 6, 28, 12, 0))
        )
    }
}
