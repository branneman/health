package org.branneman.health.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.branneman.health.ai.AskAiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AskAiScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `estimate button disabled with no input`() {
        composeRule.setContent {
            AskAiContent(
                state           = AskAiState.Idle,
                text            = "",
                onTextChange    = {},
                imageBitmap     = null,
                onPickImage     = {},
                onClearImage    = {},
                canEstimate     = false,
                onEstimate      = {},
                onUseThis       = { _: Int, _: String?, _: String? -> },
                onEditAmount    = { _: Int, _: String? -> },
                onDiscard       = {},
                onNeedsAiConfig = {},
                onBack          = {},
            )
        }
        composeRule.onNodeWithTag("ask_ai_estimate_button").assertIsNotEnabled()
    }

    @Test
    fun `estimate button enabled after typing text`() {
        composeRule.setContent {
            AskAiContent(
                state           = AskAiState.Idle,
                text            = "tiramisu",
                onTextChange    = {},
                imageBitmap     = null,
                onPickImage     = {},
                onClearImage    = {},
                canEstimate     = true,
                onEstimate      = {},
                onUseThis       = { _: Int, _: String?, _: String? -> },
                onEditAmount    = { _: Int, _: String? -> },
                onDiscard       = {},
                onNeedsAiConfig = {},
                onBack          = {},
            )
        }
        composeRule.onNodeWithTag("ask_ai_estimate_button").assertIsEnabled()
    }

    @Test
    fun `loading indicator shown during Loading state`() {
        composeRule.setContent {
            AskAiContent(
                state           = AskAiState.Loading,
                text            = "tiramisu",
                onTextChange    = {},
                imageBitmap     = null,
                onPickImage     = {},
                onClearImage    = {},
                canEstimate     = true,
                onEstimate      = {},
                onUseThis       = { _: Int, _: String?, _: String? -> },
                onEditAmount    = { _: Int, _: String? -> },
                onDiscard       = {},
                onNeedsAiConfig = {},
                onBack          = {},
            )
        }
        composeRule.onNodeWithTag("ask_ai_loading").assertIsDisplayed()
    }

    @Test
    fun `result screen shows kcal and explanation`() {
        composeRule.setContent {
            AskAiContent(
                state           = AskAiState.Result(650, "Standard tiramisu portion.", "tiramisu", null),
                text            = "tiramisu",
                onTextChange    = {},
                imageBitmap     = null,
                onPickImage     = {},
                onClearImage    = {},
                canEstimate     = true,
                onEstimate      = {},
                onUseThis       = { _: Int, _: String?, _: String? -> },
                onEditAmount    = { _: Int, _: String? -> },
                onDiscard       = {},
                onNeedsAiConfig = {},
                onBack          = {},
            )
        }
        composeRule.onNodeWithTag("ask_ai_result_kcal").assertTextContains("650", substring = true)
        composeRule.onNodeWithTag("ask_ai_result_explanation")
            .assertTextContains("Standard tiramisu portion.", substring = true)
    }

    @Test
    fun `result screen shows Use this, Edit amount, Discard buttons`() {
        composeRule.setContent {
            AskAiContent(
                state           = AskAiState.Result(650, "Standard portion.", null, null),
                text            = "",
                onTextChange    = {},
                imageBitmap     = null,
                onPickImage     = {},
                onClearImage    = {},
                canEstimate     = false,
                onEstimate      = {},
                onUseThis       = { _: Int, _: String?, _: String? -> },
                onEditAmount    = { _: Int, _: String? -> },
                onDiscard       = {},
                onNeedsAiConfig = {},
                onBack          = {},
            )
        }
        composeRule.onNodeWithTag("ask_ai_use_this").assertIsDisplayed()
        composeRule.onNodeWithTag("ask_ai_edit_amount").assertIsDisplayed()
        composeRule.onNodeWithTag("ask_ai_discard").assertIsDisplayed()
    }

    @Test
    fun `NotConfigured error shows correct copy`() {
        composeRule.setContent {
            AskAiContent(
                state           = AskAiState.Error.NotConfigured,
                text            = "",
                onTextChange    = {},
                imageBitmap     = null,
                onPickImage     = {},
                onClearImage    = {},
                canEstimate     = false,
                onEstimate      = {},
                onUseThis       = { _: Int, _: String?, _: String? -> },
                onEditAmount    = { _: Int, _: String? -> },
                onDiscard       = {},
                onNeedsAiConfig = {},
                onBack          = {},
            )
        }
        composeRule.onNodeWithTag("ask_ai_error").assertTextContains("Settings → AI", substring = true)
    }

    @Test
    fun `EstimateFailed error shows correct copy`() {
        composeRule.setContent {
            AskAiContent(
                state           = AskAiState.Error.EstimateFailed,
                text            = "",
                onTextChange    = {},
                imageBitmap     = null,
                onPickImage     = {},
                onClearImage    = {},
                canEstimate     = false,
                onEstimate      = {},
                onUseThis       = { _: Int, _: String?, _: String? -> },
                onEditAmount    = { _: Int, _: String? -> },
                onDiscard       = {},
                onNeedsAiConfig = {},
                onBack          = {},
            )
        }
        composeRule.onNodeWithTag("ask_ai_error")
            .assertTextContains("couldn't estimate", substring = true)
    }
}
