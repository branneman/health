package org.branneman.health.ui

import androidx.core.net.toUri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.branneman.health.BuildConfig
import org.branneman.health.HealthApplication
import org.branneman.health.network.HealthApiClient
import org.branneman.health.sync.SyncWorker
import org.branneman.health.sync.lastSyncedAtFlow
import org.branneman.health.sync.syncDataStore
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val syncTimestampFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm")

@Composable
fun SettingsScreen(
    onSignOut: () -> Unit,
    onNavigateProfile: () -> Unit = {},
    onNavigateGoal: () -> Unit = {},
    onNavigateSchedule: () -> Unit = {},
    onNavigateMealButtons: () -> Unit = {},
    onNavigateDrinkButtons: () -> Unit = {},
    onNavigateTemplates: () -> Unit = {},
    onNavigateAi: () -> Unit = {},
) {
    val context = LocalContext.current
    var serverReachable by remember { mutableStateOf<Boolean?>(null) }
    val lastSyncedAt by context.syncDataStore.lastSyncedAtFlow.collectAsState(initial = null)
    val viewModel: SettingsViewModel = viewModel()
    val polarStatus by viewModel.polarStatus.collectAsState()
    val polarCallbackPending by (context.applicationContext as HealthApplication)
        .polarCallbackPending.collectAsState()

    LaunchedEffect(Unit) {
        serverReachable = HealthApiClient().isServerReachable()
    }

    LaunchedEffect(polarCallbackPending) {
        if (polarCallbackPending) {
            (context.applicationContext as HealthApplication).clearPolarCallback()
            viewModel.recheckPolarStatus()
        }
    }

    SettingsContent(
        onNavigateProfile      = onNavigateProfile,
        onNavigateGoal         = onNavigateGoal,
        onNavigateSchedule     = onNavigateSchedule,
        onNavigateMealButtons  = onNavigateMealButtons,
        onNavigateDrinkButtons = onNavigateDrinkButtons,
        onNavigateTemplates    = onNavigateTemplates,
        onNavigateAi           = onNavigateAi,
        onSignOut              = onSignOut,
        serverReachable        = serverReachable,
        lastSyncedAt           = lastSyncedAt,
        polarStatus            = polarStatus,
        onConnectPolar         = if (polarStatus == PolarStatus.NotConnected) {
            {
                viewModel.connectPolar { url ->
                    CustomTabsIntent.Builder().build()
                        .launchUrl(context, url.toUri())
                }
            }
        } else null,
        onSyncNow              = { SyncWorker.syncNow(context) },
        versionName            = BuildConfig.VERSION_NAME,
    )
}

@Composable
fun SettingsContent(
    onNavigateProfile: () -> Unit,
    onNavigateGoal: () -> Unit,
    onNavigateSchedule: () -> Unit,
    onNavigateMealButtons: () -> Unit,
    onNavigateDrinkButtons: () -> Unit,
    onNavigateTemplates: () -> Unit = {},
    onNavigateAi: () -> Unit = {},
    onSignOut: () -> Unit,
    serverReachable: Boolean? = null,
    lastSyncedAt: Long? = null,
    polarStatus: PolarStatus = PolarStatus.Unknown,
    onConnectPolar: (() -> Unit)? = null,
    onSyncNow: (() -> Unit)? = null,
    versionName: String = "",
) {
    var showSignOutConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        SettingsSectionHeader("Profile")
        SettingsNavRow("Profile", "Sex, height, age, goal weight", onNavigateProfile)
        SettingsNavRow("Goal", "Activity level · calorie deficit", onNavigateGoal)
        SettingsNavRow("Schedule", "Wake time · Bedtime", onNavigateSchedule)

        HorizontalDivider()
        SettingsSectionHeader("Quick buttons")
        SettingsNavRow("Meal buttons", null, onNavigateMealButtons)
        SettingsNavRow("Drink buttons", null, onNavigateDrinkButtons)
        SettingsNavRow("Templates", "Manage saved meals", onNavigateTemplates)

        HorizontalDivider()
        SettingsSectionHeader("Connections")
        ListItem(
            headlineContent   = { Text("Polar watch") },
            supportingContent = {
                Text(
                    text  = when (polarStatus) {
                        PolarStatus.Connected    -> "Connected"
                        PolarStatus.NotConnected -> "Not connected"
                        PolarStatus.Loading      -> "Checking…"
                        PolarStatus.Unknown      -> "Unknown"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingContent = when {
                polarStatus == PolarStatus.Connected -> {{ Text("✓", color = MaterialTheme.colorScheme.primary) }}
                onConnectPolar != null               -> {{ OutlinedButton(onClick = onConnectPolar) { Text("Connect") } }}
                else                                 -> null
            },
        )

        HorizontalDivider()
        SettingsSectionHeader("AI")
        SettingsNavRow("Anthropic AI", "API key for calorie estimates", onNavigateAi)

        HorizontalDivider()
        SettingsSectionHeader("Sync")
        ListItem(
            headlineContent   = { Text("Server") },
            supportingContent = {
                Text(
                    text  = when (serverReachable) {
                        null  -> "Checking…"
                        true  -> "Online${lastSyncedAt?.let {
                            val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault())
                            " · synced ${dt.format(syncTimestampFormatter)}"
                        } ?: ""}"
                        false -> "Offline"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingContent = if (onSyncNow != null) {{
                TextButton(onClick = onSyncNow) { Text("Sync now") }
            }} else null,
        )

        Spacer(Modifier.height(16.dp))
        Text(
            text     = "Version: $versionName",
            style    = MaterialTheme.typography.bodySmall,
            color    = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
        TextButton(
            onClick  = { showSignOutConfirm = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Sign out", color = MaterialTheme.colorScheme.error)
        }
    }

    if (showSignOutConfirm) {
        AlertDialog(
            onDismissRequest = { showSignOutConfirm = false },
            title            = { Text("Sign out?") },
            text             = { Text("Your data will be removed from this device. It's all saved on the server.") },
            confirmButton    = {
                TextButton(onClick = { showSignOutConfirm = false; onSignOut() }) {
                    Text("Sign out")
                }
            },
            dismissButton    = {
                TextButton(onClick = { showSignOutConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text     = title,
        style    = MaterialTheme.typography.labelLarge,
        color    = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingsNavRow(title: String, subtitle: String?, onClick: () -> Unit) {
    ListItem(
        headlineContent   = { Text(title) },
        supportingContent = subtitle?.let { sub -> { Text(sub) } },
        trailingContent   = { Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        modifier          = Modifier.clickable(onClick = onClick),
    )
}
