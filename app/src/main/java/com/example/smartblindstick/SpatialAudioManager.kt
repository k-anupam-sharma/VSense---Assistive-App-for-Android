package com.example.smartblindstick

import android.util.Log

class SpatialAudioManager(private val onSpeak: (String, Float, Float, Float) -> Unit) {

    private var isEnabled = false
    private var lastSpokenTime = 0L
    private var lastSpokenTarget = ""

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        Log.d("SpatialAudioManager", "Spatial audio enabled: $enabled")
    }

    fun isEnabled(): Boolean = isEnabled

    fun processDetections(detections: List<Detection>, imageWidth: Int, imageHeight: Int) {
        if (!isEnabled || detections.isEmpty()) return

        val currentTime = System.currentTimeMillis()
        val prominentDetection = detections.minByOrNull { it.distanceMeters } ?: return

        // Only tell when object is close
        if (prominentDetection.distanceMeters > 1.0f) return

        val position = getPositionDescription(prominentDetection.centerX, prominentDetection.centerY, imageWidth, imageHeight)
        
        // 3D Audio processing
        val pan = (prominentDetection.centerX / imageWidth) * 2f - 1f
        // Volume decreases as distance increases (0.5m = 1.0, 1.0m = 0.5)
        val volume = (1.0f - (prominentDetection.distanceMeters - 0.5f).coerceAtLeast(0f)).coerceIn(0.2f, 1.0f)
        // Pitch changes based on elevation (top of screen = higher pitch, bottom = lower pitch)
        val normalizedY = prominentDetection.centerY / imageHeight
        val pitch = 1.2f - (0.4f * normalizedY) // ranges from ~1.2 (top) to ~0.8 (bottom)
        
        val announcement = "${prominentDetection.label} detected, $position"
        
        // Prevent repeating the same target constantly
        if (announcement != lastSpokenTarget || currentTime - lastSpokenTime > 10000) {
            onSpeak(announcement, pan, volume, pitch)
            lastSpokenTarget = announcement
            lastSpokenTime = currentTime
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
}
