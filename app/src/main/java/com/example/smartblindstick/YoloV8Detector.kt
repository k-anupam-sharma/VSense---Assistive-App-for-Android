package com.example.smartblindstick

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

data class Detection(
    val label: String,
    val confidence: Float,
    val boundingBox: RectF,
    val centerX: Float,
    val centerY: Float,
    val distanceMeters: Float = -1f
)

class YoloV8Detector(private val context: Context) {
    private var interpreter: Interpreter? = null
    private var labels = listOf<String>()
    
    private val inputSize = 640 // standard yolov8 input
    private val numClasses = 80
    private val numElements = 4 + numClasses // x, y, w, h, p1...p80
    private val numAnchors = 8400 // typical for yolov8n 640x640

    // Pre-allocated buffers to prevent GC churn (saves ~15MB of garbage per frame)
    private val byteBuffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3).apply {
        order(ByteOrder.nativeOrder())
    }
    private val floatArray = FloatArray(inputSize * inputSize * 3)
    private val intValues = IntArray(inputSize * inputSize)
    private val outputBuffer = Array(1) { Array(numElements) { FloatArray(numAnchors) } }

    init {
        try {
            val tfliteModel = loadModelFile("yolov8n.tflite")
            val options = Interpreter.Options().apply {
                numThreads = 4
            }
            interpreter = Interpreter(tfliteModel, options)
            labels = loadLabels("coco_labels.txt")
            Log.d("YoloV8Detector", "YOLOv8 model loaded successfully.")
        } catch (e: Exception) {
            Log.e("YoloV8Detector", "Error loading YOLOv8 model", e)
        }
    }

    private fun loadModelFile(modelName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    private fun loadLabels(labelPath: String): List<String> {
        return context.assets.open(labelPath).bufferedReader().readLines()
    }

    fun detect(bitmap: Bitmap): List<Detection> {
        if (interpreter == null) return emptyList()

        // 1. Preprocess
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, false)
        val buffer = convertBitmapToByteBuffer(resizedBitmap)

        // 2. Run inference
        try {
            interpreter?.run(buffer, outputBuffer)
        } catch (e: Exception) {
            Log.e("YoloV8Detector", "Inference failed", e)
            return emptyList()
        }

        // 3. Postprocess
        val detections = mutableListOf<Detection>()
        val output = outputBuffer[0] // [84, 8400]
        
        for (i in 0 until numAnchors) {
            var maxClassScore = 0f
            var classIndex = -1
            
            for (c in 0 until numClasses) {
                val score = output[4 + c][i]
                if (score > maxClassScore) {
                    maxClassScore = score
                    classIndex = c
                }
            }

            if (maxClassScore > 0.3f) { // confidence threshold
                val cx = output[0][i]
                val cy = output[1][i]
                val w = output[2][i]
                val h = output[3][i]
                
                // Handle coordinates which may be absolute (0-640) or already normalized (0-1)
                val nx = if (cx > 2f) cx / inputSize else cx
                val ny = if (cy > 2f) cy / inputSize else cy
                val nw = if (w > 2f) w / inputSize else w
                val nh = if (h > 2f) h / inputSize else h
                
                // Calculate bounding box in normalized coordinates
                val left = nx - nw / 2
                val top = ny - nh / 2
                val right = nx + nw / 2
                val bottom = ny + nh / 2
                
                // Map back to original image dimensions
                val rectF = RectF(
                    left * bitmap.width,
                    top * bitmap.height,
                    right * bitmap.width,
                    bottom * bitmap.height
                )
                
                // Heuristic distance calculation based on normalized area
                val areaRatio = nw * nh
                val distance = 0.5f / maxOf(0.01f, Math.sqrt(areaRatio.toDouble()).toFloat())

                val label = if (classIndex in labels.indices) labels[classIndex] else "Unknown"
                
                detections.add(
                    Detection(
                        label = label,
                        confidence = maxClassScore,
                        boundingBox = rectF,
                        centerX = nx * bitmap.width,
                        centerY = ny * bitmap.height,
                        distanceMeters = distance
                    )
                )
            }
        }

        return applyNMS(detections)
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        byteBuffer.rewind()
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        
        var pixel = 0
        var floatIndex = 0
        val numPixels = inputSize * inputSize
        while (pixel < numPixels) {
            val value = intValues[pixel++]
            // Normalize to 0.0 - 1.0 for YOLOv8
            floatArray[floatIndex++] = ((value shr 16) and 0xFF) / 255.0f
            floatArray[floatIndex++] = ((value shr 8) and 0xFF) / 255.0f
            floatArray[floatIndex++] = (value and 0xFF) / 255.0f
        }
        
        // Bulk put using FloatBuffer view is astronomically faster than byteBuffer.putFloat in a loop
        byteBuffer.asFloatBuffer().put(floatArray)
        return byteBuffer
    }

    private fun applyNMS(detections: List<Detection>, iouThreshold: Float = 0.45f): List<Detection> {
        val sortedDetections = detections.sortedByDescending { it.confidence }
        val finalDetections = mutableListOf<Detection>()
        
        for (detection in sortedDetections) {
            var shouldAdd = true
            for (finalDetection in finalDetections) {
                if (detection.label == finalDetection.label) {
                    val iou = calculateIoU(detection.boundingBox, finalDetection.boundingBox)
                    if (iou > iouThreshold) {
                        shouldAdd = false
                        break
                    }
                }
            }
            if (shouldAdd) {
                finalDetections.add(detection)
            }
        }
        return finalDetections
    }

    private fun calculateIoU(box1: RectF, box2: RectF): Float {
        val intersectionLeft = maxOf(box1.left, box2.left)
        val intersectionTop = maxOf(box1.top, box2.top)
        val intersectionRight = minOf(box1.right, box2.right)
        val intersectionBottom = minOf(box1.bottom, box2.bottom)

        if (intersectionRight < intersectionLeft || intersectionBottom < intersectionTop) {
            return 0.0f
        }

        val intersectionArea = (intersectionRight - intersectionLeft) * (intersectionBottom - intersectionTop)
        val box1Area = box1.width() * box1.height()
        val box2Area = box2.width() * box2.height()
        
        return intersectionArea / (box1Area + box2Area - intersectionArea)
    }
}
