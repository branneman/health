package org.branneman.health.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import kotlin.test.assertTrue
import org.branneman.health.aLogEntry
import org.branneman.health.aLogEntryWithKcal
import org.branneman.health.aMealTemplate
import org.branneman.health.aQuickAddEntry
import org.branneman.health.aShortcut
import org.branneman.health.db.dao.LogEntryWithKcal
import org.branneman.health.db.entities.LogEntryEntity
import org.branneman.health.db.entities.MealTemplateEntity
import org.branneman.health.db.entities.ShortcutEntity
import kotlin.test.assertEquals
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
        entries: List<LogEntryWithKcal> = emptyList(),
        onDelete: (LogEntryEntity) -> Unit = {},
        onEdit: (LogEntryEntity, Int, String?) -> Unit = { _, _, _ -> },
        onOpenLogFlow: () -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                LogContent(
                    entries       = entries,
                    onDelete      = onDelete,
                    onEdit        = onEdit,
                    onOpenLogFlow = onOpenLogFlow,
                )
            }
        }
    }

    @Test fun `Log button is present`() {
        render()
        compose.onNodeWithTag("log_flow_button").assertExists()
    }

    @Test fun `tapping Log button calls onOpenLogFlow`() {
        var called = false
        render(onOpenLogFlow = { called = true })
        compose.onNodeWithTag("log_flow_button").performClick()
        assertTrue(called)
    }

    @Test fun `inline kcal input does not exist`() {
        render()
        compose.onNodeWithTag("kcal_input").assertDoesNotExist()
    }

    @Test fun `entries appear in list`() {
        val entries = listOf(
            aLogEntryWithKcal(aQuickAddEntry(loggedAt = "2026-06-11T13:00:00Z", quickAddKcal = 560, quickAddLabel = "Lunch")),
        )
        render(entries = entries)
        compose.onNodeWithText("Lunch", substring = true).assertExists()
        assertTrue(compose.onAllNodesWithText("560", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun `empty state shows nothing-logged message`() {
        render(entries = emptyList())
        compose.onNodeWithText("Nothing logged today.", substring = true).assertExists()
    }

    @Test fun `tapping food-item entry opens delete dialog and delete calls onDelete`() {
        val rawEntry = aLogEntry(loggedAt = "2026-06-11T08:00:00Z", mealType = "breakfast")
        val entry = aLogEntryWithKcal(rawEntry)
        var deleted: LogEntryEntity? = null
        render(entries = listOf(entry), onDelete = { deleted = it })
        compose.onNodeWithText("Breakfast", substring = true).performClick()
        compose.onNodeWithText("Delete").performClick()
        assert(deleted?.id == rawEntry.id)
    }

    @Test fun `tapping quick-add entry opens edit dialog with pre-populated kcal`() {
        val entry = aLogEntryWithKcal(
            aQuickAddEntry(loggedAt = "2026-06-11T08:00:00Z", quickAddKcal = 430, quickAddLabel = "Lunch")
        )
        render(entries = listOf(entry))
        compose.onNodeWithText("Lunch", substring = true).performClick()
        compose.onNodeWithTag("edit_entry_kcal_field").assertExists()
        compose.onNodeWithTag("edit_entry_kcal_field").assertTextContains("430")
    }

    @Test fun `save in edit dialog calls onEdit`() {
        val rawEntry = aQuickAddEntry(
            loggedAt = "2026-06-11T08:00:00Z", quickAddKcal = 430, quickAddLabel = "Lunch"
        )
        val entry = aLogEntryWithKcal(rawEntry)
        var editedId: String? = null
        var editedKcal: Int? = null
        render(
            entries = listOf(entry),
            onEdit  = { e, kcal, _ -> editedId = e.id; editedKcal = kcal },
        )
        compose.onNodeWithText("Lunch", substring = true).performClick()
        compose.onNodeWithTag("edit_entry_kcal_field").performTextClearance()
        compose.onNodeWithTag("edit_entry_kcal_field").performTextInput("600")
        compose.onNodeWithTag("edit_entry_save_button").performClick()
        assertEquals(rawEntry.id, editedId)
        assertEquals(600, editedKcal)
    }

    @Test fun `save button is disabled when kcal field is empty`() {
        val entry = aLogEntryWithKcal(
            aQuickAddEntry(loggedAt = "2026-06-11T08:00:00Z", quickAddKcal = 430)
        )
        render(entries = listOf(entry))
        compose.onNodeWithText("Unknown", substring = true).performClick()
        compose.onNodeWithTag("edit_entry_kcal_field").performTextClearance()
        compose.onNodeWithTag("edit_entry_save_button").assertIsNotEnabled()
    }

    private fun renderWithTemplates(
        entries: List<LogEntryWithKcal> = emptyList(),
        pinned: List<MealTemplateEntity> = emptyList(),
        onDelete: (LogEntryEntity) -> Unit = {},
        onSetUpMealButtons: () -> Unit = {},
        onLogTemplate: (MealTemplateEntity) -> Unit = {},
        onOpenLogFlow: () -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                LogContent(
                    entries            = entries,
                    pinnedTemplates    = pinned,
                    onDelete           = onDelete,
                    onSetUpMealButtons = onSetUpMealButtons,
                    onLogTemplate      = onLogTemplate,
                    onOpenLogFlow      = onOpenLogFlow,
                )
            }
        }
    }

    @Test fun `shows set-up button when no pinned templates`() {
        renderWithTemplates(pinned = emptyList())
        compose.onNodeWithText("Set up meal buttons", substring = true).assertExists()
    }

    @Test fun `shows template buttons when pinned templates exist`() {
        val template = aMealTemplate(name = "Usual breakfast", sortOrder = 0, quickAddKcal = 450)
        renderWithTemplates(pinned = listOf(template))
        compose.onNodeWithText("Usual breakfast").assertExists()
    }

    @Test fun `tapping a template button calls onLogTemplate`() {
        var logged: MealTemplateEntity? = null
        val template = aMealTemplate(name = "Usual breakfast", sortOrder = 0, quickAddKcal = 450)
        renderWithTemplates(
            pinned = listOf(template),
            onLogTemplate = { logged = it },
        )
        compose.onNodeWithText("Usual breakfast").performClick()
        assertEquals(template.id, logged?.id)
    }

    @Test fun `tapping set-up button calls onSetUpMealButtons`() {
        var tapped = false
        renderWithTemplates(pinned = emptyList(), onSetUpMealButtons = { tapped = true })
        compose.onNodeWithText("Set up meal buttons", substring = true).performClick()
        assertTrue(tapped)
    }

    private fun renderWithShortcuts(
        entries: List<LogEntryWithKcal> = emptyList(),
        shortcuts: List<ShortcutEntity> = emptyList(),
        onDelete: (LogEntryEntity) -> Unit = {},
        onSetUpDrinkButtons: () -> Unit = {},
        onLogShortcut: (ShortcutEntity) -> Unit = {},
        onOpenLogFlow: () -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                LogContent(
                    entries             = entries,
                    shortcuts           = shortcuts,
                    onDelete            = onDelete,
                    onSetUpDrinkButtons = onSetUpDrinkButtons,
                    onLogShortcut       = onLogShortcut,
                    onOpenLogFlow       = onOpenLogFlow,
                )
            }
        }
    }

    @Test
    fun `empty shortcuts shows setup button`() {
        renderWithShortcuts(shortcuts = emptyList())
        compose.onNodeWithText("Set up drink buttons").assertExists()
    }

    @Test
    fun `tapping setup button calls onSetUpDrinkButtons`() {
        var called = false
        renderWithShortcuts(shortcuts = emptyList(), onSetUpDrinkButtons = { called = true })
        compose.onNodeWithText("Set up drink buttons").performClick()
        assertTrue(called)
    }

    @Test
    fun `configured shortcuts render a button per shortcut`() {
        val shortcut = aShortcut(emoji = "🍺", label = "Pils", kcal = 150)
        renderWithShortcuts(shortcuts = listOf(shortcut))
        compose.onNodeWithText("🍺 Pils").assertExists()
    }

    @Test
    fun `tapping shortcut calls onLogShortcut with correct entity`() {
        var logged: ShortcutEntity? = null
        val shortcut = aShortcut(emoji = "🍺", label = "Pils", kcal = 150)
        renderWithShortcuts(shortcuts = listOf(shortcut), onLogShortcut = { logged = it })
        compose.onNodeWithText("🍺 Pils").performClick()
        assertEquals(shortcut, logged)
    }
}
