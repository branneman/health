package org.branneman.health.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.branneman.health.aMealTemplate
import org.branneman.health.uuid
import org.branneman.health.db.HealthDatabase
import org.branneman.health.db.SyncStatus
import org.branneman.health.db.entities.MealTemplateItemEntity
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MealTemplateDaoTest {

    private lateinit var db: HealthDatabase
    private lateinit var dao: MealTemplateDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HealthDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.mealTemplateDao()
    }

    @After fun tearDown() { db.close() }

    @Test
    fun `upsert and observeAll returns non-deleted templates`() = runTest {
        val userId = uuid()
        dao.upsert(aMealTemplate(userId = userId, name = "Breakfast"))
        val result = dao.observeAll().first()
        assertEquals(1, result.size)
        assertEquals("Breakfast", result[0].name)
    }

    @Test
    fun `observeAll excludes PENDING_DELETE templates`() = runTest {
        val userId = uuid()
        val id = uuid()
        dao.upsert(aMealTemplate(id = id, userId = userId))
        dao.updateSyncStatus(id, SyncStatus.PENDING_DELETE)
        assertTrue(dao.observeAll().first().isEmpty())
    }

    @Test
    fun `upsertItem and getItems round-trips`() = runTest {
        val userId = uuid()
        val templateId = uuid()
        val foodItemId = uuid()
        dao.upsert(aMealTemplate(id = templateId, userId = userId))
        dao.upsertItem(MealTemplateItemEntity(templateId = templateId, foodItemId = foodItemId, grams = 150.0))
        val items = dao.getItems(templateId)
        assertEquals(1, items.size)
        assertEquals(150.0, items[0].grams)
    }

    @Test
    fun `observePinned returns only templates with non-null sortOrder ordered ascending`() = runTest {
        val userId = uuid()
        dao.upsert(aMealTemplate(userId = userId, name = "Dinner",    sortOrder = null))
        dao.upsert(aMealTemplate(userId = userId, name = "Lunch",     sortOrder = 1, quickAddKcal = 600))
        dao.upsert(aMealTemplate(userId = userId, name = "Breakfast", sortOrder = 0, quickAddKcal = 450))
        val pinned = dao.observePinned().first()
        assertEquals(2, pinned.size)
        assertEquals("Breakfast", pinned[0].name)
        assertEquals("Lunch",     pinned[1].name)
    }

    @Test
    fun `observePinned excludes PENDING_DELETE templates`() = runTest {
        val id = uuid()
        dao.upsert(aMealTemplate(id = id, userId = uuid(), sortOrder = 0, quickAddKcal = 400))
        dao.updateSyncStatus(id, SyncStatus.PENDING_DELETE)
        assertTrue(dao.observePinned().first().isEmpty())
    }

    @Test
    fun `quickAddKcal and sortOrder round-trip through upsert`() = runTest {
        val id = uuid()
        dao.upsert(aMealTemplate(id = id, userId = uuid(), sortOrder = 0, quickAddKcal = 500))
        val result = dao.observePinned().first()
        assertEquals(500, result[0].quickAddKcal)
        assertEquals(0,   result[0].sortOrder)
    }

    @Test
    fun `deleteAllForUser removes templates and items`() = runTest {
        val userId = uuid()
        val templateId = uuid()
        val foodItemId = uuid()
        dao.upsert(aMealTemplate(id = templateId, userId = userId))
        dao.upsertItem(MealTemplateItemEntity(templateId = templateId, foodItemId = foodItemId, grams = 100.0))
        dao.deleteAllItemsForUser(userId)
        dao.deleteAllForUser(userId)
        assertTrue(dao.observeAll().first().isEmpty())
        assertTrue(dao.getItems(templateId).isEmpty())
    }

    @Test
    fun `getById returns entity for known id`() = runTest {
        val id = uuid()
        dao.upsert(aMealTemplate(id = id, userId = uuid(), name = "Pasta", quickAddKcal = 700))
        val result = dao.getById(id)
        assertEquals("Pasta", result?.name)
        assertEquals(700, result?.quickAddKcal)
    }

    @Test
    fun `getById returns null for unknown id`() = runTest {
        assertNull(dao.getById("nonexistent"))
    }

    @Test
    fun `deleteById removes the row`() = runTest {
        val id = uuid()
        dao.upsert(aMealTemplate(id = id, userId = uuid(), quickAddKcal = 500))
        dao.deleteById(id)
        assertNull(dao.getById(id))
        assertTrue(dao.observeAll().first().isEmpty())
    }

    @Test
    fun `getItemsForTemplate respects sortOrder ascending`() = runTest {
        val templateId = uuid()
        val foodId1 = uuid()
        val foodId2 = uuid()
        val foodId3 = uuid()
        dao.upsert(aMealTemplate(id = templateId, userId = uuid()))
        dao.upsertItem(MealTemplateItemEntity(templateId = templateId, foodItemId = foodId2, grams = 100.0, sortOrder = 1))
        dao.upsertItem(MealTemplateItemEntity(templateId = templateId, foodItemId = foodId3, grams = 50.0,  sortOrder = 2))
        dao.upsertItem(MealTemplateItemEntity(templateId = templateId, foodItemId = foodId1, grams = 200.0, sortOrder = 0))
        val items = dao.getItemsForTemplate(templateId)
        assertEquals(3, items.size)
        assertEquals(foodId1, items[0].foodItemId)
        assertEquals(foodId2, items[1].foodItemId)
        assertEquals(foodId3, items[2].foodItemId)
    }

    @Test
    fun `deleteItemsForTemplate removes only that template's items`() = runTest {
        val templateId1 = uuid()
        val templateId2 = uuid()
        val foodId = uuid()
        dao.upsert(aMealTemplate(id = templateId1, userId = uuid()))
        dao.upsert(aMealTemplate(id = templateId2, userId = uuid()))
        dao.upsertItem(MealTemplateItemEntity(templateId = templateId1, foodItemId = foodId, grams = 100.0))
        dao.upsertItem(MealTemplateItemEntity(templateId = templateId2, foodItemId = foodId, grams = 200.0))
        dao.deleteItemsForTemplate(templateId1)
        assertTrue(dao.getItemsForTemplate(templateId1).isEmpty())
        assertEquals(1, dao.getItemsForTemplate(templateId2).size)
    }
}
