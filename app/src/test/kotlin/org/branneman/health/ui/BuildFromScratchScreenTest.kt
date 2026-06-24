package org.branneman.health.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.branneman.health.aFoodItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BuildFromScratchScreenTest {

    @get:Rule val rule = createComposeRule()

    private fun launch(
        ingredients: List<Ingredient> = emptyList(),
        totalKcal: Int = 0,
        pendingFoodItem: org.branneman.health.db.entities.FoodItemEntity? = null,
        onAddIngredient: () -> Unit = {},
        onRemoveAt: (Int) -> Unit = {},
        onGramsConfirmed: (org.branneman.health.db.entities.FoodItemEntity, Double) -> Unit = { _, _ -> },
        onLog: (String) -> Unit = {},
        onSaveAsTemplate: (String) -> Unit = {},
        onBack: (Int?) -> Unit = {},
    ) {
        rule.setContent {
            BuildFromScratchContent(
                ingredients      = ingredients,
                totalKcal        = totalKcal,
                pendingFoodItem  = pendingFoodItem,
                onAddIngredient  = onAddIngredient,
                onRemoveAt       = onRemoveAt,
                onGramsConfirmed = onGramsConfirmed,
                onLog            = onLog,
                onSaveAsTemplate = onSaveAsTemplate,
                onBack           = onBack,
            )
        }
    }

    @Test fun `Log button disabled when ingredient list is empty`() {
        launch(ingredients = emptyList())
        rule.onNodeWithTag("bfs_log_button").assertIsNotEnabled()
    }

    @Test fun `Log button enabled when list has at least one ingredient`() {
        val item = aFoodItem(kcalPer100g = 200.0)
        launch(ingredients = listOf(Ingredient(item, 100.0)), totalKcal = 200)
        rule.onNodeWithTag("bfs_log_button").assertIsEnabled()
    }

    @Test fun `running kcal total is displayed`() {
        launch(totalKcal = 350)
        rule.onNodeWithText("350 kcal").assertIsDisplayed()
    }

    @Test fun `grams dialog shown when pendingFoodItem is not null`() {
        val item = aFoodItem(name = "Pasta")
        launch(pendingFoodItem = item)
        rule.onNodeWithTag("bfs_grams_dialog").assertExists()
    }

    @Test fun `grams dialog not shown when pendingFoodItem is null`() {
        launch(pendingFoodItem = null)
        rule.onNodeWithTag("bfs_grams_dialog").assertDoesNotExist()
    }

    @Test fun `meal type picker shown after tapping Log button`() {
        val item = aFoodItem(kcalPer100g = 100.0)
        launch(ingredients = listOf(Ingredient(item, 100.0)), totalKcal = 100)
        rule.onNodeWithTag("bfs_log_button").performClick()
        rule.onNodeWithTag("bfs_meal_type_sheet").assertExists()
    }

    @Test fun `bail-out quick-add kcal is total kcal`() {
        val item = aFoodItem(kcalPer100g = 300.0)
        var capturedKcal: Int? = null
        launch(
            ingredients = listOf(Ingredient(item, 100.0)),
            totalKcal   = 300,
            onBack      = { kcal -> capturedKcal = kcal },
        )
        rule.onNodeWithTag("bfs_back_button").performClick()
        // confirm dialog → choose Quick Add
        rule.onNodeWithTag("bfs_bailout_quick_add").performClick()
        assertEquals(300, capturedKcal)
    }
}
