package org.branneman.health.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.branneman.health.db.dao.LogEntryWithKcal
import org.branneman.health.db.entities.LogEntryEntity
import org.branneman.health.db.entities.MealTemplateEntity
import org.branneman.health.db.entities.ShortcutEntity
import org.branneman.health.log.LogViewModel
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

@Composable
fun LogScreen(
    viewModel: LogViewModel = viewModel(),
    onSetUpMealButtons: () -> Unit = {},
    shortcuts: List<ShortcutEntity> = emptyList(),
    onSetUpDrinkButtons: () -> Unit = {},
    onLogShortcut: (ShortcutEntity) -> Unit = {},
    onOpenLogFlow: () -> Unit = {},
    externalUndo: (() -> Unit)? = null,
    onExternalUndoConsumed: () -> Unit = {},
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val pinnedTemplates by viewModel.pinnedTemplates.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var lastAction by remember { mutableStateOf<LogAction?>(null) }

    LaunchedEffect(lastAction) {
        val action = lastAction ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message     = action.message,
            actionLabel = if (action is LogAction.Saved) null else "Undo",
            duration    = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) {
            when (action) {
                is LogAction.Added   -> viewModel.undoAdd()
                is LogAction.Deleted -> viewModel.undoDelete()
                is LogAction.Saved   -> Unit
            }
        }
        lastAction = null
    }

    LaunchedEffect(externalUndo) {
        val undoAction = externalUndo ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message     = "Logged",
            actionLabel = "Undo",
            duration    = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) undoAction()
        onExternalUndoConsumed()
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        LogContent(
            entries             = entries,
            pinnedTemplates     = pinnedTemplates,
            shortcuts           = shortcuts,
            onDelete            = { entry ->
                viewModel.deleteEntry(entry)
                lastAction = LogAction.Deleted("Deleted")
            },
            onEdit              = { entry, kcal, label ->
                viewModel.editEntry(entry, kcal, label)
                lastAction = LogAction.Saved("Saved")
            },
            onSetUpMealButtons  = onSetUpMealButtons,
            onLogTemplate       = { template ->
                viewModel.logFromTemplate(template)
                lastAction = LogAction.Added("Logged")
            },
            onSetUpDrinkButtons = onSetUpDrinkButtons,
            onLogShortcut       = { shortcut ->
                onLogShortcut(shortcut)
                lastAction = LogAction.Added("Logged")
            },
            onOpenLogFlow       = onOpenLogFlow,
            modifier            = Modifier.padding(padding),
        )
    }
}

private sealed interface LogAction {
    val message: String
    data class Added(override val message: String)   : LogAction
    data class Deleted(override val message: String) : LogAction
    data class Saved(override val message: String)   : LogAction
}

