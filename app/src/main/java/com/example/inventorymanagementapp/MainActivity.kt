package com.example.inventorymanagementapp

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class InventoryItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val category: String,
    val quantity: Int,
    val embedding: FloatArray,
    val lastUpdated: Long = System.currentTimeMillis()
)

object LocalInventoryStorage {
    private const val FILE_NAME = "inventory_items.json"

    fun loadItems(context: Context): List<InventoryItem> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        return try {
            val jsonArray = JSONArray(file.readText())
            val list = mutableListOf<InventoryItem>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val embArray = obj.getJSONArray("embedding")
                val floatArray = FloatArray(embArray.length()) { idx ->
                    embArray.getDouble(idx).toFloat()
                }
                list.add(
                    InventoryItem(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        category = obj.getString("category"),
                        quantity = obj.getInt("quantity"),
                        embedding = floatArray,
                        lastUpdated = obj.getLong("lastUpdated")
                    )
                )
            }
            list
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun saveItems(context: Context, items: List<InventoryItem>) {
        val jsonArray = JSONArray()
        for (item in items) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("category", item.category)
                put("quantity", item.quantity)
                put("lastUpdated", item.lastUpdated)
                val embArray = JSONArray()
                item.embedding.forEach { embArray.put(it.toDouble()) }
                put("embedding", embArray)
            }
            jsonArray.put(obj)
        }
        File(context.filesDir, FILE_NAME).writeText(jsonArray.toString())
    }
}
class ShapeRecognitionHelper(context: Context) {
    private var embedder: ImageEmbedder? = null

    init {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("mobilenet_v3_small.tflite")
                .build()
            val options = ImageEmbedder.ImageEmbedderOptions.builder()
                .setBaseOptions(baseOptions)
                .setL2Normalize(true)
                .setRunningMode(RunningMode.IMAGE)
                .build()
            embedder = ImageEmbedder.createFromOptions(context, options)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun extractEmbedding(bitmap: Bitmap): FloatArray? {
        val safeBitmap = if (bitmap.config != Bitmap.Config.ARGB_8888) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }
        val mpImage = BitmapImageBuilder(safeBitmap).build()
        val result = embedder?.embed(mpImage) ?: return null
        return result.embeddingResult().embeddings().firstOrNull()?.floatEmbedding()
    }

    fun findBestMatch(
        newEmbedding: FloatArray,
        existingItems: List<InventoryItem>,
        threshold: Double = 0.72
    ): Pair<InventoryItem, Double>? {
        var bestItem: InventoryItem? = null
        var bestScore = -1.0

        for (item in existingItems) {
            if (item.embedding.size == newEmbedding.size) {
                var dot = 0.0
                for (i in newEmbedding.indices) {
                    dot += newEmbedding[i] * item.embedding[i]
                }
                if (dot > bestScore) {
                    bestScore = dot
                    bestItem = item
                }
            }
        }
        return if (bestItem != null && bestScore >= threshold) {
            Pair(bestItem, bestScore)
        } else {
            null
        }
    }

    fun close() {
        embedder?.close()
    }
}

