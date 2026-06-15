package org.branneman.health.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.branneman.health.aShortcut
import org.branneman.health.db.entities.ShortcutEntity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DrinkButtonsScreenTest {

    @get:Rule val compose = createComposeRule()

    private fun render(
        draft: List<ShortcutEntity> = emptyList(),
        onSave: (List<ShortcutEntity>) -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                DrinkButtonsContent(draft = draft, onSave = onSave, onBack = onBack)
            }
        }
    }

    @Test fun `empty state shows add button and disabled save`() {
        render(draft = emptyList())
        compose.onNodeWithText("Add button").assertExists()
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test fun `save is enabled when a row has emoji, label, and positive kcal`() {
        val shortcut = aShortcut(emoji = "🍺", label = "Pils", kcal = 150, sortOrder = 0)
        render(draft = listOf(shortcut))
        compose.onNodeWithText("Save").assertIsEnabled()
    }

    @Test fun `save is disabled when kcal is zero`() {
        val shortcut = aShortcut(emoji = "🍺", label = "Pils", kcal = 0, sortOrder = 0)
        render(draft = listOf(shortcut))
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test fun `save is disabled when emoji is blank`() {
        val shortcut = aShortcut(emoji = "", label = "Pils", kcal = 150, sortOrder = 0)
        render(draft = listOf(shortcut))
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test fun `save is disabled when label is blank`() {
        val shortcut = aShortcut(emoji = "🍺", label = "", kcal = 150, sortOrder = 0)
        render(draft = listOf(shortcut))
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test fun `configured rows are rendered with emoji, label, and kcal`() {
        val shortcut = aShortcut(emoji = "🍺", label = "Pils", kcal = 150, sortOrder = 0)
        render(draft = listOf(shortcut))
        compose.onNodeWithText("🍺").assertExists()
        compose.onNodeWithText("Pils").assertExists()
        compose.onNodeWithText("150").assertExists()
    }

    @Test fun `tapping delete removes row`() {
        val pils = aShortcut(emoji = "🍺", label = "Pils", kcal = 150, sortOrder = 0)
        val wine = aShortcut(emoji = "🍷", label = "Wine", kcal = 120, sortOrder = 1)
        render(draft = listOf(pils, wine))
        compose.onNodeWithContentDescription("Delete Pils").performClick()
        compose.onNodeWithText("Pils").assertDoesNotExist()
        compose.onNodeWithText("Wine").assertExists()
    }

    @Test fun `add dialog confirm is disabled when all fields are empty`() {
        render()
        compose.onNodeWithText("Add button").performClick()
        compose.onNodeWithText("Add").assertIsNotEnabled()
    }

    @Test fun `add dialog confirm is disabled when emoji is blank`() {
        render()
        compose.onNodeWithText("Add button").performClick()
        compose.onNodeWithText("Label").performTextInput("Pils")
        compose.onAllNodesWithText("kcal")[0].performTextInput("150")
        compose.onNodeWithText("Add").assertIsNotEnabled()
    }

    @Test fun `add dialog confirm is disabled when label is blank`() {
        render()
        compose.onNodeWithText("Add button").performClick()
        compose.onNodeWithText("Emoji").performTextInput("🍺")
        compose.onAllNodesWithText("kcal")[0].performTextInput("150")
        compose.onNodeWithText("Add").assertIsNotEnabled()
    }

    @Test fun `add dialog confirm is disabled when kcal is zero`() {
        render()
        compose.onNodeWithText("Add button").performClick()
        compose.onNodeWithText("Emoji").performTextInput("🍺")
        compose.onNodeWithText("Label").performTextInput("Pils")
        compose.onNodeWithText("Add").assertIsNotEnabled()
    }

    @Test fun `add dialog confirm is enabled when all fields are valid`() {
        render()
        compose.onNodeWithText("Add button").performClick()
        compose.onNodeWithText("Emoji").performTextInput("🍺")
        compose.onNodeWithText("Label").performTextInput("Pils")
        compose.onAllNodesWithText("kcal")[0].performTextInput("150")
        compose.onNodeWithText("Add").assertIsEnabled()
    }

    @Test fun `adding a row via dialog then saving passes new row to onSave`() {
        var saved: List<ShortcutEntity>? = null
        render(onSave = { saved = it })

        compose.onNodeWithText("Add button").performClick()
        compose.onNodeWithText("Emoji").performTextInput("🍺")
        compose.onNodeWithText("Label").performTextInput("Pils")
        compose.onAllNodesWithText("kcal")[0].performTextInput("150")
        compose.onNodeWithText("Add").performClick()

        compose.onNodeWithText("Save").performClick()

        assertEquals(1, saved?.size)
        assertEquals("🍺", saved?.first()?.emoji)
        assertEquals("Pils", saved?.first()?.label)
        assertEquals(150, saved?.first()?.kcal)
    }
}
