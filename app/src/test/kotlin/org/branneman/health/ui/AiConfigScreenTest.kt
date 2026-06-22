package org.branneman.health.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.branneman.health.AiConfigStatusDto
import org.branneman.health.ai.AiRepository
import org.branneman.health.network.AiEstimateApiResult
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AiConfigScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun fakeRepo(
        status: AiConfigStatusDto = AiConfigStatusDto(configured = false, expiresAt = null),
    ): AiRepository = object : AiRepository {
        override suspend fun getStatus() = status
        override suspend fun saveKey(apiKey: String, expiresAt: String?) = status
        override suspend fun removeKey() = Unit
        override suspend fun estimate(text: String?, imageBytes: ByteArray?) =
            AiEstimateApiResult.NotConfigured
    }

    @Test
    fun `status badge shows Not configured when not configured`() {
        composeRule.setContent {
            AiConfigContent(
                status = AiConfigStatusDto(configured = false, expiresAt = null),
                apiKeyInput = "",
                onApiKeyChange = {},
                keyVisible = false,
                onToggleKeyVisible = {},
                onSave = {},
                onRemove = {},
                onBack = {},
                isSaving = false,
            )
        }
        composeRule.onNodeWithTag("ai_config_status_badge").assertTextContains("Not configured")
    }

    @Test
    fun `status badge shows Connected when configured`() {
        composeRule.setContent {
            AiConfigContent(
                status = AiConfigStatusDto(configured = true, expiresAt = null),
                apiKeyInput = "",
                onApiKeyChange = {},
                keyVisible = false,
                onToggleKeyVisible = {},
                onSave = {},
                onRemove = {},
                onBack = {},
                isSaving = false,
            )
        }
        composeRule.onNodeWithTag("ai_config_status_badge").assertTextContains("Connected")
    }

    @Test
    fun `status badge shows Expired when configured with past expiry`() {
        composeRule.setContent {
            AiConfigContent(
                status = AiConfigStatusDto(configured = false, expiresAt = "2020-01-01"),
                apiKeyInput = "",
                onApiKeyChange = {},
                keyVisible = false,
                onToggleKeyVisible = {},
                onSave = {},
                onRemove = {},
                onBack = {},
                isSaving = false,
            )
        }
        composeRule.onNodeWithTag("ai_config_status_badge").assertTextContains("Expired")
    }

    @Test
    fun `save button disabled when key field is empty`() {
        composeRule.setContent {
            AiConfigContent(
                status = AiConfigStatusDto(configured = false, expiresAt = null),
                apiKeyInput = "",
                onApiKeyChange = {},
                keyVisible = false,
                onToggleKeyVisible = {},
                onSave = {},
                onRemove = {},
                onBack = {},
                isSaving = false,
            )
        }
        composeRule.onNodeWithTag("ai_config_save_button").assertIsNotEnabled()
    }

    @Test
    fun `save button enabled when key field is non-empty`() {
        composeRule.setContent {
            AiConfigContent(
                status = AiConfigStatusDto(configured = false, expiresAt = null),
                apiKeyInput = "sk-ant-test",
                onApiKeyChange = {},
                keyVisible = false,
                onToggleKeyVisible = {},
                onSave = {},
                onRemove = {},
                onBack = {},
                isSaving = false,
            )
        }
        composeRule.onNodeWithTag("ai_config_save_button").assertIsEnabled()
    }

    @Test
    fun `key field is masked by default`() {
        composeRule.setContent {
            AiConfigContent(
                status = AiConfigStatusDto(configured = false, expiresAt = null),
                apiKeyInput = "sk-ant-secret",
                onApiKeyChange = {},
                keyVisible = false,
                onToggleKeyVisible = {},
                onSave = {},
                onRemove = {},
                onBack = {},
                isSaving = false,
            )
        }
        // Password field: semantic value should be obscured (not readable as plain text)
        composeRule.onNodeWithTag("ai_config_key_field")
            .assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Password))
    }
}
