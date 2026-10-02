package com.intellguide.saarthi.ui.screens

import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.intellguide.saarthi.camera.CameraManager
import com.intellguide.saarthi.camera.FrameMetrics
import com.intellguide.saarthi.ui.VoiceUiState
import com.intellguide.saarthi.ui.VoiceViewModel
import com.intellguide.saarthi.ui.components.BoundingBoxOverlay
import com.intellguide.saarthi.ui.theme.*
import com.intellguide.saarthi.vision.DetectedObjectInfo
import com.intellguide.saarthi.vision.SpeechAlertDebouncer
import com.intellguide.saarthi.vision.TFLiteObjectDetector

@Composable
fun CameraPreviewScreen(
    viewModel: VoiceViewModel,
    hasCameraPermission: Boolean,
    hasMicPermission: Boolean,
    onRequestCameraPermission: () -> Unit,
    onRequestMicPermission: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var cameraManager by remember { mutableStateOf<CameraManager?>(null) }
    var frameMetrics by remember { mutableStateOf(FrameMetrics()) }
    var detectedObjects by remember { mutableStateOf<List<DetectedObjectInfo>>(emptyList()) }
    var isTorchEnabled by remember { mutableStateOf(false) }

    val objectDetector = remember { TFLiteObjectDetector(context) }
    val speechDebouncer = remember { SpeechAlertDebouncer(cooldownDurationMs = 4000L) }

    val uiState by viewModel.uiState.collectAsState()

    DisposableEffect(Unit) {
        onDispose {
            cameraManager?.stopCamera()
            objectDetector.close()
            speechDebouncer.reset()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        if (hasCameraPermission) {
            // 1. Live Camera Preview Viewport
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                    }.also { previewView ->
                        val manager = CameraManager(
                            context = ctx,
                            lifecycleOwner = lifecycleOwner,
                            previewView = previewView,
                            targetFps = 6.0f,
                            onFrameSampled = { imageProxy, rotationDegrees ->
                                // Run 80-Class Object Detection
                                val objects = objectDetector.detectObjects(imageProxy, rotationDegrees)
                                detectedObjects = objects

                                // Single prioritized voice alert with speaking lock
                                val spokenAlert = speechDebouncer.getPrioritizedSpeechAlert(
                                    detectedObjects = objects,
                                    isCurrentlySpeaking = uiState == VoiceUiState.SPEAKING || uiState == VoiceUiState.LISTENING
                                )

                                if (spokenAlert != null) {
                                    viewModel.speakFeedback(spokenAlert)
                                }
                            },
                            onMetricsUpdated = { metrics ->
                                frameMetrics = metrics
                            }
                        )
                        cameraManager = manager
                        manager.startCamera()
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // 2. Real-Time Bounding Box Overlay Canvas
            BoundingBoxOverlay(detectedObjects = detectedObjects)
        } else {
            CameraPermissionPrompt(onRequestPermission = onRequestCameraPermission)
        }

        // --- TOP OVERLAY: Top Bar & Live Metrics Telemetry Pill ---
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(DarkBackground.copy(alpha = 0.85f), Color.Transparent)
                    )
                )
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(SurfaceDark.copy(alpha = 0.8f))
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back to Dashboard",
                        tint = TextWhite
                    )
                }

                Text(
                    text = "Live Perception & Vision",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextWhite,
                    fontWeight = FontWeight.Bold
                )

                IconButton(
                    onClick = {
                        val newState = !isTorchEnabled
                        cameraManager?.toggleTorch(newState) { state ->
                            isTorchEnabled = state
                            viewModel.speakFeedback(if (state) "Flashlight turned on." else "Flashlight turned off.")
                        }
                    },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            if (isTorchEnabled) Color(0xFFF59E0B).copy(alpha = 0.3f) else SurfaceDark.copy(alpha = 0.8f)
                        )
                ) {
                    Icon(
                        imageVector = if (isTorchEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                        contentDescription = "Toggle Flashlight",
                        tint = if (isTorchEnabled) Color(0xFFF59E0B) else TextWhite
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Diagnostic Telemetry Pill
            if (hasCameraPermission) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = SurfaceDark.copy(alpha = 0.85f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (objectDetector.isModelLoaded) SuccessGreen else Color(0xFFF59E0B))
                        )
                        Text(
                            text = if (objectDetector.isModelLoaded) {
                                "TFLite 80-Class COCO | FPS: ${"%.1f".format(frameMetrics.effectiveFps)} | Objects: ${detectedObjects.size}"
                            } else {
                                "TFLite Model Pending (Place ssd_mobilenet.tflite in assets/)"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                            color = TextWhite
                        )
                    }
                }
            }
        }

        // --- BOTTOM OVERLAY: Real-Time Detection Summary & Voice Assistant ---
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, DarkBackground.copy(alpha = 0.92f))
                    )
                )
                .padding(horizontal = 16.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Live Detected Obstacles Status Card
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = SurfaceDark.copy(alpha = 0.92f),
                border = androidx.compose.foundation.BorderStroke(1.dp, PrimaryBlue.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Visibility,
                        contentDescription = null,
                        tint = AccentCyan,
                        modifier = Modifier.size(24.dp)
                    )
                    Column {
                        Text(
                            text = if (detectedObjects.isNotEmpty()) {
                                "Obstacles in Path (${detectedObjects.size})"
                            } else {
                                "Pathway Clear"
                            },
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp),
                            color = TextWhite,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (detectedObjects.isNotEmpty()) {
                                detectedObjects.joinToString(", ") { "${it.label} (${it.spatialDirection})" }
                            } else {
                                "No immediate obstacles detected • Scanning at 6 FPS"
                            },
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                            color = AccentCyan
                        )
                    }
                }
            }

            // Action Row: Push-to-Talk Mic & Return Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onBack,
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark.copy(alpha = 0.9f)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.height(56.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Home,
                        contentDescription = null,
                        tint = PrimaryBlue,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Dashboard", color = TextWhite, fontWeight = FontWeight.SemiBold)
                }

                // Accessible Mic Button on Camera screen
                FloatingActionButton(
                    onClick = {
                        if (hasMicPermission) {
                            viewModel.onMicButtonClicked()
                        } else {
                            onRequestMicPermission()
                        }
                    },
                    containerColor = when (uiState) {
                        VoiceUiState.LISTENING -> AccentCyan
                        VoiceUiState.SPEAKING -> PrimaryBlue
                        VoiceUiState.PROCESSING -> Color(0xFFA855F7)
                        else -> PrimaryBlue
                    },
                    contentColor = DarkBackground,
                    shape = CircleShape,
                    modifier = Modifier
                        .size(56.dp)
                        .shadow(8.dp, CircleShape)
                ) {
                    Icon(
                        imageVector = if (uiState == VoiceUiState.LISTENING) Icons.Default.GraphicEq else Icons.Default.Mic,
                        contentDescription = "Voice Assistant - Tap to Speak",
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun CameraPermissionPrompt(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.VideocamOff,
            contentDescription = null,
            tint = Color(0xFFEF4444),
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Camera Permission Required",
            style = MaterialTheme.typography.titleMedium,
            color = TextWhite,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Saarthi needs camera access to analyze your environment and detect obstacles in real time.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = onRequestPermission,
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Grant Camera Permission", color = DarkBackground, fontWeight = FontWeight.Bold)
        }
    }
}
