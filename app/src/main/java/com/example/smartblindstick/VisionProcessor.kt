package com.example.smartblindstick

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.sqrt

class VisionProcessor(private val context: Context) {

    // Options for Face Detection (ML Kit)
    private val faceDetectorOptions = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .build()
    private val faceDetector = FaceDetection.getClient(faceDetectorOptions)



    // Face Recognition (MobileFaceNet)
    private var faceInterpreter: Interpreter? = null
    private val knownFaces = mutableMapOf<String, FloatArray>()
    private val FACE_IMAGE_SIZE = 112
    private val EMBEDDING_SIZE = 192 // Standard for many MobileFaceNet models

    // Cooldown state for TTS
    private var lastFaceSpeakTime: Long = 0

    private val yoloDetector = YoloV8Detector(context)
    private val nvidiaApiClient = NvidiaApiClient()
    
    // Managers
    var spatialAudioManager: SpatialAudioManager? = null
    var visualMemoryManager: VisualMemoryManager? = null
    var microNavigationManager: MicroNavigationManager? = null
    var autoFlashlightManager: AutoFlashlightManager? = null
    
    var onDetectionsUpdated: ((List<Detection>, Int, Int) -> Unit)? = null
    
    private var isYoloProcessing = false
    private val scope = CoroutineScope(Dispatchers.Default)

    init {
        initializeFaceInterpreter()
        CoroutineScope(Dispatchers.IO).launch {
            loadKnownFaces()
        }
    }

    private fun initializeFaceInterpreter() {
        try {
            val assetFileDescriptor = context.assets.openFd("mobilefacenet.tflite")
            val fileInputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
            val fileChannel = fileInputStream.channel
            val startOffset = assetFileDescriptor.startOffset
            val declaredLength = assetFileDescriptor.declaredLength
            val mappedByteBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
            
            faceInterpreter = Interpreter(mappedByteBuffer)
            Log.d("VisionProcessor", "MobileFaceNet loaded successfully.")
        } catch (e: Exception) {
            Log.e("VisionProcessor", "Failed to load MobileFaceNet. Make sure mobilefacenet.tflite is in assets/", e)
        }
    }

    private fun loadKnownFaces() {
        processAssetDir("known_faces", null)
    }

