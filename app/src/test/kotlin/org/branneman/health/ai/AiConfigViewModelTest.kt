package org.branneman.health.ai

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.branneman.health.AiConfigStatusDto
import org.branneman.health.network.AiEstimateApiResult
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
class AiConfigViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After  fun tearDown() { Dispatchers.resetMain() }

    private fun failingRepo() = object : AiRepository {
        override suspend fun getStatus() = AiConfigStatusDto(configured = false, expiresAt = null)
        override suspend fun saveKey(apiKey: String, expiresAt: String?): AiConfigStatusDto? = null
        override suspend fun removeKey() = Unit
        override suspend fun estimate(text: String?, imageBytes: ByteArray?) =
            AiEstimateApiResult.NotConfigured
    }

    private fun makeVm(repo: AiRepository = failingRepo()) =
        AiConfigViewModel.forTest(ApplicationProvider.getApplicationContext<Application>(), repo)

    @Test
    fun `saveError is false initially`() = runTest {
        val vm = makeVm()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(false, vm.saveError.value)
    }

    @Test
    fun `saveError becomes true when save fails`() = runTest {
        val vm = makeVm()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.save("sk-ant-test", null)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(true, vm.saveError.value)
    }

    @Test
    fun `saveError is cleared by setting it to false (simulates DisposableEffect on screen exit)`() = runTest {
        val vm = makeVm()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.save("sk-ant-test", null)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(true, vm.saveError.value)

        vm.saveError.value = false  // what DisposableEffect onDispose does

        assertEquals(false, vm.saveError.value)
    }
}
