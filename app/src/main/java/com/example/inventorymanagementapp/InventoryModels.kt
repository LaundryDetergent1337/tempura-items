package com.example.inventorymanagementapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder
import org.json.JSONArray
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

data class ReferencePhoto(
    val id: String = UUID.randomUUID().toString(),
    val imagePath: String,
    val embedding: FloatArray
)

data class InventoryItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val category: String,
    val quantity: Int,
    val references: List<ReferencePhoto> = emptyList(),
    val lastUpdated: Long = System.currentTimeMillis()
)

data class DetectedBox(
    val rect: RectF,
    val confidence: Float,
    val similarity: Double = 0.0
)

object LocalInventoryStorage {
    private const val FILE_NAME = "inventory_v2.json"

    fun saveCroppedImage(context: Context, bitmap: Bitmap): String {
        val dir = File(context.filesDir, "ref_photos").apply { if (!exists()) mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        return file.absolutePath
    }

    fun loadBitmap(path: String): Bitmap? {
        val file = File(path)
        return if (file.exists()) BitmapFactory.decodeFile(path) else null
    }

    fun loadItems(context: Context): List<InventoryItem> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        return try {
            val jsonArray = JSONArray(file.readText())
            val list = mutableListOf<InventoryItem>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val refsJson = obj.optJSONArray("references") ?: JSONArray()
                val refs = mutableListOf<ReferencePhoto>()
                for (r in 0 until refsJson.length()) {
                    val rObj = refsJson.getJSONObject(r)
                    val embArray = rObj.getJSONArray("embedding")
                    val floats = FloatArray(embArray.length()) { idx ->
                        embArray.getDouble(idx).toFloat()
                    }
                    refs.add(
                        ReferencePhoto(
                            id = rObj.getString("id"),
                            imagePath = rObj.getString("imagePath"),
                            embedding = floats
                        )
                    )
                }
                list.add(
                    InventoryItem(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        category = obj.getString("category"),
                        quantity = obj.getInt("quantity"),
                        references = refs,
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
            val refsJson = JSONArray()
            for (ref in item.references) {
                val embArray = JSONArray()
                ref.embedding.forEach { embArray.put(it.toDouble()) }
                refsJson.put(
                    JSONObject().apply {
                        put("id", ref.id)
                        put("imagePath", ref.imagePath)
                        put("embedding", embArray)
                    }
                )
            }
            jsonArray.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("name", item.name)
                    put("category", item.category)
                    put("quantity", item.quantity)
                    put("lastUpdated", item.lastUpdated)
                    put("references", refsJson)
                }
            )
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

    fun maxSimilarityToItem(candidate: FloatArray, item: InventoryItem): Double {
        var best = -1.0
        for (ref in item.references) {
            if (ref.embedding.size == candidate.size) {
                var dot = 0.0
                for (i in candidate.indices) {
                    dot += candidate[i] * ref.embedding[i]
                }
                if (dot > best) best = dot
            }
        }
        return best
    }

    fun close() {
        embedder?.close()
    }
}

class YoloDetectorHelper(context: Context, modelName: String = "sku110k_yolo.tflite") {
    private var interpreter: Interpreter? = null
    private val inputSize = 640
    private var reusableOutput: Array<Array<FloatArray>>? = null

    // Add these 3 reusable fields inside YoloDetectorHelper right below inputSize = 640
    private val totalPixels = inputSize * inputSize
    private val reusableBuffer = ByteBuffer.allocateDirect(4 * 3 * totalPixels).apply {
        order(ByteOrder.nativeOrder())
    }
    private val reusablePixels = IntArray(totalPixels)
    var statusMessage: String = "Loading..."

    init {
        try {
            val afd = context.assets.openFd(modelName)
            val modelBuffer = afd.createInputStream().channel.map(
                java.nio.channels.FileChannel.MapMode.READ_ONLY,
                afd.startOffset,
                afd.declaredLength
            )
            val options = Interpreter.Options().apply { setNumThreads(4) }
            val interp = Interpreter(modelBuffer, options)
            interpreter = interp
            val inShape = interp.getInputTensor(0).shape().joinToString("x")
            val outShape = interp.getOutputTensor(0).shape().joinToString("x")
            statusMessage = "OK ($inShape -> $outShape)"
        } catch (e: Exception) {
            statusMessage = "ERR: ${e.message}"
            e.printStackTrace()
        }
    }

    fun detect(bitmap: Bitmap, confThreshold: Float = 0.25f, iouThreshold: Float = 0.45f): List<DetectedBox> {
        val interp = interpreter ?: return emptyList()
        return try {
            val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
            val inputBuffer = bitmapToByteBuffer(resized)

            val outShape = interp.getOutputTensor(0).shape()
            val dim1 = outShape.get(1)
            val dim2 = outShape.get(2)
            val candidates = mutableListOf<DetectedBox>()

            val output = reusableOutput ?: Array(1) { Array(dim1) { FloatArray(dim2) } }.also {
                reusableOutput = it
            }

            if (dim1 < dim2) {

                interp.run(inputBuffer, output)
                for (i in 0 until dim2) {
                    var maxConf = 0f
                    for (c in 4 until dim1) {
                        if (output[0][c][i] > maxConf) maxConf = output[0][c][i]
                    }
                    if (maxConf >= confThreshold) {
                        val cx = output[0][0][i]
                        val cy = output[0][1][i]
                        val w = output[0][2][i]
                        val h = output[0][3][i]
                        val scale = if (cx > 1.5f || w > 1.5f) inputSize.toFloat() else 1f
                        val left = ((cx - w / 2f) / scale).coerceIn(0f, 1f)
                        val top = ((cy - h / 2f) / scale).coerceIn(0f, 1f)
                        val right = ((cx + w / 2f) / scale).coerceIn(0f, 1f)
                        val bottom = ((cy + h / 2f) / scale).coerceIn(0f, 1f)
                        if (right > left && bottom > top) {
                            candidates.add(DetectedBox(RectF(left, top, right, bottom), maxConf))
                        }
                    }
                }
            } else {
                interp.run(inputBuffer, output)
                for (i in 0 until dim1) {
                    var maxConf = 0f
                    for (c in 4 until dim2) {
                        if (output[0][i][c] > maxConf) maxConf = output[0][i][c]
                    }
                    if (maxConf >= confThreshold) {
                        val cx = output[0][i][0]
                        val cy = output[0][i][1]
                        val w = output[0][i][2]
                        val h = output[0][i][3]
                        val scale = if (cx > 1.5f || w > 1.5f) inputSize.toFloat() else 1f
                        val left = ((cx - w / 2f) / scale).coerceIn(0f, 1f)
                        val top = ((cy - h / 2f) / scale).coerceIn(0f, 1f)
                        val right = ((cx + w / 2f) / scale).coerceIn(0f, 1f)
                        val bottom = ((cy + h / 2f) / scale).coerceIn(0f, 1f)
                        if (right > left && bottom > top) {
                            candidates.add(DetectedBox(RectF(left, top, right, bottom), maxConf))
                        }
                    }
                }
            }
            applyNms(candidates, iouThreshold)
        } catch (e: Exception) {
            statusMessage = "RUN ERR: ${e.message}"
            emptyList()
        }
    }

    private fun bitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        reusableBuffer.rewind()
        bitmap.getPixels(reusablePixels, 0, inputSize, 0, 0, inputSize, inputSize)
        val inv255 = 1f / 255f
        for (i in 0 until totalPixels) {
            reusableBuffer.putFloat(((reusablePixels[i] shr 16) and 0xFF) * inv255)
        }
        for (i in 0 until totalPixels) {
            reusableBuffer.putFloat(((reusablePixels[i] shr 8) and 0xFF) * inv255)
        }
        for (i in 0 until totalPixels) {
            reusableBuffer.putFloat((reusablePixels[i] and 0xFF) * inv255)
        }
        reusableBuffer.rewind()
        return reusableBuffer
    }

    private fun applyNms(boxes: List<DetectedBox>, iouThreshold: Float): List<DetectedBox> {
        val sorted = boxes.sortedByDescending { it.confidence }.toMutableList()
        val selected = mutableListOf<DetectedBox>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            selected.add(best)
            sorted.removeAll { iou(best.rect, it.rect) > iouThreshold }
        }
        return selected
    }

