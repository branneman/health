package org.branneman.health.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.branneman.health.dashboard.DashboardUiState
import org.branneman.health.dashboard.WeeklyVerdict
import org.branneman.health.db.entities.SportTonightEntity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DashboardScreenTest {

    @get:Rule val compose = createComposeRule()

    private fun render(
        state: DashboardUiState = DashboardUiState(),
        onSetSportTonight: (String, String) -> Unit = { _, _ -> },
        onClearSportTonight: () -> Unit = {},
        onLogWeight: (Double) -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                DashboardContent(
                    state = state,
                    onSetSportTonight = onSetSportTonight,
                    onClearSportTonight = onClearSportTonight,
                    onLogWeight = onLogWeight,
                )
            }
        }
    }

    @Test fun `shows budget remaining as big number`() {
        render(state = DashboardUiState(
            isLoading = false,
            caloriesIn = 0, caloriesOut = 2147, caloriesOutSource = "estimate",
            targetDeficit = 300, caloriesLeft = 1847,
        ))
        compose.onNodeWithText("1847", substring = true).assertExists()
    }

    @Test fun `shows estimated source label`() {
        render(state = DashboardUiState(
            isLoading = false, caloriesOutSource = "estimate", caloriesLeft = 1847, budgetLabel = "left (estimated)",
        ))
        compose.onNodeWithText("estimated", substring = true, ignoreCase = true).assertExists()
    }

    @Test fun `shows calories in and out values`() {
        render(state = DashboardUiState(
            isLoading = false,
            caloriesIn = 520, caloriesOut = 2147, caloriesOutSource = "estimate",
            caloriesLeft = 1327,
        ))
        compose.onNodeWithText("520", substring = true).assertExists()
        compose.onNodeWithText("2147", substring = true).assertExists()
    }

    @Test fun `shows in and out labels`() {
        render(state = DashboardUiState(isLoading = false, caloriesLeft = 1847))
        compose.onNodeWithText("in", substring = true, ignoreCase = true).assertExists()
        compose.onNodeWithText("out", substring = true, ignoreCase = true).assertExists()
    }

    @Test fun `sport tonight inactive shows set button`() {
        render(state = DashboardUiState(isLoading = false, sportTonight = null, caloriesLeft = 1847))
        compose.onNodeWithText("sport tonight", substring = true, ignoreCase = true).assertExists()
    }

    @Test fun `sport tonight active shows activity type`() {
        render(state = DashboardUiState(
            isLoading = false,
            sportTonight = SportTonightEntity(date = "2026-06-11", activityType = "climbing", intensity = "normal", estimatedKcal = 600),
            caloriesLeft = 2447,
        ))
        compose.onNodeWithText("Climbing", substring = true, ignoreCase = true).assertExists()
    }

    @Test fun `sport tonight active shows estimated kcal`() {
        render(state = DashboardUiState(
            isLoading = false,
            sportTonight = SportTonightEntity(date = "2026-06-11", activityType = "climbing", intensity = "normal", estimatedKcal = 600),
            caloriesLeft = 2447,
        ))
        compose.onNodeWithText("600", substring = true).assertExists()
    }

    @Test fun `sport tonight active shows intensity chips`() {
        render(state = DashboardUiState(
            isLoading = false,
            sportTonight = SportTonightEntity(date = "2026-06-11", activityType = "climbing", intensity = "normal", estimatedKcal = 600),
            caloriesLeft = 2447,
        ))
        compose.onNodeWithText("Normal").assertExists()
    }

    @Test fun `tapping intensity chip calls onSetSportTonight`() {
        var called: Pair<String, String>? = null
        render(
            state = DashboardUiState(
                isLoading = false,
                sportTonight = SportTonightEntity(date = "2026-06-11", activityType = "climbing", intensity = "normal", estimatedKcal = 600),
                caloriesLeft = 2447,
            ),
            onSetSportTonight = { a, i -> called = a to i },
        )
        compose.onNodeWithText("Hard").performClick()
        assert(called == "climbing" to "hard")
    }

    @Test fun `tapping clear calls onClearSportTonight`() {
        var cleared = false
        render(
            state = DashboardUiState(
                isLoading = false,
                sportTonight = SportTonightEntity(date = "2026-06-11", activityType = "rowing", intensity = "normal", estimatedKcal = 600),
                caloriesLeft = 2447,
            ),
            onClearSportTonight = { cleared = true },
        )
        compose.onNodeWithText("clear", substring = true, ignoreCase = true).performScrollTo().performClick()
        assert(cleared)
    }

    // --- Weight chip ---

    @Test fun `weight chip shows dashes when not logged today`() {
        render(state = DashboardUiState(isLoading = false, weightKgToday = null))
        compose.onNodeWithText("-- kg", substring = true).assertExists()
    }

    @Test fun `weight chip shows value when logged today`() {
        render(state = DashboardUiState(isLoading = false, weightKgToday = 82.5))
        compose.onNodeWithText("82.5 kg", substring = true).assertExists()
    }

    @Test fun `tapping weight chip opens log weight dialog`() {
        render(state = DashboardUiState(isLoading = false, weightKgToday = null))
        compose.onNodeWithText("-- kg", substring = true).performClick()
        compose.onNodeWithText("Log weight").assertExists()
    }

    @Test fun `tapping logged weight chip also opens dialog`() {
        render(state = DashboardUiState(isLoading = false, weightKgToday = 82.5))
        compose.onNodeWithText("82.5 kg", substring = true).performClick()
        compose.onNodeWithText("Log weight").assertExists()
    }

    @Test fun `save button is disabled with no input`() {
        render(state = DashboardUiState(isLoading = false, weightKgToday = null))
        compose.onNodeWithText("-- kg", substring = true).performClick()
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test fun `save button is enabled for valid weight`() {
        render(state = DashboardUiState(isLoading = false, weightKgToday = null))
        compose.onNodeWithText("-- kg", substring = true).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("82.5")
        compose.onNodeWithText("Save").assertIsEnabled()
    }

    @Test fun `save button is disabled for two decimal places`() {
        render(state = DashboardUiState(isLoading = false, weightKgToday = null))
        compose.onNodeWithText("-- kg", substring = true).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("82.55")
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test fun `tapping save calls onLogWeight with parsed kg`() {
        var logged: Double? = null
        render(
            state = DashboardUiState(isLoading = false, weightKgToday = null),
            onLogWeight = { logged = it },
        )
        compose.onNodeWithText("-- kg", substring = true).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("82.5")
        compose.onNodeWithText("Save").performClick()
        assertEquals(82.5, logged)
    }

    @Test fun `dialog pre-fills with current value when editing`() {
        render(state = DashboardUiState(isLoading = false, weightKgToday = 82.5))
        compose.onNodeWithText("82.5 kg", substring = true).performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("82.5")
    }

    // --- budgetLabel ---

    @Test fun `shows left label when budgetLabel is left`() {
        render(state = DashboardUiState(isLoading = false, caloriesLeft = 1847, budgetLabel = "left"))
        compose.onNodeWithText("left", substring = false, ignoreCase = true).assertExists()
    }

    @Test fun `shows kcal over and negative number when over budget`() {
        render(state = DashboardUiState(isLoading = false, caloriesLeft = -200, budgetLabel = "kcal over"))
        compose.onNodeWithText("-200", substring = true).assertExists()
        compose.onNodeWithText("kcal over", substring = true, ignoreCase = true).assertExists()
    }

    @Test fun `shows left balance label`() {
        render(state = DashboardUiState(isLoading = false, caloriesLeft = 2100, budgetLabel = "left (balance)"))
        compose.onNodeWithText("left (balance)", substring = true, ignoreCase = true).assertExists()
    }

    // --- Weekly verdict card ---

    @Test fun `green verdict shows message on green card`() {
        render(state = DashboardUiState(isLoading = false, weeklyVerdict = WeeklyVerdict.Green(-0.3)))
        compose.onNodeWithText("Down 0.3 kg this week — on track.").assertExists()
        compose.onNodeWithTag("verdict-green").assertExists()
    }

    @Test fun `amber behind verdict shows message on amber card`() {
        render(state = DashboardUiState(isLoading = false, weeklyVerdict = WeeklyVerdict.AmberBehind(0.0)))
        compose.onNodeWithText("Flat this week — slightly behind.").assertExists()
        compose.onNodeWithTag("verdict-amber").assertExists()
    }

    @Test fun `amber fast verdict shows message on amber card`() {
        render(state = DashboardUiState(isLoading = false, weeklyVerdict = WeeklyVerdict.AmberFast(-0.8)))
        compose.onNodeWithText("Down 0.8 kg this week — dropping quickly, watch your intake.").assertExists()
        compose.onNodeWithTag("verdict-amber").assertExists()
    }

    @Test fun `grace period shows neutral card`() {
        render(state = DashboardUiState(isLoading = false, weeklyVerdict = WeeklyVerdict.GracePeriod))
        compose.onNodeWithText("Building baseline — keep logging.").assertExists()
        compose.onNodeWithTag("verdict-neutral").assertExists()
    }

    @Test fun `not enough data shows neutral card`() {
        render(state = DashboardUiState(isLoading = false, weeklyVerdict = WeeklyVerdict.NotEnoughData))
        compose.onNodeWithText("Not enough weigh-ins for a reliable verdict this week.").assertExists()
        compose.onNodeWithTag("verdict-neutral").assertExists()
    }

    @Test fun `no verdict hides the weekly zone`() {
        render(state = DashboardUiState(isLoading = false, weeklyVerdict = null))
        compose.onNodeWithText("This week").assertDoesNotExist()
        compose.onNodeWithTag("verdict-green").assertDoesNotExist()
        compose.onNodeWithTag("verdict-amber").assertDoesNotExist()
        compose.onNodeWithTag("verdict-neutral").assertDoesNotExist()
    }
}
