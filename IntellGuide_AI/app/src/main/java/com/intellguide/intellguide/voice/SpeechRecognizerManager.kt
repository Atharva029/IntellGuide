package com.intellguide.intellguide.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * Manages Speech-To-Text (STT) using Android's native SpeechRecognizer.
 * Crash-proof and safe across all OEM Android devices.
 */
class SpeechRecognizerManager(
    private val context: Context,
    private val onReadyForSpeech: () -> Unit = {},
    private val onRmsChanged: (Float) -> Unit = {},
    private val onPartialResult: (String) -> Unit = {},
    private val onFinalResult: (String) -> Unit = {},
    private val onErrorOccurred: (String) -> Unit = {},
    private val onEndOfSpeech: () -> Unit = {}
) {

    private val tag = "SpeechRecognizerManager"
    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    var isListening: Boolean = false
        private set

    init {
        mainHandler.post {
            initializeRecognizer()
        }
    }

    private fun initializeRecognizer() {
        try {
            if (speechRecognizer != null) {
                try {
                    speechRecognizer?.destroy()
                } catch (ignored: Throwable) {}
                speechRecognizer = null
            }

            if (SpeechRecognizer.isRecognitionAvailable(context)) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(createRecognitionListener())
                }
                Log.d(tag, "SpeechRecognizer created successfully.")
            } else {
                Log.w(tag, "Speech recognition is not available on this device.")
                onErrorOccurred("Speech recognition service not found on device.")
            }
        } catch (t: Throwable) {
            Log.e(tag, "Error initializing SpeechRecognizer: ${t.message}", t)
            onErrorOccurred("Unable to initialize speech recognizer: ${t.localizedMessage}")
        }
    }

    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(tag, "Ready for speech input.")
                isListening = true
                mainHandler.post { onReadyForSpeech() }
            }

            override fun onBeginningOfSpeech() {
                Log.d(tag, "User began speaking.")
            }

            override fun onRmsChanged(rmsdB: Float) {
                val normalized = ((rmsdB + 2f) / 12f).coerceIn(0.05f, 1.0f)
                mainHandler.post { onRmsChanged(normalized) }
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d(tag, "End of speech detected.")
                isListening = false
                mainHandler.post { onEndOfSpeech() }
            }

            override fun onError(error: Int) {
                isListening = false
                val errorMessage = getErrorDescription(error)
                Log.e(tag, "SpeechRecognizer Error ($error): $errorMessage")
                mainHandler.post { onErrorOccurred(errorMessage) }
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val spokenText = matches?.firstOrNull()?.trim() ?: ""
                Log.d(tag, "Final Speech Result: '$spokenText'")
                mainHandler.post {
                    if (spokenText.isNotEmpty()) {
                        onFinalResult(spokenText)
                    } else {
                        onErrorOccurred("No speech detected.")
                    }
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val partialText = matches?.firstOrNull()?.trim() ?: ""
                if (partialText.isNotEmpty()) {
                    mainHandler.post { onPartialResult(partialText) }
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    /**
     * Starts listening for user's voice input.
     */
    fun startListening() {
        mainHandler.post {
            try {
                if (speechRecognizer == null) {
                    initializeRecognizer()
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                }

                speechRecognizer?.startListening(intent)
                isListening = true
            } catch (t: Throwable) {
                Log.e(tag, "Failed to start listening", t)
                isListening = false
                onErrorOccurred("Microphone error: ${t.localizedMessage ?: "Unknown error"}")
            }
        }
    }

    /**
     * Stops listening explicitly.
     */
    fun stopListening() {
        mainHandler.post {
            try {
                if (isListening) {
                    speechRecognizer?.stopListening()
                    isListening = false
                }
            } catch (t: Throwable) {
                Log.e(tag, "Error stopping SpeechRecognizer", t)
            }
        }
    }

    /**
     * Releases recognizer resources safely.
     */
    fun destroy() {
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = null
                isListening = false
            } catch (t: Throwable) {
                Log.e(tag, "Error destroying SpeechRecognizer", t)
            }
        }
    }

    private fun getErrorDescription(errorCode: Int): String {
        return when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Speech recognition cancelled"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required"
            SpeechRecognizer.ERROR_NETWORK -> "Network connection error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No voice recognized"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer is busy"
            SpeechRecognizer.ERROR_SERVER -> "Server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input detected"
            else -> "Speech recognition error ($errorCode)"
        }
    }
}
