package org.branneman.health.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.branneman.health.db.HealthDatabase
import org.branneman.health.db.entities.SportTonightEntity
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SportTonightDaoTest {

    private lateinit var db: HealthDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HealthDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() { db.close() }

    @Test fun `upsert and getForDate returns entity`() = runTest {
        db.sportTonightDao().upsert(
            SportTonightEntity(date = "2026-06-10", activityType = "climbing", intensity = "normal", estimatedKcal = 600)
        )
        val result = db.sportTonightDao().getForDate("2026-06-10")
        assertNotNull(result)
        assertEquals("climbing", result.activityType)
        assertEquals("normal", result.intensity)
        assertEquals(600, result.estimatedKcal)
    }

    @Test fun `getForDate returns null for different date`() = runTest {
        db.sportTonightDao().upsert(
            SportTonightEntity(date = "2026-06-10", activityType = "climbing", intensity = "normal", estimatedKcal = 600)
        )
        assertNull(db.sportTonightDao().getForDate("2026-06-09"))
    }

    @Test fun `upsert replaces existing row for same date`() = runTest {
        db.sportTonightDao().upsert(
            SportTonightEntity(date = "2026-06-10", activityType = "climbing", intensity = "light", estimatedKcal = 400)
        )
        db.sportTonightDao().upsert(
            SportTonightEntity(date = "2026-06-10", activityType = "climbing", intensity = "hard", estimatedKcal = 780)
        )
        val result = db.sportTonightDao().getForDate("2026-06-10")
        assertNotNull(result)
        assertEquals("hard", result.intensity)
        assertEquals(780, result.estimatedKcal)
    }

    @Test fun `deleteForDate removes entry`() = runTest {
        db.sportTonightDao().upsert(
            SportTonightEntity(date = "2026-06-10", activityType = "rowing", intensity = "normal", estimatedKcal = 600)
        )
        db.sportTonightDao().deleteForDate("2026-06-10")
        assertNull(db.sportTonightDao().getForDate("2026-06-10"))
    }
}
