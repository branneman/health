package org.branneman.health.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.branneman.health.aLogEntry
import org.branneman.health.aQuickAddEntry
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
    fun `deleteById removes the entry`() = runTest {
        val userId = uuid()
        val id = uuid()
        dao.upsert(aLogEntry(id = id, userId = userId))
        assertEquals(1, dao.observeAll().first().size)

        dao.deleteById(id)

        assertTrue(dao.observeAll().first().isEmpty())
    }

    @Test
    fun `sumQuickAddKcalForDate only counts today`() = runTest {
        val userId = uuid()
        dao.upsert(aQuickAddEntry(userId = userId, loggedAt = "2026-06-11T08:00:00Z", quickAddKcal = 400))
        dao.upsert(aQuickAddEntry(userId = userId, loggedAt = "2026-06-10T19:00:00Z", quickAddKcal = 800))

        val sum = dao.sumQuickAddKcalForDate(userId, "2026-06-11%")

        assertEquals(400, sum)
    }

    @Test
    fun `sumQuickAddKcalForDate excludes entries with null quickAddKcal`() = runTest {
        val userId = uuid()
        dao.upsert(aQuickAddEntry(userId = userId, loggedAt = "2026-06-11T08:00:00Z", quickAddKcal = 350))
        dao.upsert(aLogEntry(userId = userId, loggedAt = "2026-06-11T12:00:00Z"))

        val sum = dao.sumQuickAddKcalForDate(userId, "2026-06-11%")

        assertEquals(350, sum)
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

    @Test
    fun `updateQuickAdd sets new kcal and label and marks PENDING_UPDATE`() = runTest {
        val entry = aQuickAddEntry(quickAddKcal = 400, quickAddLabel = "old label",
                                   syncStatus = SyncStatus.SYNCED)
        dao.upsert(entry)

        dao.updateQuickAdd(entry.id, 600, "new label")

        val updated = dao.observeAll().first().single()
        assertEquals(600, updated.quickAddKcal)
        assertEquals("new label", updated.quickAddLabel)
        assertEquals(SyncStatus.PENDING_UPDATE, updated.syncStatus)
    }

    @Test
    fun `updateQuickAdd sets null label when label is null`() = runTest {
        val entry = aQuickAddEntry(quickAddKcal = 300, quickAddLabel = "had label",
                                   syncStatus = SyncStatus.SYNCED)
        dao.upsert(entry)

        dao.updateQuickAdd(entry.id, 300, null)

        val updated = dao.observeAll().first().single()
        assertEquals(300, updated.quickAddKcal)
        kotlin.test.assertNull(updated.quickAddLabel)
        assertEquals(SyncStatus.PENDING_UPDATE, updated.syncStatus)
    }
}
