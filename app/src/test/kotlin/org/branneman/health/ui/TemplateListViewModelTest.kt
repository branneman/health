package org.branneman.health.ui

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
import org.branneman.health.aMealTemplate
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TemplateListViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var db: HealthDatabase
    private lateinit var tokenStore: TokenStore
    private lateinit var viewModel: TemplateListViewModel
    private val userId = "test-user-id"

    @Before fun setUp() {
        Dispatchers.setMain(testDispatcher)
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), HealthDatabase::class.java,
        ).allowMainThreadQueries().build()
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(testDispatcher),
            produceFile = {
                File.createTempFile("test_auth_templatelistvm", ".preferences_pb").also { it.deleteOnExit() }
            },
        )
        tokenStore = TokenStore(dataStore)
        viewModel = TemplateListViewModel(db, tokenStore)
    }

    @After fun tearDown() { db.close(); Dispatchers.resetMain() }

    @Test fun `pinned templates appear before unpinned regardless of insertion order`() = runTest {
        db.mealTemplateDao().upsert(aMealTemplate(userId = userId, name = "Zucchini",  quickAddKcal = 300, sortOrder = null, syncStatus = SyncStatus.SYNCED))
        db.mealTemplateDao().upsert(aMealTemplate(userId = userId, name = "Breakfast", quickAddKcal = 450, sortOrder = 0,    syncStatus = SyncStatus.SYNCED))
        db.mealTemplateDao().upsert(aMealTemplate(userId = userId, name = "Lunch",     quickAddKcal = 600, sortOrder = 1,    syncStatus = SyncStatus.SYNCED))
        val result = viewModel.templates.first { it.size == 3 }
        assertEquals("Breakfast", result[0].name)
        assertEquals("Lunch",     result[1].name)
        assertEquals("Zucchini",  result[2].name)
    }

    @Test fun `unpinned templates are alphabetical`() = runTest {
        db.mealTemplateDao().upsert(aMealTemplate(userId = userId, name = "Zucchini",  quickAddKcal = 300, syncStatus = SyncStatus.SYNCED))
        db.mealTemplateDao().upsert(aMealTemplate(userId = userId, name = "Apple",     quickAddKcal = 100, syncStatus = SyncStatus.SYNCED))
        db.mealTemplateDao().upsert(aMealTemplate(userId = userId, name = "Bolognese", quickAddKcal = 700, syncStatus = SyncStatus.SYNCED))
        val result = viewModel.templates.first { it.size == 3 }
        assertEquals("Apple",     result[0].name)
        assertEquals("Bolognese", result[1].name)
        assertEquals("Zucchini",  result[2].name)
    }

    @Test fun `templates with null quickAddKcal are excluded`() = runTest {
        db.mealTemplateDao().upsert(aMealTemplate(userId = userId, name = "Has kcal", quickAddKcal = 500, syncStatus = SyncStatus.SYNCED))
        db.mealTemplateDao().upsert(aMealTemplate(userId = userId, name = "No kcal",  quickAddKcal = null, syncStatus = SyncStatus.SYNCED))
        val result = viewModel.templates.first { it.isNotEmpty() }
        assertEquals(1, result.size)
        assertEquals("Has kcal", result.single().name)
    }

    @Test fun `logFromTemplate writes entry with multiplied kcal and template name as label`() = runTest {
        val farFuture = OffsetDateTime.now().plusDays(30).toString()
        tokenStore.save("token", farFuture, userId)
        val template = aMealTemplate(userId = userId, name = "Pasta", quickAddKcal = 500, syncStatus = SyncStatus.SYNCED)
        db.mealTemplateDao().upsert(template)
        viewModel.logFromTemplate(template, 1.2f, OffsetDateTime.now().toString())
        val entries = db.logEntryDao().observeAll().first { it.isNotEmpty() }
        assertEquals(600, entries.single().quickAddKcal) // round(500 * 1.2) = 600
        assertEquals("Pasta", entries.single().quickAddLabel)
    }

    @Test fun `logFromTemplate rounds fractional kcal`() = runTest {
        val farFuture = OffsetDateTime.now().plusDays(30).toString()
        tokenStore.save("token", farFuture, userId)
        val template = aMealTemplate(userId = userId, name = "Soup", quickAddKcal = 100, syncStatus = SyncStatus.SYNCED)
        db.mealTemplateDao().upsert(template)
        viewModel.logFromTemplate(template, 0.8f, OffsetDateTime.now().toString())
        val entries = db.logEntryDao().observeAll().first { it.isNotEmpty() }
        assertEquals(80, entries.single().quickAddKcal) // round(100 * 0.8) = 80
    }

    @Test fun `undoLog removes last logged entry`() = runTest {
        val farFuture = OffsetDateTime.now().plusDays(30).toString()
        tokenStore.save("token", farFuture, userId)
        val template = aMealTemplate(userId = userId, name = "Stir-fry", quickAddKcal = 620, syncStatus = SyncStatus.SYNCED)
        db.mealTemplateDao().upsert(template)
        viewModel.logFromTemplate(template, 1.0f, OffsetDateTime.now().toString())
        db.logEntryDao().observeAll().first { it.isNotEmpty() }
        viewModel.undoLog()
        assertTrue(db.logEntryDao().observeAll().first { it.isEmpty() }.isEmpty())
    }
}