@Composable
fun LogContent(
    entries: List<LogEntryWithKcal>,
    onDelete: (LogEntryEntity) -> Unit,
    onEdit: (LogEntryEntity, Int, String?) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier,
    pinnedTemplates: List<MealTemplateEntity> = emptyList(),
    shortcuts: List<ShortcutEntity> = emptyList(),
    onSetUpMealButtons: () -> Unit = {},
    onLogTemplate: (MealTemplateEntity) -> Unit = {},
    onSetUpDrinkButtons: () -> Unit = {},
    onLogShortcut: (ShortcutEntity) -> Unit = {},
    onOpenLogFlow: () -> Unit = {},
) {
    var entryToDelete by remember { mutableStateOf<LogEntryWithKcal?>(null) }
    var entryToEdit   by remember { mutableStateOf<LogEntryWithKcal?>(null) }

    entryToDelete?.let { ewk ->
        DeleteConfirmDialog(
            entry     = ewk,
            onConfirm = { onDelete(ewk.entry); entryToDelete = null },
            onDismiss = { entryToDelete = null },
        )
    }

    entryToEdit?.let { ewk ->
        EditEntryDialog(
            entry     = ewk,
            onSave    = { kcal, label -> onEdit(ewk.entry, kcal, label); entryToEdit = null },
            onDelete  = { entryToDelete = ewk; entryToEdit = null },
            onDismiss = { entryToEdit = null },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // --- Meal button row ---
        if (pinnedTemplates.isEmpty()) {
            OutlinedButton(
                onClick  = onSetUpMealButtons,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Set up meal buttons →")
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .fillMaxWidth(),
            ) {
                pinnedTemplates.forEach { template ->
                    Button(onClick = { onLogTemplate(template) }) {
                        Text(template.name)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Drink shortcuts row
        if (shortcuts.isEmpty()) {
            OutlinedButton(
                onClick  = onSetUpDrinkButtons,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Set up drink buttons")
            }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shortcuts) { shortcut ->
                    Button(onClick = { onLogShortcut(shortcut) }) {
                        Text("${shortcut.emoji} ${shortcut.label}")
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick  = onOpenLogFlow,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("log_flow_button"),
        ) {
            Text("Log  ›")
        }

        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(modifier = Modifier.weight(1f))
            Text(
                text     = "Today",
                style    = MaterialTheme.typography.labelSmall,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            HorizontalDivider(modifier = Modifier.weight(1f))
        }

        Spacer(Modifier.height(8.dp))

        if (entries.isEmpty()) {
            Text(
                text     = "Nothing logged today.",
                style    = MaterialTheme.typography.bodyMedium,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        } else {
            val total = entries.sumOf { it.totalKcal }
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(entries, key = { it.entry.id }) { ewk ->
                    LogEntryRow(
                        entry   = ewk,
                        onClick = {
                            if (ewk.entry.quickAddKcal != null) entryToEdit = ewk
                            else entryToDelete = ewk
                        },
                    )
                    HorizontalDivider()
                }
            }
            Text(
                text     = "$total kcal logged today",
                style    = MaterialTheme.typography.bodySmall,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 8.dp),
            )
        }
    }
}

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun LogEntryRow(entry: LogEntryWithKcal, onClick: () -> Unit) {
    val time = remember(entry.entry.loggedAt) {
        runCatching { OffsetDateTime.parse(entry.entry.loggedAt).format(timeFmt) }.getOrDefault("--:--")
    }
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text  = time,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = entry.displayLabel, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            text  = "${entry.totalKcal} kcal",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DeleteConfirmDialog(
    entry: LogEntryWithKcal,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val time = remember(entry.entry.loggedAt) {
        runCatching { OffsetDateTime.parse(entry.entry.loggedAt).format(timeFmt) }.getOrDefault("--:--")
    }
    val title = "${entry.displayLabel} — ${entry.totalKcal} kcal — $time"
    AlertDialog(
        onDismissRequest = onDismiss,
        title            = { Text(title) },
        confirmButton    = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton    = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EditEntryDialog(
    entry: LogEntryWithKcal,
    onSave: (kcal: Int, label: String?) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val time = remember(entry.entry.loggedAt) {
        runCatching { OffsetDateTime.parse(entry.entry.loggedAt).format(timeFmt) }.getOrDefault("--:--")
    }
    var kcalText  by remember { mutableStateOf(entry.entry.quickAddKcal?.toString() ?: "") }
    var labelText by remember { mutableStateOf(entry.entry.quickAddLabel ?: "") }

    val kcalValue = kcalText.toIntOrNull()
    val saveEnabled = kcalValue != null && kcalValue > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title  = { Text(time) },
        text   = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value         = labelText,
                    onValueChange = { labelText = it },
                    label         = { Text("Label (optional)") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value         = kcalText,
                    onValueChange = { kcalText = it },
                    label         = { Text("kcal") },
                    singleLine    = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier      = Modifier
                        .fillMaxWidth()
                        .testTag("edit_entry_kcal_field"),
                )
            }
        },
        confirmButton = {
            Button(
                onClick  = { onSave(kcalValue!!, labelText.trim().ifEmpty { null }) },
                enabled  = saveEnabled,
                modifier = Modifier.testTag("edit_entry_save_button"),
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete)  { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
