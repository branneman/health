package org.branneman.health.ui

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
        onSignOut              = onSignOut,
        serverReachable        = serverReachable,
        lastSyncedAt           = lastSyncedAt,
        polarStatus            = polarStatus,
        onConnectPolar         = if (polarStatus == PolarStatus.NotConnected) {
            {
                viewModel.connectPolar { url ->
                    CustomTabsIntent.Builder().build()
                        .launchUrl(context, Uri.parse(url))
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
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        // Server / Sync section
        SettingsStatusRow(
            label = when (serverReachable) {
                null  -> "Server: Checking…"
                true  -> "Server: Online${lastSyncedAt?.let {
                    val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault())
                    " · synced ${dt.format(syncTimestampFormatter)}"
                } ?: ""}"
                false -> "Server: Offline"
            },
            action = if (onSyncNow != null) {
                { TextButton(onClick = onSyncNow) { Text("Sync now") } }
            } else null,
        )

        // Polar / Devices section
        SettingsStatusRow(
            label = when (polarStatus) {
                PolarStatus.Connected    -> "Polar: Connected"
                PolarStatus.NotConnected -> "Polar: Not connected"
                PolarStatus.Loading      -> "Polar: Checking…"
                PolarStatus.Unknown      -> "Polar: Unknown"
            },
            action = when {
                polarStatus == PolarStatus.Connected -> {{ Text("✓", color = MaterialTheme.colorScheme.primary) }}
                onConnectPolar != null               -> {{ OutlinedButton(onClick = onConnectPolar) { Text("Connect") } }}
                else                                 -> null
            },
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // Navigation rows
        SettingsNavRow("Profile", "Sex, height, age, goal weight", onNavigateProfile)
        SettingsNavRow("Goal", "Activity level · calorie deficit", onNavigateGoal)
        SettingsNavRow("Schedule", "Wake time · Bedtime", onNavigateSchedule)
        SettingsNavRow("Meal buttons", null, onNavigateMealButtons)
        SettingsNavRow("Drink buttons", null, onNavigateDrinkButtons)

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        Text(
            text     = "Version: $versionName",
            style    = MaterialTheme.typography.bodySmall,
            color    = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
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
private fun SettingsStatusRow(
    label: String,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text     = label,
            style    = MaterialTheme.typography.bodyMedium,
            color    = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (action != null) action()
    }
}

@Composable
private fun SettingsNavRow(title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text  = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text  = "›",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
