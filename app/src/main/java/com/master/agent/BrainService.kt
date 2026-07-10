package com.master.agent

import android.util.Log
import android.os.Handler
import android.os.Looper
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
        private const val GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta2/models/gemini-2.5-flash:generateText"
        private const val MAX_RETRIES = 3
        private const val INITIAL_RETRY_DELAY_MS = 1000L
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
            put("prompt", JSONObject().apply {
                put("text", "$systemInstruction\n\n$prompt")
            })
            put("temperature", 0.2)
            put("maxOutputTokens", 1024)
            put("topP", 0.95)
        }

        val request = Request.Builder()
            .url("$GEMINI_URL?key=$apiKey")
            .post(requestBodyJson.toString().toRequestBody(mediaType))
            .build()

        sendRequest(request, callback, MAX_RETRIES, INITIAL_RETRY_DELAY_MS)
    }

    private fun sendRequest(request: Request, callback: BrainCallback, retriesLeft: Int, delayMs: Long) {
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.e(TAG, "Gemini API request failed", e)
                callback.onFailure(e.message ?: "Network error")
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val responseStr = response.body?.string()
                if (!response.isSuccessful || responseStr == null) {
                    val code = response.code
                    Log.e(TAG, "Gemini API returned error code $code: $responseStr")
                    if ((code == 429 || code >= 500) && retriesLeft > 0) {
                        Log.w(TAG, "Retrying Gemini request in ${delayMs}ms. Retries left: ${retriesLeft - 1}")
                        Handler(Looper.getMainLooper()).postDelayed({
                            sendRequest(request, callback, retriesLeft - 1, delayMs * 2)
                        }, delayMs)
                        return
                    }
                    callback.onFailure("API Error code $code")
                    return
                }

                try {
                    val rootJson = JSONObject(responseStr)
                    val candidates = rootJson.getJSONArray("candidates")
                    val firstCandidate = candidates.getJSONObject(0)
                    val responseText = firstCandidate.optString("output", null)
                        ?: firstCandidate.getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                    val actionJson = JSONObject(responseText.trim())
                    callback.onSuccess(actionJson)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse Gemini response: $responseStr", e)
                    callback.onFailure("Parsing error: ${e.message}")
                }
            }
        })
    }
}
