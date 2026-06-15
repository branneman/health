package org.branneman.health.ui

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.branneman.health.aShortcut
import org.branneman.health.db.HealthDatabase
import org.branneman.health.db.SyncStatus
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DrinkButtonsViewModelTest {

    private lateinit var db: HealthDatabase
    private val userId = "user-abc"
    private lateinit var viewModel: DrinkButtonsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HealthDatabase::class.java,
        ).allowMainThreadQueries().build()
        viewModel = DrinkButtonsViewModel(db)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `save inserts rows with PENDING_CREATE`() = runTest {
        val rows = listOf(
            aShortcut(userId = userId, emoji = "🍺", label = "Pils", kcal = 150, sortOrder = 0),
            aShortcut(userId = userId, emoji = "🍷", label = "Wine", kcal = 120, sortOrder = 1),
        )
        viewModel.save(rows, userId)
        val saved = db.shortcutDao().observeAll().first { it.size == 2 }
        assertEquals(2, saved.size)
        assertEquals(SyncStatus.PENDING_CREATE, saved.first().syncStatus)
    }

    @Test
    fun `save assigns contiguous sortOrder`() = runTest {
        val rows = listOf(
            aShortcut(userId = userId, emoji = "🍺", label = "Pils", kcal = 150, sortOrder = 99),
            aShortcut(userId = userId, emoji = "🍷", label = "Wine", kcal = 120, sortOrder = 99),
        )
        viewModel.save(rows, userId)
        val saved = db.shortcutDao().observeAll().first { it.size == 2 }
            .sortedBy { it.sortOrder }
        assertEquals(0, saved[0].sortOrder)
        assertEquals(1, saved[1].sortOrder)
    }
}
