package org.branneman.health.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.branneman.health.network.HealthApiClient

@Composable
fun SettingsScreen() {
    var serverReachable by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) {
        serverReachable = HealthApiClient().isServerReachable()
    }

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
    }
}
