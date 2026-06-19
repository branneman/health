package org.branneman.health.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import org.branneman.health.db.entities.MealTemplateEntity

@Composable
fun TemplatesScreen(
    onBack: () -> Unit,
    viewModel: TemplatesViewModel = viewModel(),
) {
    val templates by viewModel.templates.collectAsStateWithLifecycle()
    TemplatesContent(
        templates = templates,
        onCreate  = { name, kcal -> viewModel.create(name, kcal) },
        onUpdate  = { id, name, kcal -> viewModel.update(id, name, kcal) },
        onDelete  = { id -> viewModel.delete(id) },
        onBack    = onBack,
    )
}

@Composable
fun TemplatesContent(
    templates: List<MealTemplateEntity>,
    onCreate: (String, Int) -> Unit,
    onUpdate: (String, String, Int) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<MealTemplateEntity?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        TextButton(onClick = onBack) {
            Text("← Back")
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text  = "Templates",
                style = MaterialTheme.typography.titleLarge,
            )
            TextButton(
                onClick  = { showAddDialog = true },
                modifier = Modifier.testTag("add_template_button"),
            ) {
                Text("+ Add")
            }
        }

        if (templates.isEmpty()) {
            Spacer(modifier = Modifier.height(32.dp))
            Text(
                text  = "No templates yet. Tap + Add to create one.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(templates, key = { it.id }) { template ->
                    ListItem(
                        headlineContent   = {
                            val prefix = if (template.sortOrder != null) "📌 " else ""
                            Text("$prefix${template.name}")
                        },
                        supportingContent = {
                            val kcalText = template.quickAddKcal?.let { "$it kcal" } ?: "—"
                            Text(kcalText)
                        },
                        modifier = Modifier
                            .testTag("template_item_${template.id}")
                            .clickable { editTarget = template },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (showAddDialog) {
        TemplateDialog(
            initial   = null,
            onSave    = { name, kcal ->
                onCreate(name, kcal)
                showAddDialog = false
            },
            onDelete  = {},
            onDismiss = { showAddDialog = false },
        )
    }

    editTarget?.let { target ->
        TemplateDialog(
            initial   = target,
            onSave    = { name, kcal ->
                onUpdate(target.id, name, kcal)
                editTarget = null
            },
            onDelete  = {
                onDelete(target.id)
                editTarget = null
            },
            onDismiss = { editTarget = null },
        )
    }
}

@Composable
private fun TemplateDialog(
    initial: MealTemplateEntity?,
    onSave: (String, Int) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial?.name ?: "") }
    var kcal by remember(initial) { mutableStateOf(initial?.quickAddKcal?.toString() ?: "") }

    val saveEnabled = name.isNotBlank() && (kcal.toIntOrNull() ?: 0) > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (initial == null) "New template" else "Edit template")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value         = name,
                    onValueChange = { name = it },
                    label         = { Text("Name") },
                    singleLine    = true,
                    modifier      = Modifier
                        .fillMaxWidth()
                        .testTag("template_name_field"),
                )
                OutlinedTextField(
                    value         = kcal,
                    onValueChange = { kcal = it.filter(Char::isDigit) },
                    label         = { Text("kcal") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine    = true,
                    modifier      = Modifier
                        .fillMaxWidth()
                        .testTag("template_kcal_field"),
                )
                if (initial != null) {
                    TextButton(
                        onClick  = onDelete,
                        modifier = Modifier.testTag("template_delete_button"),
                        colors   = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text("Delete")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick  = { onSave(name.trim(), kcal.toInt()) },
                enabled  = saveEnabled,
                modifier = Modifier.testTag("template_save_button"),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
