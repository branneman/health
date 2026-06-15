package org.branneman.health.ui

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    onNavigateMealButtons: () -> Unit = {},
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
        onNavigateMealButtons = onNavigateMealButtons,
        onSignOut             = onSignOut,
        serverReachable       = serverReachable,
        lastSyncedAt          = lastSyncedAt,
        polarStatus           = polarStatus,
        onConnectPolar        = if (polarStatus == PolarStatus.NotConnected) {
            {
                viewModel.connectPolar { url ->
                    CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
                }
            }
        } else null,
        onSyncNow             = { SyncWorker.syncNow(context) },
        versionName           = BuildConfig.VERSION_NAME,
    )
}

@Composable
fun SettingsContent(
    onNavigateMealButtons: () -> Unit,
    onNavigateDrinkButtons: () -> Unit = {},
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
            .padding(16.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Server: ${
                when (serverReachable) {
                    null -> "Checking…"
                    true -> "Online"
                    false -> "Offline"
                }
            }"
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Last synced: ${
                lastSyncedAt?.let {
                    LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault())
                        .format(syncTimestampFormatter)
                } ?: "Never"
            }",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Polar: ${when (polarStatus) {
                PolarStatus.Loading -> "Checking…"
                PolarStatus.Connected -> "Connected"
                PolarStatus.NotConnected -> "Not connected"
                PolarStatus.Unknown -> "Unknown"
            }}",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (onConnectPolar != null) {
            TextButton(
                onClick  = onConnectPolar,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Connect Polar")
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        TextButton(
            onClick  = onNavigateMealButtons,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Meal buttons →")
        }
        TextButton(
            onClick  = onNavigateDrinkButtons,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Drink buttons →")
        }
        if (onSyncNow != null) {
            TextButton(
                onClick  = onSyncNow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Sync now")
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text  = "Version: $versionName",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))
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
            title = { Text("Sign out?") },
            text  = { Text("Your data will be removed from this device. It's all saved on the server.") },
            confirmButton = {
                TextButton(onClick = {
                    showSignOutConfirm = false
                    onSignOut()
                }) { Text("Sign out") }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutConfirm = false }) { Text("Cancel") }
            },
        )
    }
}
