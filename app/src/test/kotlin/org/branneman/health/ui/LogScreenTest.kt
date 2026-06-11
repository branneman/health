package org.branneman.health.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlin.test.assertTrue
import org.branneman.health.aQuickAddEntry
import org.branneman.health.db.entities.LogEntryEntity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LogScreenTest {

    @get:Rule val compose = createComposeRule()

    private fun render(
        entries: List<LogEntryEntity> = emptyList(),
        onAdd: (String, String) -> Unit = { _, _ -> },
        onDelete: (LogEntryEntity) -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                LogContent(entries = entries, onAdd = onAdd, onDelete = onDelete)
            }
        }
    }

    @Test fun `Add button is disabled when kcal field is empty`() {
        render()
        compose.onNodeWithText("Add").assertIsNotEnabled()
    }

    @Test fun `Add button is enabled when positive kcal is entered`() {
        render()
        compose.onNodeWithTag("kcal_input").performTextInput("350")
        compose.onNodeWithText("Add").assertIsEnabled()
    }

    @Test fun `Add button is disabled when kcal is zero`() {
        render()
        compose.onNodeWithTag("kcal_input").performTextInput("0")
        compose.onNodeWithText("Add").assertIsNotEnabled()
    }

    @Test fun `entries appear in list`() {
        val entries = listOf(
            aQuickAddEntry(loggedAt = "2026-06-11T13:00:00Z", quickAddKcal = 560, quickAddLabel = "Lunch"),
        )
        render(entries = entries)
        compose.onNodeWithText("Lunch", substring = true).assertExists()
        assertTrue(compose.onAllNodesWithText("560", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun `empty state shows nothing-logged message`() {
        render(entries = emptyList())
        compose.onNodeWithText("Nothing logged today.", substring = true).assertExists()
    }

    @Test fun `tapping entry calls onDelete`() {
        val entry = aQuickAddEntry(loggedAt = "2026-06-11T08:00:00Z", quickAddKcal = 430, quickAddLabel = "Breakfast")
        var deleted: LogEntryEntity? = null
        render(entries = listOf(entry), onDelete = { deleted = it })
        compose.onNodeWithText("Breakfast", substring = true).performClick()
        compose.onNodeWithText("Delete").performClick()
        assert(deleted?.id == entry.id)
    }

    @Test fun `tapping Add calls onAdd with kcal and label`() {
        var result: Pair<String, String>? = null
        render(onAdd = { kcal, label -> result = kcal to label })
        compose.onNodeWithTag("kcal_input").performTextInput("350")
        compose.onNodeWithTag("label_input").performTextInput("Pasta")
        compose.onNodeWithText("Add").performClick()
        assert(result == "350" to "Pasta")
    }
}
