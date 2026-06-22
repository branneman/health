package org.branneman.health.ai

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.branneman.health.AiConfigRequestDto
import org.branneman.health.AiConfigStatusDto
import org.branneman.health.AiEstimateResponseDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AiDtoSerializationTest {

    @Test
    fun `AiConfigStatusDto configured true with expiry round-trips`() {
        val dto = AiConfigStatusDto(configured = true, expiresAt = "2027-01-01")
        val json = Json.encodeToString(dto)
        val decoded = Json.decodeFromString<AiConfigStatusDto>(json)
        assertEquals(true, decoded.configured)
        assertEquals("2027-01-01", decoded.expiresAt)
    }

    @Test
    fun `AiConfigStatusDto configured false with null expiry round-trips`() {
        val dto = AiConfigStatusDto(configured = false, expiresAt = null)
        val json = Json.encodeToString(dto)
        val decoded = Json.decodeFromString<AiConfigStatusDto>(json)
        assertEquals(false, decoded.configured)
        assertNull(decoded.expiresAt)
    }

    @Test
    fun `AiConfigRequestDto with expiry round-trips`() {
        val dto = AiConfigRequestDto(apiKey = "sk-ant-test", expiresAt = "2027-06-01")
        val json = Json.encodeToString(dto)
        val decoded = Json.decodeFromString<AiConfigRequestDto>(json)
        assertEquals("sk-ant-test", decoded.apiKey)
        assertEquals("2027-06-01", decoded.expiresAt)
    }

    @Test
    fun `AiConfigRequestDto without expiry round-trips`() {
        val dto = AiConfigRequestDto(apiKey = "sk-ant-test", expiresAt = null)
        val json = Json.encodeToString(dto)
        val decoded = Json.decodeFromString<AiConfigRequestDto>(json)
        assertEquals("sk-ant-test", decoded.apiKey)
        assertNull(decoded.expiresAt)
    }

    @Test
    fun `AiEstimateResponseDto round-trips`() {
        val dto = AiEstimateResponseDto(kcal = 650, explanation = "Tiramisu, standard portion.")
        val json = Json.encodeToString(dto)
        val decoded = Json.decodeFromString<AiEstimateResponseDto>(json)
        assertEquals(650, decoded.kcal)
        assertEquals("Tiramisu, standard portion.", decoded.explanation)
    }
}
