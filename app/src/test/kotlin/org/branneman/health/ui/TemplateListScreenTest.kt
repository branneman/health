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
class TemplateListScreenTest {

    @get:Rule val compose = createComposeRule()

    private fun render(
        templates: List<MealTemplateEntity> = emptyList(),
        onLogTemplate: (MealTemplateEntity, Float) -> Unit = { _, _ -> },
        onBack: () -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                TemplateListContent(
                    templates     = templates,
                    onLogTemplate = onLogTemplate,
                    onBack        = onBack,
                )
            }
        }
    }

    @Test fun `empty state shows guidance message`() {
        render(templates = emptyList())
        compose.onNodeWithText("No templates yet", substring = true).assertExists()
    }

    @Test fun `pinned template shows pin indicator`() {
        val t = aMealTemplate(name = "Breakfast", sortOrder = 0, quickAddKcal = 450)
        render(templates = listOf(t))
        compose.onNodeWithText("📌 Breakfast").assertExists()
    }

    @Test fun `unpinned template has no pin indicator`() {
        val t = aMealTemplate(name = "Pasta", sortOrder = null, quickAddKcal = 700)
        render(templates = listOf(t))
        compose.onNodeWithText("Pasta").assertExists()
        compose.onNodeWithText("📌 Pasta").assertDoesNotExist()
    }

    @Test fun `tapping a template opens portion adjuster`() {
        val t = aMealTemplate(name = "Stir-fry", quickAddKcal = 620)
        render(templates = listOf(t))
        compose.onNodeWithText("Stir-fry").performClick()
        compose.onNodeWithTag("portion_option_0").assertExists() // Lighter
        compose.onNodeWithTag("portion_option_1").assertExists() // Normal
        compose.onNodeWithTag("portion_option_2").assertExists() // Heavier
    }

    @Test fun `tapping Log in adjuster calls onLogTemplate with multiplier 1_0`() {
        var logged: Pair<MealTemplateEntity, Float>? = null
        val t = aMealTemplate(name = "Dinner", quickAddKcal = 800)
        render(
            templates = listOf(t),
            onLogTemplate = { template, mult -> logged = template to mult },
        )
        compose.onNodeWithText("Dinner").performClick()
        compose.onNodeWithTag("portion_log_button").performClick()
        assertEquals(t.id, logged?.first?.id)
        assertEquals(1.0f, logged?.second)
    }

    @Test fun `selecting Lighter then Log calls onLogTemplate with 0_8`() {
        var logged: Pair<MealTemplateEntity, Float>? = null
        val t = aMealTemplate(name = "Dinner", quickAddKcal = 800)
        render(
            templates = listOf(t),
            onLogTemplate = { template, mult -> logged = template to mult },
        )
        compose.onNodeWithText("Dinner").performClick()
        compose.onNodeWithTag("portion_option_0").performClick()
        compose.onNodeWithTag("portion_log_button").performClick()
        assertEquals(0.8f, logged?.second)
    }

    @Test fun `back button calls onBack`() {
        var called = false
        render(onBack = { called = true })
        compose.onNodeWithText("← Back").performClick()
        assertTrue(called)
    }

    @Test fun `Normal is default selection in portion adjuster`() {
        var logged: Float? = null
        val t = aMealTemplate(name = "Soup", quickAddKcal = 400)
        render(
            templates = listOf(t),
            onLogTemplate = { _, mult -> logged = mult },
        )
        compose.onNodeWithText("Soup").performClick()
        compose.onNodeWithTag("portion_log_button").performClick()
        assertEquals(1.0f, logged) // Default selection = Normal = 1.0f
    }
}
