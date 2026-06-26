package org.branneman.health.log

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.branneman.health.aQuickAddEntry
import org.branneman.health.aShortcut
import org.branneman.health.auth.TokenStore
import org.branneman.health.db.HealthDatabase
import org.branneman.health.db.SyncStatus
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.OffsetDateTime
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LogViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var db: HealthDatabase
    private lateinit var tokenStore: TokenStore
    private lateinit var viewModel: LogViewModel

    private val userId = "test-user-id"

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HealthDatabase::class.java,
        ).allowMainThreadQueries().build()
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(testDispatcher),
            produceFile = {
                File.createTempFile("test_auth_logvm", ".preferences_pb").also { it.deleteOnExit() }
            },
        )
        tokenStore = TokenStore(dataStore)
        viewModel = LogViewModel(db, tokenStore)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `shortcuts StateFlow emits shortcuts from Room`() = runTest {
        val shortcut = aShortcut(userId = userId, label = "Pils", sortOrder = 0)
        db.shortcutDao().upsertAll(listOf(shortcut))
        val result = viewModel.shortcuts.first { it.isNotEmpty() }
        assertEquals(1, result.size)
        assertEquals("Pils", result.first().label)
    }

    @Test
    fun `logFromShortcut creates a log entry with emoji+label and kcal`() = runTest {
        val farFuture = OffsetDateTime.now().plusDays(30).toString()
        tokenStore.save("test-token", farFuture, userId)
        val shortcut = aShortcut(userId = userId, emoji = "🍺", label = "Pils", kcal = 150)
        viewModel.logFromShortcut(shortcut)
        val entries = db.logEntryDao().observeAll().first { it.isNotEmpty() }
        val entry = entries.single()
        assertEquals(150, entry.quickAddKcal)
        assertEquals("🍺 Pils", entry.quickAddLabel)
    }

    @Test
    fun `editEntry updates kcal and label in Room`() = runTest {
        val farFuture = OffsetDateTime.now().plusDays(30).toString()
        tokenStore.save("test-token", farFuture, userId)

        val entry = aQuickAddEntry(
            userId       = userId,
            quickAddKcal = 400,
            quickAddLabel = "old",
            syncStatus   = SyncStatus.SYNCED,
        )
        db.logEntryDao().upsert(entry)

        viewModel.editEntry(entry, 600, "new label")
        testDispatcher.scheduler.advanceUntilIdle()

        val updated = db.logEntryDao().observeAll().first().single()
        assertEquals(600, updated.quickAddKcal)
        assertEquals("new label", updated.quickAddLabel)
        assertEquals(SyncStatus.PENDING_UPDATE, updated.syncStatus)
    }

    @Test
    fun `logSingleItem creates a log entry with food name and computed kcal`() = runTest {
        val farFuture = OffsetDateTime.now().plusDays(30).toString()
        tokenStore.save("test-token", farFuture, userId)

        viewModel.logSingleItem("Apple", 85)
        testDispatcher.scheduler.advanceUntilIdle()

        val entries = db.logEntryDao().observeAll().first { it.isNotEmpty() }
        val entry = entries.single()
        assertEquals(85, entry.quickAddKcal)
        assertEquals("Apple", entry.quickAddLabel)
        assertEquals("unknown", entry.mealType)
        assertEquals(SyncStatus.PENDING_CREATE, entry.syncStatus)
    }
}
