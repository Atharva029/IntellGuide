package com.intellguide.saarthi

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.intellguide.saarthi.ui.Screen
import com.intellguide.saarthi.ui.VoiceViewModel
import com.intellguide.saarthi.ui.screens.CameraPreviewScreen
import com.intellguide.saarthi.ui.screens.CurrencyDetectorScreen
import com.intellguide.saarthi.ui.screens.DashboardScreen
import com.intellguide.saarthi.ui.screens.ModuleDetailScreen
import com.intellguide.saarthi.ui.screens.VoiceScreen
import com.intellguide.saarthi.ui.theme.SaarthiTheme

class MainActivity : ComponentActivity() {

    private val voiceViewModel: VoiceViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            SaarthiTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var hasMicPermission by remember {
                        mutableStateOf(
                            ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                        )
                    }

                    var hasCameraPermission by remember {
                        mutableStateOf(
                            ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED
                        )
                    }

                    val micPermissionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestPermission()
                    ) { isGranted ->
                        hasMicPermission = isGranted
                        if (isGranted) {
                            voiceViewModel.speakWelcomeGreeting()
                        } else {
                            voiceViewModel.speakFeedback(
                                "Microphone permission is required for voice interaction. Please grant permission."
                            )
                        }
                    }

                    val cameraPermissionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestPermission()
                    ) { isGranted ->
                        hasCameraPermission = isGranted
                        if (isGranted) {
                            voiceViewModel.speakFeedback("Camera permission granted. Starting live environmental perception.")
                        } else {
                            voiceViewModel.speakFeedback("Camera permission is required to analyze your surroundings.")
                        }
                    }

                    LaunchedEffect(Unit) {
                        if (!hasMicPermission) {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            voiceViewModel.speakWelcomeGreeting()
                        }
                    }

                    val currentScreen by voiceViewModel.currentScreen.collectAsState()

                    // Back Navigation Logic:
                    // Modules -> Dashboard
                    // Dashboard -> VoiceWelcome
                    // VoiceWelcome -> Exits
                    when (currentScreen) {
                        Screen.HomeDashboard -> {
                            BackHandler {
                                voiceViewModel.navigateTo(Screen.VoiceWelcome, "Returned to Voice Assistant.")
                            }
                        }
                        Screen.VoiceWelcome -> {
                            // Default system behavior (exits)
                        }
                        else -> {
                            BackHandler {
                                voiceViewModel.navigateTo(Screen.HomeDashboard, "Returned to Home Dashboard.")
                            }
                        }
                    }

                    Crossfade(targetState = currentScreen, label = "ScreenTransition") { screen ->
                        when (screen) {
                            Screen.VoiceWelcome -> {
                                VoiceScreen(
                                    viewModel = voiceViewModel,
                                    hasMicPermission = hasMicPermission,
                                    onRequestPermission = {
                                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                )
                            }

                            Screen.HomeDashboard -> {
                                DashboardScreen(
                                    viewModel = voiceViewModel,
                                    hasMicPermission = hasMicPermission,
                                    onRequestMicPermission = {
                                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    },
                                    onNavigateTo = { targetScreen ->
                                        voiceViewModel.navigateTo(targetScreen)
                                    }
                                )
                            }

                            Screen.LiveCamera -> {
                                CameraPreviewScreen(
                                    viewModel = voiceViewModel,
                                    hasCameraPermission = hasCameraPermission,
                                    hasMicPermission = hasMicPermission,
                                    onRequestCameraPermission = {
                                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                    },
                                    onRequestMicPermission = {
                                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    },
                                    onBack = {
                                        voiceViewModel.navigateTo(Screen.HomeDashboard, "Returned to Home Dashboard.")
                                    }
                                )
                            }

                            Screen.OcrReader -> {
                                ModuleDetailScreen(
                                    title = "Read Text (OCR)",
                                    subtitle = "Reads signs, notice boards, documents, and labels",
                                    icon = Icons.Default.TextFields,
                                    accentColor = Color(0xFF8B5CF6),
                                    statusText = "Text recognition engine ready. Point camera at text.",
                                    viewModel = voiceViewModel,
                                    onBack = {
                                        voiceViewModel.navigateTo(Screen.HomeDashboard, "Returned to Home Dashboard.")
                                    }
                                )
                            }

                            Screen.CurrencyDetector -> {
                                CurrencyDetectorScreen(
                                    viewModel = voiceViewModel,
                                    hasCameraPermission = hasCameraPermission,
                                    hasMicPermission = hasMicPermission,
                                    onRequestCameraPermission = {
                                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                    },
                                    onRequestMicPermission = {
                                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    },
                                    onBack = {
                                        voiceViewModel.navigateTo(Screen.HomeDashboard, "Returned to Home Dashboard.")
                                    }
                                )
                            }

                            Screen.DailyRoutes -> {
                                ModuleDetailScreen(
                                    title = "Daily Routes",
                                    subtitle = "Learned frequent paths and repeated journeys",
                                    icon = Icons.Default.AltRoute,
                                    accentColor = Color(0xFFEC4899),
                                    statusText = "Route tracker initialized. GPS checkpoint active.",
                                    viewModel = voiceViewModel,
                                    onBack = {
                                        voiceViewModel.navigateTo(Screen.HomeDashboard, "Returned to Home Dashboard.")
                                    }
                                )
                            }

                            Screen.EmergencySos -> {
                                ModuleDetailScreen(
                                    title = "Emergency SOS",
                                    subtitle = "Fast location broadcast and emergency contact alert",
                                    icon = Icons.Default.Sos,
                                    accentColor = Color(0xFFEF4444),
                                    statusText = "Emergency channel active. Location ready to broadcast.",
                                    viewModel = voiceViewModel,
                                    onBack = {
                                        voiceViewModel.navigateTo(Screen.HomeDashboard, "Returned to Home Dashboard.")
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
