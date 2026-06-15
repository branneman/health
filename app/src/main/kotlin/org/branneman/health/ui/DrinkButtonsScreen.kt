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
import org.branneman.health.db.entities.ShortcutEntity
import java.util.UUID

@Composable
fun DrinkButtonsScreen(
    onBack: () -> Unit,
    viewModel: DrinkButtonsViewModel = viewModel(),
) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    DrinkButtonsContent(
        draft  = draft ?: emptyList(),
        onSave = { rows ->
            viewModel.save(rows)
            onBack()
        },
        onBack = onBack,
    )
}

@Composable
fun DrinkButtonsContent(
    draft: List<ShortcutEntity>,
    onSave: (List<ShortcutEntity>) -> Unit,
    onBack: () -> Unit,
) {
    var rows by remember(draft) { mutableStateOf(draft) }
    var showAddDialog by remember { mutableStateOf(false) }

    val saveEnabled = rows.isNotEmpty() && rows.all {
        it.emoji.isNotBlank() && it.label.isNotBlank() && it.kcal > 0
    }

    if (showAddDialog) {
        AddDrinkButtonDialog(
            onConfirm = { emoji, label, kcal ->
                val nextOrder = (rows.maxOfOrNull { it.sortOrder } ?: -1) + 1
                rows = rows + ShortcutEntity(
                    id        = UUID.randomUUID().toString(),
                    userId    = rows.firstOrNull()?.userId ?: "",
                    emoji     = emoji,
                    label     = label,
                    kcal      = kcal,
                    sortOrder = nextOrder,
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
                text     = "Drink buttons",
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
                DrinkButtonRow(
                    row      = row,
                    onUp     = {
                        if (index > 0) {
                            val m = rows.toMutableList()
                            val t = m[index - 1]; m[index - 1] = m[index]; m[index] = t
                            rows = m.drinkReindexed()
                        }
                    },
                    onDown   = {
                        if (index < rows.size - 1) {
                            val m = rows.toMutableList()
                            val t = m[index + 1]; m[index + 1] = m[index]; m[index] = t
                            rows = m.drinkReindexed()
                        }
                    },
                    onDelete = {
                        rows = rows.toMutableList().also { it.removeAt(index) }.drinkReindexed()
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
private fun DrinkButtonRow(
    row: ShortcutEntity,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onDelete: () -> Unit,
    onChange: (ShortcutEntity) -> Unit,
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
            value         = row.emoji,
            onValueChange = { onChange(row.copy(emoji = it)) },
            label         = { Text("Emoji") },
            singleLine    = true,
            modifier      = Modifier.width(72.dp),
        )
        OutlinedTextField(
            value         = row.label,
            onValueChange = { onChange(row.copy(label = it)) },
            label         = { Text("Label") },
            singleLine    = true,
            modifier      = Modifier.weight(1f),
        )
        OutlinedTextField(
            value           = if (row.kcal == 0) "" else row.kcal.toString(),
            onValueChange   = { onChange(row.copy(kcal = it.filter(Char::isDigit).toIntOrNull() ?: 0)) },
            label           = { Text("kcal") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine      = true,
            modifier        = Modifier.width(72.dp),
        )
        TextButton(
            onClick  = onDelete,
            modifier = Modifier.semantics { contentDescription = "Delete ${row.label}" },
        ) {
            Text("✕")
        }
    }
}

@Composable
private fun AddDrinkButtonDialog(
    onConfirm: (String, String, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var emoji by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var kcal  by remember { mutableStateOf("") }
    val confirmEnabled = emoji.isNotBlank() && label.isNotBlank() && (kcal.toIntOrNull() ?: 0) > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title   = { Text("New drink button") },
        text    = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value         = emoji,
                    onValueChange = { emoji = it },
                    label         = { Text("Emoji") },
                    singleLine    = true,
                )
                OutlinedTextField(
                    value         = label,
                    onValueChange = { label = it },
                    label         = { Text("Label") },
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
            TextButton(onClick = { onConfirm(emoji, label, kcal.toInt()) }, enabled = confirmEnabled) {
                Text("Add")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun List<ShortcutEntity>.drinkReindexed(): List<ShortcutEntity> =
    mapIndexed { i, e -> e.copy(sortOrder = i) }
