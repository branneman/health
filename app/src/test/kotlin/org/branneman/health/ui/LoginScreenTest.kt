package org.branneman.health.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LoginScreenTest {

    @get:Rule val compose = createComposeRule()

    private fun render(
        sessionExpired: Boolean = false,
        isLoading: Boolean = false,
        errorMessage: String? = null,
        onSignIn: (String, String) -> Unit = { _, _ -> },
    ) {
        compose.setContent {
            LoginScreen(
                sessionExpired = sessionExpired,
                isLoading = isLoading,
                errorMessage = errorMessage,
                onSignIn = onSignIn,
            )
        }
    }

    @Test
    fun `sign-in button is disabled when fields are empty`() {
        render()
        compose.onNodeWithText("Sign in").assertIsNotEnabled()
    }

    @Test
    fun `sign-in button is enabled when both fields are filled`() {
        render()
        compose.onNodeWithText("Email").performTextInput("user@example.com")
        compose.onNodeWithText("Password").performTextInput("secret")
        compose.onNodeWithText("Sign in").assertIsEnabled()
    }

    @Test
    fun `sign-in button is disabled while loading`() {
        render(isLoading = true)
        compose.onNodeWithText("Email").performTextInput("user@example.com")
        compose.onNodeWithText("Password").performTextInput("secret")
        compose.onNodeWithText("Sign in").assertIsNotEnabled()
    }

    @Test
    fun `error message is shown when provided`() {
        render(errorMessage = "Invalid credentials")
        compose.onNodeWithText("Invalid credentials").assertExists()
    }

    @Test
    fun `session-expired banner shown when sessionExpired is true`() {
        render(sessionExpired = true)
        compose.onNodeWithText("Session expired", substring = true).assertExists()
    }

    @Test
    fun `onSignIn called with entered credentials`() {
        var capturedEmail = ""
        var capturedPassword = ""
        render(onSignIn = { e, p -> capturedEmail = e; capturedPassword = p })

        compose.onNodeWithText("Email").performTextInput("user@example.com")
        compose.onNodeWithText("Password").performTextInput("secret")
        compose.onNodeWithText("Sign in").performClick()

        assertEquals("user@example.com", capturedEmail)
        assertEquals("secret", capturedPassword)
    }
}
