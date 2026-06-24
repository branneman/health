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
class TemplatesViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var db: HealthDatabase
    private lateinit var tokenStore: TokenStore
    private lateinit var viewModel: TemplatesViewModel
    private val userId = "test-user-id"

    @Before fun setUp() {
        Dispatchers.setMain(testDispatcher)
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), HealthDatabase::class.java,
        ).allowMainThreadQueries().build()
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(testDispatcher),
            produceFile = {
                File.createTempFile("test_auth_templatesvm", ".preferences_pb").also { it.deleteOnExit() }
            },
        )
        tokenStore = TokenStore(dataStore)
        viewModel = TemplatesViewModel(db, tokenStore)
    }

    @After fun tearDown() { db.close(); Dispatchers.resetMain() }

    @Test fun `create inserts template with PENDING_CREATE and no sortOrder`() = runTest {
        val farFuture = OffsetDateTime.now().plusDays(30).toString()
        tokenStore.save("token", farFuture, userId)
        viewModel.create("Chicken stir-fry", 620)
        val templates = db.mealTemplateDao().observeAll().first { it.isNotEmpty() }
        val t = templates.single()
        assertEquals("Chicken stir-fry", t.name)
        assertEquals(620, t.quickAddKcal)
        assertEquals(SyncStatus.PENDING_CREATE, t.syncStatus)
        assertEquals(null, t.sortOrder)
    }

    @Test fun `update changes name and kcal and sets PENDING_CREATE`() = runTest {
        val farFuture = OffsetDateTime.now().plusDays(30).toString()
        tokenStore.save("token", farFuture, userId)
        val id = "template-id"
        db.mealTemplateDao().upsert(aMealTemplate(id = id, userId = userId, name = "Old name",
            quickAddKcal = 400, syncStatus = SyncStatus.SYNCED))
        viewModel.update(id, "New name", 500)
        val templates = db.mealTemplateDao().observeAll().first { list ->
            list.any { it.name == "New name" }
        }
        val t = templates.single()
        assertEquals("New name", t.name)
        assertEquals(500, t.quickAddKcal)
        assertEquals(SyncStatus.PENDING_CREATE, t.syncStatus)
    }

    @Test fun `delete sets PENDING_DELETE and template disappears from observeAll`() = runTest {
        val id = "template-id"
        db.mealTemplateDao().upsert(aMealTemplate(id = id, userId = userId, quickAddKcal = 500,
            syncStatus = SyncStatus.SYNCED))
        viewModel.delete(id)
        val templates = db.mealTemplateDao().observeAll().first { it.isEmpty() }
        assertTrue(templates.isEmpty())
        assertEquals(1, db.mealTemplateDao().getByStatus(SyncStatus.PENDING_DELETE).size)
    }

    @Test fun `templates flow emits alphabetically sorted list`() = runTest {
        db.mealTemplateDao().upsert(aMealTemplate(userId = userId, name = "Zucchini soup", quickAddKcal = 300, syncStatus = SyncStatus.SYNCED))
        db.mealTemplateDao().upsert(aMealTemplate(userId = userId, name = "Apple pie",     quickAddKcal = 450, syncStatus = SyncStatus.SYNCED))
        db.mealTemplateDao().upsert(aMealTemplate(userId = userId, name = "Bolognese",     quickAddKcal = 700, syncStatus = SyncStatus.SYNCED))
        val result = viewModel.templates.first { it.size == 3 }
        assertEquals("Apple pie",     result[0].template.name)
        assertEquals("Bolognese",     result[1].template.name)
        assertEquals("Zucchini soup", result[2].template.name)
    }
}
