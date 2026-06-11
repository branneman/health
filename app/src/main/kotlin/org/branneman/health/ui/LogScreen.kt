package org.branneman.health.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.branneman.health.db.entities.LogEntryEntity
import org.branneman.health.log.LogViewModel
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

@Composable
fun LogScreen(viewModel: LogViewModel = viewModel()) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var lastAction by remember { mutableStateOf<LogAction?>(null) }

    LaunchedEffect(lastAction) {
        val action = lastAction ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message     = action.message,
            actionLabel = "Undo",
            duration    = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) {
            when (action) {
                is LogAction.Added   -> viewModel.undoAdd()
                is LogAction.Deleted -> viewModel.undoDelete()
            }
        }
        lastAction = null
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        LogContent(
            entries  = entries,
            onAdd    = { kcal, label ->
                viewModel.addEntry(kcal, label)
                lastAction = LogAction.Added("Logged")
            },
            onDelete = { entry ->
                viewModel.deleteEntry(entry)
                lastAction = LogAction.Deleted("Deleted")
            },
            modifier = Modifier.padding(padding),
        )
    }
}

private sealed interface LogAction {
    val message: String
    data class Added(override val message: String)   : LogAction
    data class Deleted(override val message: String) : LogAction
}

@Composable
fun LogContent(
    entries: List<LogEntryEntity>,
    onAdd: (kcal: String, label: String) -> Unit,
    onDelete: (LogEntryEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    var kcal by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var entryToDelete by remember { mutableStateOf<LogEntryEntity?>(null) }
    val kcalFocusRequester = remember { FocusRequester() }
    val addEnabled = kcal.isNotEmpty() && (kcal.toIntOrNull() ?: 0) > 0

    LaunchedEffect(Unit) { kcalFocusRequester.requestFocus() }

    entryToDelete?.let { entry ->
        DeleteConfirmDialog(
            entry     = entry,
            onConfirm = { onDelete(entry); entryToDelete = null },
            onDismiss = { entryToDelete = null },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value         = kcal,
                onValueChange = { kcal = it.filter(Char::isDigit) },
                label         = { Text("kcal") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction    = ImeAction.Next,
                ),
                singleLine = true,
                modifier   = Modifier
                    .width(90.dp)
                    .focusRequester(kcalFocusRequester)
                    .testTag("kcal_input"),
            )
            OutlinedTextField(
                value         = label,
                onValueChange = { label = it },
                label         = { Text("label (optional)") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (addEnabled) { onAdd(kcal, label); kcal = ""; label = "" }
                }),
                singleLine = true,
                modifier   = Modifier
                    .weight(1f)
                    .testTag("label_input"),
            )
            Button(
                onClick = { onAdd(kcal, label); kcal = ""; label = "" },
                enabled = addEnabled,
            ) { Text("Add") }
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
            val total = entries.sumOf { it.quickAddKcal ?: 0 }
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(entries, key = { it.id }) { entry ->
                    LogEntryRow(entry = entry, onClick = { entryToDelete = entry })
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
private fun LogEntryRow(entry: LogEntryEntity, onClick: () -> Unit) {
    val time = remember(entry.loggedAt) {
        runCatching { OffsetDateTime.parse(entry.loggedAt).format(timeFmt) }.getOrDefault("--:--")
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
            if (entry.quickAddLabel != null) {
                Text(text = entry.quickAddLabel, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(
            text  = "${entry.quickAddKcal ?: 0} kcal",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DeleteConfirmDialog(
    entry: LogEntryEntity,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val time = remember(entry.loggedAt) {
        runCatching { OffsetDateTime.parse(entry.loggedAt).format(timeFmt) }.getOrDefault("--:--")
    }
    val title = buildString {
        entry.quickAddLabel?.let { append("$it — ") }
        append("${entry.quickAddKcal ?: 0} kcal — $time")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title            = { Text(title) },
        confirmButton    = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton    = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
