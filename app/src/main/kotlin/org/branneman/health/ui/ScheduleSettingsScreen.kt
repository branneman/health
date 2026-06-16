package org.branneman.health.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun ScheduleSettingsScreen(
    onBack: () -> Unit,
    viewModel: ProfileSettingsViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }
    ScheduleSettingsContent(
        state    = state,
        onUpdate = viewModel::update,
        onSave   = { viewModel.save(onSuccess = onBack) },
        onBack   = onBack,
    )
}

@Composable
fun ScheduleSettingsContent(
    state: ProfileSettingsUiState,
    onUpdate: (ProfileSettingsUiState.() -> ProfileSettingsUiState) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    var showDiscardDialog by remember { mutableStateOf(false) }

    fun requestBack() { if (state.scheduleDirty) showDiscardDialog = true else onBack() }

    BackHandler { requestBack() }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier          = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            TextButton(onClick = ::requestBack) { Text("← Back") }
            Text(
                text     = "Schedule",
                style    = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
        }

        if (state.isLoading) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Loading…")
            }
        } else {
            Column(
                modifier            = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Used to calculate how much eating time is left in your day.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))

                ScheduleTimeRow(
                    label   = "Wake time",
                    time    = state.wakeTime,
                    onMinus = { onUpdate { copy(wakeTime = adjustTime(wakeTime, -30)) } },
                    onPlus  = { onUpdate { copy(wakeTime = adjustTime(wakeTime, +30)) } },
                )
                ScheduleTimeRow(
                    label   = "Bedtime",
                    time    = state.bedtime,
                    onMinus = { onUpdate { copy(bedtime = adjustTime(bedtime, -30)) } },
                    onPlus  = { onUpdate { copy(bedtime = adjustTime(bedtime, +30)) } },
                )

                state.saveError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (state.scheduleDirty) {
                Text(
                    text     = "⚠ Unsaved changes",
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Button(
                onClick  = onSave,
                enabled  = state.scheduleDirty && !state.isSaving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.isSaving) CircularProgressIndicator(modifier = Modifier.size(18.dp))
                else Text("Save")
            }
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title            = { Text("Discard unsaved changes?") },
            text             = { Text("Your changes will be lost.") },
            confirmButton    = {
                TextButton(onClick = { showDiscardDialog = false; onBack() }) { Text("Discard") }
            },
            dismissButton    = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("Keep editing") }
            },
        )
    }
}

@Composable
private fun ScheduleTimeRow(label: String, time: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier              = Modifier.fillMaxWidth(),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onMinus, modifier = Modifier.size(40.dp), contentPadding = PaddingValues(0.dp)) {
            Text("−")
        }
        Text(time, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
        OutlinedButton(onClick = onPlus, modifier = Modifier.size(40.dp), contentPadding = PaddingValues(0.dp)) {
            Text("+")
        }
    }
}
