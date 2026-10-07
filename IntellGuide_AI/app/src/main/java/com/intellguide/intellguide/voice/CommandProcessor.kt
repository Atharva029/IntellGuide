package com.intellguide.intellguide.voice

/**
 * Enumeration of all recognized voice command intents across IntellGuide modules.
 */
enum class VoiceIntent {
    GREETING,
    START_NAVIGATION,
    STOP_NAVIGATION,
    DESCRIBE_SCENE,
    READ_TEXT,
    IDENTIFY_CURRENCY,
    DAILY_ROUTES,
    EMERGENCY_SOS,
    GO_HOME,
    VOICE_MODE,
    UNKNOWN
}

/**
 * Result returned after intent parsing.
 * @param intent The identified intent enum.
 * @param rawText The raw transcribed spoken text.
 * @param spokenResponse Natural language text that IntellGuide will speak back via TTS.
 * @param actionTitle Short description of the triggered action.
 */
data class CommandResult(
    val intent: VoiceIntent,
    val rawText: String,
    val spokenResponse: String,
    val actionTitle: String
)

/**
 * Intelligent Intent Processor for IntellGuide AI.
 * Uses flexible semantic keyword matching to understand natural variations of commands.
 */
object CommandProcessor {

    fun process(spokenText: String): CommandResult {
        val normalized = spokenText.lowercase().trim().replace(Regex("[^a-z0-9\\s]"), "")

        return when {
            // 1. Navigation to Dashboard / Home
            isGoHome(normalized) -> CommandResult(
                intent = VoiceIntent.GO_HOME,
                rawText = spokenText,
                spokenResponse = "Opening Home Dashboard.",
                actionTitle = "Home Dashboard Opened"
            )

            // 2. Emergency SOS
            isEmergency(normalized) -> CommandResult(
                intent = VoiceIntent.EMERGENCY_SOS,
                rawText = spokenText,
                spokenResponse = "Emergency alert triggered. Sending your location to your emergency contacts.",
                actionTitle = "Emergency SOS Dispatched"
            )

            // 3. Indian Currency Recognition
            isCurrency(normalized) -> CommandResult(
                intent = VoiceIntent.IDENTIFY_CURRENCY,
                rawText = spokenText,
                spokenResponse = "Currency recognition mode activated. Hold the note steadily in front of the camera.",
                actionTitle = "Currency Mode Active"
            )

            // 4. Text Reading / OCR
            isOCR(normalized) -> CommandResult(
                intent = VoiceIntent.READ_TEXT,
                rawText = spokenText,
                spokenResponse = "Text reading mode activated. Point the camera towards the text or document.",
                actionTitle = "OCR Text Reader Active"
            )

            // 5. Scene Understanding / What is in front of me / Live Camera
            isSceneDescription(normalized) -> CommandResult(
                intent = VoiceIntent.DESCRIBE_SCENE,
                rawText = spokenText,
                spokenResponse = "Looking ahead. Camera detection started to scan your surroundings.",
                actionTitle = "Vision & Scene Analysis Active"
            )

            // 6. Navigation Control
            isStartNavigation(normalized) -> CommandResult(
                intent = VoiceIntent.START_NAVIGATION,
                rawText = spokenText,
                spokenResponse = "Navigation started. Scanning your pathway for obstacles.",
                actionTitle = "Navigation Started"
            )

            isStopNavigation(normalized) -> CommandResult(
                intent = VoiceIntent.STOP_NAVIGATION,
                rawText = spokenText,
                spokenResponse = "Navigation stopped.",
                actionTitle = "Navigation Halted"
            )

            // 7. Daily Routes
            isDailyRoutes(normalized) -> CommandResult(
                intent = VoiceIntent.DAILY_ROUTES,
                rawText = spokenText,
                spokenResponse = "Opening your saved daily routes.",
                actionTitle = "Daily Routes Active"
            )

            // 8. Greeting / Hello IntellGuide
            isGreeting(normalized) -> CommandResult(
                intent = VoiceIntent.GREETING,
                rawText = spokenText,
                spokenResponse = "Hello! I am IntellGuide. How can I help you today?",
                actionTitle = "Greeting Received"
            )

            isVoiceMode(normalized) -> CommandResult(
                intent = VoiceIntent.VOICE_MODE,
                rawText = spokenText,
                spokenResponse = "Voice assistant mode ready.",
                actionTitle = "Voice Assistant Mode"
            )

            // 9. Unknown / Fallback
            else -> CommandResult(
                intent = VoiceIntent.UNKNOWN,
                rawText = spokenText,
                spokenResponse = "I didn't quite catch that. You can say 'Go to dashboard', 'Start navigation', 'Live camera', 'Read text', or 'Identify currency'.",
                actionTitle = "Command Not Recognized"
            )
        }
    }

