package org.branneman.health.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.branneman.health.db.entities.MealTemplateEntity

@Composable
fun MealButtonsScreen(
    onBack: () -> Unit,
    viewModel: MealButtonsViewModel = viewModel(),
) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    MealButtonsContent(
        draft  = draft ?: emptyList(),
        onSave = { viewModel.save() },
        onBack = onBack,
    )
}

@Composable
fun MealButtonsContent(
    draft: List<MealTemplateEntity>,
    onSave: (List<MealTemplateEntity>) -> Unit,
    onBack: () -> Unit,
) {
    var rows by remember(draft) { mutableStateOf(draft) }
    var showAddDialog by remember { mutableStateOf(false) }

    val saveEnabled = rows.isNotEmpty() && rows.all { it.name.isNotBlank() && (it.quickAddKcal ?: 0) > 0 }

    if (showAddDialog) {
        AddButtonDialog(
            onConfirm = { name, kcal ->
                val nextOrder = (rows.maxOfOrNull { it.sortOrder ?: -1 } ?: -1) + 1
                rows = rows + MealTemplateEntity(
                    userId       = rows.firstOrNull()?.userId ?: "",
                    name         = name,
                    sortOrder    = nextOrder,
                    quickAddKcal = kcal,
                )
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text(
                text     = "Meal buttons",
                style    = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick  = { onSave(rows) },
                enabled  = saveEnabled,
                modifier = Modifier.padding(start = 8.dp),
            ) { Text("Save") }
        }

        Spacer(Modifier.height(16.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            itemsIndexed(rows, key = { _, r -> r.id }) { index, row ->
                MealButtonRow(
                    row      = row,
                    onUp     = {
                        if (index > 0) {
                            val m = rows.toMutableList()
                            val t = m[index - 1]; m[index - 1] = m[index]; m[index] = t
                            rows = m.reindexed()
                        }
                    },
                    onDown   = {
                        if (index < rows.size - 1) {
                            val m = rows.toMutableList()
                            val t = m[index + 1]; m[index + 1] = m[index]; m[index] = t
                            rows = m.reindexed()
                        }
                    },
                    onDelete = {
                        rows = rows.toMutableList().also { it.removeAt(index) }.reindexed()
                    },
                    onChange = { updated ->
                        rows = rows.toMutableList().also { it[index] = updated }
                    },
                )
                HorizontalDivider()
            }
        }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick  = { showAddDialog = true },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Add button") }
    }
}

@Composable
private fun MealButtonRow(
    row: MealTemplateEntity,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onDelete: () -> Unit,
    onChange: (MealTemplateEntity) -> Unit,
) {
    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier              = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column {
            TextButton(onClick = onUp,   modifier = Modifier.size(40.dp)) { Text("↑") }
            TextButton(onClick = onDown, modifier = Modifier.size(40.dp)) { Text("↓") }
        }
        OutlinedTextField(
            value         = row.name,
            onValueChange = { onChange(row.copy(name = it)) },
            label         = { Text("Name") },
            singleLine    = true,
            modifier      = Modifier.weight(1f),
        )
        OutlinedTextField(
            value           = row.quickAddKcal?.toString() ?: "",
            onValueChange   = { onChange(row.copy(quickAddKcal = it.filter(Char::isDigit).toIntOrNull())) },
            label           = { Text("kcal") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine      = true,
            modifier        = Modifier.width(72.dp),
        )
        TextButton(
            onClick  = onDelete,
            modifier = Modifier.semantics { contentDescription = "Delete ${row.name}" },
        ) {
            Text("✕")
        }
    }
}

@Composable
private fun AddButtonDialog(onConfirm: (String, Int) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var kcal by remember { mutableStateOf("") }
    val confirmEnabled = name.isNotBlank() && (kcal.toIntOrNull() ?: 0) > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title   = { Text("New meal button") },
        text    = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value         = name,
                    onValueChange = { name = it },
                    label         = { Text("Name") },
                    singleLine    = true,
                )
                OutlinedTextField(
                    value           = kcal,
                    onValueChange   = { kcal = it.filter(Char::isDigit) },
                    label           = { Text("kcal") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine      = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, kcal.toInt()) }, enabled = confirmEnabled) {
                Text("Add")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
