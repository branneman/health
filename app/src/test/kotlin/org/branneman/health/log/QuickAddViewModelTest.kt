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
import org.branneman.health.auth.TokenStore
import org.branneman.health.db.HealthDatabase
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class QuickAddViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var db: HealthDatabase
    private lateinit var tokenStore: TokenStore
    private lateinit var viewModel: QuickAddViewModel
    private val userId = "test-user-id"

    @Before fun setUp() {
        Dispatchers.setMain(testDispatcher)
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), HealthDatabase::class.java,
        ).allowMainThreadQueries().build()
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(testDispatcher),
            produceFile = {
                File.createTempFile("test_auth_quickadd", ".preferences_pb").also { it.deleteOnExit() }
            },
        )
        tokenStore = TokenStore(dataStore)
        viewModel = QuickAddViewModel(db, tokenStore)
    }

    @After fun tearDown() { db.close(); Dispatchers.resetMain() }

    @Test fun `logQuickAdd writes entry with kcal and null label when blank`() = runTest {
        val farFuture = OffsetDateTime.now().plusDays(30).toString()
        tokenStore.save("token", farFuture, userId)
        viewModel.logQuickAdd(500, null, OffsetDateTime.now().toString())
        val entries = db.logEntryDao().observeAll().first { it.isNotEmpty() }
        assertEquals(500, entries.single().quickAddKcal)
        assertNull(entries.single().quickAddLabel)
    }

    @Test fun `logQuickAdd writes entry with label when provided`() = runTest {
        val farFuture = OffsetDateTime.now().plusDays(30).toString()
        tokenStore.save("token", farFuture, userId)
        viewModel.logQuickAdd(800, "Pasta at work", OffsetDateTime.now().toString())
        val entries = db.logEntryDao().observeAll().first { it.isNotEmpty() }
        assertEquals(800, entries.single().quickAddKcal)
        assertEquals("Pasta at work", entries.single().quickAddLabel)
    }

    @Test fun `undoLog removes last logged entry`() = runTest {
        val farFuture = OffsetDateTime.now().plusDays(30).toString()
        tokenStore.save("token", farFuture, userId)
        viewModel.logQuickAdd(400, "Snack", OffsetDateTime.now().toString())
        db.logEntryDao().observeAll().first { it.isNotEmpty() }
        viewModel.undoLog()
        assertTrue(db.logEntryDao().observeAll().first { it.isEmpty() }.isEmpty())
    }
}
