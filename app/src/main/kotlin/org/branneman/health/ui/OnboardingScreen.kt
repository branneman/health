package org.branneman.health.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.branneman.health.onboarding.OnboardingUiState
import org.branneman.health.onboarding.OnboardingViewModel
import kotlin.math.roundToInt

internal fun adjustTime(time: String, deltaMinutes: Int): String {
    val parts = time.split(":")
    val h = parts.getOrNull(0)?.toIntOrNull() ?: 7
    val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val total = ((h * 60 + m + deltaMinutes) % (24 * 60) + 24 * 60) % (24 * 60)
    return "%02d:%02d".format(total / 60, total % 60)
}

private data class ActivityOption(val value: String, val label: String, val subtitle: String)

private val activityOptions = listOf(
    ActivityOption("sedentary",         "Mostly sitting",    "Desk job, ≤1 sport/week"),
    ActivityOption("lightly_active",    "Lightly active",    "2–4 sport sessions/week"),
    ActivityOption("moderately_active", "Moderately active", "5+ sessions/week"),
)

@Composable
fun OnboardingScreen(viewModel: OnboardingViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    when (state.step) {
        1 -> OnboardingStep1(
            state    = state,
            onUpdate = viewModel::update,
            onNext   = viewModel::goNext,
        )
        2 -> OnboardingStep2(
            state    = state,
            onUpdate = viewModel::update,
            onBack   = viewModel::goBack,
            onNext   = viewModel::goNext,
        )
        3 -> OnboardingStep3(
            state    = state,
            onUpdate = viewModel::update,
            onBack   = viewModel::goBack,
            onNext   = viewModel::goNext,
        )
        4 -> OnboardingStep4(
            state    = state,
            onUpdate = viewModel::update,
            onBack   = viewModel::goBack,
            onSave   = viewModel::save,
        )
        else -> error("Unexpected onboarding step: ${state.step}")
    }
}

@Composable
fun OnboardingStep1(
    state: OnboardingUiState,
    onUpdate: (OnboardingUiState.() -> OnboardingUiState) -> Unit,
    onNext: () -> Unit,
) {
    val goalError = state.goalWeightKg.isNotEmpty() && state.currentWeightKg.isNotEmpty() &&
            (state.goalWeightKg.toDoubleOrNull() ?: 0.0) > (state.currentWeightKg.toDoubleOrNull() ?: 0.0)

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Set up your profile  1/4", style = MaterialTheme.typography.headlineSmall)

        // Sex toggle
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
            value         = state.heightCm,
            onValueChange = { onUpdate { copy(heightCm = it) } },
            label         = { Text("Height (cm)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier      = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value         = state.currentWeightKg,
            onValueChange = { onUpdate { copy(currentWeightKg = it) } },
            label         = { Text("Current weight (kg)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier      = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value         = state.goalWeightKg,
            onValueChange = { onUpdate { copy(goalWeightKg = it) } },
            label         = { Text("Goal weight (kg)") },
            isError       = goalError,
            supportingText = if (goalError) {{ Text("Goal must be ≤ current weight") }} else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier      = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value         = state.age,
            onValueChange = { onUpdate { copy(age = it) } },
            label         = { Text("Age") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier      = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.weight(1f))

        Button(
            onClick  = onNext,
            enabled  = state.step1Valid,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Continue") }
    }
}

@Composable
fun OnboardingStep2(
    state: OnboardingUiState,
    onUpdate: (OnboardingUiState.() -> OnboardingUiState) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("How active are you?  2/4", style = MaterialTheme.typography.headlineSmall)

        activityOptions.forEach { option ->
            Row(
                modifier = Modifier
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

        state.estimatedTdeeKcal?.let { tdee ->
            Text(
                "Estimated output: ~$tdee kcal/day (estimated)",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(Modifier.weight(1f))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Back") }
            Button(onClick = onNext, modifier = Modifier.weight(1f)) { Text("Continue") }
        }
    }
}

@Composable
fun OnboardingStep3(
    state: OnboardingUiState,
    onUpdate: (OnboardingUiState.() -> OnboardingUiState) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("How fast?  3/4", style = MaterialTheme.typography.headlineSmall)

        Text("${state.targetDeficit} kcal/day", style = MaterialTheme.typography.titleMedium)

        Slider(
            value         = state.targetDeficit.toFloat(),
            onValueChange = { onUpdate { copy(targetDeficit = (it / 25).roundToInt() * 25) } },
            valueRange    = 0f..600f,
            steps         = 23, // 25 kcal steps: (600-0)/25 - 1 = 23 internal steps
            modifier      = Modifier.fillMaxWidth(),
        )

        Text("Recommended range: 250–400 kcal/day", style = MaterialTheme.typography.bodySmall)

        when {
            state.targetDeficit == 0 ->
                Text("Maintain weight — no active deficit")
            else -> {
                state.kgPerWeek?.let { Text("≈ ${"%.2f".format(it)} kg/week") }
                state.monthsToGoal?.let { Text("Goal reached in ~${"%.0f".format(it)} months") }
            }
        }

        if (state.targetDeficit > 500) {
            Text(
                "⚠ Above 500 kcal/day risks muscle loss.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        state.saveError?.let { error ->
            Snackbar(
                action = {
                    TextButton(onClick = { onUpdate { copy(saveError = null) } }) {
                        Text("Dismiss")
                    }
                }
            ) { Text(error) }
        }

        Spacer(Modifier.weight(1f))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Back") }
            Button(onClick = onNext, modifier = Modifier.weight(1f)) { Text("Continue") }
        }
    }
}

@Composable
fun OnboardingStep4(
    state: OnboardingUiState,
    onUpdate: (OnboardingUiState.() -> OnboardingUiState) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Your schedule  4/4", style = MaterialTheme.typography.headlineSmall)

        Text(
            "When do you typically wake up and go to bed? This lets the budget know how much eating time is left in your day.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(Modifier.height(8.dp))

        TimeAdjustRow(
            label    = "Wake time",
            time     = state.wakeTime,
            onMinus  = { onUpdate { copy(wakeTime = adjustTime(wakeTime, -30)) } },
            onPlus   = { onUpdate { copy(wakeTime = adjustTime(wakeTime, +30)) } },
        )

        TimeAdjustRow(
            label    = "Bedtime",
            time     = state.bedtime,
            onMinus  = { onUpdate { copy(bedtime = adjustTime(bedtime, -30)) } },
            onPlus   = { onUpdate { copy(bedtime = adjustTime(bedtime, +30)) } },
        )

        state.saveError?.let { error ->
            Snackbar(
                action = {
                    TextButton(onClick = { onUpdate { copy(saveError = null) } }) {
                        Text("Dismiss")
                    }
                }
            ) { Text(error) }
        }

        Spacer(Modifier.weight(1f))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Back") }
            Button(
                onClick  = onSave,
                enabled  = !state.isSaving,
                modifier = Modifier.weight(1f),
            ) {
                if (state.isSaving) CircularProgressIndicator(modifier = Modifier.size(18.dp))
                Text("Done")
            }
        }
    }
}

@Composable
private fun TimeAdjustRow(
    label: String,
    time: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onMinus, modifier = Modifier.size(40.dp), contentPadding = PaddingValues(0.dp)) {
            Text("−")
        }
        Text(
            time,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        OutlinedButton(onClick = onPlus, modifier = Modifier.size(40.dp), contentPadding = PaddingValues(0.dp)) {
            Text("+")
        }
    }
}