fun buildExcelXml(items: List<InventoryItem>): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    val sb = StringBuilder()
    sb.append("<?xml version=\"1.0\"?>")
    sb.append("<?mso-application progid=\"Excel.Sheet\"?>")
    sb.append("<Workbook xmlns=\"urn:schemas-microsoft-com:office:spreadsheet\" xmlns:ss=\"urn:schemas-microsoft-com:office:spreadsheet\">")
    sb.append("<Styles><Style ss:ID=\"Header\"><Font ss:Bold=\"1\"/></Style></Styles>")
    sb.append("<Worksheet ss:Name=\"Inventory\"><Table>")

    sb.append("<Row>")
    listOf("No.", "Item Name", "Category", "Quantity", "Last Updated").forEach { header ->
        sb.append("<Cell ss:StyleID=\"Header\"><Data ss:Type=\"String\">$header</Data></Cell>")
    }
    sb.append("</Row>")

    items.forEachIndexed { index, item ->
        sb.append("<Row>")
        sb.append("<Cell><Data ss:Type=\"Number\">${index + 1}</Data></Cell>")
        sb.append("<Cell><Data ss:Type=\"String\">${esc(item.name)}</Data></Cell>")
        sb.append("<Cell><Data ss:Type=\"String\">${esc(item.category)}</Data></Cell>")
        sb.append("<Cell><Data ss:Type=\"Number\">${item.quantity}</Data></Cell>")
        sb.append("<Cell><Data ss:Type=\"String\">${sdf.format(Date(item.lastUpdated))}</Data></Cell>")
        sb.append("</Row>")
    }

    sb.append("</Table></Worksheet></Workbook>")
    return sb.toString()
}

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

    DisposableEffect(Unit) {
        onDispose { shapeHelper.close() }
    }

    var items by remember { mutableStateOf(LocalInventoryStorage.loadItems(context)) }
    var searchQuery by remember { mutableStateOf("") }
    var pendingNewEmbedding by remember { mutableStateOf<FloatArray?>(null) }
    var matchedItemPair by remember { mutableStateOf<Pair<InventoryItem, Double>?>(null) }
    var lastExportedUri by remember { mutableStateOf<Uri?>(null) }

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

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        if (bitmap != null) {
            val embedding = shapeHelper.extractEmbedding(bitmap)
            if (embedding == null) {
                Toast.makeText(
                    context,
                    "Model missing! Put mobilenet_v3_small.tflite in app/src/main/assets/",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                val match = shapeHelper.findBestMatch(embedding, items, threshold = 0.72)
                matchedItemPair = match
                pendingNewEmbedding = embedding
            }
        }
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
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { cameraLauncher.launch(null) },
                icon = { Text("+", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
                text = { Text("Scan Object") }
            )
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
                            "Tap 'Scan Object' to register your first item."
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
                    contentPadding = PaddingValues(bottom = 88.dp)
                ) {
                    items(filteredItems, key = { it.id }) { item ->
                        InventoryCard(
                            item = item,
                            onIncrement = { changeQuantity(item.id, +1) },
                            onDecrement = { changeQuantity(item.id, -1) },
                            onDelete = { updateAndSave(items.filterNot { it.id == item.id }) }
                        )
                    }
                }
            }
        }
    }

    val currentMatch = matchedItemPair
    if (currentMatch != null) {
        val (matchedItem, score) = currentMatch
        val percent = (score * 100).toInt()
        AlertDialog(
            onDismissRequest = {
                matchedItemPair = null
                pendingNewEmbedding = null
            },
            title = { Text("Object Identified ($percent% Match)") },
            text = {
                Column {
                    Text("Name: ${matchedItem.name}", fontWeight = FontWeight.Bold)
                    Text("Category: ${matchedItem.category}")
                    Text("Current Stock: ${matchedItem.quantity}")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Add +1 to this item's stock?")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        changeQuantity(matchedItem.id, +1)
                        matchedItemPair = null
                        pendingNewEmbedding = null
                        Toast.makeText(context, "+1 added to ${matchedItem.name}", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Confirm (+1)")
                }
            },
            dismissButton = {
                TextButton(onClick = { matchedItemPair = null }) {
                    Text("Register as New Item")
                }
            }
        )
    }

    val newEmb = pendingNewEmbedding
    if (newEmb != null && matchedItemPair == null) {
        var name by remember { mutableStateOf("") }
        var category by remember { mutableStateOf("") }
        var qtyText by remember { mutableStateOf("1") }

        AlertDialog(
            onDismissRequest = { pendingNewEmbedding = null },
            title = { Text("Register New Object Shape") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("New shape detected! Fill out its details so the app recognizes it next time:")
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
                        val qty = qtyText.toIntOrNull() ?: 1
                        val newItem = InventoryItem(
                            name = name.trim(),
                            category = category.trim().ifBlank { "General" },
                            quantity = qty,
                            embedding = newEmb
                        )
                        updateAndSave(listOf(newItem) + items)
                        pendingNewEmbedding = null
                        Toast.makeText(context, "Saved ${newItem.name}!", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Save Item")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingNewEmbedding = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun InventoryCard(
    item: InventoryItem,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onDelete: () -> Unit
) {
    val sdf = remember { SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault()) }

    Card(modifier = Modifier.fillMaxWidth()) {
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
                    text = "Category: ${item.category}",
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
                IconButton(onClick = onDelete) {
                    Text("✕", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}