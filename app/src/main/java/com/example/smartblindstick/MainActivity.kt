package com.example.smartblindstick

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Camera
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.smartblindstick.ui.theme.SmartBlindStickTheme
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var tts: TextToSpeech
    private lateinit var compassManager: CompassManager
    
    private lateinit var fallDetectionManager: FallDetectionManager
    private lateinit var navigationManager: NavigationManager
    private lateinit var visionProcessor: VisionProcessor
    private lateinit var voiceAssistantManager: VoiceAssistantManager
    private lateinit var ocrProcessor: OcrProcessor
    
    // New feature managers
    private lateinit var spatialAudioManager: SpatialAudioManager
    private lateinit var visualMemoryManager: VisualMemoryManager
    private lateinit var microNavigationManager: MicroNavigationManager
    private lateinit var autoFlashlightManager: AutoFlashlightManager
    
    @Volatile
    private var isOcrRequested = false
    private var ocrRequestTime = 0L
    @Volatile
    private var isNvidiaRequested = false
    @Volatile
    private var nvidiaPrompt = ""

    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.VIBRATE
    )

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        var allGranted = true
        permissions.entries.forEach {
            if (!it.value) allGranted = false
        }
        if (allGranted) {
            fallDetectionManager.startListening()
        } else {
            Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        tts = TextToSpeech(this, this)
        fallDetectionManager = FallDetectionManager(this)
        navigationManager = NavigationManager(this)
        visionProcessor = VisionProcessor(this)
        ocrProcessor = OcrProcessor(this)
        
        compassManager = CompassManager(this)
        spatialAudioManager = SpatialAudioManager { msg, pan, vol, pitch -> speak(msg, pan, vol, pitch, false) }
        visualMemoryManager = VisualMemoryManager(this) { msg, pan, vol, pitch -> speak(msg, pan, vol, pitch, true) }
        visualMemoryManager.compassManager = compassManager
        microNavigationManager = MicroNavigationManager(this) { msg -> speak(msg, isHighPriority = false) }
        autoFlashlightManager = AutoFlashlightManager(this) { msg -> speak(msg, isHighPriority = false) }
        
        visionProcessor.spatialAudioManager = spatialAudioManager
        visionProcessor.visualMemoryManager = visualMemoryManager
        visionProcessor.microNavigationManager = microNavigationManager
        visionProcessor.autoFlashlightManager = autoFlashlightManager
        
        autoFlashlightManager.startMonitoring()
        compassManager.start()
        
        voiceAssistantManager = VoiceAssistantManager(this) { command ->
            runOnUiThread {
                handleVoiceCommand(command)
            }
        }
        
        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            fallDetectionManager.startListening()
        } else {
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }

        setContent {
            SmartBlindStickTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        cameraExecutor = cameraExecutor,
                        visionProcessor = visionProcessor,
                        onAnalyzeImage = { imageProxy ->
                            if (isOcrRequested) {
                                ocrProcessor.processImageProxy(imageProxy) { detectedText ->
                                    isOcrRequested = false
                                    speak("I read: $detectedText", isHighPriority = true)
                                }
                                if (System.currentTimeMillis() - ocrRequestTime > 5000 && isOcrRequested) {
                                    isOcrRequested = false
                                    speak("I couldn't find any clear text.", isHighPriority = true)
                                }
                            } else if (isNvidiaRequested) {
                                isNvidiaRequested = false
                                val currentPrompt = nvidiaPrompt
                                visionProcessor.processForNvidia(imageProxy, currentPrompt) { answer ->
                                    speak(answer, isHighPriority = true)
                                }
                            } else {
                                visionProcessor.processImageProxy(imageProxy)
                            }
                        },
                        onCameraBound = { camera ->
                            autoFlashlightManager.setCamera(camera)
                        },
                        onMicClick = {
                            tts.stop()
                            voiceAssistantManager.startListening()
                        }
                    )
                }
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTS", "Language not supported")
            } else {
                speak("Smart Blind Stick system initialized")
                // Do not auto-start listening anymore
            }
        }
    }

    private fun handleVoiceCommand(command: String) {
        when {
            command.contains("read") -> {
                isOcrRequested = true
                ocrRequestTime = System.currentTimeMillis()
                speak("Let me read that for you.", isHighPriority = true)
            }
            command.contains("stop") -> {
                tts.stop()
                speak("Stopped.", isHighPriority = true)
            }
            command.contains("turn on flashlight") || command.contains("turn flashlight on") || command.contains("torch on") -> {
                autoFlashlightManager.toggleTorch(true)
            }
            command.contains("turn off flashlight") || command.contains("turn flashlight off") || command.contains("torch off") -> {
                autoFlashlightManager.toggleTorch(false)
            }
            command.contains("auto flashlight") || command.contains("automatic flashlight") -> {
                autoFlashlightManager.resetToAuto()
            }
            command.contains("ask nvidia") || command.contains("nvidia") || command.contains("describe") -> {
                val prompt = command.replace("ask nvidia", "").replace("nvidia", "").trim()
                nvidiaPrompt = if (prompt.isEmpty()) "Describe what you see in front of me." else prompt
                isNvidiaRequested = true
                speak("Asking Nvidia...", isHighPriority = true)
            }
            command.contains("go to") || command.contains("navigate to") -> {
                val destination = command.replace("go to", "").replace("navigate to", "").trim()
                if (destination.isNotEmpty()) {
                    speak("Starting navigation to $destination", isHighPriority = true)
                    navigationManager.startNavigation(destination)
                } else {
                    speak("Please specify a destination.", isHighPriority = true)
                }
            }
            command.contains("switch to spatial") || command.contains("spacial") || command.contains("special") -> {
                spatialAudioManager.setEnabled(true)
                speak("Spatial audio enabled", isHighPriority = true)
            }
            command.contains("switch to voice") || command.contains("voice") -> {
                spatialAudioManager.setEnabled(false)
                speak("Spatial audio disabled, voice enabled", isHighPriority = true)
            }
            command.contains("where is my") || command.contains("where did i put my") -> {
                speak("Let me check my visual memory.", isHighPriority = true)
                visualMemoryManager.answerQuery(command)
            }
            command.contains("transit mode") || command.contains("find a door") -> {
                microNavigationManager.currentMode = NavMode.TRANSIT
                nvidiaPrompt = "I am looking for a door or an exit. Tell me exactly where it is using a 3x3 grid (e.g. 'door is center'). If you don't see one, say 'No doors in current view'."
                isNvidiaRequested = true
                speak("Scanning for doors and transit vehicles...", isHighPriority = true)
            }
            command.contains("find a seat") || command.contains("find a bed") -> {
                microNavigationManager.currentMode = NavMode.SEAT
                nvidiaPrompt = "Differentiate between any beds, chairs, couches, or doors in this view. Tell me their exact locations using a 3x3 grid (e.g. 'bed is bottom left', 'door is center')."
                isNvidiaRequested = true
                speak("Scanning room layout for seats, beds, and doors...", isHighPriority = true)
            }
            command.contains("find a cup") || command.contains("find a bottle") -> {
                microNavigationManager.currentMode = NavMode.CUP
            }
            command.startsWith("find ") -> {
                val target = command.replace("find ", "").trim()
                if (target.isNotEmpty()) {
                    microNavigationManager.startFinding(target)
                    
                    // Trigger cloud Vision AI for advanced object recognition
                    nvidiaPrompt = "I am looking for a $target. Do you see it in this image? If so, tell me exactly where it is using a 3x3 grid (e.g. 'top left', 'center', 'bottom right') and provide a brief description. If you don't see it, reply EXACTLY with 'Not found in current view'."
                    isNvidiaRequested = true
                    speak("Scanning for $target...", isHighPriority = true)
                }
            }
            command.contains("normal mode") -> {
                microNavigationManager.currentMode = NavMode.NORMAL
            }
            else -> {
                speak("Command not recognized. Please try again.", isHighPriority = true)
            }
        }
    }

    private var currentSpeechIsHighPriority = false

    fun speak(text: String, pan: Float = 0f, volume: Float = 1.0f, pitch: Float = 1.0f, isHighPriority: Boolean = false) {
        if (tts.isSpeaking && currentSpeechIsHighPriority && !isHighPriority) {
            return // Don't allow background tracking to interrupt high-priority AI answers
        }
        
        currentSpeechIsHighPriority = isHighPriority
        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_PAN, pan.coerceIn(-1.0f, 1.0f))
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.coerceIn(0.0f, 1.0f))
        tts.setPitch(pitch.coerceIn(0.5f, 2.0f))
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "")
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        tts.stop()
        tts.shutdown()
        fallDetectionManager.stopListening()
        voiceAssistantManager.destroy()
        autoFlashlightManager.stopMonitoring()
        compassManager.stop()
    }
}

