package org.branneman.health.ui

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FoodSearchViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var db: HealthDatabase
    private lateinit var vm: FoodSearchViewModel

    @Before fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = HealthDatabase.buildInMemory(app)
        val dataStore = PreferenceDataStoreFactory.create(
            scope       = CoroutineScope(testDispatcher),
            produceFile = { File.createTempFile("test_auth_fsvm", ".preferences_pb").also { it.deleteOnExit() } },
        )
        vm = FoodSearchViewModel.forTest(app, db, TokenStore(dataStore))
    }

    @After fun tearDown() { db.close(); Dispatchers.resetMain() }

    @Test
    fun `resetSearch clears query, results, offline flag, and barcodeNotFound`() = runTest {
        vm.onQueryChange("banana")
        assertEquals("banana", vm.query.value)

        vm.resetSearch()

        assertEquals("", vm.query.value)
        assertEquals(emptyList(), vm.results.value)
        assertEquals(false, vm.isOffline.value)
        assertEquals(false, vm.barcodeNotFound.value)
    }
}
