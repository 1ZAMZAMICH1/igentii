package com.master.agent

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class BrainService(
    private val apiKey: String,
    private val apiBaseUrl: String = "https://generativelanguage.googleapis.com",
    private val modelName: String = "gemini-2.0-flash"
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val mediaType = "application/json; charset=utf-8".toMediaType()

    companion object {
        private const val TAG = "MasterBrain"
    }

    interface BrainCallback {
        fun onSuccess(action: JSONObject)
        fun onFailure(error: String)
    }

    fun decideNextStep(userInstruction: String, screenHierarchy: String, activityHistory: String, callback: BrainCallback) {
        val systemInstruction = """
            You are "Master", a personal autonomous OS agent running on the user's Android phone.
            Your goal is to fulfill the user's command: "$userInstruction".
            You interact with the phone by analyzing the current screen UI elements and outputting a single action in JSON format.
            
            IMPORTANT:
             - Base your decision on the provided "screenHierarchy" which includes coordinates for elements (bounds).
             - Try to reach the target by clicking elements. If you need to open an app, use 'launch_app' action with the correct package name (e.g. "com.whatsapp", "ru.yandex.music").
             - If you need to speak to the user, use the 'speak' action.
             - If the task is completed, return the 'done' action.

            You MUST respond ONLY with a JSON object in this format. Do not add markdown wrapping like ```json or text before/after. Return raw JSON code:
            {
               "reasoning": "Brief explanation of what you see and what you are doing",
               "action": "click" | "swipe" | "type" | "launch_app" | "speak" | "done" | "wait",
               "x": Float,
               "y": Float,
               "text": "text value",
               "packageName": "com.whatsapp",
               "startX": Float, "startY": Float, "endX": Float, "endY": Float, "duration": Long,
               "ms": Long
            }
        """.trimIndent()

        val prompt = """
            User Request: $userInstruction
            
            Activity history so far:
            $activityHistory
            
            Current screen elements hierarchy JSON:
            $screenHierarchy
        """.trimIndent()

        // Проверяем, какой тип API используется (Gemini или OpenAI-совместимый)
        val isGemini = apiBaseUrl.contains("generativelanguage.googleapis.com") || apiBaseUrl.isEmpty()

        val requestBuilder = Request.Builder()

        if (isGemini) {
            val url = if (apiBaseUrl.endsWith("/")) "${apiBaseUrl}v1beta/models/$modelName:generateContent?key=$apiKey" 
                      else "$apiBaseUrl/v1beta/models/$modelName:generateContent?key=$apiKey"
            
            val requestBodyJson = JSONObject().apply {
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
                })
                put("contents", JSONArray().put(JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                }))
                put("generationConfig", JSONObject().apply {
                    put("responseMimeType", "application/json")
                })
            }
            
            requestBuilder.url(url)
            requestBuilder.post(requestBodyJson.toString().toRequestBody(mediaType))
        } else {
            // Формат OpenAI / DeepSeek / Hugging Face / OpenRouter / Groq / Qwen / LLaMA
            val url = if (apiBaseUrl.endsWith("/chat/completions")) apiBaseUrl 
                      else if (apiBaseUrl.endsWith("/")) "${apiBaseUrl}chat/completions"
                      else "$apiBaseUrl/chat/completions"

            val requestBodyJson = JSONObject().apply {
                put("model", modelName)
                
                val messagesArray = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemInstruction)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                }
                put("messages", messagesArray)
                put("response_format", JSONObject().put("type", "json_object"))
            }

            requestBuilder.url(url)
            requestBuilder.header("Authorization", "Bearer $apiKey")
            requestBuilder.post(requestBodyJson.toString().toRequestBody(mediaType))
        }

        client.newCall(requestBuilder.build()).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.e(TAG, "API request failed", e)
                callback.onFailure(e.message ?: "Network error")
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val responseStr = response.body?.string()
                if (!response.isSuccessful || responseStr == null) {
                    Log.e(TAG, "API returned error code ${response.code}: $responseStr")
                    callback.onFailure("API Error code ${response.code}")
                    return
                }

                try {
                    val rootJson = JSONObject(responseStr)
                    val responseText = if (isGemini) {
                        val candidates = rootJson.getJSONArray("candidates")
                        val firstCandidate = candidates.getJSONObject(0)
                        val content = firstCandidate.getJSONObject("content")
                        val parts = content.getJSONArray("parts")
                        parts.getJSONObject(0).getString("text").trim()
                    } else {
                        // OpenAI format parser
                        val choices = rootJson.getJSONArray("choices")
                        val firstChoice = choices.getJSONObject(0)
                        val message = firstChoice.getJSONObject("message")
                        message.getString("content").trim()
                    }
                    
                    // Очистка от ```json ... ``` оберток если модель проигнорировала инструкцию
                    var cleanedText = responseText
                    if (cleanedText.startsWith("```json")) {
                        cleanedText = cleanedText.removePrefix("```json").trim()
                    }
                    if (cleanedText.endsWith("```")) {
                        cleanedText = cleanedText.removeSuffix("```").trim()
                    }
                    
                    val actionJson = JSONObject(cleanedText)
                    callback.onSuccess(actionJson)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse API response: $responseStr", e)
                    callback.onFailure("Parsing error. Respose was: $responseText")
                }
            }
        })
    }
}
