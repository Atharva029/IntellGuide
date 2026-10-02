package com.intellguide.intellguide.ui

/**
 * Represents navigation screens across the IntellGuide application.
 */
sealed class Screen(val route: String) {
    object VoiceWelcome : Screen("voice_welcome")
    object HomeDashboard : Screen("home_dashboard")
    object LiveCamera : Screen("live_camera")
    object OcrReader : Screen("ocr_reader")
    object CurrencyDetector : Screen("currency_detector")
    object DailyRoutes : Screen("daily_routes")
    object EmergencySos : Screen("emergency_sos")
}
