package org.branneman.health.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.branneman.health.onboarding.OnboardingUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OnboardingScreenTest {

    @get:Rule val compose = createComposeRule()

    private fun renderStep1(
        state: OnboardingUiState = OnboardingUiState(),
        onUpdate: (OnboardingUiState.() -> OnboardingUiState) -> Unit = {},
        onNext: () -> Unit = {},
    ) {
        compose.setContent {
            OnboardingStep1(state = state, onUpdate = onUpdate, onNext = onNext)
        }
    }

    private fun renderStep2(
        state: OnboardingUiState = OnboardingUiState(
            sex = "male", heightCm = "177", currentWeightKg = "84.0",
            goalWeightKg = "74.0", age = "39",
        ),
        onUpdate: (OnboardingUiState.() -> OnboardingUiState) -> Unit = {},
        onBack: () -> Unit = {},
        onNext: () -> Unit = {},
    ) {
        compose.setContent {
            OnboardingStep2(state = state, onUpdate = onUpdate, onBack = onBack, onNext = onNext)
        }
    }

    private fun renderStep3(
        state: OnboardingUiState = OnboardingUiState(
            sex = "male", heightCm = "177", currentWeightKg = "84.0",
            goalWeightKg = "74.0", age = "39",
        ),
        onUpdate: (OnboardingUiState.() -> OnboardingUiState) -> Unit = {},
        onBack: () -> Unit = {},
        onNext: () -> Unit = {},
    ) {
        compose.setContent {
            OnboardingStep3(state = state, onUpdate = onUpdate, onBack = onBack, onNext = onNext)
        }
    }

    // Step 1

    @Test
    fun `step 1 Continue is disabled when all fields empty`() {
        renderStep1()
        compose.onNodeWithText("Continue").assertIsNotEnabled()
    }

    @Test
    fun `step 1 Continue is disabled when goal weight exceeds current weight`() {
        renderStep1(
            state = OnboardingUiState(
                sex = "male", heightCm = "177",
                currentWeightKg = "74.0", goalWeightKg = "80.0", age = "39"
            )
        )
        compose.onNodeWithText("Continue").assertIsNotEnabled()
    }

    @Test
    fun `step 1 Continue is enabled when all fields valid`() {
        renderStep1(
            state = OnboardingUiState(
                sex = "male", heightCm = "177",
                currentWeightKg = "84.0", goalWeightKg = "74.0", age = "39"
            )
        )
        compose.onNodeWithText("Continue").assertIsEnabled()
    }

    @Test
    fun `step 1 has no Back button`() {
        renderStep1()
        compose.onNodeWithText("Back").assertDoesNotExist()
    }

    // Step 2

    @Test
    fun `step 2 shows estimated output label`() {
        renderStep2()
        compose.onNodeWithText("Estimated output", substring = true).assertExists()
        compose.onNodeWithText("(estimated)", substring = true).assertExists()
    }

    @Test
    fun `step 2 has Back button`() {
        renderStep2()
        compose.onNodeWithText("Back").assertExists()
    }

    // Step 3

    @Test
    fun `step 3 shows Continue button`() {
        renderStep3()
        compose.onNodeWithText("Continue").assertExists()
    }

    @Test
    fun `step 3 shows muscle loss warning above 500 kcal`() {
        renderStep3(state = OnboardingUiState(
            sex = "male", heightCm = "177", currentWeightKg = "84.0",
            goalWeightKg = "74.0", age = "39", targetDeficit = 550,
        ))
        compose.onNodeWithText("muscle loss", substring = true).assertExists()
    }

    @Test
    fun `step 3 does not show muscle loss warning at 300 kcal`() {
        renderStep3(state = OnboardingUiState(
            sex = "male", heightCm = "177", currentWeightKg = "84.0",
            goalWeightKg = "74.0", age = "39", targetDeficit = 300,
        ))
        compose.onNodeWithText("muscle loss", substring = true).assertDoesNotExist()
    }

    @Test
    fun `step 3 shows maintain weight when deficit is 0`() {
        renderStep3(state = OnboardingUiState(
            sex = "male", heightCm = "177", currentWeightKg = "84.0",
            goalWeightKg = "84.0", age = "39", targetDeficit = 0,
        ))
        compose.onNodeWithText("Maintain weight", substring = true).assertExists()
    }
}