@Composable
fun MainScreen(
    cameraExecutor: ExecutorService,
    visionProcessor: VisionProcessor,
    onAnalyzeImage: (androidx.camera.core.ImageProxy) -> Unit,
    onCameraBound: (Camera) -> Unit,
    onMicClick: () -> Unit
) {
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var currentDetections by remember { mutableStateOf<List<Detection>>(emptyList()) }
    var imageDimensions by remember { mutableStateOf(Pair(1, 1)) }
    
    DisposableEffect(visionProcessor) {
        visionProcessor.onDetectionsUpdated = { detections, width, height ->
            currentDetections = detections
            imageDimensions = Pair(width, height)
        }
        onDispose {
            visionProcessor.onDetectionsUpdated = null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .align(Alignment.Center)
                .clickable { onMicClick() }
        ) {
            CameraPreviewFeed(
            cameraExecutor = cameraExecutor,
            onAnalyzeImage = onAnalyzeImage,
            onCameraBound = onCameraBound,
            lensFacing = lensFacing,
            modifier = Modifier.fillMaxSize()
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            val scaleX = size.width / imageDimensions.first.toFloat()
            val scaleY = size.height / imageDimensions.second.toFloat()
            
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.GREEN
                textSize = 60f
                isFakeBoldText = true
            }
            
            for (det in currentDetections) {
                val left = det.boundingBox.left * scaleX
                val top = det.boundingBox.top * scaleY
                val right = det.boundingBox.right * scaleX
                val bottom = det.boundingBox.bottom * scaleY
                
                drawRect(
                    color = Color.Green,
                    topLeft = Offset(left, top),
                    size = Size(right - left, bottom - top),
                    style = Stroke(width = 8f)
                )
                
                drawContext.canvas.nativeCanvas.drawText(
                    det.label, left, top - 10f, paint
                )
            }
        }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(onClick = onMicClick) {
                Text("Tap to Speak")
            }
            Button(onClick = {
                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                    CameraSelector.LENS_FACING_FRONT
                } else {
                    CameraSelector.LENS_FACING_BACK
                }
            }) {
                Text("Flip Camera")
            }
        }
    }
}

@Composable
fun CameraPreviewFeed(
    cameraExecutor: ExecutorService,
    onAnalyzeImage: (androidx.camera.core.ImageProxy) -> Unit,
    onCameraBound: (Camera) -> Unit,
    lensFacing: Int,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        factory = { ctx ->
            PreviewView(ctx)
        },
        update = { previewView ->
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor) { imageProxy ->
                            onAnalyzeImage(imageProxy)
                        }
                    }

                val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

                try {
                    cameraProvider.unbindAll()
                    val camera = cameraProvider.bindToLifecycle(
                        lifecycleOwner, cameraSelector, preview, imageAnalyzer
                    )
                    onCameraBound(camera)
                } catch (exc: Exception) {
                    Log.e("CameraX", "Use case binding failed", exc)
                }
            }, ContextCompat.getMainExecutor(context))
        },
        modifier = modifier
    )
}