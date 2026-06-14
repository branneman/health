package org.branneman.health.log

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class DateFlowTest {

    private class TestClock(
        private var instant: Instant,
        private val zone: ZoneId = ZoneOffset.UTC,
    ) : Clock() {
        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = TestClock(instant, zone)
        override fun instant(): Instant = instant
        fun advanceTo(newInstant: Instant) { instant = newInstant }
    }

    @Test
    fun `emits today on start`() = runTest {
        val clock = TestClock(LocalDateTime.of(2026, 6, 15, 10, 0, 0).toInstant(ZoneOffset.UTC))
        val results = mutableListOf<LocalDate>()
        val job = launch { dateFlow(clock).collect { results.add(it) } }
        runCurrent() // initial emission, then suspends in delay until midnight
        assertEquals(LocalDate.of(2026, 6, 15), results.first())
        job.cancel()
    }

    @Test
    fun `emits new date after midnight`() = runTest {
        val zone = ZoneOffset.UTC
        val beforeMidnight = LocalDateTime.of(2026, 6, 15, 23, 59, 55).toInstant(zone)
        val afterMidnight  = LocalDateTime.of(2026, 6, 16,  0,  0,  1).toInstant(zone)

        val clock = TestClock(beforeMidnight, zone)
        val results = mutableListOf<LocalDate>()
        val job = launch { dateFlow(clock).collect { results.add(it) } }

        // Flow emits 2026-06-15 and suspends in delay(5000)
        runCurrent()
        assertEquals(listOf(LocalDate.of(2026, 6, 15)), results)

        // Advance clock past midnight, then unblock the 5-second delay
        clock.advanceTo(afterMidnight)
        advanceTimeBy(6_000)
        runCurrent()

        assertEquals(2, results.size)
        assertEquals(LocalDate.of(2026, 6, 16), results[1])

        job.cancel()
    }

    @Test
    fun `does not emit again within the same day`() = runTest {
        val zone = ZoneOffset.UTC
        val beforeMidnight = LocalDateTime.of(2026, 6, 15, 23, 59, 55).toInstant(zone)
        val afterMidnight  = LocalDateTime.of(2026, 6, 16,  0,  0,  1).toInstant(zone)

        val clock = TestClock(beforeMidnight, zone)
        val results = mutableListOf<LocalDate>()
        val job = launch { dateFlow(clock).collect { results.add(it) } }

        runCurrent()
        clock.advanceTo(afterMidnight)
        advanceTimeBy(6_000)
        runCurrent()

        assertEquals(2, results.size) // 2026-06-15, then 2026-06-16

        // Advance another 12 hours — next midnight is still ~12h away
        advanceTimeBy(12 * 60 * 60 * 1_000L)
        runCurrent()

        assertEquals(2, results.size) // no third emission

        job.cancel()
    }
}
