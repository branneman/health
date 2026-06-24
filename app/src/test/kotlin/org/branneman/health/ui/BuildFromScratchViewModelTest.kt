package org.branneman.health.ui

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.branneman.health.aFoodItem
import org.branneman.health.db.HealthDatabase
import org.branneman.health.db.SyncStatus
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BuildFromScratchViewModelTest {

    private lateinit var db: HealthDatabase
    private lateinit var vm: BuildFromScratchViewModel

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HealthDatabase::class.java)
            .allowMainThreadQueries().build()
        vm = BuildFromScratchViewModel(db)
    }

    @After fun tearDown() { db.close() }

    @Test
    fun `running kcal total is sum of ingredient kcal contributions`() = runTest {
        val rice    = aFoodItem(kcalPer100g = 130.0)  // 100g → 130 kcal
        val chicken = aFoodItem(kcalPer100g = 165.0)  // 200g → 330 kcal
        vm.addIngredient(rice, 100.0)
        vm.addIngredient(chicken, 200.0)
        // 130 + 330 = 460
        assertEquals(460, vm.totalKcal.first())
    }

    @Test
    fun `total kcal rounds each item contribution`() = runTest {
        val item = aFoodItem(kcalPer100g = 200.0)
        vm.addIngredient(item, 75.0)  // 75/100 * 200 = 150 kcal
        assertEquals(150, vm.totalKcal.first())
    }

    @Test
    fun `bail-out kcal equals current total`() = runTest {
        val item = aFoodItem(kcalPer100g = 400.0)
        vm.addIngredient(item, 50.0)  // 50/100 * 400 = 200 kcal
        assertEquals(200, vm.bailOutKcal)
    }

    @Test
    fun `removeAt removes ingredient and updates total`() = runTest {
        val a = aFoodItem(kcalPer100g = 100.0)
        val b = aFoodItem(kcalPer100g = 200.0)
        vm.addIngredient(a, 100.0)
        vm.addIngredient(b, 100.0)
        vm.removeAt(0)
        assertEquals(200, vm.totalKcal.first())
    }

    @Test
    fun `log writes LogEntryEntity and items to Room`() = runTest {
        val item = aFoodItem(kcalPer100g = 200.0)
        db.foodItemDao().upsert(item)
        vm.addIngredient(item, 100.0)
        vm.log("lunch", item.userId)
        val entries = db.logEntryDao().getByStatus(SyncStatus.PENDING_CREATE)
        assertEquals(1, entries.size)
        assertEquals("lunch", entries[0].mealType)
        val logItems = db.logEntryDao().getItemsForEntry(entries[0].id)
        assertEquals(1, logItems.size)
        assertEquals(200.0, logItems[0].kcalPer100g)
    }
}
