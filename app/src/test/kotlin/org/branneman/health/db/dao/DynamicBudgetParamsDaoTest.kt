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
            eatingFractionSport = 0.833,
            eatingFractionNonSport = 0.875,
            postWorkoutModeSport = true,
            postWorkoutModeNonSport = false,
        )
        db.dynamicBudgetParamsDao().upsert(entity)
        val result = db.dynamicBudgetParamsDao().getForDate("2026-06-15")
        assertNotNull(result)
        assertEquals(2400, result.expectedTodaySport)
        assertEquals(2000, result.expectedTodayNonSport)
        assertEquals(0.833, result.eatingFractionSport)
        assertEquals(0.875, result.eatingFractionNonSport)
        assertEquals(true, result.postWorkoutModeSport)
        assertEquals(false, result.postWorkoutModeNonSport)
    }

    @Test fun `getForDate returns null for missing date`() = runTest {
        db.dynamicBudgetParamsDao().upsert(
            DynamicBudgetParamsEntity(
                date = "2026-06-15",
                expectedTodaySport = 2400,
                expectedTodayNonSport = null,
                eatingFractionSport = null,
                eatingFractionNonSport = null,
                postWorkoutModeSport = false,
                postWorkoutModeNonSport = false,
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
                eatingFractionSport = 0.8,
                eatingFractionNonSport = null,
                postWorkoutModeSport = false,
                postWorkoutModeNonSport = false,
            )
        )
        db.dynamicBudgetParamsDao().upsert(
            DynamicBudgetParamsEntity(
                date = "2026-06-15",
                expectedTodaySport = 2400,
                expectedTodayNonSport = null,
                eatingFractionSport = 0.875,
                eatingFractionNonSport = null,
                postWorkoutModeSport = true,
                postWorkoutModeNonSport = false,
            )
        )
        val result = db.dynamicBudgetParamsDao().getForDate("2026-06-15")
        assertNotNull(result)
        assertEquals(0.875, result.eatingFractionSport)
        assertEquals(true, result.postWorkoutModeSport)
    }

    @Test fun `nullable fields stored as null when no history`() = runTest {
        db.dynamicBudgetParamsDao().upsert(
            DynamicBudgetParamsEntity(
                date = "2026-06-15",
                expectedTodaySport = null,
                expectedTodayNonSport = null,
                eatingFractionSport = null,
                eatingFractionNonSport = null,
                postWorkoutModeSport = false,
                postWorkoutModeNonSport = false,
            )
        )
        val result = db.dynamicBudgetParamsDao().getForDate("2026-06-15")
        assertNotNull(result)
        assertNull(result.expectedTodaySport)
        assertNull(result.eatingFractionSport)
    }

    @Test fun `sport and non-sport fields are stored independently`() = runTest {
        db.dynamicBudgetParamsDao().upsert(
            DynamicBudgetParamsEntity(
                date = "2026-06-15",
                expectedTodaySport = 2400,
                expectedTodayNonSport = 2000,
                eatingFractionSport = 0.833,
                eatingFractionNonSport = 0.875,
                postWorkoutModeSport = true,
                postWorkoutModeNonSport = false,
            )
        )
        val result = db.dynamicBudgetParamsDao().getForDate("2026-06-15")
        assertNotNull(result)
        assertEquals(2400, result.expectedTodaySport)
        assertEquals(2000, result.expectedTodayNonSport)
        assertEquals(0.833, result.eatingFractionSport)
        assertEquals(0.875, result.eatingFractionNonSport)
        assertEquals(true, result.postWorkoutModeSport)
        assertEquals(false, result.postWorkoutModeNonSport)
    }
}
