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

    @Test
    fun `kcal of 1 is valid lower boundary`() {
        val service = makeService { ClaudeEstimate(1, "Trace amount.") }
        val result = service.estimate("key", "food", null, null)
        assertEquals(AiEstimateResponseDto(1, "Trace amount."), result)
    }

    @Test
    fun `kcal of 9999 is valid upper boundary`() {
        val service = makeService { ClaudeEstimate(9999, "Enormous feast.") }
        val result = service.estimate("key", "food", null, null)
        assertEquals(AiEstimateResponseDto(9999, "Enormous feast."), result)
    }

    @Test
    fun `explanation of exactly 300 chars is valid upper boundary`() {
        val explanation = "a".repeat(300)
        val service = makeService { ClaudeEstimate(500, explanation) }
        val result = service.estimate("key", "food", null, null)
        assertEquals(explanation, result.explanation)
    }

    @Test
    fun `whitespace-only explanation throws ClaudeEstimateException`() {
        val service = makeService { ClaudeEstimate(500, "   ") }
        assertFailsWith<ClaudeEstimateException> {
            service.estimate("key", "food", null, null)
        }
    }
}
