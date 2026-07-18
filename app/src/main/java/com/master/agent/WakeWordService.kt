package com.master.agent

import android.app.Service
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

class WakeWordService : Service(), TextToSpeech.OnInitListener {

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var orchestrator: BrainOrchestrator? = null
    private var isListening = false
    private var isAwaitingCommand = false
    private var audioManager: AudioManager? = null
    
    // Храним оригинальную громкость системного канала
    private var originalSystemVolume = -1
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "MasterWakeWord"
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        
        // Глушим системные звуки (клики, уведомления и БИПЫ Google-распознавателя)
        // пока работает служба голосовой активации. Это скроет бесконечные звуки старта/стопа.
        try {
            originalSystemVolume = audioManager?.getStreamVolume(AudioManager.STREAM_SYSTEM) ?: -1
            audioManager?.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Could not adjust system volume stream", e)
        }

        tts = TextToSpeech(this, this)
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onEndOfSpeech() {
                isListening = false
            }

            override fun onError(error: Int) {
                isListening = false
                // Тихо перезапускаем в случае тайм-аута тишины
                if (!isAwaitingCommand) {
                    handler.postDelayed({ startListeningQuiet() }, 200L)
                }
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val text = matches[0].lowercase(Locale.ROOT).trim()
                    Log.d(TAG, "Heard: $text")

                    if (isAwaitingCommand) {
                        isAwaitingCommand = false
                        if (text.isNotEmpty()) {
                            orchestrator?.executeCommand(text)
                        } else {
                            speak("Не расслышал.")
                            handler.postDelayed({ startListeningQuiet() }, 400L)
                        }
                    } else {
                        val hasTrigger = text.contains("мастер") || text.contains("master")
                        if (hasTrigger) {
                            val command = extractCommandAfterTrigger(text)
                            if (command.isNotEmpty()) {
                                orchestrator?.executeCommand(command)
                            } else {
                                speakThenListen("Слушаю")
                            }
                        } else {
                            handler.postDelayed({ startListeningQuiet() }, 200L)
                        }
                    }
                } else {
                    if (!isAwaitingCommand) {
                        handler.postDelayed({ startListeningQuiet() }, 200L)
                    }
                }
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val apiKey = intent?.getStringExtra("API_KEY") ?: ""
        val apiUrl = intent?.getStringExtra("API_URL") ?: "https://generativelanguage.googleapis.com"
        val modelName = intent?.getStringExtra("MODEL_NAME") ?: "gemini-2.0-flash"
        
        if (apiKey.isNotEmpty()) {
            tts?.let {
                orchestrator = BrainOrchestrator(applicationContext, apiKey, it, apiUrl, modelName) {
                    // Команда выполнена. Возвращаемся в тихий режим локального ожидания "Мастера"
                    handler.postDelayed({ startListeningQuiet() }, 400L)
                }
            }
        }
        startListeningQuiet()
        return START_STICKY
    }

    private fun startListeningQuiet() {
        if (isListening) return
        isListening = true
        isAwaitingCommand = false
        
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        
        try {
            // Принудительно глушим системный звук прямо перед запуском
            audioManager?.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0)
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting voice detection", e)
        }
    }

    private fun startListeningForCommand() {
        if (isListening) return
        isListening = true
        isAwaitingCommand = true
        
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        }
        
        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting command listening", e)
        }
    }

    private fun speakThenListen(text: String) {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onError(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                handler.postDelayed({ startListeningForCommand() }, 100L)
            }
        })
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "WakeReply")
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "SpeakID")
    }

    private fun extractCommandAfterTrigger(text: String): String {
        listOf("мастер", "master").forEach { trigger ->
            val idx = text.indexOf(trigger)
            if (idx != -1) {
                val after = text.substring(idx + trigger.length)
                    .trimStart(',', ' ', '.', '!')
                if (after.length > 2) return after
            }
        }
        return ""
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("ru", "RU")
            tts?.setSpeechRate(1.15f) // Делаем голос быстрее и менее занудным
            tts?.setPitch(1.0f)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        // Восстанавливаем оригинальную громкость системных звуков при выключении службы
        if (originalSystemVolume != -1) {
            try {
                audioManager?.setStreamVolume(AudioManager.STREAM_SYSTEM, originalSystemVolume, 0)
            } catch (e: Exception) {
                Log.e(TAG, "Could not restore system volume stream", e)
            }
        }
        speechRecognizer?.destroy()
        tts?.shutdown()
        handler.removeCallbacksAndMessages(null)
    }
}
