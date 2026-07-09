package com.master.agent

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class WakeWordService : Service(), TextToSpeech.OnInitListener {

    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizerIntent: Intent? = null
    private var tts: TextToSpeech? = null
    private var orchestrator: BrainOrchestrator? = null
    private var isListening = false

    companion object {
        private const val TAG = "MasterWakeWord"
    }

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
        
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale("ru").toString())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            
            override fun onEndOfSpeech() {
                isListening = false
            }

            override fun onError(error: Int) {
                isListening = false
                restartListening()
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val text = matches[0].lowercase(Locale.ROOT)
                    Log.d(TAG, "Recognized speech: $text")

                    if (text.contains("мастер") || text.contains("master")) {
                        val command = extractCommand(text)
                        if (command.isNotEmpty()) {
                            triggerOrchestrator(command)
                        } else {
                            tts?.speak("Слушаю вас, хозяин", TextToSpeech.QUEUE_FLUSH, null, "TriggerID")
                        }
                    }
                }
                restartListening()
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val apiKey = intent?.getStringExtra("API_KEY") ?: ""
        if (apiKey.isNotEmpty()) {
            tts?.let {
                orchestrator = BrainOrchestrator(applicationContext, apiKey, it)
            }
        }
        startListening()
        return START_STICKY
    }

    private fun startListening() {
        if (!isListening) {
            isListening = true
            speechRecognizer?.startListening(recognizerIntent)
        }
    }

    private fun restartListening() {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            startListening()
        }, 500)
    }

    private fun extractCommand(text: String): String {
        val triggerRussian = "мастер"
        val triggerEnglish = "master"
        
        var index = text.indexOf(triggerRussian)
        if (index != -1) {
            return text.substring(index + triggerRussian.length).trim()
        }
        
        index = text.indexOf(triggerEnglish)
        if (index != -1) {
            return text.substring(index + triggerEnglish.length).trim()
        }
        return ""
    }

    private fun triggerOrchestrator(command: String) {
        orchestrator?.executeCommand(command)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("ru")
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        tts?.shutdown()
    }
}
