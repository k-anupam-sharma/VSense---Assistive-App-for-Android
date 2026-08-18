package com.example.smartblindstick

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

import android.os.Handler
import android.os.Looper

class VoiceAssistantManager(
    private val context: Context,
    private val onCommandReceived: (String) -> Unit
) {
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var isAwake = false
    private var wakeTime: Long = 0
    private val AWAKE_DURATION = 10000L // 10 seconds of listening for commands after wake word
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        mainHandler.post {
            initializeRecognizer()
        }
    }

    private fun initializeRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    Log.e("VoiceAssistant", "Speech recognition error: $error")
                    isListening = false
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val text = matches[0].lowercase()
                        Log.d("VoiceAssistant", "Heard: $text")
                        processText(text)
                    }
                    isListening = false
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        } else {
            Log.e("VoiceAssistant", "Speech recognition is not available on this device.")
        }
    }

    fun startListening() {
        mainHandler.post {
            if (isListening || speechRecognizer == null) return@post

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            
            try {
                // Instantly wake up the assistant when triggered manually via button
                isAwake = true
                wakeTime = System.currentTimeMillis()
                
                speechRecognizer?.startListening(intent)
                isListening = true
                Log.d("VoiceAssistant", "Started listening...")
            } catch (e: Exception) {
                Log.e("VoiceAssistant", "Failed to start listening", e)
            }
        }
    }

    fun stopListening() {
        mainHandler.post {
            speechRecognizer?.stopListening()
            isListening = false
        }
    }

    fun destroy() {
        mainHandler.post {
            speechRecognizer?.destroy()
        }
    }

    private fun processText(text: String) {
        val currentTime = System.currentTimeMillis()
        
        // Reset awake state if duration passed
        if (isAwake && currentTime - wakeTime > AWAKE_DURATION) {
            isAwake = false
            Log.d("VoiceAssistant", "Assistant went back to sleep.")
        }

        // List of wake words for "Rubix"
        val wakeWords = listOf("rubix", "rubik", "rubics", "rubiks", "ruby", "rubic")
        var matchedWakeWord = ""
        for (word in wakeWords) {
            if (text.contains(word)) {
                matchedWakeWord = word
                break
            }
        }

        if (matchedWakeWord.isNotEmpty()) {
            isAwake = true
            wakeTime = currentTime
            
            // Extract the command after the wake word
            val command = text.substringAfter(matchedWakeWord).trim()
            if (command.isNotEmpty()) {
                onCommandReceived(command)
            } else {
                // Just the wake word
                if (context is MainActivity) {
                    context.speak("hello! what can i do for u")
                }
            }
        } else if (isAwake) {
            // Already awake, process anything as a command
            wakeTime = currentTime // Refresh awake timer
            onCommandReceived(text)
        }
    }
}
