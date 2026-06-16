package org.branneman.health

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.branneman.health.auth.AuthState
import org.branneman.health.auth.AuthViewModel
import org.branneman.health.log.LogViewModel
import org.branneman.health.ui.ConnectPolarScreen
import org.branneman.health.ui.DashboardScreen
import org.branneman.health.ui.DrinkButtonsScreen
import org.branneman.health.ui.LogScreen
import org.branneman.health.ui.LoginScreen
import org.branneman.health.ui.MealButtonsScreen
import org.branneman.health.ui.OnboardingScreen
import org.branneman.health.ui.SettingsScreen

private enum class Tab(val label: String, val emoji: String) {
    Dashboard("Dashboard", "📊"),
    Log("Log", "✏️"),
    Settings("Settings", "⚙️")
}

@Composable
fun App() {
    val authViewModel: AuthViewModel = viewModel()
    val authState by authViewModel.authState.collectAsStateWithLifecycle()

    var loginError by remember { mutableStateOf<String?>(null) }
    var isLoggingIn by remember { mutableStateOf(false) }

    MaterialTheme {
        when (authState) {
            AuthState.Loading -> {
                // blank screen while token is being checked — avoids flash of login screen
            }

            AuthState.LoggedOut -> LoginScreen(
                sessionExpired = false,
                isLoading = isLoggingIn,
                errorMessage = loginError,
                onSignIn = { username, password ->
                    isLoggingIn = true
                    loginError = null
                    authViewModel.login(username, password) { error ->
                        isLoggingIn = false
                        loginError = when {
                            error.contains("401") || error.contains("Unauthorized") ->
                                "Wrong credentials"
                            error.contains("UnresolvedAddressException") ||
                                    error.contains("ConnectException") ->
                                "Check your connection — login requires internet"
                            else -> "Wrong credentials"
                        }
                    }
                }
            )

            AuthState.Expired -> LoginScreen(
                sessionExpired = true,
                isLoading = isLoggingIn,
                errorMessage = loginError,
                onSignIn = { username, password ->
                    isLoggingIn = true
                    loginError = null
                    authViewModel.login(username, password) { error ->
                        isLoggingIn = false
                        loginError = when {
                            error.contains("401") || error.contains("Unauthorized") ->
                                "Wrong credentials"
                            error.contains("UnresolvedAddressException") ||
                                    error.contains("ConnectException") ->
                                "Check your connection — login requires internet"
                            else -> "Wrong credentials"
                        }
                    }
                }
            )

            AuthState.NeedsOnboarding -> OnboardingScreen()

            AuthState.NeedsPolarSetup -> ConnectPolarScreen(
                onSetupComplete = { authViewModel.completePolarSetup() }
            )

            AuthState.LoggedIn -> MainNav(authViewModel)
        }
    }
}

private enum class SettingsPage { Main, MealButtons, DrinkButtons, Profile, Goal, Schedule }

@Composable
private fun MainNav(authViewModel: AuthViewModel) {
    var currentTab by remember { mutableStateOf(Tab.Dashboard) }
    var settingsPage by remember { mutableStateOf(SettingsPage.Main) }

    LaunchedEffect(currentTab) {
        if (currentTab != Tab.Settings) settingsPage = SettingsPage.Main
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = currentTab == tab,
                        onClick  = { currentTab = tab },
                        icon     = { Text(tab.emoji) },
                        label    = { Text(tab.label) }
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            val logVm: LogViewModel = viewModel()
            when (currentTab) {
                Tab.Dashboard -> DashboardScreen()
                Tab.Log -> LogScreen(
                    viewModel          = logVm,
                    onSetUpMealButtons = {
                        currentTab   = Tab.Settings
                        settingsPage = SettingsPage.MealButtons
                    },
                    shortcuts          = logVm.shortcuts.collectAsStateWithLifecycle().value,
                    onSetUpDrinkButtons = {
                        currentTab   = Tab.Settings
                        settingsPage = SettingsPage.DrinkButtons
                    },
                    onLogShortcut      = { shortcut -> logVm.logFromShortcut(shortcut) },
                )
                Tab.Settings -> when (settingsPage) {
                    SettingsPage.Main -> SettingsScreen(
                        onSignOut              = { authViewModel.logout() },
                        onNavigateMealButtons  = { settingsPage = SettingsPage.MealButtons },
                        onNavigateDrinkButtons = { settingsPage = SettingsPage.DrinkButtons },
                        onNavigateProfile      = { settingsPage = SettingsPage.Profile },
                        onNavigateGoal         = { settingsPage = SettingsPage.Goal },
                        onNavigateSchedule     = { settingsPage = SettingsPage.Schedule },
                    )
                    SettingsPage.MealButtons -> MealButtonsScreen(
                        onBack = { settingsPage = SettingsPage.Main }
                    )
                    SettingsPage.DrinkButtons -> DrinkButtonsScreen(
                        onBack = { settingsPage = SettingsPage.Main }
                    )
                    SettingsPage.Profile  -> { /* TODO Task 6 */ }
                    SettingsPage.Goal     -> { /* TODO Task 6 */ }
                    SettingsPage.Schedule -> { /* TODO Task 6 */ }
                }
            }
        }
    }
}
