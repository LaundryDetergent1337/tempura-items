package com.example.inventorymanagementapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    InventoryAppScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryAppScreen() {
    val context = LocalContext.current
    val shapeHelper = remember { ShapeRecognitionHelper(context) }
    val yoloHelper = remember { YoloDetectorHelper(context) }

    DisposableEffect(Unit) {
        onDispose {
            shapeHelper.close()
            yoloHelper.close()
        }
    }

    var items by remember { mutableStateOf(LocalInventoryStorage.loadItems(context)) }
    var searchQuery by remember { mutableStateOf("") }
    var lastExportedUri by remember { mutableStateOf<Uri?>(null) }

    // Flowchart Screen & Dialog States
    var showLiveScanner by remember { mutableStateOf(false) }
    var showAddNewItemDialog by remember { mutableStateOf(false) }
    var editingItemId by remember { mutableStateOf<String?>(null) }
    var refPhotosItemId by remember { mutableStateOf<String?>(null) }
    var itemToDelete by remember { mutableStateOf<InventoryItem?>(null) }

    fun updateAndSave(newList: List<InventoryItem>) {
        items = newList
        LocalInventoryStorage.saveItems(context, newList)
    }

    fun changeQuantity(itemId: String, delta: Int) {
        val updated = items.map { item ->
            if (item.id == itemId) {
                val newQty = (item.quantity + delta).coerceAtLeast(0)
                item.copy(quantity = newQty, lastUpdated = System.currentTimeMillis())
            } else {
                item
            }
        }
        updateAndSave(updated)
    }

    val excelLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/vnd.ms-excel")
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(buildExcelXml(items).toByteArray(Charsets.UTF_8))
                }
                lastExportedUri = uri
                Toast.makeText(context, "Excel file saved!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Route 1: Live Video Feed Screen (Top Wing of Flowchart)
    if (showLiveScanner) {
        LiveScannerScreen(
            items = items,
            yoloHelper = yoloHelper,
            shapeHelper = shapeHelper,
            similarityThreshold = 0.50,
            onAddCountToItem = { itemId, added ->
                changeQuantity(itemId, added)
                Toast.makeText(context, "Added +$added to inventory!", Toast.LENGTH_SHORT).show()
            },
            onBack = { showLiveScanner = false }
        )
        return
    }

    // Route 2: Reference Photos Gallery & Crop Screen (Bottom-Right Wing)
    val refTarget = items.find { it.id == refPhotosItemId }
    if (refTarget != null) {
        ReferencePhotosScreen(
            item = refTarget,
            shapeHelper = shapeHelper,
            onUpdateReferences = { updatedRefs ->
                updateAndSave(
                    items.map {
                        if (it.id == refTarget.id) {
                            it.copy(references = updatedRefs, lastUpdated = System.currentTimeMillis())
                        } else {
                            it
                        }
                    }
                )
            },
            onBack = { refPhotosItemId = null }
        )
        return
    }

    // Route 3: Edit Item Details Page (Bottom Wing)
    val editTarget = items.find { it.id == editingItemId }
    if (editTarget != null) {
        EditItemDetailsScreen(
            item = editTarget,
            onSaveDetails = { newName, newCategory, newQty ->
                updateAndSave(
                    items.map {
                        if (it.id == editTarget.id) {
                            it.copy(
                                name = newName,
                                category = newCategory,
                                quantity = newQty,
                                lastUpdated = System.currentTimeMillis()
                            )
                        } else {
                            it
                        }
                    }
                )
                editingItemId = null
                Toast.makeText(context, "Item updated!", Toast.LENGTH_SHORT).show()
            },
            onOpenReferencePhotos = { refPhotosItemId = editTarget.id },
            onBack = { editingItemId = null }
        )
        return
    }

    val filteredItems = items.filter {
        it.name.contains(searchQuery, ignoreCase = true) ||
                it.category.contains(searchQuery, ignoreCase = true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Shape Inventory", fontWeight = FontWeight.Bold) },
                actions = {
                    TextButton(
                        onClick = {
                            if (items.isEmpty()) {
                                Toast.makeText(context, "Inventory is empty!", Toast.LENGTH_SHORT).show()
                            } else {
                                val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                                excelLauncher.launch("Inventory_Report_$dateStr.xls")
                            }
                        }
                    ) {
                        Text("Export Excel", fontWeight = FontWeight.Bold)
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { showLiveScanner = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Scan Existing Items", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = { showAddNewItemDialog = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("+ Add Item", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .fillMaxSize()
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search items or categories...") },
                leadingIcon = { Text("🔍") },
                singleLine = true
            )

            if (lastExportedUri != null) {
                Spacer(modifier = Modifier.height(8.dp))
                AssistChip(
                    onClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/vnd.ms-excel"
                            putExtra(Intent.EXTRA_STREAM, lastExportedUri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share Excel Report"))
                    },
                    label = { Text("Share Last Exported Excel File") }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (filteredItems.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (items.isEmpty()) {
                            "Tap '+ Add Item' below to create your first item and add reference photos."
                        } else {
                            "No matching items found."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(filteredItems, key = { it.id }) { item ->
                        InventoryCard(
                            item = item,
                            onCardClick = { editingItemId = item.id },
                            onIncrement = { changeQuantity(item.id, +1) },
                            onDecrement = { changeQuantity(item.id, -1) },
                            onDeleteRequest = { itemToDelete = item }
                        )
                    }
                }
            }
        }
    }

    val deleteTarget = itemToDelete
    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            title = { Text("Delete Item?") },
            text = { Text("Are you sure you want to delete '${deleteTarget.name}'?") },
            confirmButton = {
                Button(
                    onClick = {
                        updateAndSave(items.filterNot { it.id == deleteTarget.id })
                        itemToDelete = null
                        Toast.makeText(context, "Item deleted.", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Yes")
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToDelete = null }) {
                    Text("No")
                }
            }
        )
    }

    if (showAddNewItemDialog) {
        var name by remember { mutableStateOf("") }
        var category by remember { mutableStateOf("") }
        var qtyText by remember { mutableStateOf("0") }

        AlertDialog(
            onDismissRequest = { showAddNewItemDialog = false },
            title = { Text("Create New Item") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Item Name") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = category,
                        onValueChange = { category = it },
                        label = { Text("Category") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = qtyText,
                        onValueChange = { qtyText = it },
                        label = { Text("Initial Quantity") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = name.isNotBlank(),
                    onClick = {
                        val newItem = InventoryItem(
                            name = name.trim(),
                            category = category.trim().ifBlank { "General" },
                            quantity = qtyText.toIntOrNull() ?: 0
                        )
                        updateAndSave(listOf(newItem) + items)
                        showAddNewItemDialog = false
                        refPhotosItemId = newItem.id
                    }
                ) {
                    Text("Next: Add Ref Photo")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddNewItemDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditItemDetailsScreen(
    item: InventoryItem,
    onSaveDetails: (name: String, category: String, quantity: Int) -> Unit,
    onOpenReferencePhotos: () -> Unit,
    onBack: () -> Unit
) {
    var name by remember { mutableStateOf(item.name) }
    var category by remember { mutableStateOf(item.category) }
    var qtyText by remember { mutableStateOf(item.quantity.toString()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Item Details", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Item Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = category,
                onValueChange = { category = it },
                label = { Text("Category") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = qtyText,
                onValueChange = { qtyText = it },
                label = { Text("Quantity") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedButton(
                onClick = onOpenReferencePhotos,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Manage Reference Photos (${item.references.size} Saved)")
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                enabled = name.isNotBlank(),
                onClick = {
                    onSaveDetails(
                        name.trim(),
                        category.trim().ifBlank { "General" },
                        qtyText.toIntOrNull() ?: item.quantity
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Changes", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun InventoryCard(
    item: InventoryItem,
    onCardClick: () -> Unit,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onDeleteRequest: () -> Unit
) {
    val sdf = remember { SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCardClick() }
    ) {
        Row(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Category: ${item.category} • Refs: ${item.references.size}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Updated: ${sdf.format(Date(item.lastUpdated))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDecrement) {
                    Text("-", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                Text(
                    text = "${item.quantity}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                TextButton(onClick = onIncrement) {
                    Text("+", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = onDeleteRequest) {
                    Text("✕", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}