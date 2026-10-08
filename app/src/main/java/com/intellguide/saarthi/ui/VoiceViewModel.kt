package com.intellguide.saarthi.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.intellguide.saarthi.emergency.data.EmergencyContactEntity
import com.intellguide.saarthi.emergency.data.EmergencyRepository
import com.intellguide.saarthi.emergency.sos.AvailableContact
import com.intellguide.saarthi.emergency.sos.SosCallResult
import com.intellguide.saarthi.emergency.sos.SosManager
import com.intellguide.saarthi.voice.CommandProcessor
import com.intellguide.saarthi.voice.CommandResult
import com.intellguide.saarthi.voice.SpeechRecognizerManager
import com.intellguide.saarthi.voice.TTSManager
import com.intellguide.saarthi.voice.VoiceIntent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
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

    // Emergency Data Repository and SOS Manager
    private val emergencyRepository = EmergencyRepository(application)
    private val sosManager = SosManager(application)

    // SOS Contact Selection State
    private var sosAwaitingContacts: List<AvailableContact> = emptyList()
    private var pendingContactToCall: AvailableContact? = null

    // Channel for requesting CALL_PHONE permission from Activity
    private val _requestCallPermissionEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val requestCallPermissionEvent: SharedFlow<Unit> = _requestCallPermissionEvent.asSharedFlow()

    val emergencyRegistration: StateFlow<EmergencyContactEntity?> =
        emergencyRepository.emergencyContactFlow.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

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
                val msg = if (sosAwaitingContacts.isNotEmpty()) {
                    if (sosAwaitingContacts.size == 1) "Listening... Say ${sosAwaitingContacts[0].name}"
                    else "Listening... Say ${sosAwaitingContacts[0].name} or ${sosAwaitingContacts[1].name}"
                } else {
                    "Listening... Speak your command"
                }
                _statusMessage.value = msg
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
                _rmsLevel.value = 0.1f
                if (sosAwaitingContacts.isNotEmpty()) {
                    _uiState.value = VoiceUiState.ERROR
                    val retryPrompt = if (sosAwaitingContacts.size == 1) {
                        "I did not hear a name. Please say ${sosAwaitingContacts[0].name}."
                    } else {
                        "I did not hear a name. Please say ${sosAwaitingContacts[0].name} or ${sosAwaitingContacts[1].name}."
                    }
                    _statusMessage.value = retryPrompt
                    speakFeedback(retryPrompt) {
                        startListeningForSosContact()
                    }
                } else {
                    _uiState.value = VoiceUiState.ERROR
                    _statusMessage.value = error
                    speakFeedback("I could not hear any speech. Please tap the button to try again.")
                }
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

        // If currently in SOS contact selection mode, route speech directly to contact matching
        if (sosAwaitingContacts.isNotEmpty()) {
            handleSosContactSelection(spokenText)
            return
        }

        val result: CommandResult = CommandProcessor.process(spokenText)
        _lastIntent.value = result.intent
        _spokenResponse.value = result.spokenResponse
        _statusMessage.value = result.actionTitle

        // Automatically transition screen based on voice intent
        when (result.intent) {
            VoiceIntent.GO_HOME -> {
                _currentScreen.value = Screen.HomeDashboard
                speakFeedback(result.spokenResponse)
            }
            VoiceIntent.START_NAVIGATION, VoiceIntent.DESCRIBE_SCENE -> {
                _currentScreen.value = Screen.LiveCamera
                speakFeedback(result.spokenResponse)
            }
            VoiceIntent.READ_TEXT -> {
                _currentScreen.value = Screen.OcrReader
                speakFeedback(result.spokenResponse)
            }
            VoiceIntent.IDENTIFY_CURRENCY -> {
                _currentScreen.value = Screen.CurrencyDetector
                speakFeedback(result.spokenResponse)
            }
            VoiceIntent.DAILY_ROUTES -> {
                _currentScreen.value = Screen.DailyRoutes
                speakFeedback(result.spokenResponse)
            }
            VoiceIntent.EMERGENCY_SOS -> {
                _currentScreen.value = Screen.EmergencySos
                triggerEmergencySos()
            }
            VoiceIntent.VOICE_MODE -> {
                _currentScreen.value = Screen.VoiceWelcome
                speakFeedback(result.spokenResponse)
            }
            VoiceIntent.GREETING, VoiceIntent.STOP_NAVIGATION, VoiceIntent.UNKNOWN -> {
                speakFeedback(result.spokenResponse)
            }
        }
    }

    /**
     * Handles voice selection of emergency contact name.
     */
    private fun handleSosContactSelection(spokenText: String) {
        val normalized = spokenText.lowercase().trim().replace(Regex("[^a-z0-9\\s]"), "")

        // Allow user to cancel SOS by voice
        if (normalized == "cancel" || normalized == "stop" || normalized == "exit" ||
            normalized == "go back" || normalized == "abort" || normalized == "cancel emergency"
        ) {
            sosAwaitingContacts = emptyList()
            pendingContactToCall = null
            _statusMessage.value = "Emergency cancelled"
            speakFeedback("Emergency mode cancelled.")
            return
        }

        val matched = sosManager.matchContact(spokenText, sosAwaitingContacts)
        if (matched != null) {
            // Valid registered contact recognized -> initiate direct phone call
            sosAwaitingContacts = emptyList()
            executeDirectEmergencyCall(matched)
        } else {
            // Unregistered contact or unknown speech
            val candidate = sosManager.extractCandidateName(spokenText)
            val errorMsg = if (candidate.isNotBlank() && candidate.length <= 30) {
                if (sosAwaitingContacts.size == 1) {
                    "$candidate is not registered as an emergency contact. Please say ${sosAwaitingContacts[0].name}."
                } else {
                    "$candidate is not registered as an emergency contact. Please say ${sosAwaitingContacts[0].name} or ${sosAwaitingContacts[1].name}."
                }
            } else {
                if (sosAwaitingContacts.size == 1) {
                    "Contact not recognized. Please say ${sosAwaitingContacts[0].name}."
                } else {
                    "Contact not recognized. Please say ${sosAwaitingContacts[0].name} or ${sosAwaitingContacts[1].name}."
                }
            }
            _statusMessage.value = errorMsg
            speakFeedback(errorMsg) {
                startListeningForSosContact()
            }
        }
    }

    /**
     * Triggers Emergency SOS calling sequence:
     * 1. Retrieves registered emergency contacts from Room DB.
     * 2. Speaks available contact names via TTS.
     * 3. Automatically activates speech recognition to listen for contact name without touch.
     */
    fun triggerEmergencySos() {
        viewModelScope.launch {
            _currentScreen.value = Screen.EmergencySos
            val contacts = sosManager.getAvailableContacts()
            if (contacts.isEmpty()) {
                sosAwaitingContacts = emptyList()
                val msg = "No emergency contacts are registered."
                _statusMessage.value = msg
                speakFeedback(msg)
                return@launch
            }

            sosAwaitingContacts = contacts

            val announcement: String
            val statusPrompt: String

            if (contacts.size == 1) {
                val c1 = contacts[0]
                announcement = "Emergency mode activated. Your emergency contact is ${c1.name}. Say ${c1.name} to call."
                statusPrompt = "Say ${c1.name} to call"
            } else {
                val c1 = contacts[0]
                val c2 = contacts[1]
                announcement = "Emergency mode activated. Who do you want to call? ${c1.name} or ${c2.name}."
                statusPrompt = "Say ${c1.name} or ${c2.name}"
            }

            _statusMessage.value = statusPrompt
            speakFeedback(announcement) {
                startListeningForSosContact()
            }
        }
    }

    private fun startListeningForSosContact() {
        if (sosAwaitingContacts.isEmpty()) return
        _uiState.value = VoiceUiState.LISTENING
        _recognizedText.value = ""
        val statusPrompt = if (sosAwaitingContacts.size == 1) {
            "Listening... Say ${sosAwaitingContacts[0].name}"
        } else {
            "Listening... Say ${sosAwaitingContacts[0].name} or ${sosAwaitingContacts[1].name}"
        }
        _statusMessage.value = statusPrompt
        speechRecognizerManager?.startListening()
    }

    /**
     * Executes direct phone call using ACTION_CALL without dial pad or manual confirmation.
     */
    fun executeDirectEmergencyCall(contact: AvailableContact) {
        val hasPermission = ContextCompat.checkSelfPermission(
            getApplication(),
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            _statusMessage.value = "Calling ${contact.name}..."
            speakFeedback("Calling ${contact.name}.") {
                val success = sosManager.makeDirectCall(contact.phoneNumber)
                if (!success) {
                    _statusMessage.value = "Unable to connect phone call"
                    speakFeedback("Unable to connect phone call to ${contact.name}.")
                }
            }
        } else {
            // Permission not granted: request runtime permission and explain
            pendingContactToCall = contact
            _statusMessage.value = "Phone call permission required"
            speakFeedback("Phone call permission is required for emergency calling. Please grant permission.") {
                _requestCallPermissionEvent.tryEmit(Unit)
            }
        }
    }

    /**
     * Called when runtime CALL_PHONE permission result is received from Activity.
     */
    fun onCallPermissionResult(isGranted: Boolean) {
        val pending = pendingContactToCall
        pendingContactToCall = null

        if (isGranted) {
            if (pending != null) {
                executeDirectEmergencyCall(pending)
            } else {
                speakFeedback("Direct phone call permission granted.")
            }
        } else {
            _statusMessage.value = "Phone call permission denied"
            speakFeedback("Phone call permission was denied. Cannot place emergency call without permission.")
        }
    }

    /**
     * Direct call helper for Contact 1.
     */
    fun callContact1Direct() {
        val contact = emergencyRegistration.value
        if (contact != null && EmergencyRepository.isValidPhoneNumber(contact.contact1Phone) && contact.contact1Name.isNotBlank()) {
            val contactInfo = AvailableContact(
                name = contact.contact1Name.trim(),
                phoneNumber = EmergencyRepository.cleanPhoneNumber(contact.contact1Phone),
                contactIndex = 1
            )
            executeDirectEmergencyCall(contactInfo)
        } else {
            speakFeedback("Primary contact number is invalid or missing.")
        }
    }

    /**
     * Direct call helper for Contact 2.
     */
    fun callContact2Direct() {
        val contact = emergencyRegistration.value
        if (contact != null && EmergencyRepository.isValidPhoneNumber(contact.contact2Phone) && contact.contact2Name.isNotBlank()) {
            val contactInfo = AvailableContact(
                name = contact.contact2Name.trim(),
                phoneNumber = EmergencyRepository.cleanPhoneNumber(contact.contact2Phone),
                contactIndex = 2
            )
            executeDirectEmergencyCall(contactInfo)
        } else {
            speakFeedback("Secondary contact number is invalid or missing.")
        }
    }

    /**
     * Saves or updates emergency contact registration in Room Database.
     */
    fun saveEmergencyRegistration(
        userName: String,
        c1Name: String,
        c1Phone: String,
        c2Name: String,
        c2Phone: String,
        onResult: (Result<Unit>) -> Unit
    ) {
        viewModelScope.launch {
            val result = emergencyRepository.saveEmergencyContact(
                userName = userName,
                c1Name = c1Name,
                c1Phone = c1Phone,
                c2Name = c2Name,
                c2Phone = c2Phone
            )

            if (result.isSuccess) {
                speakFeedback("Emergency registration saved successfully.")
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: "Validation failed"
                speakFeedback("Registration error: $errorMsg")
            }

            onResult(result)
        }
    }

    fun speakFeedback(textToSpeak: String, onDone: (() -> Unit)? = null) {
        viewModelScope.launch {
            _spokenResponse.value = textToSpeak
            ttsManager?.speak(
                text = textToSpeak,
                onStart = {
                    _uiState.value = VoiceUiState.SPEAKING
                },
                onDone = {
                    _uiState.value = VoiceUiState.IDLE
                    if (sosAwaitingContacts.isEmpty()) {
                        _statusMessage.value = "Tap the mic button to speak"
                    }
                    _rmsLevel.value = 0.1f
                    onDone?.invoke()
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
