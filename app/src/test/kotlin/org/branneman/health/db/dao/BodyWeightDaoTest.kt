package org.branneman.health.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.branneman.health.aBodyWeightEntry
import org.branneman.health.uuid
import org.branneman.health.db.HealthDatabase
import org.branneman.health.db.SyncStatus
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BodyWeightDaoTest {

    private lateinit var db: HealthDatabase
    private lateinit var dao: BodyWeightDao
    private val userId = uuid()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HealthDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.bodyWeightDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `upsert and observe entries`() = runTest {
        val entry = aBodyWeightEntry(userId = userId, date = "2026-06-01", kg = 82.5)
        dao.upsert(entry)
        val result = dao.observeAll().first()
        assertEquals(1, result.size)
        assertEquals(82.5, result[0].kg)
    }

    @Test
    fun `getByStatus returns only PENDING_CREATE entries`() = runTest {
        val id1 = uuid()
        dao.upsert(aBodyWeightEntry(id = id1, userId = userId, date = "2026-06-01", kg = 82.0, syncStatus = SyncStatus.PENDING_CREATE))
        dao.upsert(aBodyWeightEntry(userId = userId, date = "2026-06-02", kg = 81.9, syncStatus = SyncStatus.SYNCED))
        val pending = dao.getByStatus(SyncStatus.PENDING_CREATE)
        assertEquals(1, pending.size)
        assertEquals(id1, pending[0].id)
    }

    @Test
    fun `deleteAllForUser clears all entries`() = runTest {
        dao.upsert(aBodyWeightEntry(userId = userId, date = "2026-06-01", kg = 80.0))
        dao.upsert(aBodyWeightEntry(userId = userId, date = "2026-06-02", kg = 79.5))
        dao.deleteAllForUser(userId)
        assertTrue(dao.observeAll().first().isEmpty())
    }
}
