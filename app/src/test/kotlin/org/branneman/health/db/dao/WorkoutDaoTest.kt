package org.branneman.health.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.branneman.health.aWorkout
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
class WorkoutDaoTest {

    private lateinit var db: HealthDatabase
    private lateinit var dao: WorkoutDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HealthDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.workoutDao()
    }

    @After fun tearDown() { db.close() }

    @Test
    fun `upsertAll and observeAll returns entries in descending date order`() = runTest {
        val userId = uuid()
        dao.upsertAll(listOf(
            aWorkout(userId = userId, date = "2026-01-01"),
            aWorkout(userId = userId, date = "2026-01-03"),
        ))
        val result = dao.observeAll().first()
        assertEquals(2, result.size)
        assertEquals("2026-01-03", result[0].date)
        assertEquals("2026-01-01", result[1].date)
    }

    @Test
    fun `deleteAllForUser removes only that user's workouts`() = runTest {
        val userId = uuid()
        val otherId = uuid()
        dao.upsertAll(listOf(
            aWorkout(userId = userId, date = "2026-01-01"),
            aWorkout(userId = otherId, date = "2026-01-02"),
        ))
        dao.deleteAllForUser(userId)
        val remaining = dao.observeAll().first()
        assertEquals(1, remaining.size)
        assertEquals(otherId, remaining[0].userId)
    }
}
