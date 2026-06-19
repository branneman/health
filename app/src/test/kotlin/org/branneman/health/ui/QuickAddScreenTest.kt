package org.branneman.health.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class QuickAddScreenTest {

    @get:Rule val compose = createComposeRule()

    private fun render(
        onLog: (String, String) -> Unit = { _, _ -> },
        onBack: () -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                QuickAddContent(onLog = onLog, onBack = onBack)
            }
        }
    }

    @Test fun `Log button is disabled when kcal is empty`() {
        render()
        compose.onNodeWithTag("quick_add_log_button").assertIsNotEnabled()
    }

    @Test fun `Log button is enabled when kcal is positive`() {
        render()
        compose.onNodeWithTag("quick_add_kcal").performTextInput("500")
        compose.onNodeWithTag("quick_add_log_button").assertIsEnabled()
    }

    @Test fun `Log button is disabled when kcal is zero`() {
        render()
        compose.onNodeWithTag("quick_add_kcal").performTextInput("0")
        compose.onNodeWithTag("quick_add_log_button").assertIsNotEnabled()
    }

    @Test fun `tapping Log calls onLog with kcal and label`() {
        var result: Pair<String, String>? = null
        render(onLog = { kcal, label -> result = kcal to label })
        compose.onNodeWithTag("quick_add_kcal").performTextInput("800")
        compose.onNodeWithTag("quick_add_label").performTextInput("Pasta at work")
        compose.onNodeWithTag("quick_add_log_button").performClick()
        assertEquals("800" to "Pasta at work", result)
    }

    @Test fun `tapping Log without label calls onLog with empty label`() {
        var result: Pair<String, String>? = null
        render(onLog = { kcal, label -> result = kcal to label })
        compose.onNodeWithTag("quick_add_kcal").performTextInput("400")
        compose.onNodeWithTag("quick_add_log_button").performClick()
        assertEquals("400" to "", result)
    }

    @Test fun `back button calls onBack`() {
        var called = false
        render(onBack = { called = true })
        compose.onNodeWithText("← Back").performClick()
        assertTrue(called)
    }
}
