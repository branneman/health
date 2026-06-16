package org.branneman.health.ui

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
import org.branneman.health.dashboard.DashboardUiState
import org.branneman.health.dashboard.DashboardViewModel
import org.branneman.health.dashboard.isValidWeightInput
import org.branneman.health.db.entities.SportTonightEntity

private val activities = listOf("climbing" to "Climbing", "rowing" to "Rowing", "other" to "Other")
private val intensities = listOf("light" to "Light", "normal" to "Normal", "hard" to "Hard")

@Composable
fun DashboardScreen(viewModel: DashboardViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DashboardContent(
        state = state,
        onSetSportTonight = viewModel::setSportTonight,
        onClearSportTonight = viewModel::clearSportTonight,
        onLogWeight = viewModel::logWeight,
    )
}

@Composable
fun DashboardContent(
    state: DashboardUiState,
    onSetSportTonight: (String, String) -> Unit,
    onClearSportTonight: () -> Unit,
    onLogWeight: (Double) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(
            text = "Today",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        BudgetSection(state)
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        WeightChipRow(weightKg = state.weightKgToday, onLogWeight = onLogWeight)
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        SportTonightSection(state, onSetSportTonight, onClearSportTonight)
    }
}

@Composable
private fun BudgetSection(state: DashboardUiState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = state.caloriesLeft.toString(),
            style = MaterialTheme.typography.displayMedium,
        )
        Text(
            text = state.budgetLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
            modifier = Modifier.fillMaxWidth(),
        ) {
            InOutColumn(value = state.caloriesIn, label = "in")
            InOutColumn(
                value = state.caloriesOut,
                label = if (state.caloriesOutSource == "estimate") "out (est.)" else "out",
            )
        }
    }
}

@Composable
private fun InOutColumn(value: Int, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value.toString(), style = MaterialTheme.typography.titleMedium)
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WeightChipRow(
    weightKg: Double?,
    onLogWeight: (Double) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }

    TextButton(
        onClick = { showDialog = true },
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(
            text = if (weightKg != null) "⚖ ${"%.1f".format(weightKg)} kg" else "⚖ -- kg",
            style = MaterialTheme.typography.bodyMedium,
            color = if (weightKg != null) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showDialog) {
        WeightEntryDialog(
            initialValue = weightKg,
            onSave = { kg ->
                onLogWeight(kg)
                showDialog = false
            },
            onDismiss = { showDialog = false },
        )
    }
}

@Composable
private fun WeightEntryDialog(
    initialValue: Double?,
    onSave: (Double) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf(initialValue?.let { "%.1f".format(it) } ?: "") }
    val isValid = isValidWeightInput(input)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log weight") },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("kg") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { input.replace(',', '.').toDoubleOrNull()?.let { onSave(it) } },
                enabled = isValid,
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun SportTonightSection(
    state: DashboardUiState,
    onSetSportTonight: (String, String) -> Unit,
    onClearSportTonight: () -> Unit,
) {
    val sport = state.sportTonight
    if (sport == null) {
        var expanded by remember { mutableStateOf(false) }
        if (!expanded) {
            TextButton(onClick = { expanded = true }) {
                Text("Set sport tonight")
            }
        } else {
            SportTonightPicker(
                onSet = { a, i -> onSetSportTonight(a, i) },
                onDismiss = { expanded = false },
            )
        }
    } else {
        SportTonightActive(sport = sport, onSetSportTonight = onSetSportTonight, onClear = onClearSportTonight)
    }
}

@Composable
private fun SportTonightActive(
    sport: SportTonightEntity,
    onSetSportTonight: (String, String) -> Unit,
    onClear: () -> Unit,
) {
    Column {
        val activityLabel = activities.firstOrNull { it.first == sport.activityType }?.second ?: sport.activityType
        Text("$activityLabel tonight", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            intensities.forEach { (value, label) ->
                FilterChip(
                    selected = sport.intensity == value,
                    onClick  = { onSetSportTonight(sport.activityType, value) },
                    label    = { Text(label) },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "+${sport.estimatedKcal} kcal est.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onClear, contentPadding = PaddingValues(0.dp)) {
            Text("clear", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SportTonightPicker(
    onSet: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedActivity by remember { mutableStateOf("climbing") }
    var selectedIntensity by remember { mutableStateOf("normal") }
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            activities.forEach { (value, label) ->
                FilterChip(
                    selected = selectedActivity == value,
                    onClick  = { selectedActivity = value },
                    label    = { Text(label) },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            intensities.forEach { (value, label) ->
                FilterChip(
                    selected = selectedIntensity == value,
                    onClick  = { selectedIntensity = value },
                    label    = { Text(label) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            Button(onClick = { onSet(selectedActivity, selectedIntensity) }) { Text("Done") }
        }
    }
}
