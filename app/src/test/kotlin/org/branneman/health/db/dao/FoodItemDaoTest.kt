package org.branneman.health.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.branneman.health.aFoodItem
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
class FoodItemDaoTest {

    private lateinit var db: HealthDatabase
    private lateinit var dao: FoodItemDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HealthDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.foodItemDao()
    }

    @After fun tearDown() { db.close() }

    @Test
    fun `upsertAll and getByStatus returns SYNCED items`() = runTest {
        val userId = uuid()
        dao.upsertAll(listOf(aFoodItem(userId = userId, name = "Banana")))
        val result = dao.getByStatus(SyncStatus.SYNCED)
        assertEquals(1, result.size)
        assertEquals("Banana", result[0].name)
    }

    @Test
    fun `updateSyncStatus changes the status`() = runTest {
        val userId = uuid()
        val id = uuid()
        dao.upsertAll(listOf(aFoodItem(id = id, userId = userId)))
        dao.updateSyncStatus(id, SyncStatus.PENDING_DELETE)
        assertTrue(dao.getByStatus(SyncStatus.SYNCED).isEmpty())
        assertEquals(1, dao.getByStatus(SyncStatus.PENDING_DELETE).size)
    }

    @Test
    fun `deleteAllForUser removes only that user's items`() = runTest {
        val userId = uuid()
        val otherId = uuid()
        dao.upsertAll(listOf(
            aFoodItem(userId = userId, name = "Mine"),
            aFoodItem(userId = otherId, name = "Theirs"),
        ))
        dao.deleteAllForUser(userId)
        val remaining = dao.getByStatus(SyncStatus.SYNCED)
        assertEquals(1, remaining.size)
        assertEquals("Theirs", remaining[0].name)
    }
}
