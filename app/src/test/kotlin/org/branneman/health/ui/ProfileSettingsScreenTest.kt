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
class ProfileSettingsScreenTest {

    @get:Rule val compose = createComposeRule()

    private val loaded = ProfileSettingsUiState(
        isLoading         = false,
        sex               = "male",
        heightCm          = "182",
        age               = "34",
        goalWeightKg      = "78.0",
        savedSex          = "male",
        savedHeightCm     = "182",
        savedAge          = "34",
        savedGoalWeightKg = "78.0",
    )

    @Test fun `shows loading indicator while isLoading`() {
        compose.setContent { MaterialTheme { ProfileSettingsContent(ProfileSettingsUiState(isLoading = true), null, {}, {}, {}) } }
        compose.onNodeWithText("Loading…").assertExists()
    }

    @Test fun `pre-populates fields from state`() {
        compose.setContent { MaterialTheme { ProfileSettingsContent(loaded, null, {}, {}, {}) } }
        compose.onNodeWithText("182").assertExists()
        compose.onNodeWithText("34").assertExists()
        compose.onNodeWithText("78.0").assertExists()
    }

    @Test fun `Save button disabled when profileDirty is false`() {
        compose.setContent { MaterialTheme { ProfileSettingsContent(loaded, null, {}, {}, {}) } }
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test fun `Save enabled and unsaved note shown when profileDirty`() {
        val dirty = loaded.copy(age = "35")
        compose.setContent { MaterialTheme { ProfileSettingsContent(dirty, null, {}, {}, {}) } }
        compose.onNodeWithText("Save").assertIsEnabled()
        compose.onNodeWithText("Unsaved changes", substring = true).assertExists()
    }

    @Test fun `Save button calls onSave`() {
        val dirty = loaded.copy(age = "35")
        var saved = false
        compose.setContent { MaterialTheme { ProfileSettingsContent(dirty, null, {}, onSave = { saved = true }, {}) } }
        compose.onNodeWithText("Save").performClick()
        assertTrue(saved)
    }

    @Test fun `goal weight warning shown when goal exceeds latest weight`() {
        compose.setContent {
            MaterialTheme { ProfileSettingsContent(loaded.copy(goalWeightKg = "90.0"), latestWeightKg = 82.0, {}, {}, {}) }
        }
        compose.onNodeWithText("above your current weight", substring = true).assertExists()
    }

    @Test fun `back button calls onBack when not dirty`() {
        var backCalled = false
        compose.setContent { MaterialTheme { ProfileSettingsContent(loaded, null, {}, {}, onBack = { backCalled = true }) } }
        compose.onNodeWithText("← Back").performClick()
        assertTrue(backCalled)
    }

    @Test fun `back button shows discard dialog when profileDirty`() {
        compose.setContent { MaterialTheme { ProfileSettingsContent(loaded.copy(age = "35"), null, {}, {}, {}) } }
        compose.onNodeWithText("← Back").performClick()
        compose.onNodeWithText("Discard unsaved changes?").assertExists()
    }
}
