package org.branneman.health.dashboard

import org.branneman.health.db.SyncStatus
import org.branneman.health.db.entities.LogEntryEntity
import org.branneman.health.uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DashboardLogicTest {

    // --- caloriesIn reactive filter rules ---

    @Test fun `caloriesIn sum includes only today's entries`() {
        val today = "2026-06-11"
        val entries = listOf(
            LogEntryEntity(id = uuid(), userId = "u1", loggedAt = "${today}T08:00:00Z",
                mealType = "unknown", quickAddKcal = 400, quickAddLabel = null,
                syncStatus = SyncStatus.PENDING_CREATE),
            LogEntryEntity(id = uuid(), userId = "u1", loggedAt = "2026-06-10T19:00:00Z",
                mealType = "unknown", quickAddKcal = 800, quickAddLabel = null,
                syncStatus = SyncStatus.SYNCED),
        )
        val sum = entries
            .filter { it.userId == "u1" && it.loggedAt.startsWith(today) }
            .sumOf { it.quickAddKcal ?: 0 }
        assertEquals(400, sum)
    }

    @Test fun `caloriesIn sum excludes PENDING_DELETE entries`() {
        val today = "2026-06-11"
        val entries = listOf(
            LogEntryEntity(id = uuid(), userId = "u1", loggedAt = "${today}T08:00:00Z",
                mealType = "unknown", quickAddKcal = 400, quickAddLabel = null,
                syncStatus = SyncStatus.SYNCED),
            LogEntryEntity(id = uuid(), userId = "u1", loggedAt = "${today}T12:00:00Z",
                mealType = "unknown", quickAddKcal = 600, quickAddLabel = null,
                syncStatus = SyncStatus.PENDING_DELETE),
        )
        val sum = entries
            .filter { it.userId == "u1" && it.loggedAt.startsWith(today) && it.syncStatus != SyncStatus.PENDING_DELETE }
            .sumOf { it.quickAddKcal ?: 0 }
        assertEquals(400, sum)
    }

    @Test fun `caloriesIn sum excludes entries with null quickAddKcal`() {
        val today = "2026-06-11"
        val entries = listOf(
            LogEntryEntity(id = uuid(), userId = "u1", loggedAt = "${today}T08:00:00Z",
                mealType = "unknown", quickAddKcal = 350, quickAddLabel = null,
                syncStatus = SyncStatus.PENDING_CREATE),
            LogEntryEntity(id = uuid(), userId = "u1", loggedAt = "${today}T12:00:00Z",
                mealType = "breakfast", quickAddKcal = null, quickAddLabel = null,
                syncStatus = SyncStatus.SYNCED),
        )
        val sum = entries
            .filter { it.userId == "u1" && it.loggedAt.startsWith(today) }
            .sumOf { it.quickAddKcal ?: 0 }
        assertEquals(350, sum)
    }

    // --- isValidWeightInput ---

    @Test fun `valid integer weight passes`() {
        assertTrue(isValidWeightInput("85"))
    }

    @Test fun `valid one-decimal weight passes`() {
        assertTrue(isValidWeightInput("85.5"))
    }

    @Test fun `explicitly one-decimal zero passes`() {
        assertTrue(isValidWeightInput("85.0"))
    }

    @Test fun `two decimal places fails`() {
        assertFalse(isValidWeightInput("85.24"))
    }

    @Test fun `below minimum fails`() {
        assertFalse(isValidWeightInput("19.9"))
    }

    @Test fun `minimum boundary passes`() {
        assertTrue(isValidWeightInput("20.0"))
    }

    @Test fun `maximum boundary passes`() {
        assertTrue(isValidWeightInput("300.0"))
    }

    @Test fun `above maximum fails`() {
        assertFalse(isValidWeightInput("300.1"))
    }

    @Test fun `non-numeric input fails`() {
        assertFalse(isValidWeightInput("abc"))
    }

    @Test fun `empty input fails`() {
        assertFalse(isValidWeightInput(""))
    }

    @Test fun `comma as decimal separator passes`() {
        assertTrue(isValidWeightInput("85,5"))
    }

    @Test fun `comma with two decimal places fails`() {
        assertFalse(isValidWeightInput("85,24"))
    }

    @Test fun `comma at minimum boundary passes`() {
        assertTrue(isValidWeightInput("20,0"))
    }

    // --- computeCaloriesLeft ---

    @Test fun `under budget returns positive`() {
        // caloriesOut = expected=2387 (actual=null, below threshold), D=300
        // caloriesLeft = 2387 - 300 - 1773 = 314
        assertEquals(314, computeCaloriesLeft(
            expectedToday = 2387, targetDeficit = 300,
            actualBurnedToday = null, caloriesIn = 1773,
        ))
    }

    @Test fun `on budget returns zero`() {
        // caloriesLeft = 2387 - 300 - 2087 = 0
        assertEquals(0, computeCaloriesLeft(
            expectedToday = 2387, targetDeficit = 300,
            actualBurnedToday = null, caloriesIn = 2087,
        ))
    }

    @Test fun `over budget returns negative`() {
        // caloriesLeft = 2387 - 300 - 2200 = -113
        assertEquals(-113, computeCaloriesLeft(
            expectedToday = 2387, targetDeficit = 300,
            actualBurnedToday = null, caloriesIn = 2200,
        ))
    }

    @Test fun `uses actual when actual is at least 90 percent of expected`() {
        // actual=2160 >= 2400*0.9=2160 → caloriesOut=2160
        // caloriesLeft = 2160 - 300 - 1500 = 360
        assertEquals(360, computeCaloriesLeft(
            expectedToday = 2400, targetDeficit = 300,
            actualBurnedToday = 2160, caloriesIn = 1500,
        ))
    }

    @Test fun `uses expected when actual is below 90 percent of expected`() {
        // actual=2159 < 2400*0.9=2160 → caloriesOut=2400
        // caloriesLeft = 2400 - 300 - 1500 = 600
        assertEquals(600, computeCaloriesLeft(
            expectedToday = 2400, targetDeficit = 300,
            actualBurnedToday = 2159, caloriesIn = 1500,
        ))
    }

    @Test fun `uses expected when actual is null`() {
        // actual=null → caloriesOut=2400
        // caloriesLeft = 2400 - 300 - 1500 = 600
        assertEquals(600, computeCaloriesLeft(
            expectedToday = 2400, targetDeficit = 300,
            actualBurnedToday = null, caloriesIn = 1500,
        ))
    }
}
