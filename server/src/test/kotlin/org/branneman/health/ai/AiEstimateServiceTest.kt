package org.branneman.health.ai

import org.branneman.health.AiEstimateResponseDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AiEstimateServiceTest {

    private fun makeService(result: () -> ClaudeEstimate): AiEstimateService {
        val gateway = object : AnthropicGateway {
            override fun estimate(
                apiKey: String,
                text: String?,
                imageBase64: String?,
                imageMimeType: String?,
            ) = result()
        }
        return AiEstimateService(gateway)
    }

    private fun makeFailingService(ex: Exception): AiEstimateService {
        val gateway = object : AnthropicGateway {
            override fun estimate(
                apiKey: String,
                text: String?,
                imageBase64: String?,
                imageMimeType: String?,
            ): ClaudeEstimate = throw ex
        }
        return AiEstimateService(gateway)
    }

    @Test
    fun `valid response builds AiEstimateResponseDto`() {
        val service = makeService { ClaudeEstimate(650, "Standard tiramisu portion.") }
        val result = service.estimate("key", "tiramisu", null, null)
        assertEquals(AiEstimateResponseDto(650, "Standard tiramisu portion."), result)
    }

    @Test
    fun `kcal of 0 throws ClaudeEstimateException`() {
        val service = makeService { ClaudeEstimate(0, "explanation") }
        assertFailsWith<ClaudeEstimateException> {
            service.estimate("key", "food", null, null)
        }
    }

    @Test
    fun `kcal greater than 9999 throws ClaudeEstimateException`() {
        val service = makeService { ClaudeEstimate(10000, "explanation") }
        assertFailsWith<ClaudeEstimateException> {
            service.estimate("key", "food", null, null)
        }
    }

    @Test
    fun `empty explanation throws ClaudeEstimateException`() {
        val service = makeService { ClaudeEstimate(500, "") }
        assertFailsWith<ClaudeEstimateException> {
            service.estimate("key", "food", null, null)
        }
    }

    @Test
    fun `explanation over 300 chars throws ClaudeEstimateException`() {
        val service = makeService { ClaudeEstimate(500, "x".repeat(301)) }
        assertFailsWith<ClaudeEstimateException> {
            service.estimate("key", "food", null, null)
        }
    }

    @Test
    fun `gateway throwing ClaudeEstimateException propagates`() {
        val service = makeFailingService(ClaudeEstimateException("timeout"))
        assertFailsWith<ClaudeEstimateException> {
            service.estimate("key", "food", null, null)
        }
    }

    @Test
    fun `gateway throwing RuntimeException wraps in ClaudeEstimateException`() {
        val service = makeFailingService(RuntimeException("network error"))
        assertFailsWith<ClaudeEstimateException> {
            service.estimate("key", "food", null, null)
        }
    }
}
