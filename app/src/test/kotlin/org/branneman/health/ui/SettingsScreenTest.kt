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
class SettingsScreenTest {

    @get:Rule val compose = createComposeRule()

    private fun render(
        onNavigateProfile: () -> Unit = {},
        onNavigateGoal: () -> Unit = {},
        onNavigateSchedule: () -> Unit = {},
        onNavigateMealButtons: () -> Unit = {},
        onNavigateDrinkButtons: () -> Unit = {},
        serverReachable: Boolean? = true,
        lastSyncedAt: Long? = null,
        polarStatus: PolarStatus = PolarStatus.Connected,
        onConnectPolar: (() -> Unit)? = null,
        onSyncNow: (() -> Unit)? = null,
    ) {
        compose.setContent {
            MaterialTheme {
                SettingsContent(
                    onNavigateProfile = onNavigateProfile,
                    onNavigateGoal = onNavigateGoal,
                    onNavigateSchedule = onNavigateSchedule,
                    onNavigateMealButtons = onNavigateMealButtons,
                    onNavigateDrinkButtons = onNavigateDrinkButtons,
                    onSignOut = {},
                    serverReachable = serverReachable,
                    lastSyncedAt = lastSyncedAt,
                    polarStatus = polarStatus,
                    onConnectPolar = onConnectPolar,
                    onSyncNow = onSyncNow,
                )
            }
        }
    }

    @Test fun `Profile row is present and navigates`() {
        var tapped = false
        render(onNavigateProfile = { tapped = true })
        compose.onAllNodesWithText("Profile", substring = true).filterToOne(hasClickAction()).performClick()
        assertTrue(tapped)
    }

    @Test fun `Goal row is present and navigates`() {
        var tapped = false
        render(onNavigateGoal = { tapped = true })
        compose.onNodeWithText("Goal", substring = true).performClick()
        assertTrue(tapped)
    }

    @Test fun `Schedule row is present and navigates`() {
        var tapped = false
        render(onNavigateSchedule = { tapped = true })
        compose.onNodeWithText("Schedule", substring = true).performClick()
        assertTrue(tapped)
    }

    @Test fun `Meal buttons row is present and navigates`() {
        var tapped = false
        render(onNavigateMealButtons = { tapped = true })
        compose.onNodeWithText("Meal buttons", substring = true).performClick()
        assertTrue(tapped)
    }

    @Test fun `Drink buttons row is present and navigates`() {
        var tapped = false
        render(onNavigateDrinkButtons = { tapped = true })
        compose.onNodeWithText("Drink buttons", substring = true).performClick()
        assertTrue(tapped)
    }

    @Test fun `shows Connect button when Polar not connected`() {
        var tapped = false
        render(polarStatus = PolarStatus.NotConnected, onConnectPolar = { tapped = true })
        compose.onAllNodesWithText("Connect", substring = true).filterToOne(hasClickAction())
            .performScrollTo().performClick()
        assertTrue(tapped)
    }

    @Test fun `shows Connected status when Polar connected`() {
        render(polarStatus = PolarStatus.Connected)
        compose.onNodeWithText("Connected", substring = true).assertExists()
    }

    @Test fun `Sync now button is present and calls onSyncNow`() {
        var tapped = false
        render(onSyncNow = { tapped = true })
        compose.onNodeWithText("Sync now", substring = true).performScrollTo().performClick()
        assertTrue(tapped)
    }

    @Test fun `server status text uses consistent style - Online shown`() {
        render(serverReachable = true)
        compose.onNodeWithText("Online", substring = true).assertExists()
    }

    @Test fun `server status shows Offline when server unreachable`() {
        render(serverReachable = false)
        compose.onNodeWithText("Offline", substring = true).assertExists()
    }

}
