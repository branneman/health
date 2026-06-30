package org.branneman.health

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.launch
import org.branneman.health.util.effectiveDate
import org.branneman.health.auth.AuthState
import org.branneman.health.auth.AuthViewModel
import org.branneman.health.db.entities.FoodItemEntity
import org.branneman.health.log.LogViewModel
import org.branneman.health.ui.AiConfigScreen
import org.branneman.health.ui.AskAiScreen
import org.branneman.health.ui.BuildFromScratchScreen
import org.branneman.health.ui.BuildFromScratchViewModel
import org.branneman.health.ui.ConnectPolarScreen
import org.branneman.health.ui.DashboardScreen
import org.branneman.health.ui.DrinkButtonsScreen
import org.branneman.health.ui.EditIngredientTemplateScreen
import org.branneman.health.ui.FoodSearchScreen
import org.branneman.health.ui.LogScreen
import org.branneman.health.ui.LoginScreen
import org.branneman.health.ui.MealButtonsScreen
import org.branneman.health.ui.OnboardingScreen
import org.branneman.health.ui.GoalSettingsScreen
import org.branneman.health.ui.ProfileSettingsScreen
import org.branneman.health.ui.QuickAddScreen
import org.branneman.health.ui.ScheduleSettingsScreen
import org.branneman.health.ui.SettingsScreen
import org.branneman.health.ui.SingleItemLogScreen
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

private enum class SettingsPage { Main, MealButtons, DrinkButtons, Profile, Goal, Schedule, Templates, Ai, EditIngredientTemplate, TemplatesFoodSearch }

private enum class LogPage { Main, TemplateList, QuickAdd, AskAi, BuildFromScratch, FoodSearch, SingleItemSearch, SingleItemGrams }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainNav(authViewModel: AuthViewModel) {
    val coroutineScope = rememberCoroutineScope()
    var currentTab by remember { mutableStateOf(Tab.Dashboard) }
    var settingsPage by remember { mutableStateOf(SettingsPage.Main) }
    var logPage by remember { mutableStateOf(LogPage.Main) }
    var showLogSheet by remember { mutableStateOf(false) }
    var pendingLogUndoAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var quickAddPrefill by remember { mutableStateOf<Pair<Int, String?>?>(null) }
    var selectedFoodItemForLog      by remember { mutableStateOf<FoodItemEntity?>(null) }
    var selectedFoodItemForTemplate by remember { mutableStateOf<FoodItemEntity?>(null) }
    var editingTemplateId           by remember { mutableStateOf<String?>(null) }
    var loadIngredientTemplateId    by remember { mutableStateOf<String?>(null) }
    var selectedFoodItemForSingleLog by remember { mutableStateOf<FoodItemEntity?>(null) }
    var singleItemAutoLaunchScan     by remember { mutableStateOf(false) }
    var currentLoggedAt              by remember { mutableStateOf("") }

