package org.branneman.health.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.branneman.health.aMealTemplate
import org.branneman.health.db.entities.MealTemplateEntity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TemplatesScreenTest {

    @get:Rule val compose = createComposeRule()

    private fun render(
        templates: List<MealTemplateEntity> = emptyList(),
        onCreate: (String, Int) -> Unit = { _, _ -> },
        onUpdate: (String, String, Int) -> Unit = { _, _, _ -> },
        onDelete: (String) -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                TemplatesContent(
                    templates = templates,
                    onCreate  = onCreate,
                    onUpdate  = onUpdate,
                    onDelete  = onDelete,
                    onBack    = onBack,
                )
            }
        }
    }

    @Test fun `empty state shows guidance`() {
        render()
        compose.onNodeWithText("No templates yet", substring = true).assertExists()
    }

    @Test fun `templates are listed`() {
        val t = aMealTemplate(name = "Pasta bolognese", quickAddKcal = 720)
        render(templates = listOf(t))
        compose.onNodeWithText("Pasta bolognese").assertExists()
        compose.onNodeWithText("720 kcal").assertExists()
    }

    @Test fun `pinned template shows pin indicator`() {
        val t = aMealTemplate(name = "Breakfast", sortOrder = 0, quickAddKcal = 450)
        render(templates = listOf(t))
        compose.onNodeWithText("📌 Breakfast").assertExists()
    }

    @Test fun `add dialog Save is disabled when name is blank`() {
        render()
        compose.onNodeWithTag("add_template_button").performClick()
        compose.onNodeWithTag("add_template_kcal_total").performClick()
        compose.onNodeWithTag("template_kcal_field").performTextInput("500")
        compose.onNodeWithTag("template_save_button").assertIsNotEnabled()
    }

    @Test fun `add dialog Save is disabled when kcal is empty`() {
        render()
        compose.onNodeWithTag("add_template_button").performClick()
        compose.onNodeWithTag("add_template_kcal_total").performClick()
        compose.onNodeWithTag("template_name_field").performTextInput("Pasta")
        compose.onNodeWithTag("template_save_button").assertIsNotEnabled()
    }

    @Test fun `add dialog calls onCreate with name and kcal`() {
        var result: Pair<String, Int>? = null
        render(onCreate = { name, kcal -> result = name to kcal })
        compose.onNodeWithTag("add_template_button").performClick()
        compose.onNodeWithTag("add_template_kcal_total").performClick()
        compose.onNodeWithTag("template_name_field").performTextInput("Chicken soup")
        compose.onNodeWithTag("template_kcal_field").performTextInput("380")
        compose.onNodeWithTag("template_save_button").performClick()
        assertEquals("Chicken soup" to 380, result)
    }

    @Test fun `tapping existing template opens edit dialog pre-filled`() {
        val t = aMealTemplate(name = "Old pasta", quickAddKcal = 700)
        render(templates = listOf(t))
        compose.onNodeWithTag("template_item_${t.id}").performClick()
        compose.onNodeWithTag("template_name_field").assertTextContains("Old pasta")
        compose.onNodeWithTag("template_kcal_field").assertTextContains("700")
    }

    @Test fun `edit dialog calls onUpdate`() {
        val t = aMealTemplate(name = "Old name", quickAddKcal = 400)
        var result: Triple<String, String, Int>? = null
        render(
            templates = listOf(t),
            onUpdate  = { id, name, kcal -> result = Triple(id, name, kcal) },
        )
        compose.onNodeWithTag("template_item_${t.id}").performClick()
        compose.onNodeWithTag("template_name_field").performTextClearance()
        compose.onNodeWithTag("template_name_field").performTextInput("New name")
        compose.onNodeWithTag("template_kcal_field").performTextClearance()
        compose.onNodeWithTag("template_kcal_field").performTextInput("600")
        compose.onNodeWithTag("template_save_button").performClick()
        assertEquals(Triple(t.id, "New name", 600), result)
    }

    @Test fun `edit dialog calls onDelete`() {
        val t = aMealTemplate(name = "To delete", quickAddKcal = 500)
        var deletedId: String? = null
        render(templates = listOf(t), onDelete = { deletedId = it })
        compose.onNodeWithTag("template_item_${t.id}").performClick()
        compose.onNodeWithTag("template_delete_button").performClick()
        assertEquals(t.id, deletedId)
    }

    @Test fun `back button calls onBack`() {
        var called = false
        render(onBack = { called = true })
        compose.onNodeWithText("← Back").performClick()
        assertTrue(called)
    }
}
