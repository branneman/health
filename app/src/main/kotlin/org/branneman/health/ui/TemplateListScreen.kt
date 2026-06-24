package org.branneman.health.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.branneman.health.db.entities.MealTemplateEntity

@Composable
fun TemplateListScreen(
    onBack: () -> Unit,
    onLogged: (undoAction: () -> Unit) -> Unit = {},
    onSelectIngredientTemplate: (String) -> Unit = {},
    viewModel: TemplateListViewModel = viewModel(),
) {
    val templates by viewModel.templates.collectAsStateWithLifecycle()
    val ingredientTemplates by viewModel.ingredientTemplates.collectAsStateWithLifecycle()

    Scaffold { padding ->
        TemplateListContent(
            templates                  = templates,
            ingredientTemplates        = ingredientTemplates,
            onLogTemplate              = { template, multiplier ->
                viewModel.logFromTemplate(template, multiplier)
                onLogged { viewModel.undoLog() }
            },
            onSelectIngredientTemplate = onSelectIngredientTemplate,
            onBack                     = onBack,
            modifier                   = Modifier.padding(padding),
        )
    }
}

@Composable
fun TemplateListContent(
    templates: List<MealTemplateEntity>,
    onLogTemplate: (MealTemplateEntity, Float) -> Unit,
    onBack: () -> Unit,
    ingredientTemplates: List<MealTemplateEntity> = emptyList(),
    onSelectIngredientTemplate: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var selectedTemplate by remember { mutableStateOf<MealTemplateEntity?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        TextButton(onClick = onBack) {
            Text("← Back")
        }

        if (templates.isEmpty() && ingredientTemplates.isEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text(
                text  = "No templates yet — add one in Settings → Templates.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(templates, key = { it.id }) { template ->
                    ListItem(
                        headlineContent = {
                            val displayName = if (template.sortOrder != null) {
                                "📌 ${template.name}"
                            } else {
                                template.name
                            }
                            Text(displayName)
                        },
                        modifier = Modifier.clickable { selectedTemplate = template },
                    )
                    HorizontalDivider()
                }
                if (ingredientTemplates.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text  = "Ingredient templates",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    items(ingredientTemplates, key = { "i_${it.id}" }) { template ->
                        ListItem(
                            headlineContent   = { Text(template.name) },
                            supportingContent = { Text("Build from ingredients") },
                            modifier          = Modifier.clickable { onSelectIngredientTemplate(template.id) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    selectedTemplate?.let { template ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable { selectedTemplate = null },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                modifier    = Modifier.fillMaxWidth(),
                onClick     = {},
                tonalElevation = 3.dp,
            ) {
                PortionAdjusterContent(
                    template = template,
                    onLog    = { multiplier ->
                        onLogTemplate(template, multiplier)
                        selectedTemplate = null
                    },
                )
            }
        }
    }
}

private val portionMultipliers = listOf(0.8f, 1.0f, 1.2f)
private val portionLabels      = listOf("Lighter", "Normal", "Heavier")

@Composable
private fun PortionAdjusterContent(
    template: MealTemplateEntity,
    onLog: (Float) -> Unit,
) {
    var selectedIndex by remember { mutableIntStateOf(1) }
    val baseKcal = template.quickAddKcal ?: 0
    val previewKcal = (baseKcal * portionMultipliers[selectedIndex]).toInt()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
    ) {
        Text(text = template.name, style = MaterialTheme.typography.titleLarge)
        Text(
            text  = "$previewKcal kcal · ${portionLabels[selectedIndex].lowercase()} portion",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        portionMultipliers.forEachIndexed { index, multiplier ->
            val adjustedKcal = (baseKcal * multiplier).toInt()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier          = Modifier
                    .fillMaxWidth()
                    .clickable { selectedIndex = index }
                    .padding(vertical = 4.dp)
                    .testTag("portion_option_$index"),
            ) {
                RadioButton(
                    selected = selectedIndex == index,
                    onClick  = { selectedIndex = index },
                )
                Text(
                    text     = "${portionLabels[index]}  ×${"%.1f".format(multiplier)}  $adjustedKcal kcal",
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick  = { onLog(portionMultipliers[selectedIndex]) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("portion_log_button"),
        ) {
            Text("Log")
        }
    }
}
