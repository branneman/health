package org.branneman.health.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.branneman.health.aFoodItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FoodSearchScreenTest {

    @get:Rule val rule = createComposeRule()

    private fun launch(
        query: String = "",
        results: List<FoodSearchResult> = emptyList(),
        selectedItem: org.branneman.health.db.entities.FoodItemEntity? = null,
        isOffline: Boolean = false,
        onQueryChange: (String) -> Unit = {},
        onSelectResult: (FoodSearchResult) -> Unit = {},
        onBarcodeButton: () -> Unit = {},
        onManualCreate: (String, Double, Double?, Double?, Double?) -> Unit = { _, _, _, _, _ -> },
        onBack: () -> Unit = {},
    ) {
        rule.setContent {
            FoodSearchContent(
                query           = query,
                results         = results,
                selectedItem    = selectedItem,
                isOffline       = isOffline,
                onQueryChange   = onQueryChange,
                onSelectResult  = onSelectResult,
                onBarcodeButton = onBarcodeButton,
                onManualCreate  = onManualCreate,
                onBack          = onBack,
            )
        }
    }

    @Test fun `empty state shows search field and barcode button`() {
        launch()
        rule.onNodeWithTag("food_search_field").assertExists()
        rule.onNodeWithTag("food_barcode_button").assertExists()
        rule.onNodeWithTag("food_no_results_form").assertDoesNotExist()
    }

    @Test fun `results list shows items`() {
        val item = aFoodItem(name = "Oatmeal")
        launch(results = listOf(FoodSearchResult(item, true)))
        rule.onNodeWithText("Oatmeal").assertIsDisplayed()
    }

    @Test fun `offline notice shown when isOffline is true`() {
        launch(isOffline = true)
        rule.onNodeWithTag("food_offline_notice").assertIsDisplayed()
    }

    @Test fun `manual form appears when no results and query is non-empty`() {
        launch(query = "xyz_nonexistent")
        rule.onNodeWithTag("food_no_results_form").assertExists()
    }

    @Test fun `manual form hidden when query is blank`() {
        launch(query = "")
        rule.onNodeWithTag("food_no_results_form").assertDoesNotExist()
    }
}
