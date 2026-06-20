package org.branneman.health

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class E2ESmokeTest {

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val email    = "test+e2e@bran.name"
    private val password = BuildConfig.E2E_PASSWORD.takeIf { it.isNotEmpty() }
        ?: error("E2E_PASSWORD not set")

    private fun waitForText(text: String, substring: Boolean = false, ignoreCase: Boolean = false, timeoutMs: Long = 10_000) {
        compose.waitUntil(timeoutMillis = timeoutMs) {
            compose.onAllNodesWithText(text, substring = substring, ignoreCase = ignoreCase).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForTag(tag: String, timeoutMs: Long = 10_000) {
        compose.waitUntil(timeoutMillis = timeoutMs) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @After fun cleanup() {
        runCatching { signOut() }
    }

    private fun signOut() {
        compose.onAllNodesWithText("Settings").filterToOne(hasClickAction()).performClick()
        waitForText("Sign out", ignoreCase = true)
        compose.onNodeWithText("Sign out", ignoreCase = true).performScrollTo().performClick()
        waitForText("Sign out?")
        compose.onAllNodesWithText("Sign out", ignoreCase = true).onLast().performClick()
    }

    private fun signIn() {
        // Wait for the loading splash to resolve to any known state.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Email", substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty() ||
            compose.onAllNodesWithText("Skip for now").fetchSemanticsNodes().isNotEmpty() ||
            compose.onAllNodesWithText("Dashboard").fetchSemanticsNodes().isNotEmpty()
        }
        // If already authenticated (previous test's cleanup didn't sign out), do it now.
        if (compose.onAllNodesWithText("Email", substring = true, ignoreCase = true).fetchSemanticsNodes().isEmpty()) {
            if (compose.onAllNodesWithText("Skip for now").fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithText("Skip for now").performClick()
                waitForText("Dashboard")
            }
            signOut()
            compose.waitUntil(timeoutMillis = 15_000) {
                compose.onAllNodesWithText("Email", substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty()
            }
        }
        compose.onNodeWithText("Email", substring = true, ignoreCase = true)
            .performTextInput(email)
        compose.onNodeWithText("Password", substring = true, ignoreCase = true)
            .performTextInput(password)
        compose.onNodeWithText("Sign in", ignoreCase = true)
            .performClick()
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithText("Today", substring = true).fetchSemanticsNodes().isNotEmpty() ||
            compose.onAllNodesWithText("Skip for now").fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithText("Skip for now").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("Skip for now").performClick()
            waitForText("Today", substring = true)
        }
    }

    @Test
    fun loginViewDashboardLogEntryAndSignOut() {
        signIn()

        compose.onNodeWithText("Log").performClick()
        waitForTag("log_flow_button")
        compose.onNodeWithTag("log_flow_button").performClick()

        waitForTag("log_quick_add")
        compose.onNodeWithTag("log_quick_add").performClick()

        waitForTag("quick_add_kcal")
        compose.onNodeWithTag("quick_add_kcal").performTextInput("123")
        compose.onNodeWithTag("quick_add_log_button").performClick()

        waitForText("123 kcal")

        compose.onNodeWithText("Dashboard").performClick()
        waitForText("Today", substring = true)

        compose.onNodeWithText("Log").performClick()
        waitForText("123 kcal")
        compose.onNodeWithText("123 kcal").performClick()
        waitForText("Delete")
        compose.onNodeWithText("Delete").performClick()
    }

    @Test
    fun logOneTapMealButtonAndVerify() {
        signIn()

        compose.onNodeWithText("Log").performClick()

        // LoginSyncService runs as part of sign-in, so the Breakfast button is already in Room.
        waitForText("Breakfast")
        compose.onAllNodesWithText("Breakfast").filterToOne(hasClickAction()).performClick()

        waitForText("550 kcal")

        compose.onNodeWithText("550 kcal").performClick()
        waitForText("Delete")
        compose.onNodeWithText("Delete").performClick()
    }

    @Test
    fun logBodyWeightAndVerify() {
        signIn()

        // The seed SQL leaves no body-weight entry for today, so the chip shows "-- kg".
        waitForText("-- kg", substring = true)
        compose.onNodeWithText("-- kg", substring = true).performClick()
        waitForText("Log weight")

        compose.onNode(hasSetTextAction()).performTextInput("82.5")
        compose.onNodeWithText("Save").performClick()

        waitForText("82.5 kg", substring = true)
    }
}
