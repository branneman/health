package org.branneman.health.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.branneman.health.db.entities.FoodItemEntity

@Composable
fun BuildFromScratchScreen(
    pendingFoodItem: FoodItemEntity?,
    onPendingFoodItemConsumed: () -> Unit,
    onAddIngredient: () -> Unit,
    onLogged: (mealType: String) -> Unit,
    onSavedAsTemplate: (name: String) -> Unit,
    onBack: (bailOutKcal: Int?) -> Unit,
    initialTemplateId: String? = null,
    loggedAt: String = "",
    viewModel: BuildFromScratchViewModel = viewModel(),
) {
    val ingredients by viewModel.ingredients.collectAsStateWithLifecycle()
    val totalKcal   by viewModel.totalKcal.collectAsStateWithLifecycle()

    var pendingItem by remember { mutableStateOf<FoodItemEntity?>(null) }
    LaunchedEffect(pendingFoodItem) {
        if (pendingFoodItem != null) {
            pendingItem = pendingFoodItem
            onPendingFoodItemConsumed()
        }
    }

    LaunchedEffect(initialTemplateId) {
        if (initialTemplateId != null) viewModel.loadFromTemplate(initialTemplateId)
    }

    BuildFromScratchContent(
        ingredients      = ingredients,
        totalKcal        = totalKcal,
        pendingFoodItem  = pendingItem,
        onAddIngredient  = onAddIngredient,
        onRemoveAt       = viewModel::removeAt,
        onGramsConfirmed = { item, grams ->
            viewModel.addIngredient(item, grams)
            pendingItem = null
        },
        onLog            = { mealType ->
            viewModel.log(mealType, loggedAt)
            onLogged(mealType)
        },
        onSaveAsTemplate = { name ->
            viewModel.saveAsTemplate(name)
            onSavedAsTemplate(name)
        },
        onBack           = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuildFromScratchContent(
    ingredients: List<Ingredient>,
    totalKcal: Int,
    pendingFoodItem: FoodItemEntity?,
    onAddIngredient: () -> Unit,
    onRemoveAt: (Int) -> Unit,
    onGramsConfirmed: (FoodItemEntity, Double) -> Unit,
    onLog: (mealType: String) -> Unit,
    onSaveAsTemplate: (name: String) -> Unit,
    onBack: (bailOutKcal: Int?) -> Unit,
) {
    var showMealTypeSheet by remember { mutableStateOf(false) }
    var showTemplateSheet by remember { mutableStateOf(false) }
    var showAbandonDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        TextButton(
            onClick  = { if (ingredients.isEmpty()) onBack(null) else showAbandonDialog = true },
            modifier = Modifier.testTag("bfs_back_button"),
        ) { Text("← Back") }

        Text("Build from scratch", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text("$totalKcal kcal", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            itemsIndexed(ingredients, key = { i, _ -> i }) { index, ingredient ->
                ListItem(
                    headlineContent   = { Text(ingredient.item.name) },
                    supportingContent = { Text("${ingredient.grams}g · ${(ingredient.grams / 100.0 * ingredient.item.kcalPer100g).toInt()} kcal") },
                    trailingContent   = {
                        TextButton(onClick = { onRemoveAt(index) }) { Text("Remove") }
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
            onClick  = { showMealTypeSheet = true },
            enabled  = ingredients.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().testTag("bfs_log_button"),
        ) { Text("Log") }
    }

    if (pendingFoodItem != null) {
        GramsDialog(
            foodName  = pendingFoodItem.name,
            onConfirm = { grams -> onGramsConfirmed(pendingFoodItem, grams) },
            onDismiss = {},
            modifier  = Modifier.testTag("bfs_grams_dialog"),
        )
    }

    if (showMealTypeSheet) {
        MealTypeSheet(
            modifier  = Modifier.testTag("bfs_meal_type_sheet"),
            onSelect  = { mealType ->
                showMealTypeSheet = false
                onLog(mealType)
                showTemplateSheet = true
            },
            onDismiss = { showMealTypeSheet = false },
        )
    }

    if (showTemplateSheet) {
        SaveAsTemplateSheet(
            onSave    = { name -> onSaveAsTemplate(name); showTemplateSheet = false },
            onSkip    = { showTemplateSheet = false },
            onDismiss = { showTemplateSheet = false },
        )
    }

    if (showAbandonDialog) {
        AlertDialog(
            onDismissRequest = { showAbandonDialog = false },
            title   = { Text("Abandon meal?") },
            text    = { Text("You have ${ingredients.size} ingredient(s) added.") },
            confirmButton = {
                TextButton(
                    onClick  = { showAbandonDialog = false; onBack(null) },
                    modifier = Modifier.testTag("bfs_bailout_discard"),
                ) { Text("Discard") }
            },
            dismissButton = {
                TextButton(
                    onClick  = { showAbandonDialog = false; onBack(totalKcal) },
                    modifier = Modifier.testTag("bfs_bailout_quick_add"),
                ) { Text("Use $totalKcal kcal in Quick Add") }
            },
        )
    }
}

@Composable
private fun GramsDialog(
    foodName: String,
    onConfirm: (Double) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var grams by remember { mutableStateOf("") }
    Box(modifier = modifier) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title            = { Text("How many grams of $foodName?") },
            text             = {
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MealTypeSheet(
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 16.dp).padding(bottom = 32.dp)) {
            Text("Meal type", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            listOf("breakfast", "lunch", "dinner", "snack").forEach { type ->
                TextButton(onClick = { onSelect(type) }, modifier = Modifier.fillMaxWidth()) {
                    Text(type.replaceFirstChar { it.uppercase() })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SaveAsTemplateSheet(
    onSave: (String) -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Save as template?", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value         = name,
                onValueChange = { name = it },
                label         = { Text("Template name") },
                singleLine    = true,
                modifier      = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onSkip) { Text("Skip") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) { Text("Save") }
            }
        }
    }
}