    private fun iou(a: RectF, b: RectF): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)
        val interArea = max(0f, interRight - interLeft) * max(0f, interBottom - interTop)
        val unionArea = (a.width() * a.height()) + (b.width() * b.height()) - interArea
        return if (unionArea <= 0f) 0f else interArea / unionArea
    }

    fun close() {
        interpreter?.close()
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
    listOf("No.", "Item Name", "Category", "Quantity", "Reference Photos", "Last Updated").forEach { header ->
        sb.append("<Cell ss:StyleID=\"Header\"><Data ss:Type=\"String\">$header</Data></Cell>")
    }
    sb.append("</Row>")

    items.forEachIndexed { index, item ->
        sb.append("<Row>")
        sb.append("<Cell><Data ss:Type=\"Number\">${index + 1}</Data></Cell>")
        sb.append("<Cell><Data ss:Type=\"String\">${esc(item.name)}</Data></Cell>")
        sb.append("<Cell><Data ss:Type=\"String\">${esc(item.category)}</Data></Cell>")
        sb.append("<Cell><Data ss:Type=\"Number\">${item.quantity}</Data></Cell>")
        sb.append("<Cell><Data ss:Type=\"Number\">${item.references.size}</Data></Cell>")
        sb.append("<Cell><Data ss:Type=\"String\">${sdf.format(Date(item.lastUpdated))}</Data></Cell>")
        sb.append("</Row>")
    }

    sb.append("</Table></Worksheet></Workbook>")
    return sb.toString()
}
