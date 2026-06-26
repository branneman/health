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
class SingleItemLogScreenTest {

    @get:Rule val compose = createComposeRule()

    private fun render(
        foodName: String = "Apple",
        kcalPer100g: Double = 200.0,
        onLog: (Int) -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                SingleItemLogContent(
                    foodName    = foodName,
                    kcalPer100g = kcalPer100g,
                    onLog       = onLog,
                    onBack      = onBack,
                )
            }
        }
    }

    @Test fun `Log button is disabled when grams field is empty`() {
        render()
        compose.onNodeWithTag("single_item_log_button").assertIsNotEnabled()
    }

    @Test fun `Log button is disabled when grams is zero`() {
        render()
        compose.onNodeWithTag("single_item_grams_field").performTextInput("0")
        compose.onNodeWithTag("single_item_log_button").assertIsNotEnabled()
    }

    @Test fun `kcal preview appears and is correct when grams are entered`() {
        render(kcalPer100g = 200.0)
        compose.onNodeWithTag("single_item_grams_field").performTextInput("150")
        // 150g * 200 kcal/100g = 300 kcal
        compose.onNodeWithTag("single_item_kcal_preview").assertTextContains("300 kcal")
    }

    @Test fun `kcal preview is not shown when grams field is empty`() {
        render()
        compose.onNodeWithTag("single_item_kcal_preview").assertDoesNotExist()
    }

    @Test fun `tapping Log calls onLog with correct kcal`() {
        var logged: Int? = null
        render(kcalPer100g = 200.0, onLog = { logged = it })
        compose.onNodeWithTag("single_item_grams_field").performTextInput("150")
        compose.onNodeWithTag("single_item_log_button").performClick()
        kotlin.test.assertEquals(300, logged)
    }

    @Test fun `back button calls onBack`() {
        var called = false
        render(onBack = { called = true })
        compose.onNodeWithText("← Back").performClick()
        assertTrue(called)
    }

    @Test fun `food name is shown as title`() {
        render(foodName = "Banana")
        compose.onNodeWithText("Banana").assertIsDisplayed()
    }
}
