package org.branneman.health

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class E2ESmokeTest {

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val email    = System.getenv("E2E_EMAIL")    ?: "test+e2e@bran.name"
    private val password = System.getenv("E2E_PASSWORD") ?: error("E2E_PASSWORD not set")

    @After fun cleanup() {
        runCatching {
            compose.onNodeWithText("Settings", substring = true).performClick()
            compose.onNodeWithText("Sign out", substring = true, ignoreCase = true).performClick()
        }
    }

    @Test
    fun loginViewDashboardLogEntryAndSignOut() {
        compose.onNodeWithText("Email", substring = true, ignoreCase = true)
            .performTextInput(email)
        compose.onNodeWithText("Password", substring = true, ignoreCase = true)
            .performTextInput(password)
        compose.onNodeWithText("Sign in", substring = true, ignoreCase = true)
            .performClick()

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Today", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText("Log").performClick()

        compose.onNodeWithTag("kcal_input").performTextInput("123")
        compose.onNodeWithText("Add").performClick()

        compose.waitUntil(timeoutMillis = 3_000) {
            compose.onAllNodesWithText("123", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText("Dashboard").performClick()
        compose.waitUntil(timeoutMillis = 3_000) {
            compose.onAllNodesWithText("Today", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText("Log").performClick()
        compose.onNodeWithText("123", substring = true).performClick()
        compose.onNodeWithText("Delete").performClick()
    }
}
