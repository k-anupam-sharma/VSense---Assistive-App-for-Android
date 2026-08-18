package com.example.smartblindstick

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class NvidiaApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
    // API key read from BuildConfig (set in local.properties)
    private val API_KEY = BuildConfig.NVIDIA_API_KEY
    private val API_URL = "https://integrate.api.nvidia.com/v1/chat/completions"

    fun generateResponse(image: Bitmap, prompt: String, onResponse: (String?) -> Unit) {
        val base64Image = encodeImageToBase64(image)

        val json = JSONObject()
        json.put("model", "meta/llama-3.2-11b-vision-instruct")
        json.put("max_tokens", 512)
        json.put("temperature", 0.7)
        json.put("top_p", 0.7)

        val messagesArray = JSONArray()
        
        val userMessage = JSONObject()
        userMessage.put("role", "user")
        
        val contentArray = JSONArray()
        
        val textContent = JSONObject()
        textContent.put("type", "text")
        textContent.put("text", "You are an assistant for a blind person. Give a concise, helpful 1-2 sentence answer. $prompt")
        
        val imageContent = JSONObject()
        imageContent.put("type", "image_url")
        val imageUrlObj = JSONObject()
        imageUrlObj.put("url", "data:image/jpeg;base64,$base64Image")
        imageContent.put("image_url", imageUrlObj)
        
        contentArray.put(textContent)
        contentArray.put(imageContent)
        
        userMessage.put("content", contentArray)
        messagesArray.put(userMessage)
        
        json.put("messages", messagesArray)

        val requestBody = json.toString().toRequestBody("application/json".toMediaType())
        
        val request = Request.Builder()
            .url(API_URL)
            .post(requestBody)
            .addHeader("Authorization", "Bearer $API_KEY")
            .addHeader("Accept", "application/json")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("NvidiaApiClient", "API request failed", e)
                onResponse(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                if (response.isSuccessful && responseBody != null) {
                    try {
                        val jsonObject = JSONObject(responseBody)
                        val answer = jsonObject.getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content")
                        onResponse(answer)
                    } catch (e: Exception) {
                        Log.e("NvidiaApiClient", "Error parsing Nvidia response", e)
                        onResponse(null)
                    }
                } else {
                    Log.e("NvidiaApiClient", "Nvidia API error: ${response.code} $responseBody")
                    onResponse(null)
                }
            }
        })
    }

    private fun encodeImageToBase64(bitmap: Bitmap): String {
        // Resize image to prevent massive payload
        val maxWidth = 800
        val ratio = maxWidth.toFloat() / bitmap.width
        val height = (bitmap.height * ratio).toInt()
        val resized = Bitmap.createScaledBitmap(bitmap, maxWidth, height, true)
        
        val outputStream = ByteArrayOutputStream()
        resized.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }
}