    LaunchedEffect(currentTab) {
        if (currentTab != Tab.Settings) settingsPage = SettingsPage.Main
        if (currentTab != Tab.Log) {
            logPage = LogPage.Main
            showLogSheet = false
            pendingLogUndoAction = null
            quickAddPrefill = null
            loadIngredientTemplateId = null
            selectedFoodItemForSingleLog = null
            singleItemAutoLaunchScan = false
            currentLoggedAt = ""
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
        val buildVm: BuildFromScratchViewModel = viewModel()

            when (currentTab) {
                Tab.Dashboard -> DashboardScreen()
                Tab.Log -> {
                    when (logPage) {
                        LogPage.Main -> {
                            DisposableEffect(Unit) {
                                onDispose { logVm.setSelectedDate(effectiveDate()) }
                            }

                            val pagerState = rememberPagerState(initialPage = 0, pageCount = { 365 })
                            var showDatePicker by remember { mutableStateOf(false) }

                            LaunchedEffect(pagerState.currentPage) {
                                logVm.setSelectedDate(effectiveDate().minusDays(pagerState.currentPage.toLong()))
                            }

                            Column(modifier = Modifier.fillMaxSize()) {
                                DateNavigationHeader(
                                    page        = pagerState.currentPage,
                                    currentDate = effectiveDate(),
                                    onPickDate  = { showDatePicker = true },
                                )
                                HorizontalPager(
                                    state    = pagerState,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    LogScreen(
                                        viewModel           = logVm,
                                        onSetUpMealButtons  = {
                                            currentTab   = Tab.Settings
                                            settingsPage = SettingsPage.MealButtons
                                        },
                                        shortcuts           = logVm.shortcuts.collectAsStateWithLifecycle().value,
                                        onSetUpDrinkButtons = {
                                            currentTab   = Tab.Settings
                                            settingsPage = SettingsPage.DrinkButtons
                                        },
                                        onLogShortcut           = { shortcut -> logVm.logFromShortcut(shortcut) },
                                        onOpenLogFlow           = { showLogSheet = true },
                                        externalUndo            = pendingLogUndoAction,
                                        onExternalUndoConsumed  = { pendingLogUndoAction = null },
                                    )
                                }
                            }

                            if (showDatePicker) {
                                val today = effectiveDate()
                                val initialMillis = today
                                    .minusDays(pagerState.currentPage.toLong())
                                    .atStartOfDay(ZoneOffset.UTC)
                                    .toInstant().toEpochMilli()
                                val pickerState = rememberDatePickerState(
                                    initialSelectedDateMillis = initialMillis,
                                    selectableDates = object : SelectableDates {
                                        override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                                            val picked = Instant.ofEpochMilli(utcTimeMillis)
                                                .atZone(ZoneOffset.UTC).toLocalDate()
                                            return !picked.isAfter(today) && !picked.isBefore(today.minusDays(364))
                                        }
                                    },
                                )
                                DatePickerDialog(
                                    onDismissRequest = { showDatePicker = false },
                                    confirmButton = {
                                        TextButton(onClick = {
                                            val millis = pickerState.selectedDateMillis
                                            if (millis != null) {
                                                val picked = Instant.ofEpochMilli(millis)
                                                    .atZone(ZoneOffset.UTC).toLocalDate()
                                                val page = ChronoUnit.DAYS
                                                    .between(picked, today).toInt().coerceIn(0, 364)
                                                coroutineScope.launch { pagerState.animateScrollToPage(page) }
                                            }
                                            showDatePicker = false
                                        }) { Text("OK") }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
                                    },
                                ) { DatePicker(state = pickerState) }
                            }
                        }
                        LogPage.TemplateList -> TemplateListScreen(
                            onBack   = { logPage = LogPage.Main },
                            onLogged = { undoAction ->
                                pendingLogUndoAction = undoAction
                                logPage = LogPage.Main
                            },
                            onSelectIngredientTemplate = { templateId ->
                                buildVm.reset()
                                currentLoggedAt = logVm.loggedAtForSelectedDate()
                                loadIngredientTemplateId = templateId
                                logPage = LogPage.BuildFromScratch
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
                            loggedAt       = currentLoggedAt,
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
                        LogPage.BuildFromScratch -> BuildFromScratchScreen(
                            pendingFoodItem           = selectedFoodItemForLog,
                            onPendingFoodItemConsumed = { selectedFoodItemForLog = null },
                            onAddIngredient           = { logPage = LogPage.FoodSearch },
                            initialTemplateId         = loadIngredientTemplateId,
                            onLogged                  = {
                                loadIngredientTemplateId = null
                                logPage = LogPage.Main
                            },
                            onSavedAsTemplate         = { },
                            onBack                    = { bailOutKcal ->
                                loadIngredientTemplateId = null
                                if (bailOutKcal != null) {
                                    quickAddPrefill = Pair(bailOutKcal, null)
                                    logPage = LogPage.QuickAdd
                                } else {
                                    logPage = LogPage.Main
                                }
                            },
                            loggedAt                  = currentLoggedAt,
                            viewModel                 = buildVm,
                        )
                        LogPage.FoodSearch -> FoodSearchScreen(
                            onItemSelected = { item -> selectedFoodItemForLog = item; logPage = LogPage.BuildFromScratch },
                            onBack         = { logPage = LogPage.BuildFromScratch },
                        )
                        LogPage.SingleItemSearch -> FoodSearchScreen(
                            onItemSelected = { item ->
                                selectedFoodItemForSingleLog = item
                                logPage = LogPage.SingleItemGrams
                            },
                            onBack         = {
                                selectedFoodItemForSingleLog = null
                                singleItemAutoLaunchScan = false
                                logPage = LogPage.Main
                            },
                            autoLaunchScan = singleItemAutoLaunchScan,
                        )
                        LogPage.SingleItemGrams -> {
                            val item = selectedFoodItemForSingleLog
                            if (item != null) {
                                SingleItemLogScreen(
                                    item         = item,
                                    logViewModel = logVm,
                                    onLogged     = { undoAction ->
                                        pendingLogUndoAction = undoAction
                                        selectedFoodItemForSingleLog = null
                                        singleItemAutoLaunchScan = false
                                        logPage = LogPage.Main
                                    },
                                    onBack       = {
                                        selectedFoodItemForSingleLog = null
                                        singleItemAutoLaunchScan = false
                                        logPage = LogPage.SingleItemSearch
                                    },
                                )
                            }
                        }
                    }
                    if (showLogSheet) {
                        LogFlowSheet(
                            onFromTemplate     = { showLogSheet = false; logPage = LogPage.TemplateList },
                            onQuickAdd         = { showLogSheet = false; currentLoggedAt = logVm.loggedAtForSelectedDate(); logPage = LogPage.QuickAdd },
                            onAskAi            = { showLogSheet = false; currentLoggedAt = logVm.loggedAtForSelectedDate(); logPage = LogPage.AskAi },
                            onBuildFromScratch = { showLogSheet = false; buildVm.reset(); currentLoggedAt = logVm.loggedAtForSelectedDate(); logPage = LogPage.BuildFromScratch },
                            onSingleItem       = { showLogSheet = false; singleItemAutoLaunchScan = false; logPage = LogPage.SingleItemSearch },
                            onScanAndLog       = { showLogSheet = false; singleItemAutoLaunchScan = true;  logPage = LogPage.SingleItemSearch },
                            onDismiss          = { showLogSheet = false },
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
                        onBack                   = { settingsPage = SettingsPage.Main },
                        onEditIngredientTemplate = { id -> editingTemplateId = id; settingsPage = SettingsPage.EditIngredientTemplate },
                        onNewIngredientTemplate  = { editingTemplateId = null; settingsPage = SettingsPage.EditIngredientTemplate },
                    )
                    SettingsPage.Ai -> AiConfigScreen(
                        onBack = { settingsPage = SettingsPage.Main }
                    )
                    SettingsPage.EditIngredientTemplate -> EditIngredientTemplateScreen(
                        templateId                = editingTemplateId,
                        pendingFoodItem           = selectedFoodItemForTemplate,
                        onPendingFoodItemConsumed = { selectedFoodItemForTemplate = null },
                        onAddIngredient           = { settingsPage = SettingsPage.TemplatesFoodSearch },
                        onSaved                   = { settingsPage = SettingsPage.Templates },
                        onDeleted                 = { settingsPage = SettingsPage.Templates },
                        onBack                    = { settingsPage = SettingsPage.Templates },
                    )
                    SettingsPage.TemplatesFoodSearch -> FoodSearchScreen(
                        onItemSelected = { item -> selectedFoodItemForTemplate = item; settingsPage = SettingsPage.EditIngredientTemplate },
                        onBack         = { settingsPage = SettingsPage.EditIngredientTemplate },
                    )
                }
            }
        }
    }
}

@Composable
private fun DateNavigationHeader(page: Int, currentDate: LocalDate, onPickDate: () -> Unit) {
    val today = remember(currentDate) { effectiveDate() }
    val label = remember(page) {
        when (page) {
            0    -> "Today"
            1    -> "Yesterday"
            else -> today.minusDays(page.toLong())
                .format(DateTimeFormatter.ofPattern("EEE d MMM"))
        }
    }
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        AssistChip(onClick = onPickDate, label = { Text(label) })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogFlowSheet(
    onFromTemplate: () -> Unit,
    onQuickAdd: () -> Unit,
    onAskAi: () -> Unit,
    onBuildFromScratch: () -> Unit,
    onSingleItem: () -> Unit,
    onScanAndLog: () -> Unit,
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
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Build from scratch") },
                trailingContent = { Text("›", style = MaterialTheme.typography.titleLarge) },
                modifier = Modifier
                    .clickable(onClick = onBuildFromScratch)
                    .testTag("log_build_from_scratch"),
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Single item") },
                trailingContent = { Text("›", style = MaterialTheme.typography.titleLarge) },
                modifier = Modifier
                    .clickable(onClick = onSingleItem)
                    .testTag("log_single_item"),
            )
            HorizontalDivider()
            ListItem(
                headlineContent   = { Text("Scan & log") },
                supportingContent = { Text("Scan barcode directly") },
                trailingContent   = { Text("›", style = MaterialTheme.typography.titleLarge) },
                modifier = Modifier
                    .clickable(onClick = onScanAndLog)
                    .testTag("log_scan_and_log"),
            )
        }
    }
}
