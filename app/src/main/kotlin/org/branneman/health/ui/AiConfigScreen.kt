package org.branneman.health.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.branneman.health.AiConfigStatusDto
import org.branneman.health.ai.AiConfigViewModel

@Composable
fun AiConfigScreen(
    onBack: () -> Unit,
    viewModel: AiConfigViewModel = viewModel(),
) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSaving.collectAsStateWithLifecycle()
    var apiKeyInput by remember { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }

    AiConfigContent(
        status             = status ?: AiConfigStatusDto(configured = false, expiresAt = null),
        apiKeyInput        = apiKeyInput,
        onApiKeyChange     = { apiKeyInput = it },
        keyVisible         = keyVisible,
        onToggleKeyVisible = { keyVisible = !keyVisible },
        onSave             = { viewModel.save(apiKeyInput, null) },
        onRemove           = { viewModel.remove() },
        onBack             = onBack,
        isSaving           = isSaving,
    )
}

@Composable
fun AiConfigContent(
    status: AiConfigStatusDto,
    apiKeyInput: String,
    onApiKeyChange: (String) -> Unit,
    keyVisible: Boolean,
    onToggleKeyVisible: () -> Unit,
    onSave: () -> Unit,
    onRemove: () -> Unit,
    onBack: () -> Unit,
    isSaving: Boolean,
) {
    val badgeText = when {
        status.configured                              -> "Connected"
        status.expiresAt != null && !status.configured -> "Expired"
        else                                           -> "Not configured"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text("← Back") }

        Text("AI (Anthropic)", style = MaterialTheme.typography.titleMedium)

        Text(
            text     = badgeText,
            style    = MaterialTheme.typography.bodyMedium,
            color    = if (status.configured) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("ai_config_status_badge"),
        )

        OutlinedTextField(
            value                = apiKeyInput,
            onValueChange        = onApiKeyChange,
            label                = { Text("Anthropic API key") },
            singleLine           = true,
            visualTransformation = if (keyVisible) VisualTransformation.None
                                   else PasswordVisualTransformation(),
            keyboardOptions      = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon         = {
                TextButton(onClick = onToggleKeyVisible) {
                    Text(if (keyVisible) "Hide" else "Show")
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("ai_config_key_field"),
        )

        Button(
            onClick  = onSave,
            enabled  = apiKeyInput.isNotBlank() && !isSaving,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("ai_config_save_button"),
        ) {
            Text("Save")
        }

        if (status.configured || status.expiresAt != null) {
            OutlinedButton(
                onClick  = onRemove,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Remove key")
            }
        }
    }
}
