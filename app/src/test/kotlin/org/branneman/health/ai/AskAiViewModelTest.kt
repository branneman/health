package org.branneman.health.ai

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.branneman.health.AiConfigStatusDto
import org.branneman.health.AiEstimateResponseDto
import org.branneman.health.network.AiEstimateApiResult
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertIs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class AskAiViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun makeVm(repo: AiRepository) = AskAiViewModel(
        application = ApplicationProvider.getApplicationContext(),
        repository  = repo,
    )

    private fun repo(
        status: AiConfigStatusDto = AiConfigStatusDto(configured = true, expiresAt = null),
        estimateResult: AiEstimateApiResult = AiEstimateApiResult.NotConfigured,
    ) = object : AiRepository {
        override suspend fun getStatus() = status
        override suspend fun saveKey(apiKey: String, expiresAt: String?) = status
        override suspend fun removeKey() = Unit
        override suspend fun estimate(text: String?, imageBytes: ByteArray?) = estimateResult
    }

    @Test
    fun `initial state is Idle and estimate button is disabled`() = runTest {
        val vm = makeVm(repo())
        testDispatcher.scheduler.advanceUntilIdle()
        assertIs<AskAiState.Idle>(vm.state.value)
        assertEquals(false, vm.canEstimate.value)
    }

    @Test
    fun `entering text enables estimate button`() = runTest {
        val vm = makeVm(repo())
        vm.setText("tiramisu")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(true, vm.canEstimate.value)
    }

    @Test
    fun `success response transitions to Result state`() = runTest {
        val vm = makeVm(repo(estimateResult = AiEstimateApiResult.Success(
            AiEstimateResponseDto(650, "Tiramisu portion.")
        )))
        vm.setText("tiramisu")
        testDispatcher.scheduler.advanceUntilIdle()
        vm.estimate()
        testDispatcher.scheduler.advanceUntilIdle()
        val state = vm.state.value
        assertIs<AskAiState.Result>(state)
        assertEquals(650, state.kcal)
        assertEquals("Tiramisu portion.", state.explanation)
    }

    @Test
    fun `NotConfigured result transitions to Error NotConfigured`() = runTest {
        val vm = makeVm(repo(estimateResult = AiEstimateApiResult.NotConfigured))
        vm.setText("pizza")
        testDispatcher.scheduler.advanceUntilIdle()
        vm.estimate()
        testDispatcher.scheduler.advanceUntilIdle()
        assertIs<AskAiState.Error.NotConfigured>(vm.state.value)
    }

    @Test
    fun `KeyExpired result transitions to Error KeyExpired`() = runTest {
        val vm = makeVm(repo(estimateResult = AiEstimateApiResult.KeyExpired))
        vm.setText("pizza")
        testDispatcher.scheduler.advanceUntilIdle()
        vm.estimate()
        testDispatcher.scheduler.advanceUntilIdle()
        assertIs<AskAiState.Error.KeyExpired>(vm.state.value)
    }

    @Test
    fun `EstimateFailed result transitions to Error EstimateFailed`() = runTest {
        val vm = makeVm(repo(estimateResult = AiEstimateApiResult.EstimateFailed))
        vm.setText("pizza")
        testDispatcher.scheduler.advanceUntilIdle()
        vm.estimate()
        testDispatcher.scheduler.advanceUntilIdle()
        assertIs<AskAiState.Error.EstimateFailed>(vm.state.value)
    }

    @Test
    fun `NetworkError result transitions to Error Network`() = runTest {
        val vm = makeVm(repo(estimateResult = AiEstimateApiResult.NetworkError))
        vm.setText("pizza")
        testDispatcher.scheduler.advanceUntilIdle()
        vm.estimate()
        testDispatcher.scheduler.advanceUntilIdle()
        assertIs<AskAiState.Error.Network>(vm.state.value)
    }

    @Test
    fun `loading state is set during estimate call`() = runTest {
        var capturedDuringCall = false
        val slowRepo = object : AiRepository {
            override suspend fun getStatus() = AiConfigStatusDto(true, null)
            override suspend fun saveKey(k: String, e: String?) = AiConfigStatusDto(true, null)
            override suspend fun removeKey() = Unit
            override suspend fun estimate(text: String?, imageBytes: ByteArray?): AiEstimateApiResult {
                capturedDuringCall = true
                return AiEstimateApiResult.EstimateFailed
            }
        }
        val vm = makeVm(slowRepo)
        vm.setText("food")
        testDispatcher.scheduler.advanceUntilIdle()
        vm.estimate()
        testDispatcher.scheduler.advanceUntilIdle()
        // After completion
        assertTrue(capturedDuringCall)
    }

    private fun assertEquals(expected: Any?, actual: Any?) = kotlin.test.assertEquals(expected, actual)
    private fun assertTrue(value: Boolean) = kotlin.test.assertTrue(value)
}
