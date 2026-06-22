package org.branneman.health

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.branneman.health.auth.AuthState
import org.branneman.health.auth.AuthViewModel
import org.branneman.health.log.LogViewModel
import org.branneman.health.ui.AiConfigScreen
import org.branneman.health.ui.AskAiScreen
import org.branneman.health.ui.ConnectPolarScreen
import org.branneman.health.ui.DashboardScreen
import org.branneman.health.ui.DrinkButtonsScreen
import org.branneman.health.ui.LogScreen
import org.branneman.health.ui.LoginScreen
import org.branneman.health.ui.MealButtonsScreen
import org.branneman.health.ui.OnboardingScreen
import org.branneman.health.ui.GoalSettingsScreen
import org.branneman.health.ui.ProfileSettingsScreen
import org.branneman.health.ui.QuickAddScreen
import org.branneman.health.ui.ScheduleSettingsScreen
import org.branneman.health.ui.SettingsScreen
import org.branneman.health.ui.TemplateListScreen
import org.branneman.health.ui.TemplatesScreen

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

private enum class SettingsPage { Main, MealButtons, DrinkButtons, Profile, Goal, Schedule, Templates, Ai }

private enum class LogPage { Main, TemplateList, QuickAdd, AskAi }

@Composable
private fun MainNav(authViewModel: AuthViewModel) {
    var currentTab by remember { mutableStateOf(Tab.Dashboard) }
    var settingsPage by remember { mutableStateOf(SettingsPage.Main) }
    var logPage by remember { mutableStateOf(LogPage.Main) }
    var showLogSheet by remember { mutableStateOf(false) }
    var pendingLogUndoAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var quickAddPrefill by remember { mutableStateOf<Pair<Int, String?>?>(null) }

    LaunchedEffect(currentTab) {
        if (currentTab != Tab.Settings) settingsPage = SettingsPage.Main
        if (currentTab != Tab.Log) {
            logPage = LogPage.Main
            showLogSheet = false
            pendingLogUndoAction = null
            quickAddPrefill = null
        }
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
                Tab.Log -> {
                    when (logPage) {
                        LogPage.Main -> LogScreen(
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
                            onLogShortcut           = { shortcut -> logVm.logFromShortcut(shortcut) },
                            onOpenLogFlow           = { showLogSheet = true },
                            externalUndo            = pendingLogUndoAction,
                            onExternalUndoConsumed  = { pendingLogUndoAction = null },
                        )
                        LogPage.TemplateList -> TemplateListScreen(
                            onBack   = { logPage = LogPage.Main },
                            onLogged = { undoAction ->
                                pendingLogUndoAction = undoAction
                                logPage = LogPage.Main
                            },
                        )
                        LogPage.QuickAdd -> QuickAddScreen(
                            onBack         = { logPage = LogPage.Main; quickAddPrefill = null },
                            onLogged       = { undoAction ->
                                pendingLogUndoAction = undoAction
                                quickAddPrefill = null
                                logPage = LogPage.Main
                            },
                            initialKcal    = quickAddPrefill?.first,
                            initialLabel   = quickAddPrefill?.second,
                        )
                        LogPage.AskAi -> AskAiScreen(
                            onBack         = { logPage = LogPage.Main },
                            onUseThis      = { _, _, undoAction ->
                                pendingLogUndoAction = undoAction
                                logPage = LogPage.Main
                            },
                            onEditAmount   = { kcal, label ->
                                quickAddPrefill = Pair(kcal, label)
                                logPage = LogPage.QuickAdd
                            },
                            onNeedsAiConfig = {
                                currentTab   = Tab.Settings
                                settingsPage = SettingsPage.Ai
                            },
                        )
                    }
                    if (showLogSheet) {
                        LogFlowSheet(
                            onFromTemplate = { showLogSheet = false; logPage = LogPage.TemplateList },
                            onQuickAdd     = { showLogSheet = false; logPage = LogPage.QuickAdd },
                            onAskAi        = { showLogSheet = false; logPage = LogPage.AskAi },
                            onDismiss      = { showLogSheet = false },
                        )
                    }
                }
                Tab.Settings -> when (settingsPage) {
                    SettingsPage.Main -> SettingsScreen(
                        onSignOut              = { authViewModel.logout() },
                        onNavigateMealButtons  = { settingsPage = SettingsPage.MealButtons },
                        onNavigateDrinkButtons = { settingsPage = SettingsPage.DrinkButtons },
                        onNavigateProfile      = { settingsPage = SettingsPage.Profile },
                        onNavigateGoal         = { settingsPage = SettingsPage.Goal },
                        onNavigateSchedule     = { settingsPage = SettingsPage.Schedule },
                        onNavigateTemplates    = { settingsPage = SettingsPage.Templates },
                        onNavigateAi           = { settingsPage = SettingsPage.Ai },
                    )
                    SettingsPage.MealButtons -> MealButtonsScreen(
                        onBack = { settingsPage = SettingsPage.Main }
                    )
                    SettingsPage.DrinkButtons -> DrinkButtonsScreen(
                        onBack = { settingsPage = SettingsPage.Main }
                    )
                    SettingsPage.Profile  -> ProfileSettingsScreen(
                        onBack = { settingsPage = SettingsPage.Main }
                    )
                    SettingsPage.Goal     -> GoalSettingsScreen(
                        onBack = { settingsPage = SettingsPage.Main }
                    )
                    SettingsPage.Schedule -> ScheduleSettingsScreen(
                        onBack = { settingsPage = SettingsPage.Main }
                    )
                    SettingsPage.Templates -> TemplatesScreen(
                        onBack = { settingsPage = SettingsPage.Main }
                    )
                    SettingsPage.Ai -> AiConfigScreen(
                        onBack = { settingsPage = SettingsPage.Main }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogFlowSheet(
    onFromTemplate: () -> Unit,
    onQuickAdd: () -> Unit,
    onAskAi: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .padding(bottom = 32.dp),
        ) {
            ListItem(
                headlineContent = { Text("From template") },
                trailingContent = { Text("›", style = MaterialTheme.typography.titleLarge) },
                modifier = Modifier
                    .clickable(onClick = onFromTemplate)
                    .testTag("log_from_template"),
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Quick-add calories") },
                trailingContent = { Text("›", style = MaterialTheme.typography.titleLarge) },
                modifier = Modifier
                    .clickable(onClick = onQuickAdd)
                    .testTag("log_quick_add"),
            )
            HorizontalDivider()
            ListItem(
                headlineContent   = { Text("Ask AI") },
                supportingContent = { Text("Describe or photo a meal") },
                trailingContent   = { Text("›", style = MaterialTheme.typography.titleLarge) },
                modifier = Modifier
                    .clickable(onClick = onAskAi)
                    .testTag("log_ask_ai"),
            )
        }
    }
}
