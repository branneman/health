package org.branneman.health.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.branneman.health.aUserProfile
import org.branneman.health.uuid
import org.branneman.health.db.HealthDatabase
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UserProfileDaoTest {

    private lateinit var db: HealthDatabase
    private lateinit var dao: UserProfileDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HealthDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.userProfileDao()
    }

    @After fun tearDown() { db.close() }

    @Test
    fun `observe returns null when empty`() = runTest {
        assertNull(dao.observe().first())
    }

    @Test
    fun `upsert and get returns the profile`() = runTest {
        val userId = uuid()
        dao.upsert(aUserProfile(userId = userId))
        val result = dao.get()
        assertNotNull(result)
        assertEquals(userId, result.userId)
        assertEquals(177, result.heightCm)
    }

    @Test
    fun `upsert updates existing profile`() = runTest {
        val userId = uuid()
        dao.upsert(aUserProfile(userId = userId))
        dao.upsert(aUserProfile(userId = userId).copy(heightCm = 180))
        assertEquals(180, dao.get()?.heightCm)
    }

    @Test
    fun `deleteForUser removes the profile`() = runTest {
        val userId = uuid()
        dao.upsert(aUserProfile(userId = userId))
        dao.deleteForUser(userId)
        assertNull(dao.get())
    }

    @Test
    fun `existsFlow emits false when table is empty`() = runTest {
        assertFalse(dao.existsFlow().first())
    }

    @Test
    fun `existsFlow emits true after upsert`() = runTest {
        dao.upsert(aUserProfile(userId = uuid()))
        assertTrue(dao.existsFlow().first())
    }

    @Test
    fun `existsFlow emits true then false after delete`() = runTest {
        val userId = uuid()
        dao.upsert(aUserProfile(userId = userId))
        assertTrue(dao.existsFlow().first())
        dao.deleteForUser(userId)
        assertFalse(dao.existsFlow().first())
    }

    @Test
    fun `wakeTime and bedtime round-trip correctly`() = runTest {
        dao.upsert(aUserProfile(userId = uuid(), wakeTime = "06:30", bedtime = "22:00"))
        val result = dao.get()
        assertNotNull(result)
        assertEquals("06:30", result.wakeTime)
        assertEquals("22:00", result.bedtime)
    }
}
