package com.example.smartblindstick

import android.content.Context
import android.util.Log
import com.example.smartblindstick.data.AppDatabase
import com.example.smartblindstick.data.SightingLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VisualMemoryManager(private val context: Context, private val onAnswerReady: (String, Float, Float, Float) -> Unit) {
    var compassManager: CompassManager? = null

    private val db = AppDatabase.getDatabase(context)
    private val dao = db.sightingLogDao()
    private val metaApiClient = MetaApiClient()
    
    private val scope = CoroutineScope(Dispatchers.IO)
    private var lastLogTime = 0L

    // Items we care to log (don't log floors, walls, people which move constantly)
    private val objectsOfInterest = setOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
        "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
        "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
        "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
        "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
        "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
        "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair",
        "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse", "remote",
        "keyboard", "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator", "book",
        "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush", "keys", "wallet"
    )

    fun logSightings(detections: List<Detection>, imageWidth: Int) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastLogTime < 3000) return // Log at most every 3 seconds
        
        val interestingDetections = detections.filter { objectsOfInterest.contains(it.label.lowercase()) }
        if (interestingDetections.isEmpty()) return

        val currentAzimuth = compassManager?.currentAzimuth ?: 0f

        scope.launch {
            try {
                // Delete logs older than 24 hours
                val cutoff = currentTime - (24 * 60 * 60 * 1000)
                dao.deleteOlderThan(cutoff)

                val sceneContext = detections.map { it.label }.joinToString(", ")

                for (detection in interestingDetections) {
                    val pan = (detection.centerX / imageWidth) * 2f - 1f // -1 to 1
                    val offsetAngle = pan * 30f // assuming ~60 degree horizontal FOV
                    val globalAzimuth = (currentAzimuth + offsetAngle + 360) % 360

                    val log = SightingLog(
                        objectLabel = detection.label,
                        timestamp = currentTime,
                        globalAzimuth = globalAzimuth,
                        confidence = detection.confidence,
                        sceneContext = sceneContext
                    )
                    dao.insert(log)
                    Log.d("VisualMemoryManager", "Logged: ${detection.label} at globalAzimuth $globalAzimuth")
                }
                lastLogTime = currentTime
            } catch (e: Exception) {
                Log.e("VisualMemoryManager", "Error logging sightings", e)
            }
        }
    }

    fun answerQuery(query: String) {
        // Simple NLP to extract the object from "where is my [object]"
        val words = query.lowercase().replace("?", "").split(" ")
        var targetObject = ""
        for (obj in objectsOfInterest) {
            if (words.contains(obj) || query.lowercase().contains(obj)) {
                targetObject = obj
                break
            }
        }

        if (targetObject.isEmpty()) {
            onAnswerReady("I'm not sure what object you are looking for.", 0f, 1.0f, 1.0f)
            return
        }

        scope.launch {
            val logs = dao.searchLogs(targetObject)
            if (logs.isEmpty()) {
                withContext(Dispatchers.Main) {
                    onAnswerReady("I haven't seen your $targetObject recently.", 0f, 1.0f, 1.0f)
                }
                return@launch
            }

            // Build prompt
            val contextBuilder = StringBuilder()
            val now = System.currentTimeMillis()
            val currentAzimuth = compassManager?.currentAzimuth ?: 0f
            
            logs.forEach { log ->
                val minutesAgo = (now - log.timestamp) / (60 * 1000)
                val timeStr = if (minutesAgo == 0L) "just now" else "$minutesAgo minutes ago"
                
                var diff = (log.globalAzimuth - currentAzimuth + 360) % 360
                if (diff > 180) diff -= 360
                
                val relativeDirection = when {
                    diff in -30f..30f -> "directly in front of you"
                    diff in 30f..150f -> "to your right"
                    diff in -150f..-30f -> "to your left"
                    else -> "behind you"
                }
                
                contextBuilder.append("- Seen $timeStr $relativeDirection. Other objects nearby: ${log.sceneContext}\n")
            }

            val prompt = """
                You are a helpful assistant for a blind person. The user asked: "$query"
                Based on the following recent sightings of the object from their camera, give a concise and natural 1-2 sentence answer.
                Sightings:
                $contextBuilder
            """.trimIndent()

            val answer = metaApiClient.generateResponse(prompt)
            withContext(Dispatchers.Main) {
                // If it was seen recently, let's use the latest log for panning
                val latest = logs.first()
                var diff = (latest.globalAzimuth - currentAzimuth + 360) % 360
                if (diff > 180) diff -= 360
                
                val relativeDirection = when {
                    diff in -30f..30f -> "directly in front of you"
                    diff in 30f..150f -> "to your right"
                    diff in -150f..-30f -> "to your left"
                    else -> "behind you"
                }
                
                // Calculate pan continuously from -1 to 1 based on sine of angle
                val diffRadians = Math.toRadians(diff.toDouble())
                val pan = Math.sin(diffRadians).toFloat()
                
                // Volume: 1.0 when in front (0 deg), 0.5 when behind (180 deg)
                val absDiff = Math.abs(diff)
                val volume = (1.0f - 0.5f * (absDiff / 180f)).coerceIn(0.5f, 1.0f)
                
                // Pitch: slightly lower if behind to simulate acoustic shadowing
                val pitch = if (absDiff > 90f) 0.9f else 1.0f
                
                if (answer != null) {
                    onAnswerReady(answer, pan, volume, pitch)
                } else {
                    // Fallback if API fails
                    val minutesAgo = (now - latest.timestamp) / (60 * 1000)
                    val timeStr = if (minutesAgo == 0L) "just now" else "$minutesAgo minutes ago"
                    onAnswerReady("I saw your $targetObject $timeStr $relativeDirection.", pan, volume, pitch)
                }
            }
        }
    }
}
