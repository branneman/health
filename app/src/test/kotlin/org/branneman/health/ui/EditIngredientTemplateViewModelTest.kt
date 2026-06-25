package org.branneman.health.ui

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.branneman.health.aFoodItem
import org.branneman.health.aMealTemplate
import org.branneman.health.auth.TokenStore
import org.branneman.health.db.HealthDatabase
import org.branneman.health.db.entities.MealTemplateItemEntity
import org.branneman.health.uuid
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EditIngredientTemplateViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var db: HealthDatabase
    private lateinit var tokenStore: TokenStore
    private lateinit var vm: EditIngredientTemplateViewModel

    @Before fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(app, HealthDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryCoroutineContext(testDispatcher)
            .build()
        val dataStore = PreferenceDataStoreFactory.create(
            scope       = CoroutineScope(testDispatcher),
            produceFile = { File.createTempFile("test_auth_eit", ".preferences_pb").also { it.deleteOnExit() } },
        )
        tokenStore = TokenStore(dataStore)
        vm = EditIngredientTemplateViewModel.forTest(app, db, tokenStore)
    }

    @After fun tearDown() { db.close(); Dispatchers.resetMain() }

    @Test
    fun `loadTemplate null clears name and ingredients synchronously`() = runTest {
        // Arrange: insert a template with one ingredient into the in-memory DB
        val templateId = uuid()
        val foodItem = aFoodItem(userId = "user-1", name = "Spaghetti")
        db.foodItemDao().upsert(foodItem)
        db.mealTemplateDao().upsert(aMealTemplate(id = templateId, userId = "user-1", name = "Pasta"))
        db.mealTemplateDao().upsertItem(MealTemplateItemEntity(templateId, foodItem.id, 150.0, 0))

        // Load the template and wait for async DB load
        vm.loadTemplate(templateId)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("Pasta", vm.name.value)
        assertEquals(1, vm.ingredients.value.size)

        // Loading null should clear state synchronously — no advance needed
        vm.loadTemplate(null)

        assertEquals("", vm.name.value)
        assertEquals(emptyList(), vm.ingredients.value)
    }

    @Test
    fun `loadTemplate with new id clears state before async load completes`() = runTest {
        // Arrange: two templates, each with one ingredient
        val id1 = uuid()
        val id2 = uuid()
        val food1 = aFoodItem(userId = "user-1", name = "Rice")
        val food2 = aFoodItem(userId = "user-1", name = "Chicken")
        db.foodItemDao().upsert(food1)
        db.foodItemDao().upsert(food2)
        db.mealTemplateDao().upsert(aMealTemplate(id = id1, userId = "user-1", name = "Template 1"))
        db.mealTemplateDao().upsert(aMealTemplate(id = id2, userId = "user-1", name = "Template 2"))
        db.mealTemplateDao().upsertItem(MealTemplateItemEntity(id1, food1.id, 100.0, 0))
        db.mealTemplateDao().upsertItem(MealTemplateItemEntity(id2, food2.id, 200.0, 0))

        // Load template 1 and let the async work finish
        vm.loadTemplate(id1)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("Template 1", vm.name.value)
        assertEquals(1, vm.ingredients.value.size)

        // Load template 2 — no advance yet; state must be cleared synchronously
        vm.loadTemplate(id2)
        assertEquals("", vm.name.value)
        assertEquals(emptyList(), vm.ingredients.value)

        // Advance until idle — the async load for template 2 should now complete
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("Template 2", vm.name.value)
        assertEquals(1, vm.ingredients.value.size)
    }
}
