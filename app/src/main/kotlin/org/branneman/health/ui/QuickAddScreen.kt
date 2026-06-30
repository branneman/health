package org.branneman.health.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.branneman.health.log.QuickAddViewModel

@Composable
fun QuickAddScreen(
    onBack: () -> Unit,
    onLogged: (undoAction: () -> Unit) -> Unit = {},
    initialKcal: Int? = null,
    initialLabel: String? = null,
    loggedAt: String = "",
    viewModel: QuickAddViewModel = viewModel(),
) {
    Scaffold { padding ->
        QuickAddContent(
            onLog        = { kcalStr, label ->
                kcalStr.trim().toIntOrNull()?.takeIf { it > 0 }?.let { kcal ->
                    viewModel.logQuickAdd(kcal, label.ifEmpty { null }, loggedAt)
                    onLogged { viewModel.undoLog() }
                }
            },
            onBack       = onBack,
            modifier     = Modifier.padding(padding),
            initialKcal  = initialKcal,
            initialLabel = initialLabel,
        )
    }
}

@Composable
fun QuickAddContent(
    onLog: (kcal: String, label: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialKcal: Int? = null,
    initialLabel: String? = null,
) {
    var kcal by remember { mutableStateOf(initialKcal?.toString() ?: "") }
    var label by remember { mutableStateOf(initialLabel ?: "") }
    val logEnabled = (kcal.toIntOrNull() ?: 0) > 0
    val kcalFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { kcalFocus.requestFocus() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) {
            Text("← Back")
        }

        OutlinedTextField(
            value         = kcal,
            onValueChange = { kcal = it.filter(Char::isDigit) },
            label         = { Text("kcal") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine    = true,
            modifier      = Modifier
                .fillMaxWidth()
                .focusRequester(kcalFocus)
                .testTag("quick_add_kcal"),
        )

        OutlinedTextField(
            value         = label,
            onValueChange = { label = it },
            label         = { Text("label (optional)") },
            singleLine    = true,
            modifier      = Modifier
                .fillMaxWidth()
                .testTag("quick_add_label"),
        )

        Button(
            onClick = { onLog(kcal, label) },
            enabled = logEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("quick_add_log_button"),
        ) {
            Text("Log")
        }
    }
}
