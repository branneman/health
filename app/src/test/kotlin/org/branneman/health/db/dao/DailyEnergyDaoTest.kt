package org.branneman.health.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.branneman.health.aDailyEnergy
import org.branneman.health.uuid
import org.branneman.health.db.HealthDatabase
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DailyEnergyDaoTest {

    private lateinit var db: HealthDatabase
    private lateinit var dao: DailyEnergyDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HealthDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.dailyEnergyDao()
    }

    @After fun tearDown() { db.close() }

    @Test
    fun `upsertAll and observeAll`() = runTest {
        val userId = uuid()
        dao.upsertAll(listOf(aDailyEnergy(userId = userId, date = "2026-01-01", totalKcal = 2200)))
        val result = dao.observeAll().first()
        assertEquals(1, result.size)
        assertEquals(2200, result[0].totalKcal)
    }

    @Test
    fun `upsertAll updates existing entry`() = runTest {
        val userId = uuid()
        dao.upsertAll(listOf(aDailyEnergy(userId = userId, date = "2026-01-01", totalKcal = 2200)))
        dao.upsertAll(listOf(aDailyEnergy(userId = userId, date = "2026-01-01", totalKcal = 2400)))
        val result = dao.observeAll().first()
        assertEquals(1, result.size)
        assertEquals(2400, result[0].totalKcal)
    }

    @Test
    fun `deleteAllForUser removes only that user's entries`() = runTest {
        val userId = uuid()
        val otherId = uuid()
        dao.upsertAll(listOf(
            aDailyEnergy(userId = userId, date = "2026-01-01"),
            aDailyEnergy(userId = otherId, date = "2026-01-02"),
        ))
        dao.deleteAllForUser(userId)
        val remaining = dao.observeAll().first()
        assertEquals(1, remaining.size)
        assertEquals(otherId, remaining[0].userId)
    }
}
