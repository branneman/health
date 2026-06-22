package org.branneman.health.ai

import kotlinx.serialization.json.Json
import org.branneman.health.AiEstimateResponseDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

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
    fun `null explanation is allowed — explanation is optional`() {
        val service = makeService { ClaudeEstimate(500, null) }
        val result = service.estimate("key", "food", null, null)
        assertNull(result.explanation)
    }

    @Test
    fun `explanation is passed through verbatim regardless of length`() {
        val long = "x".repeat(1000)
        val service = makeService { ClaudeEstimate(500, long) }
        val result = service.estimate("key", "food", null, null)
        assertEquals(long, result.explanation)
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
    fun `whitespace-only explanation is passed through — no server-side content validation`() {
        val service = makeService { ClaudeEstimate(500, "   ") }
        val result = service.estimate("key", "food", null, null)
        assertEquals("   ", result.explanation)
    }

    @Test
    fun `AiEstimateResponseDto serializes without explanation field when null`() {
        val dto = AiEstimateResponseDto(350, null)
        val json = Json.encodeToString(AiEstimateResponseDto.serializer(), dto)
        assertEquals("""{"kcal":350}""", json)
    }

    @Test
    fun `AiEstimateResponseDto deserializes when explanation field is absent`() {
        val json = """{"kcal":350}"""
        val dto = Json.decodeFromString(AiEstimateResponseDto.serializer(), json)
        assertEquals(350, dto.kcal)
        assertNull(dto.explanation)
    }

    @Test
    fun `AiEstimateResponseDto deserializes when explanation field is present`() {
        val json = """{"kcal":500,"explanation":"350 kcal — gin martini with olive."}"""
        val dto = Json.decodeFromString(AiEstimateResponseDto.serializer(), json)
        assertEquals(500, dto.kcal)
        assertEquals("350 kcal — gin martini with olive.", dto.explanation)
    }

    @Test
    fun `ClaudeEstimate deserializes from JSON without explanation field`() {
        val json = """{"kcal":400}"""
        val estimate = Json.decodeFromString<ClaudeEstimate>(json)
        assertEquals(400, estimate.kcal)
        assertNull(estimate.explanation)
    }

    @Test
    fun `kcal of 1 with null explanation returns correct dto`() {
        val service = makeService { ClaudeEstimate(1, null) }
        val result = service.estimate("key", "food", null, null)
        assertEquals(AiEstimateResponseDto(1, null), result)
    }
}
