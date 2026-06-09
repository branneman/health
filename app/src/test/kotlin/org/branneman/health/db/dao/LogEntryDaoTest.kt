package org.branneman.health.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.branneman.health.aLogEntry
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
class LogEntryDaoTest {

    private lateinit var db: HealthDatabase
    private lateinit var dao: LogEntryDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HealthDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.logEntryDao()
    }

    @After fun tearDown() { db.close() }

    @Test
    fun `upsert and observeAll returns non-deleted entries`() = runTest {
        val userId = uuid()
        dao.upsert(aLogEntry(userId = userId, mealType = "breakfast"))
        val result = dao.observeAll().first()
        assertEquals(1, result.size)
        assertEquals("breakfast", result[0].mealType)
    }

    @Test
    fun `observeAll excludes PENDING_DELETE entries`() = runTest {
        val userId = uuid()
        val id = uuid()
        dao.upsert(aLogEntry(id = id, userId = userId, mealType = "lunch"))
        dao.updateSyncStatus(id, SyncStatus.PENDING_DELETE)
        assertTrue(dao.observeAll().first().isEmpty())
    }

    @Test
    fun `getByStatus returns only matching entries`() = runTest {
        val userId = uuid()
        dao.upsert(aLogEntry(userId = userId))
        dao.upsert(aLogEntry(userId = userId))
        val pending = dao.getByStatus(SyncStatus.PENDING_CREATE)
        assertEquals(2, pending.size)
    }

    @Test
    fun `deleteAllForUser removes only that user's entries`() = runTest {
        val userId = uuid()
        val otherId = uuid()
        dao.upsert(aLogEntry(userId = userId))
        dao.upsert(aLogEntry(userId = otherId))
        dao.deleteAllForUser(userId)
        assertEquals(1, dao.observeAll().first().size)
    }
}
