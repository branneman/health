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
class GoalSettingsScreenTest {

    @get:Rule val compose = createComposeRule()

    private val loaded = ProfileSettingsUiState(
        isLoading          = false,
        activityLevel      = "lightly_active",
        targetDeficit      = 300,
        savedActivityLevel = "lightly_active",
        savedTargetDeficit = 300,
    )

    @Test fun `shows activity level options`() {
        compose.setContent { MaterialTheme { GoalSettingsContent(loaded, null, {}, {}, {}) } }
        compose.onNodeWithText("Mostly sitting", substring = true).assertExists()
        compose.onNodeWithText("Lightly active", substring = true).assertExists()
        compose.onNodeWithText("Moderately active", substring = true).assertExists()
    }

    @Test fun `Save disabled when goalDirty is false`() {
        compose.setContent { MaterialTheme { GoalSettingsContent(loaded, null, {}, {}, {}) } }
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test fun `Save enabled and unsaved note shown when goalDirty`() {
        val dirty = loaded.copy(targetDeficit = 400)
        compose.setContent { MaterialTheme { GoalSettingsContent(dirty, null, {}, {}, {}) } }
        compose.onNodeWithText("Save").assertIsEnabled()
        compose.onNodeWithText("Unsaved changes", substring = true).assertExists()
    }

    @Test fun `Save calls onSave`() {
        val dirty = loaded.copy(targetDeficit = 400)
        var saved = false
        compose.setContent { MaterialTheme { GoalSettingsContent(dirty, null, {}, onSave = { saved = true }, {}) } }
        compose.onNodeWithText("Save").performClick()
        assertTrue(saved)
    }

    @Test fun `shows kg-per-week estimate when deficit is nonzero`() {
        compose.setContent { MaterialTheme { GoalSettingsContent(loaded, null, {}, {}, {}) } }
        compose.onNodeWithText("kg/week", substring = true).assertExists()
    }

    @Test fun `warning shown when deficit exceeds 500`() {
        compose.setContent { MaterialTheme { GoalSettingsContent(loaded.copy(targetDeficit = 550), null, {}, {}, {}) } }
        compose.onNodeWithText("muscle loss", substring = true).assertExists()
    }

    @Test fun `back shows discard dialog when goalDirty`() {
        compose.setContent { MaterialTheme { GoalSettingsContent(loaded.copy(targetDeficit = 400), null, {}, {}, {}) } }
        compose.onNodeWithText("← Back").performClick()
        compose.onNodeWithText("Discard unsaved changes?").assertExists()
    }
}
