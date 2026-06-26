package org.branneman.health.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.branneman.health.db.entities.FoodItemEntity
import org.branneman.health.log.LogViewModel
import kotlin.math.roundToInt

@Composable
fun SingleItemLogScreen(
    item: FoodItemEntity,
    logViewModel: LogViewModel,
    onLogged: (undoAction: () -> Unit) -> Unit,
    onBack: () -> Unit,
) {
    SingleItemLogContent(
        foodName    = item.name,
        kcalPer100g = item.kcalPer100g,
        onLog       = { kcal ->
            logViewModel.logSingleItem(item.name, kcal)
            onLogged { logViewModel.undoAdd() }
        },
        onBack      = onBack,
    )
}

@Composable
fun SingleItemLogContent(
    foodName: String,
    kcalPer100g: Double,
    onLog: (kcal: Int) -> Unit,
    onBack: () -> Unit,
) {
    var gramsText by remember { mutableStateOf("") }
    val grams = gramsText.toDoubleOrNull()
    val kcal  = grams?.takeIf { it > 0.0 }?.let { (it / 100.0 * kcalPer100g).roundToInt() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text("← Back") }

        Text(foodName, style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(
            value           = gramsText,
            onValueChange   = { gramsText = it },
            label           = { Text("Grams") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine      = true,
            modifier        = Modifier
                .fillMaxWidth()
                .testTag("single_item_grams_field"),
        )

        if (kcal != null) {
            Text(
                text     = "$kcal kcal",
                style    = MaterialTheme.typography.bodyMedium,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("single_item_kcal_preview"),
            )
        }

        Button(
            onClick  = { kcal?.let { onLog(it) } },
            enabled  = kcal != null,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("single_item_log_button"),
        ) { Text("Log") }
    }
}
