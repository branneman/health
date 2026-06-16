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
class ScheduleSettingsScreenTest {

    @get:Rule val compose = createComposeRule()

    private val loaded = ProfileSettingsUiState(
        isLoading     = false,
        wakeTime      = "07:00",
        bedtime       = "23:00",
        savedWakeTime = "07:00",
        savedBedtime  = "23:00",
    )

    @Test fun `shows wake time and bedtime`() {
        compose.setContent { MaterialTheme { ScheduleSettingsContent(loaded, {}, {}, {}) } }
        compose.onNodeWithText("Wake time", substring = true).assertExists()
        compose.onNodeWithText("Bedtime", substring = true).assertExists()
        compose.onNodeWithText("07:00").assertExists()
        compose.onNodeWithText("23:00").assertExists()
    }

    @Test fun `Save disabled when scheduleDirty is false`() {
        compose.setContent { MaterialTheme { ScheduleSettingsContent(loaded, {}, {}, {}) } }
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test fun `Save enabled and unsaved note shown when scheduleDirty`() {
        val dirty = loaded.copy(wakeTime = "06:30")
        compose.setContent { MaterialTheme { ScheduleSettingsContent(dirty, {}, {}, {}) } }
        compose.onNodeWithText("Save").assertIsEnabled()
        compose.onNodeWithText("Unsaved changes", substring = true).assertExists()
    }

    @Test fun `Save calls onSave`() {
        val dirty = loaded.copy(wakeTime = "06:30")
        var saved = false
        compose.setContent { MaterialTheme { ScheduleSettingsContent(dirty, {}, onSave = { saved = true }, {}) } }
        compose.onNodeWithText("Save").performClick()
        assertTrue(saved)
    }

    @Test fun `back shows discard dialog when scheduleDirty`() {
        compose.setContent { MaterialTheme { ScheduleSettingsContent(loaded.copy(wakeTime = "06:30"), {}, {}, {}) } }
        compose.onNodeWithText("← Back").performClick()
        compose.onNodeWithText("Discard unsaved changes?").assertExists()
    }

    @Test fun `back calls onBack immediately when clean`() {
        var backCalled = false
        compose.setContent { MaterialTheme { ScheduleSettingsContent(loaded, {}, {}, onBack = { backCalled = true }) } }
        compose.onNodeWithText("← Back").performClick()
        assertTrue(backCalled)
    }
}
