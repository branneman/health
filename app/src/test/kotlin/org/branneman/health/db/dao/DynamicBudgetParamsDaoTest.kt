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
        val entity = DynamicBudgetParamsEntity(date = "2026-06-15", expectedToday = 2200)
        db.dynamicBudgetParamsDao().upsert(entity)
        val result = db.dynamicBudgetParamsDao().getForDate("2026-06-15")
        assertNotNull(result)
        assertEquals(2200, result.expectedToday)
    }

    @Test fun `getForDate returns null for missing date`() = runTest {
        db.dynamicBudgetParamsDao().upsert(
            DynamicBudgetParamsEntity(date = "2026-06-15", expectedToday = 2200)
        )
        assertNull(db.dynamicBudgetParamsDao().getForDate("2026-06-14"))
    }

    @Test fun `upsert replaces existing row for same date`() = runTest {
        db.dynamicBudgetParamsDao().upsert(
            DynamicBudgetParamsEntity(date = "2026-06-15", expectedToday = 2400)
        )
        db.dynamicBudgetParamsDao().upsert(
            DynamicBudgetParamsEntity(date = "2026-06-15", expectedToday = 2000)
        )
        val result = db.dynamicBudgetParamsDao().getForDate("2026-06-15")
        assertNotNull(result)
        assertEquals(2000, result.expectedToday)
    }

    @Test fun `nullable field stored as null when no history`() = runTest {
        db.dynamicBudgetParamsDao().upsert(
            DynamicBudgetParamsEntity(date = "2026-06-15", expectedToday = null)
        )
        val result = db.dynamicBudgetParamsDao().getForDate("2026-06-15")
        assertNotNull(result)
        assertNull(result.expectedToday)
    }
}
