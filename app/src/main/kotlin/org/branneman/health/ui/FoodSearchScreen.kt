package org.branneman.health.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import org.branneman.health.db.entities.FoodItemEntity

@Composable
fun FoodSearchScreen(
    onItemSelected: (FoodItemEntity) -> Unit,
    onBack: () -> Unit,
    viewModel: FoodSearchViewModel = viewModel(),
) {
    val context = LocalContext.current
    val query        by viewModel.query.collectAsStateWithLifecycle()
    val results      by viewModel.results.collectAsStateWithLifecycle()
    val selectedItem by viewModel.selectedItem.collectAsStateWithLifecycle()
    val isOffline    by viewModel.isOffline.collectAsStateWithLifecycle()
    var showScanner  by remember { mutableStateOf(false) }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) showScanner = true }

    LaunchedEffect(Unit) { viewModel.resetSearch() }

    LaunchedEffect(selectedItem) {
        selectedItem?.let {
            onItemSelected(it)
            viewModel.consumeSelectedItem()
        }
    }

    FoodSearchContent(
        query           = query,
        results         = results,
        selectedItem    = selectedItem,
        isOffline       = isOffline,
        onQueryChange   = viewModel::onQueryChange,
        onSelectResult  = viewModel::selectResult,
        onBarcodeButton = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                showScanner = true
            } else {
                cameraPermission.launch(Manifest.permission.CAMERA)
            }
        },
        onManualCreate  = viewModel::createManual,
        onBack          = onBack,
    )

    if (showScanner) {
        BarcodeScannerOverlay(
            onBarcodeDetected = { barcode ->
                showScanner = false
                viewModel.onBarcodeScanned(barcode)
            },
            onDismiss = { showScanner = false },
        )
    }
}

@Composable
fun FoodSearchContent(
    query: String,
    results: List<FoodSearchResult>,
    selectedItem: FoodItemEntity?,
    isOffline: Boolean,
    onQueryChange: (String) -> Unit,
    onSelectResult: (FoodSearchResult) -> Unit,
    onBarcodeButton: () -> Unit,
    onManualCreate: (String, Double, Double?, Double?, Double?) -> Unit,
    onBack: () -> Unit,
) {
    var showManualForm by remember { mutableStateOf(false) }
    LaunchedEffect(query, results) {
        showManualForm = query.isNotBlank() && results.isEmpty()
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        TextButton(onClick = onBack) { Text("← Back") }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value         = query,
                onValueChange = onQueryChange,
                label         = { Text("Search food") },
                singleLine    = true,
                modifier      = Modifier.weight(1f).testTag("food_search_field"),
            )
            TextButton(
                onClick  = onBarcodeButton,
                modifier = Modifier.testTag("food_barcode_button"),
            ) { Text("Scan") }
        }
        if (isOffline) {
            Text(
                text     = "OFD search unavailable offline — showing personal catalog only",
                style    = MaterialTheme.typography.bodySmall,
                color    = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("food_offline_notice"),
            )
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(results, key = { it.entity.id }) { result ->
                ListItem(
                    headlineContent   = { Text(result.entity.name) },
                    supportingContent = { Text("${result.entity.kcalPer100g} kcal/100g") },
                    modifier          = Modifier
                        .testTag("food_result_${result.entity.id}")
                        .clickable { onSelectResult(result) },
                )
                HorizontalDivider()
            }
        }
        if (showManualForm) {
            ManualFoodForm(
                onSave   = onManualCreate,
                modifier = Modifier.testTag("food_no_results_form"),
            )
        }
    }
}

@Composable
private fun ManualFoodForm(
    onSave: (String, Double, Double?, Double?, Double?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember { mutableStateOf("") }
    var kcal by remember { mutableStateOf("") }

    val saveEnabled = name.isNotBlank() && (kcal.toDoubleOrNull() ?: 0.0) > 0.0

    Column(modifier = modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Not found — enter manually", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(value = name, onValueChange = { name = it },
            label = { Text("Name *") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("manual_name"))
        OutlinedTextField(value = kcal, onValueChange = { kcal = it },
            label = { Text("kcal/100g *") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true, modifier = Modifier.fillMaxWidth().testTag("manual_kcal"))
        Button(
            onClick  = { onSave(name.trim(), kcal.toDouble(), null, null, null) },
            enabled  = saveEnabled,
            modifier = Modifier.testTag("manual_save"),
        ) { Text("Add ingredient") }
    }
}

@Composable
private fun BarcodeScannerOverlay(
    onBarcodeDetected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = context as LifecycleOwner
    val scanned = remember { mutableStateOf(false) }
    val scanner = remember { BarcodeScanning.getClient() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val executor = ContextCompat.getMainExecutor(ctx)
                val future = ProcessCameraProvider.getInstance(ctx)
                future.addListener({
                    val cameraProvider = future.get()
                    val preview = Preview.Builder().build()
                        .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(executor) { proxy ->
                        val mediaImage = proxy.image
                        if (mediaImage != null && !scanned.value) {
                            val inputImage = InputImage.fromMediaImage(
                                mediaImage, proxy.imageInfo.rotationDegrees,
                            )
                            scanner.process(inputImage)
                                .addOnSuccessListener { barcodes ->
                                    barcodes.firstOrNull()?.rawValue?.let { barcode ->
                                        if (!scanned.value) {
                                            scanned.value = true
                                            onBarcodeDetected(barcode)
                                        }
                                    }
                                }
                                .addOnCompleteListener { proxy.close() }
                        } else {
                            proxy.close()
                        }
                    }
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }, executor)
                previewView
            },
            modifier = Modifier.fillMaxSize(),
        )
        TextButton(
            onClick  = onDismiss,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
        ) {
            Text("✕ Close", color = Color.White, style = MaterialTheme.typography.titleMedium)
        }
    }
}
