package org.branneman.health

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class E2ESmokeTest {

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val args     = InstrumentationRegistry.getArguments()
    private val email    = args.getString("E2E_EMAIL")    ?: "test+e2e@bran.name"
    private val password = args.getString("E2E_PASSWORD").takeIf { !it.isNullOrEmpty() }
        ?: error("E2E_PASSWORD not set")

    @After fun cleanup() {
        runCatching {
            compose.onNodeWithText("Settings", substring = true).performClick()
            compose.onNodeWithText("Sign out", substring = true, ignoreCase = true).performClick()
        }
    }

    private fun signIn() {
        compose.onNodeWithText("Email", substring = true, ignoreCase = true)
            .performTextInput(email)
        compose.onNodeWithText("Password", substring = true, ignoreCase = true)
            .performTextInput(password)
        compose.onNodeWithText("Sign in", ignoreCase = true)
            .performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Today", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun loginViewDashboardLogEntryAndSignOut() {
        signIn()

        compose.onNodeWithText("Log").performClick()

        compose.onNodeWithTag("kcal_input").performTextInput("123")
        compose.onNodeWithText("Add").performClick()

        compose.waitUntil(timeoutMillis = 3_000) {
            compose.onAllNodesWithText("123 kcal").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText("Dashboard").performClick()
        compose.waitUntil(timeoutMillis = 3_000) {
            compose.onAllNodesWithText("Today", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText("Log").performClick()
        compose.onNodeWithText("123 kcal").performClick()
        compose.onNodeWithText("Delete").performClick()
    }

    @Test
    fun logOneTapMealButtonAndVerify() {
        signIn()

        compose.onNodeWithText("Log").performClick()

        // LoginSyncService runs as part of sign-in, so the Breakfast button is already in Room.
        // waitUntil guards against any brief recomposition lag.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Breakfast").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText("Breakfast").filterToOne(hasClickAction()).performClick()

        compose.waitUntil(timeoutMillis = 3_000) {
            compose.onAllNodesWithText("550 kcal").fetchSemanticsNodes().isNotEmpty()
        }

        // Cleanup: delete the entry via the delete-confirm dialog.
        compose.onNodeWithText("550 kcal").performClick()
        compose.onNodeWithText("Delete").performClick()
    }

    @Test
    fun logBodyWeightAndVerify() {
        signIn()

        // The seed SQL leaves no body-weight entry for today, so the chip shows "-- kg".
        compose.onNodeWithText("-- kg", substring = true).performClick()
        compose.onNodeWithText("Log weight").assertIsDisplayed()

        compose.onNode(hasSetTextAction()).performTextInput("82.5")
        compose.onNodeWithText("Save").performClick()

        compose.waitUntil(timeoutMillis = 3_000) {
            compose.onAllNodesWithText("82.5 kg", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
