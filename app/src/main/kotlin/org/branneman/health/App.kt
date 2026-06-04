package org.branneman.health

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.branneman.health.ui.DashboardScreen
import org.branneman.health.ui.LogScreen
import org.branneman.health.ui.SettingsScreen

private enum class Tab(val label: String, val emoji: String) {
    Dashboard("Dashboard", "📊"),
    Log("Log", "✏️"),
    Settings("Settings", "⚙️")
}

@Composable
fun App() {
    var currentTab by remember { mutableStateOf(Tab.Dashboard) }

    MaterialTheme {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = currentTab == tab,
                            onClick = { currentTab = tab },
                            icon = { Text(tab.emoji) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                when (currentTab) {
                    Tab.Dashboard -> DashboardScreen()
                    Tab.Log -> LogScreen()
                    Tab.Settings -> SettingsScreen()
                }
            }
        }
    }
}
