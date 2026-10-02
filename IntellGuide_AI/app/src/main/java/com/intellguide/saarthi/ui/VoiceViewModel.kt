package com.intellguide.saarthi.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.intellguide.saarthi.voice.CommandProcessor
import com.intellguide.saarthi.voice.CommandResult
import com.intellguide.saarthi.voice.SpeechRecognizerManager
import com.intellguide.saarthi.voice.TTSManager
import com.intellguide.saarthi.voice.VoiceIntent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Visual states for the voice interaction.
 */
enum class VoiceUiState {
    IDLE,        // Ready, waiting for user tap
    LISTENING,   // Mic is actively capturing audio
    PROCESSING,  // Analyzing speech and matching intent
    SPEAKING,    // Saarthi is speaking response via TTS
    ERROR        // Error occurred (e.g. no speech / permission denied)
}

class VoiceViewModel(application: Application) : AndroidViewModel(application) {

    // App launches into Screen.VoiceWelcome (Entry Voice UI)
    private val _currentScreen = MutableStateFlow<Screen>(Screen.VoiceWelcome)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    private val _uiState = MutableStateFlow(VoiceUiState.IDLE)
    val uiState: StateFlow<VoiceUiState> = _uiState.asStateFlow()

    private val _statusMessage = MutableStateFlow("Tap the mic button to speak")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _recognizedText = MutableStateFlow("")
    val recognizedText: StateFlow<String> = _recognizedText.asStateFlow()

    private val _spokenResponse = MutableStateFlow("")
    val spokenResponse: StateFlow<String> = _spokenResponse.asStateFlow()

    private val _rmsLevel = MutableStateFlow(0.1f)
    val rmsLevel: StateFlow<Float> = _rmsLevel.asStateFlow()

    private val _lastIntent = MutableStateFlow<VoiceIntent?>(null)
    val lastIntent: StateFlow<VoiceIntent?> = _lastIntent.asStateFlow()

    private var ttsManager: TTSManager? = null
    private var speechRecognizerManager: SpeechRecognizerManager? = null
    private var isTtsReady = false

    init {
        initializeManagers()
    }

    private fun initializeManagers() {
        ttsManager = TTSManager(getApplication()) { success ->
            isTtsReady = success
        }

        speechRecognizerManager = SpeechRecognizerManager(
            context = getApplication(),
            onReadyForSpeech = {
                _uiState.value = VoiceUiState.LISTENING
                _statusMessage.value = "Listening... Speak your command"
                _recognizedText.value = ""
            },
            onRmsChanged = { rms ->
                _rmsLevel.value = rms
            },
            onPartialResult = { partial ->
                _recognizedText.value = partial
            },
            onFinalResult = { finalSpokenText ->
                handleSpeechResult(finalSpokenText)
            },
            onErrorOccurred = { error ->
                _uiState.value = VoiceUiState.ERROR
                _statusMessage.value = error
                _rmsLevel.value = 0.1f
                speakFeedback("I could not hear any speech. Please tap the button to try again.")
            },
            onEndOfSpeech = {
                _uiState.value = VoiceUiState.PROCESSING
                _statusMessage.value = "Processing your command..."
                _rmsLevel.value = 0.1f
            }
        )
    }

    /**
     * Changes current application screen and announces via TTS.
     */
    fun navigateTo(screen: Screen, spokenAnnouncement: String? = null) {
        _currentScreen.value = screen
        if (!spokenAnnouncement.isNullOrEmpty()) {
            speakFeedback(spokenAnnouncement)
        }
    }

    /**
     * Triggered when the user taps the microphone button.
     */
    fun onMicButtonClicked() {
        when (_uiState.value) {
            VoiceUiState.IDLE, VoiceUiState.ERROR -> {
                ttsManager?.stop()
                _recognizedText.value = ""
                _spokenResponse.value = ""
                _statusMessage.value = "Starting microphone..."
                speechRecognizerManager?.startListening()
            }
            VoiceUiState.LISTENING -> {
                speechRecognizerManager?.stopListening()
            }
            VoiceUiState.SPEAKING -> {
                ttsManager?.stop()
                _uiState.value = VoiceUiState.IDLE
                _statusMessage.value = "Tap the mic button to speak"
            }
            VoiceUiState.PROCESSING -> {}
        }
    }

    /**
     * Speaks initial onboarding greeting on app launch.
     */
    fun speakWelcomeGreeting() {
        speakFeedback("Hello! Welcome to Saarthi. How may I help you today? Tap the microphone button to give a command, or say 'Go to dashboard'.")
    }

    private fun handleSpeechResult(spokenText: String) {
        _uiState.value = VoiceUiState.PROCESSING
        _recognizedText.value = spokenText

        val result: CommandResult = CommandProcessor.process(spokenText)
        _lastIntent.value = result.intent
        _spokenResponse.value = result.spokenResponse
        _statusMessage.value = result.actionTitle

        // Automatically transition screen based on voice intent
        when (result.intent) {
            VoiceIntent.GO_HOME -> {
                _currentScreen.value = Screen.HomeDashboard
            }
            VoiceIntent.START_NAVIGATION, VoiceIntent.DESCRIBE_SCENE -> {
                _currentScreen.value = Screen.LiveCamera
            }
            VoiceIntent.READ_TEXT -> {
                _currentScreen.value = Screen.OcrReader
            }
            VoiceIntent.IDENTIFY_CURRENCY -> {
                _currentScreen.value = Screen.CurrencyDetector
            }
            VoiceIntent.DAILY_ROUTES -> {
                _currentScreen.value = Screen.DailyRoutes
            }
            VoiceIntent.EMERGENCY_SOS -> {
                _currentScreen.value = Screen.EmergencySos
            }
            VoiceIntent.VOICE_MODE -> {
                _currentScreen.value = Screen.VoiceWelcome
            }
            VoiceIntent.GREETING, VoiceIntent.STOP_NAVIGATION, VoiceIntent.UNKNOWN -> {
                // Keep on current screen
            }
        }

        speakFeedback(result.spokenResponse)
    }

    fun speakFeedback(textToSpeak: String) {
        viewModelScope.launch {
            _spokenResponse.value = textToSpeak
            ttsManager?.speak(
                text = textToSpeak,
                onStart = {
                    _uiState.value = VoiceUiState.SPEAKING
                },
                onDone = {
                    _uiState.value = VoiceUiState.IDLE
                    _statusMessage.value = "Tap the mic button to speak"
                    _rmsLevel.value = 0.1f
                }
            )
        }
    }

    fun simulateCommand(commandText: String) {
        ttsManager?.stop()
        handleSpeechResult(commandText)
    }

    override fun onCleared() {
        super.onCleared()
        speechRecognizerManager?.destroy()
        ttsManager?.shutdown()
    }
}
