package com.example.smartblindstick

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

enum class NavMode {
    NORMAL, TRANSIT, SEAT, CUP, FIND
}

class MicroNavigationManager(private val context: Context, private val onSpeak: (String) -> Unit) {

    var currentMode: NavMode = NavMode.NORMAL
        set(value) {
            field = value
            when (value) {
                NavMode.TRANSIT -> onSpeak("Transit mode activated. Looking for doors.")
                NavMode.SEAT -> onSpeak("Seat mode activated. Looking for empty seats.")
                NavMode.CUP -> onSpeak("Cup mode activated. Looking for cups and bottles.")
                NavMode.NORMAL -> onSpeak("Micro navigation returned to normal mode.")
                NavMode.FIND -> {} // Handled by startFinding
            }
        }
        
    private var searchTarget: String = ""

    fun startFinding(target: String) {
        searchTarget = target.lowercase()
        currentMode = NavMode.FIND
        onSpeak("Looking for $searchTarget")
    }

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private var lastAnnouncementTime = 0L
    private var lastVibrationTime = 0L
    private var lastSpokenTarget: String = ""

    private fun attemptAnnouncement(announcement: String, distanceStr: String) {
        if (distanceStr != "close") return
        
        val currentTime = System.currentTimeMillis()
        if (announcement != lastSpokenTarget || currentTime - lastAnnouncementTime > 15000) {
            onSpeak(announcement)
            lastSpokenTarget = announcement
            lastAnnouncementTime = currentTime
        }
    }

    fun processDetections(detections: List<Detection>, imageWidth: Int, imageHeight: Int) {
        when (currentMode) {
            NavMode.NORMAL -> processNormalMode(detections, imageWidth, imageHeight)
            NavMode.TRANSIT -> processTransitMode(detections, imageWidth, imageHeight)
            NavMode.SEAT -> processSeatMode(detections, imageWidth, imageHeight)
            NavMode.CUP -> processCupMode(detections, imageWidth, imageHeight)
            NavMode.FIND -> processFindMode(detections, imageWidth, imageHeight)
        }
    }

    private fun processNormalMode(detections: List<Detection>, imageWidth: Int, imageHeight: Int) {
        val closeObjects = detections.filter { it.distanceMeters in 0f..1.0f }
        if (closeObjects.isEmpty()) return
        
        val largest = closeObjects.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: return
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastVibrationTime > 2000) {
            triggerVibration(getPositionDescription(largest.centerX, largest.centerY, imageWidth, imageHeight))
            lastVibrationTime = currentTime
        }
    }

    private fun processFindMode(detections: List<Detection>, imageWidth: Int, imageHeight: Int) {
        val targets = detections.filter { 
            val label = it.label.lowercase()
            label.contains(searchTarget) || searchTarget.contains(label) 
        }
        if (targets.isEmpty()) return

        val target = targets.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: return
        
        val position = getPositionDescription(target.centerX, target.centerY, imageWidth, imageHeight)
        val distanceStr = getDistanceDescription(target.distanceMeters)
        attemptAnnouncement("${target.label} detected $position, $distanceStr.", distanceStr)
        
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastVibrationTime > 1000) {
            triggerVibration(position)
            lastVibrationTime = currentTime
        }
    }

    private fun processTransitMode(detections: List<Detection>, imageWidth: Int, imageHeight: Int) {
        val vehicles = detections.filter { it.label in listOf("bus", "train") }
        if (vehicles.isEmpty()) return

        val largest = vehicles.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: return
        
        val position = getPositionDescription(largest.centerX, largest.centerY, imageWidth, imageHeight)
        val distanceStr = getDistanceDescription(largest.distanceMeters)
        attemptAnnouncement("Transit vehicle detected $position, $distanceStr.", distanceStr)
        
        // Haptic feedback
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastVibrationTime > 1000) {
            triggerVibration(getPositionDescription(largest.centerX, largest.centerY, imageWidth, imageHeight))
            lastVibrationTime = currentTime
        }
    }

    private fun processSeatMode(detections: List<Detection>, imageWidth: Int, imageHeight: Int) {
        val chairs = detections.filter { it.label in listOf("chair", "bench", "couch", "bed") }
        val people = detections.filter { it.label == "person" }
        
        if (chairs.isEmpty()) return

        for (chair in chairs) {
            var isOccupied = false
            for (person in people) {
                if (calculateIoU(chair.boundingBox, person.boundingBox) > 0.1f) {
                    isOccupied = true
                    break
                }
            }
            
            if (!isOccupied) {
                val position = getPositionDescription(chair.centerX, chair.centerY, imageWidth, imageHeight)
                val distanceStr = getDistanceDescription(chair.distanceMeters)
                attemptAnnouncement("Empty seat detected $position, $distanceStr.", distanceStr)
            }
        }
        
        // Haptic feedback for empty seats
        val currentTime = System.currentTimeMillis()
        for (chair in chairs) {
            var isOccupied = false
            for (person in people) {
                if (calculateIoU(chair.boundingBox, person.boundingBox) > 0.1f) {
                    isOccupied = true
                    break
                }
            }
            if (!isOccupied && currentTime - lastVibrationTime > 1000) {
                triggerVibration(getPositionDescription(chair.centerX, chair.centerY, imageWidth, imageHeight))
                lastVibrationTime = currentTime
                return
            }
        }
    }

    private fun processCupMode(detections: List<Detection>, imageWidth: Int, imageHeight: Int) {
        val targets = detections.filter { it.label in listOf("cup", "bottle", "wine glass") }
        if (targets.isEmpty()) return

        val target = targets.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: return
        
        val position = getPositionDescription(target.centerX, target.centerY, imageWidth, imageHeight)
        val distanceStr = getDistanceDescription(target.distanceMeters)
        attemptAnnouncement("${target.label} detected $position, $distanceStr.", distanceStr)
        
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastVibrationTime > 1000) {
            triggerVibration(position)
            lastVibrationTime = currentTime
        }
    }

    private fun triggerVibration(position: String) {
        when {
            position.contains("left") -> vibrateLeft()
            position.contains("right") -> vibrateRight()
            else -> vibrateCenter()
        }
    }

    private fun getDistanceDescription(distanceMeters: Float): String {
        return when {
            distanceMeters < 0 -> ""
            distanceMeters < 1.0f -> "close"
            distanceMeters < 2.5f -> "medium"
            else -> "far"
        }
    }

    private fun getPositionDescription(centerX: Float, centerY: Float, imageWidth: Int, imageHeight: Int): String {
        val hPos = when {
            centerX < imageWidth / 3 -> "left"
            centerX > 2 * imageWidth / 3 -> "right"
            else -> "center"
        }
        val vPos = when {
            centerY < imageHeight / 3 -> "top"
            centerY > 2 * imageHeight / 3 -> "bottom"
            else -> "middle"
        }
        return if (hPos == "center" && vPos == "middle") "center" else "$vPos $hPos"
    }

    private fun vibrateLeft() {
        // Short then long
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 100, 100, 300), -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 100, 100, 300), -1)
        }
    }

    private fun vibrateRight() {
        // Long then short
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 100, 100), -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 300, 100, 100), -1)
        }
    }

    private fun vibrateCenter() {
        // Solid pulse
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(200)
        }
    }

    private fun calculateIoU(box1: android.graphics.RectF, box2: android.graphics.RectF): Float {
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
