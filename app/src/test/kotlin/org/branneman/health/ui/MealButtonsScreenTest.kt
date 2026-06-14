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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MealButtonsScreenTest {

    @get:Rule val compose = createComposeRule()

    private fun render(
        draft: List<MealTemplateEntity> = emptyList(),
        onSave: (List<MealTemplateEntity>) -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                MealButtonsContent(draft = draft, onSave = onSave, onBack = onBack)
            }
        }
    }

    @Test fun `empty state shows add button and disabled save`() {
        render(draft = emptyList())
        compose.onNodeWithText("Add button").assertExists()
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test fun `save is enabled when a row has name and positive kcal`() {
        val template = aMealTemplate(name = "Breakfast", sortOrder = 0, quickAddKcal = 450)
        render(draft = listOf(template))
        compose.onNodeWithText("Save").assertIsEnabled()
    }

    @Test fun `save is disabled when kcal is zero`() {
        val template = aMealTemplate(name = "Breakfast", sortOrder = 0, quickAddKcal = 0)
        render(draft = listOf(template))
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test fun `save is disabled when name is blank`() {
        val template = aMealTemplate(name = "", sortOrder = 0, quickAddKcal = 400)
        render(draft = listOf(template))
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test fun `tapping save calls onSave with current draft`() {
        var saved: List<MealTemplateEntity>? = null
        val template = aMealTemplate(name = "Usual lunch", sortOrder = 0, quickAddKcal = 600)
        render(draft = listOf(template), onSave = { saved = it })
        compose.onNodeWithText("Save").performClick()
        assertEquals(1, saved?.size)
        assertEquals("Usual lunch", saved?.first()?.name)
    }

    @Test fun `tapping delete removes row from displayed list`() {
        val snack  = aMealTemplate(name = "Usual snack",  sortOrder = 0, quickAddKcal = 200)
        val lunch  = aMealTemplate(name = "Usual lunch",  sortOrder = 1, quickAddKcal = 600)
        render(draft = listOf(snack, lunch))
        compose.onNodeWithContentDescription("Delete Usual snack").performClick()
        compose.onNodeWithText("Usual snack").assertDoesNotExist()
        compose.onNodeWithText("Usual lunch").assertExists()
    }
}
