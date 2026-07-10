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
    private var isAwaitingCommand = false // true = режим приёма команды после вейк-ворда
    private var audioManager: AudioManager? = null
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "MasterWakeWord"
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
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
                // Тихо перезапускаем, без звуков и задержек
                if (!isAwaitingCommand) {
                    handler.postDelayed({ startListeningQuiet() }, 300L)
                }
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val text = matches[0].lowercase(Locale.ROOT).trim()
                    Log.d(TAG, "Heard: $text | awaitingCommand=$isAwaitingCommand")

                    if (isAwaitingCommand) {
                        // Это команда пользователя после вейк-ворда
                        isAwaitingCommand = false
                        if (text.isNotEmpty()) {
                            orchestrator?.executeCommand(text)
                        } else {
                            speak("Не расслышал. Попробуй снова.")
                            handler.postDelayed({ startListeningQuiet() }, 300L)
                        }
                    } else {
                        // Проверяем вейк-ворд
                        val hasTrigger = text.contains("мастер") || text.contains("master")
                        if (hasTrigger) {
                            // Проверяем, есть ли команда сразу после вейк-ворда
                            val command = extractCommandAfterTrigger(text)
                            if (command.isNotEmpty()) {
                                // Команда уже в одной фразе: "Мастер, открой телеграм"
                                orchestrator?.executeCommand(command)
                            } else {
                                // Только вейк-ворд — переходим в режим ожидания команды
                                speakThenListen("Слушаю")
                            }
                        } else {
                            handler.postDelayed({ startListeningQuiet() }, 300L)
                        }
                    }
                } else {
                    if (!isAwaitingCommand) {
                        handler.postDelayed({ startListeningQuiet() }, 300L)
                    }
                }
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val apiKey = intent?.getStringExtra("API_KEY") ?: ""
        if (apiKey.isNotEmpty()) {
            tts?.let {
                orchestrator = BrainOrchestrator(applicationContext, apiKey, it) {
                    // Когда оркестратор заканчивает, возвращаемся к тихому прослушиванию
                    handler.postDelayed({ startListeningQuiet() }, 800L)
                }
            }
        }
        startListeningQuiet()
        return START_STICKY
    }

    // Запуск без системных звуков
    private fun startListeningQuiet() {
        if (isListening) return
        // Заглушаем системный звук старта/стопа микрофона
        val currentVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
        audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)

        isListening = true
        isAwaitingCommand = false
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        }
        speechRecognizer?.startListening(intent)

        // Восстанавливаем громкость через 300мс
        handler.postDelayed({
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, currentVolume, 0)
        }, 300L)
    }

    // Запуск для приёма команды (чуть дольше тишина, другой промпт)
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
        speechRecognizer?.startListening(intent)
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
            tts?.setSpeechRate(1.1f) // Чуть быстрее стандартного
            tts?.setPitch(1.0f)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        tts?.shutdown()
        handler.removeCallbacksAndMessages(null)
    }
}
