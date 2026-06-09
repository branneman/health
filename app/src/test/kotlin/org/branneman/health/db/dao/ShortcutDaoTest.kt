package org.branneman.health.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.branneman.health.aShortcut
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
class ShortcutDaoTest {

    private lateinit var db: HealthDatabase
    private lateinit var dao: ShortcutDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HealthDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.shortcutDao()
    }

    @After fun tearDown() { db.close() }

    @Test
    fun `upsertAll and observeAll returns in sort order`() = runTest {
        val userId = uuid()
        dao.upsertAll(listOf(
            aShortcut(userId = userId, label = "B", sortOrder = 1),
            aShortcut(userId = userId, label = "A", sortOrder = 0),
        ))
        val result = dao.observeAll().first()
        assertEquals(2, result.size)
        assertEquals("A", result[0].label)
        assertEquals("B", result[1].label)
    }

    @Test
    fun `deleteAllForUser removes only that user's shortcuts`() = runTest {
        val userId = uuid()
        val otherId = uuid()
        dao.upsertAll(listOf(
            aShortcut(userId = userId, label = "Mine"),
            aShortcut(userId = otherId, label = "Theirs"),
        ))
        dao.deleteAllForUser(userId)
        val remaining = dao.observeAll().first()
        assertEquals(1, remaining.size)
        assertEquals("Theirs", remaining[0].label)
    }
}
