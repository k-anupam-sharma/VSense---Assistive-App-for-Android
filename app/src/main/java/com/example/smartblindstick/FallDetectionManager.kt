package com.example.smartblindstick

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.telephony.SmsManager
import android.util.Log
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject

class FallDetectionManager(private val context: Context) : SensorEventListener {

    private val okHttpClient = OkHttpClient()
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    
    // Fall detection state variables
    private var isFreeFallDetected = false
    private var lastFreeFallTime: Long = 0
    
    // Thresholds
    private val FREE_FALL_THRESHOLD = 3.0f // m/s^2 (near zero gravity)
    private val IMPACT_THRESHOLD = 20.0f // m/s^2 (hard impact)
    
    // Emergency contact (Change this or build UI to set it)
    private val EMERGENCY_NUMBER = "1234567890"

    init {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }

    fun startListening() {
        accelerometer?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            Log.d("FallDetection", "Started listening to accelerometer")
        }
    }

    fun stopListening() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        // Calculate total acceleration magnitude
        val acceleration = sqrt((x * x + y * y + z * z).toDouble()).toFloat()

        // 1. Detect Free Fall
        if (acceleration < FREE_FALL_THRESHOLD) {
            isFreeFallDetected = true
            lastFreeFallTime = System.currentTimeMillis()
        }

        // 2. Detect Impact shortly after Free Fall
        if (isFreeFallDetected && acceleration > IMPACT_THRESHOLD) {
            val timeSinceFreeFall = System.currentTimeMillis() - lastFreeFallTime
            
            // If impact happens within 2 seconds of free fall, it's likely a drop/fall
            if (timeSinceFreeFall < 2000) {
                Log.w("FallDetection", "FALL DETECTED! Acceleration: $acceleration")
                triggerEmergencyProtocol()
                
                // Reset state
                isFreeFallDetected = false
            }
        }
        
        // Reset free fall if too much time passes
        if (isFreeFallDetected && System.currentTimeMillis() - lastFreeFallTime > 2000) {
            isFreeFallDetected = false
        }
    }

    private fun triggerEmergencyProtocol() {
        // Speak alert using MainActivity reference
        if (context is MainActivity) {
            context.speak("Fall detected. Sending emergency message.")
        }
        
        sendTelegramAlert()
    }

    private fun sendTelegramAlert() {
        // Read credentials from BuildConfig (set in local.properties)
        val botToken = BuildConfig.TELEGRAM_BOT_TOKEN
        val chatId = BuildConfig.TELEGRAM_CHAT_ID
        val message = "Emergency! The Smart Blind Stick user may have fallen. Please check on them."

        if (botToken.isBlank() || chatId.isBlank()) {
            Log.w("FallDetection", "Telegram Bot Token or Chat ID not set. Cannot send alert.")
            return
        }

        val url = "https://api.telegram.org/bot$botToken/sendMessage"
        
        // Build JSON body
        val json = JSONObject()
        json.put("chat_id", chatId)
        json.put("text", message)
        
        val body = RequestBody.create(
            "application/json; charset=utf-8".toMediaTypeOrNull(), 
            json.toString()
        )
        
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()
            
        // Use Coroutine to send network request off the main thread
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = okHttpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    Log.d("FallDetection", "Telegram emergency message sent successfully")
                } else {
                    Log.e("FallDetection", "Failed to send Telegram message: ${response.code}")
                }
            } catch (e: Exception) {
                Log.e("FallDetection", "Error sending Telegram message", e)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
