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
        var currentProvider: String = "gemini"
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

        // Build provider-specific request
        val requestBuilder = when (currentProvider) {
            "gemini" -> {
                // Gemini-specific payload
                val requestBodyJson = JSONObject().apply {
                    put("systemInstruction", JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", systemInstruction)
                            })
                        })
                    })
                    put("contents", JSONArray().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", prompt)
                            })
                        })
                    })
                    put("generationConfig", JSONObject().apply {
                        put("responseMimeType", "application/json")
                        put("temperature", 0.2)
                        put("maxOutputTokens", 1024)
                        put("topP", 0.95)
                    })
                }

                Request.Builder()
                    .url("$GEMINI_URL?key=$apiKey")
                    .post(requestBodyJson.toString().toRequestBody(mediaType))
            }
            "huggingface" -> {
                // Placeholder for HF request - using generic payload
                val hfPayload = JSONObject().apply {
                    put("model", modelName)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", prompt)
                        })
                    })
                }

                Request.Builder()
                    .url(apiUrl)
                    .post(hfPayload.toString().toRequestBody(MediaType.parse("application/json")))
            }
            "deepseek" -> {
                // Placeholder for DeepSeek request
                val dsPayload = JSONObject().apply {
                    put("model", modelName)
                    put("prompt", prompt)
                    put("max_tokens", 1024)
                    put("temperature", 0.2)
                }

                Request.Builder()
                    .url("https://api.deepseek.com/chat/completions")
                    .post(dsPayload.toString().toRequestBody(MediaType.parse("application/json")))
            }
            "local" -> {
                // Ollama local request
                val localPayload = JSONObject().apply {
                    put("model", modelName)
                    put("prompt", prompt)
                }

                Request.Builder()
                    .url("http://localhost:11434/api/generate")
                    .post(localPayload.toString().toRequestBody(MediaType.parse("application/json")))
            }
            else -> Request.Builder().url("").post("".toRequestBody(MediaType.parse("application/json")))
        }

        val request = requestBuilder.build()
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
                    if ((code == 429 || code >= 500) && retriesLeft > 0) {
                        Log.w(TAG, "Retrying in ${delayMs}ms. Retries left: ${retriesLeft - 1}")
                        Handler(Looper.getMainLooper()).postDelayed({
                            sendRequest(request, callback, retriesLeft - 1, delayMs * 2)
                        }, delayMs)
                    } else {
                        callback.onFailure("API Error code $code")
                    }
                    return
                }

                try {
                    // Try to parse according to provider
                    val rootJson = JSONObject(responseStr)
                    // For local Ollama, expect {"response":"..."}
                    if (currentProvider == "local") {
                        val respJson = JSONObject(responseStr)
                        val answer = respJson.optString("response", "")
                        val result = JSONObject().apply {
                            put("action", "speak")
                            put("text", answer)
                        }
                        callback.onSuccess(result)
                        return
                    }
                    // Default Gemini parsing
                    val candidates = rootJson.getJSONArray("candidates")
                    val firstCandidate = candidates.getJSONObject(0)
                    val responseText = firstCandidate.optString("output", null)
                        ?: firstCandidate.getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                    val actionJson = JSONObject(responseText.trim())
                    callback.onSuccess(actionJson)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse response: $responseStr", e)
                    callback.onFailure("Parsing error: ${e.message}")
                }
            }
        })
    }

    fun setProvider(p: String) {
        currentProvider = p
    }
}