package org.branneman.health.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SettingsScreenTest {

    @get:Rule val compose = createComposeRule()

    @Test fun `Meal buttons row is present and calls onNavigateMealButtons`() {
        var tapped = false
        compose.setContent {
            MaterialTheme {
                SettingsContent(
                    onNavigateMealButtons = { tapped = true },
                    onSignOut = {},
                )
            }
        }
        compose.onNodeWithText("Meal buttons", substring = true).assertExists()
        compose.onNodeWithText("Meal buttons", substring = true).performClick()
        assertTrue(tapped)
    }
}
