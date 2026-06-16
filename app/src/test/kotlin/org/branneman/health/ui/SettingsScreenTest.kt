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

    @Test fun `drink buttons row is present and tappable`() {
        var called = false
        compose.setContent {
            MaterialTheme {
                SettingsContent(
                    onNavigateMealButtons = {},
                    onNavigateDrinkButtons = { called = true },
                    onSignOut = {},
                )
            }
        }
        compose.onNodeWithText("Drink buttons", substring = true).performClick()
        assertTrue(called)
    }

    // --- Schedule section ---

    @Test fun `schedule section shows Wake time and Bedtime labels`() {
        compose.setContent {
            MaterialTheme {
                SettingsContent(
                    onNavigateMealButtons = {},
                    onSignOut = {},
                    scheduleState = ScheduleState(wakeTime = "07:00", bedtime = "23:00"),
                )
            }
        }
        compose.onNodeWithText("Wake time", substring = true, ignoreCase = true).assertExists()
        compose.onNodeWithText("Bedtime", substring = true, ignoreCase = true).assertExists()
    }

    @Test fun `tapping wake time plus calls onWakeTimePlus`() {
        var called = false
        compose.setContent {
            MaterialTheme {
                SettingsContent(
                    onNavigateMealButtons = {},
                    onSignOut = {},
                    scheduleState = ScheduleState(wakeTime = "07:00"),
                    onWakeTimePlus = { called = true },
                )
            }
        }
        compose.onAllNodesWithText("+")[0].performClick()
        assertTrue(called)
    }

    @Test fun `Save schedule button hidden when schedule not changed`() {
        compose.setContent {
            MaterialTheme {
                SettingsContent(
                    onNavigateMealButtons = {},
                    onSignOut = {},
                    scheduleState = ScheduleState(
                        wakeTime = "07:00", savedWakeTime = "07:00",
                        bedtime  = "23:00", savedBedtime  = "23:00",
                    ),
                )
            }
        }
        compose.onNodeWithText("Save schedule", substring = true, ignoreCase = true).assertDoesNotExist()
    }

    @Test fun `Save schedule button disabled while saving`() {
        compose.setContent {
            MaterialTheme {
                SettingsContent(
                    onNavigateMealButtons = {},
                    onSignOut = {},
                    scheduleState = ScheduleState(
                        wakeTime = "07:30", savedWakeTime = "07:00",
                        isSaving = true,
                    ),
                )
            }
        }
        compose.onNodeWithText("Save schedule", substring = true, ignoreCase = true).assertIsNotEnabled()
    }
}
