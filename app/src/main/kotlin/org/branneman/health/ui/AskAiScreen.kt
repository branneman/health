package org.branneman.health.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.branneman.health.ai.AskAiState
import org.branneman.health.ai.AskAiViewModel

@Composable
fun AskAiScreen(
    onBack: () -> Unit,
    onUseThis: (kcal: Int, label: String?, undoAction: () -> Unit) -> Unit,
    onEditAmount: (kcal: Int, label: String?) -> Unit,
    onNeedsAiConfig: () -> Unit,
    viewModel: AskAiViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val text by viewModel.text.collectAsStateWithLifecycle()
    val imageBitmap by viewModel.imageBitmap.collectAsStateWithLifecycle()
    val canEstimate by viewModel.canEstimate.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.setImage(context, it) }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.reset() }
    }

    AskAiContent(
        state           = state,
        text            = text,
        onTextChange    = viewModel::setText,
        imageBitmap     = imageBitmap,
        onPickImage     = { imagePicker.launch("image/*") },
        onClearImage    = viewModel::clearImage,
        canEstimate     = canEstimate,
        onEstimate      = viewModel::estimate,
        onUseThis       = { kcal, label ->
            viewModel.logDirectly(kcal, label)
            onUseThis(kcal, label) { viewModel.undoDirectLog() }
        },
        onEditAmount    = { kcal, label -> onEditAmount(kcal, label) },
        onDiscard       = viewModel::discard,
        onNeedsAiConfig = onNeedsAiConfig,
        onBack          = onBack,
    )
}

@Composable
fun AskAiContent(
    state: AskAiState,
    text: String,
    onTextChange: (String) -> Unit,
    imageBitmap: ImageBitmap?,
    onPickImage: () -> Unit,
    onClearImage: () -> Unit,
    canEstimate: Boolean,
    onEstimate: () -> Unit,
    onUseThis: (kcal: Int, label: String?) -> Unit,
    onEditAmount: (kcal: Int, label: String?) -> Unit,
    onDiscard: () -> Unit,
    onNeedsAiConfig: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text("← Back") }

        when (state) {
            is AskAiState.Result -> {
                Text(
                    "Claude estimates: ${state.kcal} kcal",
                    style    = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.testTag("ask_ai_result_kcal"),
                )
                if (state.explanation != null) {
                    Text(
                        "\"${state.explanation}\"",
                        style    = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("ask_ai_result_explanation"),
                    )
                }
                Button(
                    onClick  = { onUseThis(state.kcal, state.inputText) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ask_ai_use_this"),
                ) { Text("Use this") }
                OutlinedButton(
                    onClick  = { onEditAmount(state.kcal, state.inputText) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ask_ai_edit_amount"),
                ) { Text("Edit amount") }
                TextButton(
                    onClick  = onDiscard,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ask_ai_discard"),
                ) { Text("Discard") }
            }

            is AskAiState.Error -> {
                val errorText = when (state) {
                    AskAiState.Error.NotConfigured ->
                        "Connect your Anthropic API key in Settings → AI"
                    AskAiState.Error.KeyExpired ->
                        "Your Anthropic API key has expired. Update it in Settings → AI."
                    AskAiState.Error.EstimateFailed ->
                        "Claude couldn't estimate this one. Try adding a description or a clearer photo."
                    AskAiState.Error.Network ->
                        "No connection — try again when you're online."
                }
                Text(
                    text     = errorText,
                    color    = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("ask_ai_error"),
                )
                if (state == AskAiState.Error.NotConfigured || state == AskAiState.Error.KeyExpired) {
                    Button(onClick = onNeedsAiConfig, modifier = Modifier.fillMaxWidth()) {
                        Text("Go to Settings → AI")
                    }
                }
                TextButton(onClick = onDiscard, modifier = Modifier.fillMaxWidth()) {
                    Text("Try again")
                }
            }

            else -> {
                OutlinedTextField(
                    value         = text,
                    onValueChange = onTextChange,
                    label         = { Text("What did you eat or drink?") },
                    placeholder   = { Text("e.g. tiramisu, restaurant portion, dry martini") },
                    singleLine    = false,
                    maxLines      = 4,
                    modifier      = Modifier
                        .fillMaxWidth()
                        .testTag("ask_ai_text_field"),
                )

                if (imageBitmap != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            bitmap             = imageBitmap,
                            contentDescription = "Selected photo",
                            modifier           = Modifier.size(80.dp),
                        )
                        TextButton(onClick = onClearImage) { Text("Remove") }
                    }
                } else {
                    OutlinedButton(onClick = onPickImage, modifier = Modifier.fillMaxWidth()) {
                        Text("Add a photo (optional)")
                    }
                }

                if (state == AskAiState.Loading) {
                    Row(
                        modifier              = Modifier
                            .fillMaxWidth()
                            .testTag("ask_ai_loading"),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.width(8.dp))
                        Text("Asking Claude…")
                    }
                } else {
                    Button(
                        onClick  = onEstimate,
                        enabled  = canEstimate,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ask_ai_estimate_button"),
                    ) {
                        Text("Get estimate")
                    }
                }
            }
        }
    }
}