    private fun isGoHome(text: String): Boolean {
        return text.contains("dashboard") || text.contains("go to dashboard") ||
               text.contains("open dashboard") || text.contains("show dashboard") ||
               text.contains("home screen") || text.contains("go to home") ||
               text.contains("open home") || text.contains("main menu") ||
               text == "home" || text == "dashboard"
    }

    private fun isGreeting(text: String): Boolean {
        return text.contains("hello") || text.contains("hi intellguide") || 
               text.contains("hey intellguide") || text.contains("namaste") ||
               text.contains("good morning") || text.contains("good afternoon") ||
               text.contains("good evening") || text == "hi" || text == "intellguide"
    }

    private fun isStartNavigation(text: String): Boolean {
        return (text.contains("start") && text.contains("navigat")) ||
               text.contains("begin navigation") || text.contains("start guide") ||
               text.contains("start guiding") || text.contains("start walk") ||
               text.startsWith("navigate") || text.contains("take me to")
    }

    private fun isStopNavigation(text: String): Boolean {
        return (text.contains("stop") && (text.contains("navigat") || text.contains("guide") || text.contains("walk"))) ||
               text.contains("end navigation") || text.contains("cancel navigation") || text == "stop"
    }

    private fun isSceneDescription(text: String): Boolean {
        return text.contains("front of me") || text.contains("in front") ||
               text.contains("whats ahead") || text.contains("what is ahead") ||
               text.contains("what do you see") || text.contains("describe scene") ||
               text.contains("look around") || text.contains("look ahead") ||
               text.contains("start detection") || text.contains("camera detection") ||
               text.contains("detect object") || text.contains("surroundings") ||
               text.contains("live camera") || text.contains("open camera") ||
               text.contains("camera")
    }

    private fun isOCR(text: String): Boolean {
        return text.contains("read text") || text.contains("read this") ||
               text.contains("read sign") || text.contains("read board") ||
               text.contains("scan text") || text.contains("ocr") ||
               text.contains("read document") || text.contains("read paper") ||
               text == "read"
    }

    private fun isCurrency(text: String): Boolean {
        return text.contains("currency") || text.contains("money") ||
               text.contains("rupee") || text.contains("cash") ||
               text.contains("identify note") || text.contains("check note") ||
               text.contains("detect note") || text.contains("read note") ||
               text.contains("how much money") || text.contains("which note") ||
               text == "currency"
    }

    private fun isDailyRoutes(text: String): Boolean {
        return text.contains("daily route") || text.contains("saved route") ||
               text.contains("frequent route") || text.contains("my route") ||
               text == "routes"
    }

    private fun isEmergency(text: String): Boolean {
        return text.contains("help me") || text.contains("emergency") ||
               text.contains("sos") || text.contains("send help") ||
               text.contains("call for help") || text.contains("danger") ||
               text == "help"
    }

    private fun isVoiceMode(text: String): Boolean {
        return text.contains("voice mode") || text.contains("voice screen") ||
               text.contains("voice assistant") || text.contains("speak mode")
    }
}
