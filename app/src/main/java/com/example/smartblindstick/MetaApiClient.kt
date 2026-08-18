package com.example.smartblindstick

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject

class MetaApiClient {
    private val client = OkHttpClient()
    private val apiKey = BuildConfig.NVIDIA_META_API_KEY
    // Assuming NVIDIA NIM or a similar OpenAI-compatible endpoint format for this key pattern
    private val apiUrl = "https://integrate.api.nvidia.com/v1/chat/completions"

    suspend fun generateResponse(prompt: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val jsonBody = JSONObject().apply {
                    put("model", "meta/llama-3.1-70b-instruct") // Using a high quality text model assuming NVIDIA endpoint
                    val messages = JSONArray()
                    messages.put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                    put("messages", messages)
                    put("max_tokens", 100)
                    put("temperature", 0.5)
                }

                val requestBody = RequestBody.create(
                    "application/json; charset=utf-8".toMediaTypeOrNull(),
                    jsonBody.toString()
                )

                val request = Request.Builder()
                    .url(apiUrl)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Accept", "application/json")
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: return@withContext null
                    val jsonResponse = JSONObject(responseBody)
                    val choices = jsonResponse.getJSONArray("choices")
                    if (choices.length() > 0) {
                        val firstChoice = choices.getJSONObject(0)
                        val message = firstChoice.getJSONObject("message")
                        return@withContext message.getString("content")
                    }
                } else {
                    Log.e("MetaApiClient", "API request failed with code: ${response.code}, body: ${response.body?.string()}")
                }
            } catch (e: Exception) {
                Log.e("MetaApiClient", "Error calling Meta API", e)
            }
            null
        }
    }
}
