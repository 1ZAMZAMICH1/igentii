package com.master.agent

import android.util.Log
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.MediaType
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class BrainService(private val apiKey: String, private val apiUrl: String, private val modelName: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val mediaType = "application/json; charset=utf-8".toMediaType()

    companion object {
        private const val TAG = "MasterBrain"
        private const val MAX_RETRIES = 3
        private const val INITIAL_RETRY_DELAY_MS = 1000L
        var currentProvider: String = "openrouter"
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
        """.trimIndent()

        val prompt = """
            User Request: $userInstruction
            
            Activity history so far:
            $activityHistory
            
            Current screen elements hierarchy JSON:
            $screenHierarchy
        """.trimIndent()

        // OpenRouter uses OpenAI-compatible API
        val requestBodyJson = JSONObject().apply {
            put("model", modelName)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemInstruction)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
            put("temperature", 0.2)
            put("max_tokens", 1024)
            put("response_format", JSONObject().apply {
                put("type", "json_object")
            })
        }

        val request = Request.Builder()
            .url(apiUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("HTTP-Referer", "https://github.com/1ZAMZAMICH1/igentii")
            .addHeader("X-Title", "Master Agent")
            .post(requestBodyJson.toString().toRequestBody(mediaType))
            .build()

        sendRequest(request, callback, MAX_RETRIES, INITIAL_RETRY_DELAY_MS)
    }

    private fun sendRequest(request: Request, callback: BrainCallback, retriesLeft: Int, delayMs: Long) {
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.e(TAG, "Request failed", e)
                callback.onFailure(e.message ?: "Network error")
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val responseStr = response.body?.string()
                if (!response.isSuccessful || responseStr == null) {
                    val code = response.code
                    Log.e(TAG, "API returned error code $code: $responseStr")
                    
                    // Don't retry on client errors (4xx) - they won't be fixed by retrying
                    if (code in 400..499) {
                        callback.onFailure("API Error code $code: ${getErrorMessage(responseStr)}")
                        return
                    }
                    
                    // Retry on rate limit (429) or server errors (5xx)
                    if ((code == 429 || code >= 500) && retriesLeft > 0) {
                        Log.w(TAG, "Retrying in ${delayMs}ms. Retries left: ${retriesLeft - 1}")
                        Handler(Looper.getMainLooper()).postDelayed({
                            sendRequest(request, callback, retriesLeft - 1, delayMs * 2)
                        }, delayMs)
                        return
                    }
                    
                    callback.onFailure("API Error code $code: ${getErrorMessage(responseStr)}")
                    return
                }

                try {
                    val rootJson = JSONObject(responseStr)
                    val choices = rootJson.getJSONArray("choices")
                    val firstChoice = choices.getJSONObject(0)
                    val message = firstChoice.getJSONObject("message")
                    val responseText = message.getString("content")
                    val actionJson = JSONObject(responseText.trim())
                    callback.onSuccess(actionJson)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse response: $responseStr", e)
                    callback.onFailure("Parsing error: ${e.message}")
                }
            }
        })
    }

    private fun getErrorMessage(responseStr: String?): String {
        return try {
            if (responseStr != null) {
                val errorJson = JSONObject(responseStr)
                errorJson.optString("error", errorJson.optString("message", "Unknown error"))
            } else {
                "No response body"
            }
        } catch (e: Exception) {
            "Unknown error"
        }
    }

    fun setProvider(p: String) {
        currentProvider = p
    }
}