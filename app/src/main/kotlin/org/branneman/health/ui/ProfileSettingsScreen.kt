package org.branneman.health.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun ProfileSettingsScreen(
    onBack: () -> Unit,
    viewModel: ProfileSettingsViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val latestWeightKg by viewModel.latestWeightKg.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }
    ProfileSettingsContent(
        state          = state,
        latestWeightKg = latestWeightKg,
        onUpdate       = viewModel::update,
        onSave         = { viewModel.save(onSuccess = onBack) },
        onBack         = onBack,
    )
}

@Composable
fun ProfileSettingsContent(
    state: ProfileSettingsUiState,
    latestWeightKg: Double?,
    onUpdate: (ProfileSettingsUiState.() -> ProfileSettingsUiState) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    var showDiscardDialog by remember { mutableStateOf(false) }

    fun requestBack() { if (state.profileDirty) showDiscardDialog = true else onBack() }

    BackHandler { requestBack() }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier          = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            TextButton(onClick = ::requestBack) { Text("← Back") }
            Text(
                text     = "Profile",
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
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Spacer(Modifier.height(4.dp))

                Text("Sex", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("male" to "Male", "female" to "Female").forEach { (value, label) ->
                        FilterChip(
                            selected = state.sex == value,
                            onClick  = { onUpdate { copy(sex = value) } },
                            label    = { Text(label) },
                        )
                    }
                }

                OutlinedTextField(
                    value           = state.heightCm,
                    onValueChange   = { onUpdate { copy(heightCm = it) } },
                    label           = { Text("Height (cm)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier        = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value           = state.age,
                    onValueChange   = { onUpdate { copy(age = it) } },
                    label           = { Text("Age") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier        = Modifier.fillMaxWidth(),
                )

                val goalAboveCurrent = latestWeightKg != null &&
                    (state.goalWeightKg.toDoubleOrNull() ?: 0.0) > latestWeightKg
                OutlinedTextField(
                    value           = state.goalWeightKg,
                    onValueChange   = { onUpdate { copy(goalWeightKg = it) } },
                    label           = { Text("Goal weight (kg)") },
                    isError         = goalAboveCurrent,
                    supportingText  = if (goalAboveCurrent) {
                        { Text("Goal is above your current weight") }
                    } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier        = Modifier.fillMaxWidth(),
                )

                state.saveError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (state.profileDirty) {
                Text(
                    text     = "⚠ Unsaved changes",
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Button(
                onClick  = onSave,
                enabled  = state.profileDirty && !state.isSaving,
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
