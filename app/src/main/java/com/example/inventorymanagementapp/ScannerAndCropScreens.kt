package com.example.inventorymanagementapp

import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.rememberUpdatedState
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors

@Composable
fun LiveScannerScreen(
    items: List<InventoryItem>,
    yoloHelper: YoloDetectorHelper,
    shapeHelper: ShapeRecognitionHelper,
    similarityThreshold: Double = 0.50,
    onAddCountToItem: (itemId: String, addedCount: Int) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    var searchQuery by remember { mutableStateOf("") }
    var selectedItemId by remember { mutableStateOf(items.firstOrNull()?.id) }
    val selectedItem = items.find { it.id == selectedItemId }
    val currentSelectedItem by rememberUpdatedState(selectedItem)


    var rawYoloBoxes by remember { mutableStateOf<List<DetectedBox>>(emptyList()) }
    var matchedBoxes by remember { mutableStateOf<List<DetectedBox>>(emptyList()) }
    var bestSimSeen by remember { mutableDoubleStateOf(0.0) }
    var showConfirmCountDialog by remember { mutableStateOf(false) }

    if (!hasCameraPermission) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Camera permission is required for live scanning.")
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Grant Permission")
                }
                TextButton(onClick = onBack) {
                    Text("Back")
                }
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    val executor = Executors.newSingleThreadExecutor()

                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()

                        analysis.setAnalyzer(executor) { imageProxy ->
                            val raw = imageProxy.toBitmap()
                            val rot = imageProxy.imageInfo.rotationDegrees.toFloat()
                            val rotated = if (rot != 0f) {
                                val m = Matrix().apply { postRotate(rot) }
                                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                            } else {
                                raw
                            }

                            if (!showConfirmCountDialog) {
                                val rawBoxes = yoloHelper.detect(rotated, confThreshold = 0.25f)
                                    .sortedByDescending { it.confidence }
                                    .take(40)

                                val target = currentSelectedItem
                                if (target != null && target.references.isNotEmpty()) {
                                    val filtered = mutableListOf<DetectedBox>()
                                    var topSim = 0.0
                                    for (box in rawBoxes) {
                                        val l = (box.rect.left * rotated.width).toInt().coerceIn(0, rotated.width - 1)
                                        val t = (box.rect.top * rotated.height).toInt().coerceIn(0, rotated.height - 1)
                                        val w = (box.rect.width() * rotated.width).toInt().coerceIn(1, rotated.width - l)
                                        val h = (box.rect.height() * rotated.height).toInt().coerceIn(1, rotated.height - t)
                                        val crop = Bitmap.createBitmap(rotated, l, t, w, h)
                                        val emb = shapeHelper.extractEmbedding(crop)
                                        crop.recycle()
                                        if (emb == null) continue

                                        val sim = shapeHelper.maxSimilarityToItem(emb, target)
                                        if (sim > topSim) topSim = sim
                                        if (sim >= similarityThreshold) {
                                            filtered.add(box.copy(similarity = sim))
                                        }
                                    }
                                    bestSimSeen = topSim
                                    matchedBoxes = filtered
                                } else {
                                    bestSimSeen = 0.0
                                    matchedBoxes = emptyList()
                                }
                            }
                            imageProxy.close()
                        }

                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis
                        )
                    }, ContextCompat.getMainExecutor(ctx))

                    previewView
                }
            )

            Canvas(modifier = Modifier.fillMaxSize()) {
                for (box in rawYoloBoxes) {
                    drawRect(
                        color = Color.Yellow,
                        topLeft = Offset(box.rect.left * size.width, box.rect.top * size.height),
                        size = Size(box.rect.width() * size.width, box.rect.height() * size.height),
                        style = Stroke(width = 3f)
                    )
                }
                for (box in matchedBoxes) {
                    drawRect(
                        color = Color.Green,
                        topLeft = Offset(box.rect.left * size.width, box.rect.top * size.height),
                        size = Size(box.rect.width() * size.width, box.rect.height() * size.height),
                        style = Stroke(width = 7f)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(onClick = onBack) {
                    Text("Back")
                }
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                ) {
                    val simPct = (bestSimSeen * 100).toInt()
                    Text(
                        text = "${yoloHelper.statusMessage} | Boxes:${rawYoloBoxes.size} | Sim:$simPct% | Match:${matchedBoxes.size}",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }


            if (selectedItem != null && matchedBoxes.isNotEmpty()) {
                Button(
                    onClick = { showConfirmCountDialog = true },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp)
                ) {
                    Text("✓ Record Count (${matchedBoxes.size})", fontWeight = FontWeight.Bold)
                }
            }
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(230.dp),
            tonalElevation = 4.dp
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search existing items to scan...") },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                val filtered = items.filter {
                    it.name.contains(searchQuery, ignoreCase = true) ||
                            it.category.contains(searchQuery, ignoreCase = true)
                }
                if (filtered.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No items found. Add an item & reference photo first.")
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(filtered, key = { it.id }) { item ->
                            val isSelected = item.id == selectedItemId
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedItemId = item.id },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    }
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .padding(10.dp)
                                        .fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(item.name, fontWeight = FontWeight.Bold)
                                    Text("Stock: ${item.quantity} | Refs: ${item.references.size}")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showConfirmCountDialog && selectedItem != null) {
        var editableCount by remember(showConfirmCountDialog) { mutableIntStateOf(matchedBoxes.size) }

        AlertDialog(
            onDismissRequest = { showConfirmCountDialog = false },
            title = { Text("Confirm Item Count") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Item: ${selectedItem.name}", fontWeight = FontWeight.Bold)
                    Text("Current Stock: ${selectedItem.quantity}")
                    Text("Detected Count (adjust if needed):")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { if (editableCount > 0) editableCount-- }) {
                            Text("-", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            text = "$editableCount",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                        TextButton(onClick = { editableCount++ }) {
                            Text("+", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onAddCountToItem(selectedItem.id, editableCount)
                        showConfirmCountDialog = false
                    }
                ) {
                    Text("OK (+$editableCount)")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmCountDialog = false }) {
                    Text("Scan Another")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReferencePhotosScreen(
    item: InventoryItem,
    shapeHelper: ShapeRecognitionHelper,
    onUpdateReferences: (List<ReferencePhoto>) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingCropBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var editingRefId by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val takePicLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        if (bitmap != null) {
            editingRefId = null
            pendingCropBitmap = bitmap
        }
    }

    val cropTarget = pendingCropBitmap
    if (cropTarget != null) {
        CropPhotoScreen(
            bitmap = cropTarget,
            onConfirmCrop = { croppedBitmap ->
                val emb = shapeHelper.extractEmbedding(croppedBitmap)
                if (emb != null) {
                    val path = LocalInventoryStorage.saveCroppedImage(context, croppedBitmap)
                    val targetEditId = editingRefId
                    val updatedList = if (targetEditId != null) {
                        item.references.map {
                            if (it.id == targetEditId) it.copy(imagePath = path, embedding = emb) else it
                        }
                    } else {
                        item.references + ReferencePhoto(imagePath = path, embedding = emb)
                    }
                    onUpdateReferences(updatedList)
                    selectedIds = emptySet()
                }
                pendingCropBitmap = null
                editingRefId = null
            },
            onBack = {
                pendingCropBitmap = null
                editingRefId = null
            }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${item.name} - Ref Photos", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                },
                actions = {
                    if (selectedIds.size == 1) {
                        TextButton(
                            onClick = {
                                val refId = selectedIds.first()
                                val ref = item.references.find { it.id == refId }
                                val bmp = ref?.let { LocalInventoryStorage.loadBitmap(it.imagePath) }
                                if (bmp != null) {
                                    editingRefId = refId
                                    pendingCropBitmap = bmp
                                }
                            }
                        ) {
                            Text("Crop", fontWeight = FontWeight.Bold)
                        }
                    }
                    if (selectedIds.isNotEmpty()) {
                        TextButton(onClick = { showDeleteConfirm = true }) {
                            Text("Delete (${selectedIds.size})", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { takePicLauncher.launch(null) },
                icon = { Text("+", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
                text = { Text("Add Reference Photo") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
        ) {
            Text(
                text = "Tap photos to select (1 photo = Crop or Delete, multiple = Delete).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (item.references.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No reference photos yet. Tap '+ Add Reference Photo' below.")
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    items(item.references, key = { it.id }) { ref ->
                        val isSelected = ref.id in selectedIds
                        val bmp = remember(ref.imagePath) { LocalInventoryStorage.loadBitmap(ref.imagePath) }

                        Card(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .border(
                                    width = if (isSelected) 4.dp else 0.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable {
                                    selectedIds = if (isSelected) selectedIds - ref.id else selectedIds + ref.id
                                }
                        ) {
                            if (bmp != null) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "Reference Photo",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("Image")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Reference Photos?") },
            text = { Text("Remove ${selectedIds.size} selected reference photo(s)?") },
            confirmButton = {
                Button(
                    onClick = {
                        onUpdateReferences(item.references.filterNot { it.id in selectedIds })
                        selectedIds = emptySet()
                        showDeleteConfirm = false
                    }
                ) {
                    Text("Yes, Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("No")
                }
            }
        )
    }
}

@Composable
fun CropPhotoScreen(
    bitmap: Bitmap,
    onConfirmCrop: (Bitmap) -> Unit,
    onBack: () -> Unit
) {
    var left by remember { mutableFloatStateOf(0.15f) }
    var top by remember { mutableFloatStateOf(0.15f) }
    var right by remember { mutableFloatStateOf(0.85f) }
    var bottom by remember { mutableFloatStateOf(0.85f) }
    var showConfirmDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "Crop Reference Object",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            val aspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
            Box(
                modifier = Modifier
                    .aspectRatio(aspectRatio)
                    .fillMaxSize()
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Captured Photo",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds
                )
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawRect(
                        color = Color.Green,
                        topLeft = Offset(left * size.width, top * size.height),
                        size = Size((right - left) * size.width, (bottom - top) * size.height),
                        style = Stroke(width = 6f)
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Left / Right Edges", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Slider(
                    value = left,
                    onValueChange = { if (it < right - 0.1f) left = it },
                    modifier = Modifier.weight(1f)
                )
                Slider(
                    value = right,
                    onValueChange = { if (it > left + 0.1f) right = it },
                    modifier = Modifier.weight(1f)
                )
            }
            Text("Top / Bottom Edges", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Slider(
                    value = top,
                    onValueChange = { if (it < bottom - 0.1f) top = it },
                    modifier = Modifier.weight(1f)
                )
                Slider(
                    value = bottom,
                    onValueChange = { if (it > top + 0.1f) bottom = it },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            OutlinedButton(onClick = onBack) {
                Text("Back")
            }
            Button(onClick = { showConfirmDialog = true }) {
                Text("✓ Check")
            }
        }
    }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text("Confirm Crop?") },
            text = { Text("Save this cropped region as the reference photo?") },
            confirmButton = {
                Button(
                    onClick = {
                        val pxX = (left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
                        val pxY = (top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
                        val pxW = ((right - left) * bitmap.width).toInt().coerceIn(1, bitmap.width - pxX)
                        val pxH = ((bottom - top) * bitmap.height).toInt().coerceIn(1, bitmap.height - pxY)
                        val cropped = Bitmap.createBitmap(bitmap, pxX, pxY, pxW, pxH)
                        showConfirmDialog = false
                        onConfirmCrop(cropped)
                    }
                ) {
                    Text("Confirm Crop")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}


