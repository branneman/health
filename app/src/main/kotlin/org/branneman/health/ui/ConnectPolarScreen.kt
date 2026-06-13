package org.branneman.health.ui

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.branneman.health.HealthApplication

@Composable
fun ConnectPolarScreen(
    onSetupComplete: () -> Unit,
    viewModel: ConnectPolarViewModel = viewModel(),
) {
    val context = LocalContext.current
    val polarCallbackPending by (context.applicationContext as HealthApplication)
        .polarCallbackPending.collectAsState()

    LaunchedEffect(polarCallbackPending) {
        if (polarCallbackPending) {
            (context.applicationContext as HealthApplication).clearPolarCallback()
            onSetupComplete()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Connect Polar", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Connect your Polar device to get real calorie data.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = {
            viewModel.getConnectUrl { url ->
                CustomTabsIntent.Builder().build()
                    .launchUrl(context, Uri.parse(url))
            }
        }) {
            Text("Connect Polar")
        }
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = onSetupComplete) {
            Text("Skip for now")
        }
    }
}
