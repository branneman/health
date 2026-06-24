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
import org.branneman.health.db.dao.MealTemplateWithKcal
import org.branneman.health.db.entities.MealTemplateEntity

@Composable
fun TemplatesScreen(
    onBack: () -> Unit,
    onEditIngredientTemplate: (String) -> Unit = {},
    onNewIngredientTemplate: () -> Unit = {},
    viewModel: TemplatesViewModel = viewModel(),
) {
    val templates by viewModel.templates.collectAsStateWithLifecycle()
    TemplatesContent(
        templates                = templates,
        onCreate                 = { name, kcal -> viewModel.create(name, kcal) },
        onUpdate                 = { id, name, kcal -> viewModel.update(id, name, kcal) },
        onDelete                 = { id -> viewModel.delete(id) },
        onBack                   = onBack,
        onEditIngredientTemplate = onEditIngredientTemplate,
        onNewIngredientTemplate  = onNewIngredientTemplate,
    )
}

@Composable
fun TemplatesContent(
    templates: List<MealTemplateWithKcal>,
    onCreate: (String, Int) -> Unit,
    onUpdate: (String, String, Int) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
    onEditIngredientTemplate: (String) -> Unit = {},
    onNewIngredientTemplate: () -> Unit = {},
) {
    var showAddDialog     by remember { mutableStateOf(false) }
    var showAddTypeSheet  by remember { mutableStateOf(false) }
    var editTarget        by remember { mutableStateOf<MealTemplateEntity?>(null) }

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
                onClick  = { showAddTypeSheet = true },
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
                items(templates, key = { it.template.id }) { twk ->
                    val isIngredient = twk.template.quickAddKcal == null
                    val isPinned     = twk.template.sortOrder != null
                    ListItem(
                        headlineContent   = {
                            val prefix = if (isPinned) "📌 " else ""
                            Text("$prefix${twk.template.name}")
                        },
                        supportingContent = {
                            val label = when {
                                isIngredient -> "${twk.computedKcal} kcal (ingredient template)"
                                isPinned     -> "${twk.computedKcal} kcal (meal button)"
                                else         -> "${twk.computedKcal} kcal (fixed calories)"
                            }
                            Text(label)
                        },
                        modifier = Modifier
                            .testTag("template_item_${twk.template.id}")
                            .clickable {
                                if (isIngredient) {
                                    onEditIngredientTemplate(twk.template.id)
                                } else {
                                    editTarget = twk.template
                                }
                            },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (showAddTypeSheet) {
        AddTemplateTypeSheet(
            onKcalTotal      = { showAddTypeSheet = false; showAddDialog = true },
            onFromIngredients = { showAddTypeSheet = false; onNewIngredientTemplate() },
            onDismiss        = { showAddTypeSheet = false },
        )
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
private fun AddTemplateTypeSheet(
    onKcalTotal: () -> Unit,
    onFromIngredients: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New template") },
        text = {
            Column {
                TextButton(
                    onClick  = onKcalTotal,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_template_kcal_total"),
                ) {
                    Text("Kcal total — enter a fixed calorie amount")
                }
                HorizontalDivider()
                TextButton(
                    onClick  = onFromIngredients,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_template_from_ingredients"),
                ) {
                    Text("From ingredients — build from food items")
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
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
