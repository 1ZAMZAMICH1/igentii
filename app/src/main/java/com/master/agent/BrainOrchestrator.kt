package com.master.agent

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

class BrainOrchestrator(
    private val context: Context,
    private val apiKey: String,
    private val tts: TextToSpeech
) {
    private val brain = BrainService(apiKey)
    private val history = StringBuilder()
    private var currentInstruction = ""
    private var isRunning = false
    
    companion object {
        private const val TAG = "MasterOrchestrator"
        private const val MAX_STEPS = 15
    }

    fun executeCommand(instruction: String) {
        if (isRunning) {
            speak("Я уже выполняю предыдущую задачу. Пожалуйста, подождите.")
            return
        }
        
        currentInstruction = instruction
        history.clear()
        isRunning = true
        
        speak("Принято, хозяин. Начинаю выполнять: $instruction")
        runAgentLoop(0)
    }

    private fun runAgentLoop(stepCount: Int) {
        if (!isRunning) return
        if (stepCount >= MAX_STEPS) {
            speak("Задача заняла слишком много шагов. Я останавливаюсь во избежание ошибок.")
            isRunning = false
            return
        }

        val service = MasterAccessibilityService.instance
        if (service == null) {
            speak("Служба специальных возможностей не активна. Включите её в настройках телефона.")
            isRunning = false
            return
        }

        val screenHierarchy = service.dumpScreenHierarchy()
        Log.d(TAG, "Step $stepCount: Screen dumped.")

        brain.decideNextStep(currentInstruction, screenHierarchy, history.toString(), object : BrainService.BrainCallback {
            override fun onSuccess(action: JSONObject) {
                val reasoning = action.optString("reasoning", "")
                val actionType = action.optString("action", "done")
                
                Log.d(TAG, "Gemini action: $actionType. Reasoning: $reasoning")
                history.append("- Step $stepCount: Action=$actionType. Reason: $reasoning\n")

                when (actionType) {
                    "launch_app" -> {
                        val packageName = action.optString("packageName")
                        if (packageName.isNotEmpty()) {
                            launchApp(packageName)
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                runAgentLoop(stepCount + 1)
                            }, 1500)
                        } else {
                            handleFailure("Не указано имя пакета для запуска.")
                        }
                    }
                    "click" -> {
                        val x = action.optDouble("x", -1.0).toFloat()
                        val y = action.optDouble("y", -1.0).toFloat()
                        if (x >= 0 && y >= 0) {
                            service.clickAt(x, y) { success ->
                                if (success) {
                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                        runAgentLoop(stepCount + 1)
                                    }, 1000)
                                } else {
                                    handleFailure("Не удалось симулировать клик в координатах $x, $y")
                                }
                            }
                        } else {
                            handleFailure("Координаты клика отсутствуют.")
                        }
                    }
                    "swipe" -> {
                        val startX = action.optDouble("startX", 0.0).toFloat()
                        val startY = action.optDouble("startY", 0.0).toFloat()
                        val endX = action.optDouble("endX", 0.0).toFloat()
                        val endY = action.optDouble("endY", 0.0).toFloat()
                        val duration = action.optLong("duration", 300)
                        service.swipe(startX, startY, endX, endY, duration) { success ->
                            if (success) {
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                    runAgentLoop(stepCount + 1)
                                }, 1200)
                            } else {
                                handleFailure("Свайп не удался.")
                            }
                        }
                    }
                    "type" -> {
                        val text = action.optString("text")
                        val focusedNode = service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                        if (focusedNode != null) {
                            val arguments = android.os.Bundle()
                            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                            focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                runAgentLoop(stepCount + 1)
                            }, 800)
                        } else {
                            handleFailure("Фокус ввода текста не найден на экране.")
                        }
                    }
                    "speak" -> {
                        val text = action.optString("text")
                        speak(text)
                        isRunning = false
                    }
                    "wait" -> {
                        val waitMs = action.optLong("ms", 1000)
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            runAgentLoop(stepCount + 1)
                        }, waitMs)
                    }
                    "done" -> {
                        val text = action.optString("text", "Готово, хозяин! Задача выполнена.")
                        speak(text)
                        isRunning = false
                    }
                    else -> {
                        handleFailure("Неизвестное действие: $actionType")
                    }
                }
            }

            override fun onFailure(error: String) {
                handleFailure("Ошибка связи с мозгом: $error")
            }
        })
    }

    private fun launchApp(packageName: String) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
        } else {
            Log.e(TAG, "Application package $packageName not found or has no launch intent")
        }
    }

    private fun handleFailure(errorMessage: String) {
        speak(errorMessage)
        isRunning = false
        Log.e(TAG, errorMessage)
    }

    private fun speak(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "UtteranceID")
    }
}