    private fun processAssetDir(dirPath: String, personNameOverride: String?) {
        try {
            val fileNames = context.assets.list(dirPath) ?: return
            
            for (fileName in fileNames) {
                if (fileName == "README.txt") continue

                val assetPath = "$dirPath/$fileName"
                val subFiles = context.assets.list(assetPath)
                
                if (!subFiles.isNullOrEmpty()) {
                    // It's a directory, use its name as the person's name
                    processAssetDir(assetPath, fileName)
                } else {
                    // It's a file
                    val inputStream = try {
                        context.assets.open(assetPath)
                    } catch (e: Exception) {
                        null
                    }
                    if (inputStream == null) continue

                    val bitmap = BitmapFactory.decodeStream(inputStream) ?: continue
                    
                    val inputImage = InputImage.fromBitmap(bitmap, 0)
                    faceDetector.process(inputImage).addOnSuccessListener { faces ->
                        if (faces.isNotEmpty()) {
                            val face = faces[0]
                            val boundingBox = face.boundingBox
                            
                            val x = maxOf(0, boundingBox.left)
                            val y = maxOf(0, boundingBox.top)
                            val width = minOf(bitmap.width - x, boundingBox.width())
                            val height = minOf(bitmap.height - y, boundingBox.height())
                            
                            if (width > 0 && height > 0) {
                                val faceBitmap = Bitmap.createBitmap(bitmap, x, y, width, height)
                                val embedding = getFaceEmbedding(faceBitmap)
                                if (embedding != null) {
                                    val name = personNameOverride ?: fileName.substringBeforeLast(".")
                                    knownFaces[name] = embedding
                                    Log.d("VisionProcessor", "Registered known face: $name")
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("VisionProcessor", "Error loading known faces from $dirPath", e)
        }
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    fun processImageProxy(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            
            // Fix: Rotate the bitmap so the ML Kit bounding box aligns correctly!
            val rawBitmap = imageProxy.toBitmap()
            val luminance = calculateAverageLuminance(rawBitmap)
            autoFlashlightManager?.processLuminance(luminance)
            
            val matrix = Matrix().apply { postRotate(imageProxy.imageInfo.rotationDegrees.toFloat()) }
            val bitmap = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
            
            processFaces(image, bitmap)
            
            if (!isYoloProcessing) {
                isYoloProcessing = true
                scope.launch {
                    try {
                        // Run YOLOv8 detection in background
                        val detections = yoloDetector.detect(bitmap)
                        
                        // Update managers on main thread
                        withContext(Dispatchers.Main) {
                            spatialAudioManager?.processDetections(detections, bitmap.width, bitmap.height)
                            visualMemoryManager?.logSightings(detections, bitmap.width)
                            microNavigationManager?.processDetections(detections, bitmap.width, bitmap.height)
                            onDetectionsUpdated?.invoke(detections, bitmap.width, bitmap.height)
                        }
                    } catch (e: Exception) {
                        Log.e("VisionProcessor", "Async YOLO failed", e)
                    } finally {
                        isYoloProcessing = false
                    }
                }
            }
        }
        
        imageProxy.close()
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    fun processForNvidia(imageProxy: ImageProxy, prompt: String, onResponse: (String) -> Unit) {
        val rawBitmap = imageProxy.toBitmap()
        val matrix = Matrix().apply { postRotate(imageProxy.imageInfo.rotationDegrees.toFloat()) }
        val bitmap = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
        
        nvidiaApiClient.generateResponse(bitmap, prompt) { answer ->
            if (answer != null) {
                onResponse(answer)
            } else {
                onResponse("Sorry, I couldn't reach the Nvidia server.")
            }
        }
        imageProxy.close()
    }

    private fun processFaces(image: InputImage, bitmap: Bitmap) {
        faceDetector.process(image)
            .addOnSuccessListener { faces ->
                for (face in faces) {
                    val bounds = face.boundingBox
                    
                    // Crop the face
                    val x = maxOf(0, bounds.left)
                    val y = maxOf(0, bounds.top)
                    val width = minOf(bitmap.width - x, bounds.width())
                    val height = minOf(bitmap.height - y, bounds.height())
                    
                    if (width > 0 && height > 0) {
                        val faceBitmap = Bitmap.createBitmap(bitmap, x, y, width, height)
                        val embedding = getFaceEmbedding(faceBitmap)
                        
                        if (embedding != null) {
                            var bestMatch = "Unknown person"
                            var minDistance = Float.MAX_VALUE
                            
                            // Compare with known faces
                            for ((name, knownEmbedding) in knownFaces) {
                                val distance = calculateCosineDistance(embedding, knownEmbedding)
                                if (distance < minDistance) {
                                    minDistance = distance
                                    bestMatch = name
                                }
                            }
                            
                            // Threshold for face match (adjustable, MobileFaceNet usually ~ 0.5-0.7)
                            if (minDistance > 0.7f) {
                                bestMatch = "Unknown person"
                            }
                            
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastFaceSpeakTime > 5000) {
                                if (context is MainActivity && bestMatch != "Unknown person") {
                                    val pan = (x + width / 2.0f) / bitmap.width * 2f - 1f
                                    val hPos = when {
                                        pan < -0.33f -> "left"
                                        pan > 0.33f -> "right"
                                        else -> "center"
                                    }
                                    context.speak("$bestMatch is to your $hPos", pan)
                                }
                                lastFaceSpeakTime = currentTime
                            }
                        }
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e("VisionProcessor", "Face detection failed", e)
            }
    }

    private fun getFaceEmbedding(bitmap: Bitmap): FloatArray? {
        if (faceInterpreter == null) return null
        
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, FACE_IMAGE_SIZE, FACE_IMAGE_SIZE, true)
        val byteBuffer = ByteBuffer.allocateDirect(4 * FACE_IMAGE_SIZE * FACE_IMAGE_SIZE * 3)
        byteBuffer.order(ByteOrder.nativeOrder())
        
        val intValues = IntArray(FACE_IMAGE_SIZE * FACE_IMAGE_SIZE)
        resizedBitmap.getPixels(intValues, 0, resizedBitmap.width, 0, 0, resizedBitmap.width, resizedBitmap.height)
        
        // Preprocess image: normalize to [-1, 1] usually for MobileFaceNet
        var pixel = 0
        for (i in 0 until FACE_IMAGE_SIZE) {
            for (j in 0 until FACE_IMAGE_SIZE) {
                val value = intValues[pixel++]
                byteBuffer.putFloat(((value shr 16 and 0xFF) - 127.5f) / 128f)
                byteBuffer.putFloat(((value shr 8 and 0xFF) - 127.5f) / 128f)
                byteBuffer.putFloat(((value and 0xFF) - 127.5f) / 128f)
            }
        }
        
        val embeddings = Array(1) { FloatArray(EMBEDDING_SIZE) }
        try {
            faceInterpreter?.run(byteBuffer, embeddings)
            return embeddings[0]
        } catch (e: Exception) {
            Log.e("VisionProcessor", "Failed to extract embedding", e)
        }
        return null
    }

    private fun calculateCosineDistance(emb1: FloatArray, emb2: FloatArray): Float {
        var dotProduct = 0.0
        var norm1 = 0.0
        var norm2 = 0.0
        for (i in emb1.indices) {
            dotProduct += emb1[i] * emb2[i]
            norm1 += emb1[i] * emb1[i]
            norm2 += emb2[i] * emb2[i]
        }
        return (1.0 - (dotProduct / (sqrt(norm1) * sqrt(norm2)))).toFloat()
    }

    private fun calculateAverageLuminance(bitmap: Bitmap): Float {
        var sum = 0f
        var count = 0
        // Sample every 10th pixel to save CPU
        val step = 10
        for (x in 0 until bitmap.width step step) {
            for (y in 0 until bitmap.height step step) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val luma = 0.299f * r + 0.587f * g + 0.114f * b
                sum += luma
                count++
            }
        }
        return if (count > 0) sum / count else 255f
    }
}
