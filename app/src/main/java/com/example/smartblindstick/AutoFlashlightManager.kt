package com.example.smartblindstick

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import androidx.camera.core.Camera

class AutoFlashlightManager(
    private val context: Context,
    private val onSpeak: (String) -> Unit
) {

    private var camera: Camera? = null
    private var isTorchOn = false
    
    // Debouncing/Hysteresis state
    private var lowLightCount = 0
    private var brightLightCount = 0
    // Based on typical Y-plane luminance (0-255)
    private val THRESHOLD_LOW = 80f // Turn on sooner in darkness
    private val THRESHOLD_BRIGHT = 200f // Only turn off when very bright
    private val REQUIRED_CONSECUTIVE_READINGS_ON = 1 // Instantly turn on
    private val REQUIRED_CONSECUTIVE_READINGS_OFF = 5 // Be sure before turning off
    
    var isManualOverride = false

    fun setCamera(camera: Camera?) {
        this.camera = camera
    }

    fun startMonitoring() {
        Log.d("AutoFlashlight", "Started camera luminance monitoring")
    }

    fun stopMonitoring() {
        setTorch(false)
    }

    fun processLuminance(averageLuminance: Float) {
        if (camera == null || !camera!!.cameraInfo.hasFlashUnit() || isManualOverride) return

        if (averageLuminance < THRESHOLD_LOW && !isTorchOn) {
            lowLightCount++
            brightLightCount = 0
            if (lowLightCount >= REQUIRED_CONSECUTIVE_READINGS_ON) {
                setTorch(true)
                onSpeak("Low light detected. Flashlight auto-enabled.")
                lowLightCount = 0
            }
        } else if (averageLuminance > THRESHOLD_BRIGHT && isTorchOn) {
            brightLightCount++
            lowLightCount = 0
            if (brightLightCount >= REQUIRED_CONSECUTIVE_READINGS_OFF) {
                setTorch(false)
                brightLightCount = 0
            }
        } else {
            // Reset counters if reading is in between thresholds
            lowLightCount = 0
            brightLightCount = 0
        }
    }

    fun toggleTorch(enable: Boolean) {
        isManualOverride = true
        setTorch(enable)
        if (enable) onSpeak("Flashlight manually turned on. Auto mode paused.") else onSpeak("Flashlight manually turned off. Auto mode paused.")
    }
    
    fun resetToAuto() {
        isManualOverride = false
        onSpeak("Auto flashlight resumed.")
    }

    private fun setTorch(enable: Boolean) {
        try {
            camera?.cameraControl?.enableTorch(enable)
            isTorchOn = enable
            Log.d("AutoFlashlight", "Torch set to $enable")
        } catch (e: Exception) {
            Log.e("AutoFlashlight", "Failed to set torch", e)
        }
    }
}
