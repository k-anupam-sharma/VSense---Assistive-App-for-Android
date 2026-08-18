package com.example.smartblindstick

import android.content.Context
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class OcrProcessor(private val context: Context) {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    
    // Cooldown mechanism to avoid reading the same text repeatedly
    private var isProcessing = false
    private var lastSpokenText = ""
    private var lastSpokenTime: Long = 0

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    fun processImageProxy(imageProxy: ImageProxy, onTextFound: (String) -> Unit) {
        if (isProcessing) {
            imageProxy.close()
            return
        }
        
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            isProcessing = true
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val detectedText = visionText.text
                    if (detectedText.isNotBlank()) {
                        Log.d("OcrProcessor", "Found text: $detectedText")
                        
                        val currentTime = System.currentTimeMillis()
                        // Avoid repeating the exact same text within 10 seconds
                        if (detectedText != lastSpokenText || (currentTime - lastSpokenTime > 10000)) {
                            lastSpokenText = detectedText
                            lastSpokenTime = currentTime
                            onTextFound(detectedText)
                        }
                    }
                }
                .addOnFailureListener { e ->
                    Log.e("OcrProcessor", "Text recognition failed", e)
                }
                .addOnCompleteListener {
                    isProcessing = false
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }
}
