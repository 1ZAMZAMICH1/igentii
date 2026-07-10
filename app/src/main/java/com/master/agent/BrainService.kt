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

class BrainService(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val mediaType = "application/json; charset=utf-8".toMediaType()

    companion object {
        private const val TAG = "MasterBrain"
        private const val GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"
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
             - If you need to speak to the user (e.g. read messages aloud or ask a question), use the 'speak' action.
             - If the task is completed, return the 'done' action.

            You MUST respond ONLY with a JSON object in this format:
            {
               "reasoning": "Brief explanation of what you see and what you are doing",
               "action": "click" | "swipe" | "type" | "launch_app" | "speak" | "done" | "wait",
               "x": Float (optional, for click),
               "y": Float (optional, for click),
               "text": "text value" (optional, for type or speak),
               "packageName": "com.whatsapp" (optional, for launch_app),
               "startX": Float, "startY": Float, "endX": Float, "endY": Float, "duration": Long (optional, for swipe),
               "ms": Long (optional, for wait)
            }
        """.trimIndent()

        val prompt = """
            User Request: $userInstruction
            
            Activity history so far:
            $activityHistory
            
            Current screen elements hierarchy JSON:
            $screenHierarchy
        """.trimIndent()

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

        val request = Request.Builder()
            .url("$GEMINI_URL?key=$apiKey")
            .post(requestBodyJson.toString().toRequestBody(mediaType))
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.e(TAG, "Gemini API request failed", e)
                callback.onFailure(e.message ?: "Network error")
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val responseStr = response.body?.string()
                if (!response.isSuccessful || responseStr == null) {
                    Log.e(TAG, "Gemini API returned error code ${response.code}: $responseStr")
                    callback.onFailure("API Error code ${response.code}")
                    return
                }

                try {
                    val rootJson = JSONObject(responseStr)
                    val candidates = rootJson.getJSONArray("candidates")
                    val firstCandidate = candidates.getJSONObject(0)
                    val content = firstCandidate.getJSONObject("content")
                    val parts = content.getJSONArray("parts")
                    val responseText = parts.getJSONObject(0).getString("text").trim()
                    
                    val actionJson = JSONObject(responseText)
                    callback.onSuccess(actionJson)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse Gemini response: $responseStr", e)
                    callback.onFailure("Parsing error: ${e.message}")
                }
            }
        })
    }
}
