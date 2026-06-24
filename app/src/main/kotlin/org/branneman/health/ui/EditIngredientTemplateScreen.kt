package org.branneman.health.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.branneman.health.db.entities.FoodItemEntity
import kotlin.math.roundToInt

@Composable
fun EditIngredientTemplateScreen(
    templateId: String?,
    pendingFoodItem: FoodItemEntity?,
    onPendingFoodItemConsumed: () -> Unit,
    onAddIngredient: () -> Unit,
    onSaved: () -> Unit,
    onBack: () -> Unit,
    viewModel: EditIngredientTemplateViewModel = viewModel(),
) {
    LaunchedEffect(templateId) { viewModel.loadTemplate(templateId) }

    val name        by viewModel.name.collectAsStateWithLifecycle()
    val ingredients by viewModel.ingredients.collectAsStateWithLifecycle()
    val totalKcal   by viewModel.totalKcal.collectAsStateWithLifecycle()

    var pendingItem by remember { mutableStateOf<FoodItemEntity?>(null) }
    LaunchedEffect(pendingFoodItem) {
        if (pendingFoodItem != null) {
            pendingItem = pendingFoodItem
            onPendingFoodItemConsumed()
        }
    }

    if (pendingItem != null) {
        GramsDialogForTemplate(
            foodName  = pendingItem!!.name,
            onConfirm = { grams ->
                viewModel.addIngredient(pendingItem!!, grams)
                pendingItem = null
            },
            onDismiss = { pendingItem = null },
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        TextButton(onClick = onBack) { Text("← Back") }
        Text(
            if (templateId == null) "New ingredient template" else "Edit ingredient template",
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value         = name,
            onValueChange = viewModel::onNameChange,
            label         = { Text("Template name") },
            singleLine    = true,
            modifier      = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text("$totalKcal kcal", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            itemsIndexed(ingredients, key = { i, _ -> i }) { index, ingredient ->
                ListItem(
                    headlineContent   = { Text(ingredient.item.name) },
                    supportingContent = { Text("${ingredient.grams}g · ${(ingredient.grams / 100.0 * ingredient.item.kcalPer100g).roundToInt()} kcal") },
                    trailingContent   = {
                        TextButton(onClick = { viewModel.removeAt(index) }) { Text("Remove") }
                    },
                )
                HorizontalDivider()
            }
        }

        Button(onClick = onAddIngredient, modifier = Modifier.fillMaxWidth()) {
            Text("+ Add ingredient")
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick  = { viewModel.save(); onSaved() },
            enabled  = name.isNotBlank() && ingredients.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save template") }
    }
}

@Composable
private fun GramsDialogForTemplate(
    foodName: String,
    onConfirm: (Double) -> Unit,
    onDismiss: () -> Unit,
) {
    var grams by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title   = { Text("How many grams of $foodName?") },
        text    = {
            OutlinedTextField(
                value           = grams,
                onValueChange   = { grams = it },
                label           = { Text("Grams") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine      = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick  = { grams.toDoubleOrNull()?.let { onConfirm(it) } },
                enabled  = (grams.toDoubleOrNull() ?: 0.0) > 0.0,
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
