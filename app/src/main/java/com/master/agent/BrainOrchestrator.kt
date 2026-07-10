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
    private val tts: TextToSpeech,
    private val onDoneCallback: (() -> Unit)? = null
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
            speak("Уже выполняю. Подожди.")
            return
        }
        
        currentInstruction = instruction
        history.clear()
        isRunning = true
        
        speak("Принято.")
        runAgentLoop(0)
    }

    private fun runAgentLoop(stepCount: Int) {
        if (!isRunning) return
        if (stepCount >= MAX_STEPS) {
            speak("Потребовалось слишком много шагов. Останавливаюсь.")
            isRunning = false
            onDoneCallback?.invoke()
            return
        }

        val service = MasterAccessibilityService.instance
        if (service == null) {
            speak("Служба специальных возможностей не активна.")
            isRunning = false
            onDoneCallback?.invoke()
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
                            }, 1200L)
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
                                    }, 700L)
                                } else {
                                    handleFailure("Не удалось нажать на элемент.")
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
                                }, 800L)
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
                            }, 600L)
                        } else {
                            handleFailure("Поле ввода не найдено на экране.")
                        }
                    }
                    "speak" -> {
                        val text = action.optString("text")
                        speak(text)
                        isRunning = false
                        onDoneCallback?.invoke()
                    }
                    "wait" -> {
                        val waitMs = action.optLong("ms", 1000)
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            runAgentLoop(stepCount + 1)
                        }, waitMs)
                    }
                    "done" -> {
                        val text = action.optString("text", "Готово.")
                        speak(text)
                        isRunning = false
                        onDoneCallback?.invoke()
                    }
                    else -> {
                        handleFailure("Неизвестное действие: $actionType")
                    }
                }
            }

            override fun onFailure(error: String) {
                handleFailure("Ошибка: $error")
            }
        })
    }

    private fun launchApp(packageName: String) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
        } else {
            Log.e(TAG, "Package $packageName not found")
        }
    }

    private fun handleFailure(errorMessage: String) {
        speak(errorMessage)
        isRunning = false
        onDoneCallback?.invoke()
        Log.e(TAG, errorMessage)
    }

    private fun speak(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "UtteranceID")
    }
}
