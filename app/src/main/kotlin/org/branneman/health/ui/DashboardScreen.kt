package org.branneman.health.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.branneman.health.dashboard.DashboardUiState
import org.branneman.health.dashboard.DashboardViewModel
import org.branneman.health.dashboard.TrendRange
import org.branneman.health.dashboard.WeeklyVerdict
import org.branneman.health.dashboard.isValidWeightInput
import org.branneman.health.dashboard.verdictMessage

@Composable
fun DashboardScreen(viewModel: DashboardViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DashboardContent(
        state = state,
        onLogWeight = viewModel::logWeight,
        onSelectTrendRange = viewModel::selectTrendRange,
    )
}

@Composable
fun DashboardContent(
    state: DashboardUiState,
    onLogWeight: (Double) -> Unit,
    onSelectTrendRange: (TrendRange) -> Unit,
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
        state.weeklyVerdict?.let { verdict ->
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text(
                text = "This week",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            WeeklyVerdictCard(verdict)
        }
        state.weightTrend?.let { trend ->
            Spacer(Modifier.height(16.dp))
            WeightTrendChart(
                trend = trend,
                selectedRange = state.selectedTrendRange,
                goalWeightKg = state.goalWeightKg,
                onSelectRange = onSelectTrendRange,
            )
        }
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
private fun WeeklyVerdictCard(verdict: WeeklyVerdict) {
    // Verdict states get a colored surface; gate states stay neutral (spec §UI).
    val (container, content, tag) = when (verdict) {
        is WeeklyVerdict.Green ->
            Triple(Color(0xFFC8E6C9), Color(0xFF1B5E20), "verdict-green")
        is WeeklyVerdict.AmberBehind, is WeeklyVerdict.AmberFast ->
            Triple(Color(0xFFFFE0B2), Color(0xFF7A4F01), "verdict-amber")
        WeeklyVerdict.GracePeriod, WeeklyVerdict.NotEnoughData ->
            Triple(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, "verdict-neutral")
    }
    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag),
    ) {
        Text(
            text = verdictMessage(verdict),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}
