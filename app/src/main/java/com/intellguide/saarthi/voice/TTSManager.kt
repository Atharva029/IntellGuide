package com.intellguide.saarthi.voice

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Manages Text-To-Speech (TTS) engine for Saarthi AI.
 * Converts response strings into natural audible voice output for visually impaired users.
 */
@SuppressLint("NewApi", "LocalSuppress")
class TTSManager(
    private val context: Context,
    private val onInitListener: (Boolean) -> Unit = {}
) : TextToSpeech.OnInitListener {

    private val tag = "TTSManager"
    private var tts: TextToSpeech? = null
    private var isInitialized = false

    private var pendingSpeech: Triple<String, (() -> Unit)?, (() -> Unit)?>? = null

    private var onSpeechStartCallback: (() -> Unit)? = null
    private var onSpeechDoneCallback: (() -> Unit)? = null

    init {
        try {
            tts = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            Log.e(tag, "Error creating TextToSpeech instance", e)
            onInitListener(false)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            try {
                val result = tts?.setLanguage(Locale.US)
                if ((result == TextToSpeech.LANG_MISSING_DATA) || (result == TextToSpeech.LANG_NOT_SUPPORTED)) {
                    Log.w(tag, "Language US is not supported or missing data, falling back to default locale.")
                    tts?.setLanguage(Locale.getDefault())
                }

                tts?.setPitch(1.0f)
                tts?.setSpeechRate(0.95f) // Slightly slower for crisp accessibility clarity

                setupUtteranceListener()
                isInitialized = true
                Log.d(tag, "TextToSpeech successfully initialized.")
                onInitListener(true)

                // Speak any pending speech queued before initialization finished
                pendingSpeech?.let { (text, onStart, onDone) ->
                    pendingSpeech = null
                    speak(text, onStart ?: {}, onDone ?: {})
                }
            } catch (e: Exception) {
                Log.e(tag, "Error configuring TextToSpeech post-init", e)
                isInitialized = false
                onInitListener(false)
            }
        } else {
            Log.e(tag, "Failed to initialize TextToSpeech (status: $status).")
            isInitialized = false
            onInitListener(false)
        }
    }

    private fun setupUtteranceListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                onSpeechStartCallback?.invoke()
            }

            override fun onDone(utteranceId: String?) {
                onSpeechDoneCallback?.invoke()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Log.e(tag, "TTS Utterance error for ID: $utteranceId")
                onSpeechDoneCallback?.invoke()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.e(tag, "TTS Utterance error: $errorCode for ID: $utteranceId")
                onSpeechDoneCallback?.invoke()
            }
        })
    }

    /**
     * Speaks the given text aloud. Queues if TTS is still initializing.
     * @param text The sentence to speak.
     * @param onStart Callback when speaking begins.
     * @param onDone Callback when speaking finishes.
     */
    fun speak(
        text: String,
        onStart: () -> Unit = {},
        onDone: () -> Unit = {}
    ) {
        if (!isInitialized || tts == null) {
            Log.d(tag, "TTS is not initialized yet. Storing speech as pending.")
            pendingSpeech = Triple(text, onStart, onDone)
            return
        }

        this.onSpeechStartCallback = onStart
        this.onSpeechDoneCallback = onDone

        val utteranceId = "Saarthi_Speech_${System.currentTimeMillis()}"
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }

        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        } catch (e: Exception) {
            Log.e(tag, "Error executing speak()", e)
            onDone()
        }
    }

    /**
     * Stops any currently playing audio immediately.
     */
    fun stop() {
        pendingSpeech = null
        if (isInitialized) {
            try {
                tts?.stop()
            } catch (e: Exception) {
                Log.e(tag, "Error stopping TTS", e)
            }
        }
    }

    /**
     * Releases TTS resources when application closes.
     */
    fun shutdown() {
        pendingSpeech = null
        if (tts != null) {
            try {
                tts?.stop()
                tts?.shutdown()
            } catch (e: Exception) {
                Log.e(tag, "Error shutting down TTS", e)
            }
            tts = null
            isInitialized = false
        }
    }
}
