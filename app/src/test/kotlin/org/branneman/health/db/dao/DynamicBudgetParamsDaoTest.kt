package org.branneman.health.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.branneman.health.db.HealthDatabase
import org.branneman.health.db.entities.DynamicBudgetParamsEntity
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
class DynamicBudgetParamsDaoTest {

    private lateinit var db: HealthDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HealthDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() { db.close() }

    @Test fun `upsert and getForDate returns entity with all fields`() = runTest {
        val entity = DynamicBudgetParamsEntity(
            date = "2026-06-15",
            expectedTodaySport = 2400,
            expectedTodayNonSport = 2000,
        )
        db.dynamicBudgetParamsDao().upsert(entity)
        val result = db.dynamicBudgetParamsDao().getForDate("2026-06-15")
        assertNotNull(result)
        assertEquals(2400, result.expectedTodaySport)
        assertEquals(2000, result.expectedTodayNonSport)
    }

    @Test fun `getForDate returns null for missing date`() = runTest {
        db.dynamicBudgetParamsDao().upsert(
            DynamicBudgetParamsEntity(
                date = "2026-06-15",
                expectedTodaySport = 2400,
                expectedTodayNonSport = null,
            )
        )
        assertNull(db.dynamicBudgetParamsDao().getForDate("2026-06-14"))
    }

    @Test fun `upsert replaces existing row for same date`() = runTest {
        db.dynamicBudgetParamsDao().upsert(
            DynamicBudgetParamsEntity(
                date = "2026-06-15",
                expectedTodaySport = 2400,
                expectedTodayNonSport = null,
            )
        )
        db.dynamicBudgetParamsDao().upsert(
            DynamicBudgetParamsEntity(
                date = "2026-06-15",
                expectedTodaySport = 2600,
                expectedTodayNonSport = 2000,
            )
        )
        val result = db.dynamicBudgetParamsDao().getForDate("2026-06-15")
        assertNotNull(result)
        assertEquals(2600, result.expectedTodaySport)
        assertEquals(2000, result.expectedTodayNonSport)
    }

    @Test fun `nullable fields stored as null when no history`() = runTest {
        db.dynamicBudgetParamsDao().upsert(
            DynamicBudgetParamsEntity(
                date = "2026-06-15",
                expectedTodaySport = null,
                expectedTodayNonSport = null,
            )
        )
        val result = db.dynamicBudgetParamsDao().getForDate("2026-06-15")
        assertNotNull(result)
        assertNull(result.expectedTodaySport)
        assertNull(result.expectedTodayNonSport)
    }

    @Test fun `sport and non-sport fields are stored independently`() = runTest {
        db.dynamicBudgetParamsDao().upsert(
            DynamicBudgetParamsEntity(
                date = "2026-06-15",
                expectedTodaySport = 2400,
                expectedTodayNonSport = 2000,
            )
        )
        val result = db.dynamicBudgetParamsDao().getForDate("2026-06-15")
        assertNotNull(result)
        assertEquals(2400, result.expectedTodaySport)
        assertEquals(2000, result.expectedTodayNonSport)
    }
}
