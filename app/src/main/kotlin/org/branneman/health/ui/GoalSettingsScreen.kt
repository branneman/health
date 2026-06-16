package org.branneman.health.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt

private data class GoalActivityOption(val value: String, val label: String, val subtitle: String)

private val activityOptions = listOf(
    GoalActivityOption("sedentary",         "Mostly sitting",    "Desk job, ≤1 sport/week"),
    GoalActivityOption("lightly_active",    "Lightly active",    "2–4 sport sessions/week"),
    GoalActivityOption("moderately_active", "Moderately active", "5+ sessions/week"),
)

@Composable
fun GoalSettingsScreen(
    onBack: () -> Unit,
    viewModel: ProfileSettingsViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val latestWeightKg by viewModel.latestWeightKg.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }
    GoalSettingsContent(
        state          = state,
        latestWeightKg = latestWeightKg,
        onUpdate       = viewModel::update,
        onSave         = { viewModel.save(onSuccess = onBack) },
        onBack         = onBack,
    )
}

@Composable
fun GoalSettingsContent(
    state: ProfileSettingsUiState,
    latestWeightKg: Double?,
    onUpdate: (ProfileSettingsUiState.() -> ProfileSettingsUiState) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    var showDiscardDialog by remember { mutableStateOf(false) }

    fun requestBack() { if (state.goalDirty) showDiscardDialog = true else onBack() }

    BackHandler { requestBack() }

    val kgPerWeek = if (state.targetDeficit == 0) null else state.targetDeficit / 7700.0
    val monthsToGoal = latestWeightKg?.let { currentKg ->
        val goalKg = state.goalWeightKg.toDoubleOrNull() ?: return@let null
        if (state.targetDeficit == 0 || currentKg <= goalKg) null
        else (currentKg - goalKg) * 7700.0 / state.targetDeficit / 30.0
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier          = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            TextButton(onClick = ::requestBack) { Text("← Back") }
            Text(
                text     = "Goal",
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
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Spacer(Modifier.height(4.dp))

                Text("Activity level", style = MaterialTheme.typography.labelLarge)
                activityOptions.forEach { option ->
                    Row(
                        modifier          = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = state.activityLevel == option.value,
                                onClick  = { onUpdate { copy(activityLevel = option.value) } },
                            )
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = state.activityLevel == option.value,
                            onClick  = { onUpdate { copy(activityLevel = option.value) } },
                        )
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(option.label, style = MaterialTheme.typography.bodyLarge)
                            Text(option.subtitle, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text("Target deficit", style = MaterialTheme.typography.labelLarge)
                Text("${state.targetDeficit} kcal/day", style = MaterialTheme.typography.titleMedium)
                Slider(
                    value         = state.targetDeficit.toFloat(),
                    onValueChange = { onUpdate { copy(targetDeficit = (it / 25).roundToInt() * 25) } },
                    valueRange    = 0f..600f,
                    steps         = 23,
                    modifier      = Modifier.fillMaxWidth(),
                )
                Text("Recommended: 250–400 kcal/day", style = MaterialTheme.typography.bodySmall)

                kgPerWeek?.let {
                    Text("≈ ${"%.2f".format(it)} kg/week", style = MaterialTheme.typography.bodyMedium)
                }
                monthsToGoal?.let {
                    Text("Goal reached in ~${"%.0f".format(it)} months", style = MaterialTheme.typography.bodyMedium)
                }

                if (state.targetDeficit > 500) {
                    Text(
                        "⚠ Above 500 kcal/day risks muscle loss.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                state.saveError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (state.goalDirty) {
                Text(
                    text     = "⚠ Unsaved changes",
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Button(
                onClick  = onSave,
                enabled  = state.goalDirty && !state.isSaving,
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
