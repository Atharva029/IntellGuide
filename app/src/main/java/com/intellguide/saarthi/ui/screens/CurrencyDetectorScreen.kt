package com.intellguide.saarthi.ui.screens

import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.intellguide.saarthi.ui.VoiceViewModel
import com.intellguide.saarthi.ui.theme.*
import com.intellguide.saarthi.vision.CurrencyDetectionResult
import com.intellguide.saarthi.vision.IndianCurrencyDetector
import java.util.concurrent.Executors

@Composable
fun CurrencyDetectorScreen(
    viewModel: VoiceViewModel,
    hasCameraPermission: Boolean,
    hasMicPermission: Boolean,
    onRequestCameraPermission: () -> Unit,
    onRequestMicPermission: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var currentResult by remember { mutableStateOf<CurrencyDetectionResult?>(null) }
    var autoAnnounceEnabled by remember { mutableStateOf(false) }
    var lastAlertTime by remember { mutableLongStateOf(0L) }
    var lastAlertDenomination by remember { mutableIntStateOf(0) }

    val currencyDetector = remember { IndianCurrencyDetector(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            currencyDetector.close()
            cameraExecutor.shutdown()
        }
    }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkBackground)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back to Home Dashboard",
                        tint = TextWhite
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CurrencyRupee,
                        contentDescription = null,
                        tint = Color(0xFFF59E0B),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Currency Detection",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextWhite,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Toggle Auto Speech Announce Mode
                IconButton(
                    onClick = {
                        autoAnnounceEnabled = !autoAnnounceEnabled
                        val stateMsg = if (autoAnnounceEnabled)
                            "Auto voice announcements enabled."
                        else
                            "Auto voice announcements muted. Tap to hear result."
                        viewModel.speakFeedback(stateMsg)
                    }
                ) {
                    Icon(
                        imageVector = if (autoAnnounceEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                        contentDescription = "Toggle Auto Announce",
                        tint = if (autoAnnounceEnabled) Color(0xFF10B981) else TextMuted
                    )
                }
            }
        },
        containerColor = DarkBackground
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (!hasCameraPermission) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = null,
                        tint = Color(0xFFF59E0B),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Camera Access Required",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextWhite,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Saarthi needs camera access to identify Indian Rupee banknotes and coins in real time.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = onRequestCameraPermission,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B))
                    ) {
                        Text("Grant Camera Permission", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                // Live CameraX Preview Engine
                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                        }

                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener({
                            val cameraProvider = cameraProviderFuture.get()

                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }

                            val imageAnalysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()

                            var consecutiveMatches = 0
                            var candidateDenom = 0

                            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                val rotation = imageProxy.imageInfo.rotationDegrees
                                currencyDetector.processFrame(imageProxy, rotation) { result ->
                                    if (result != null) {
                                        if (result.denomination == candidateDenom) {
                                            consecutiveMatches++
                                        } else {
                                            candidateDenom = result.denomination
                                            consecutiveMatches = 1
                                        }

                                        // Update UI badge when result is stable across 4 consecutive frames
                                        if (consecutiveMatches >= 4) {
                                            currentResult = result
                                            val now = System.currentTimeMillis()

                                            // ONLY speak automatically IF Auto-Announce is enabled AND 6 seconds have passed
                                            if (autoAnnounceEnabled) {
                                                if (result.denomination != lastAlertDenomination && (now - lastAlertTime) > 6000) {
                                                    lastAlertTime = now
                                                    lastAlertDenomination = result.denomination
                                                    viewModel.speakFeedback(result.spokenAlert)
                                                }
                                            }
                                        }
                                    } else {
                                        consecutiveMatches = 0
                                    }
                                }
                            }

                            try {
                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    imageAnalysis
                                )
                            } catch (e: Exception) {
                                Log.e("CurrencyScreen", "Camera binding failed", e)
                            }
                        }, ContextCompat.getMainExecutor(ctx))

                        previewView
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable {
                            val alertMsg = currentResult?.spokenAlert
                                ?: "No currency detected yet. Hold banknote or coin inside the target frame."
                            viewModel.speakFeedback(alertMsg)
                        }
                )

                // Rectangular Banknote Focus Target Box
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(440.dp)
                            .border(
                                width = 3.5.dp,
                                color = if (currentResult != null) Color(0xFF10B981) else Color(0xFFF59E0B),
                                shape = RoundedCornerShape(24.dp)
                            )
                            .background(
                                color = if (currentResult != null)
                                    Color(0x2210B981)
                                else
                                    Color(0x11F59E0B)
                            )
                    )
                }

                // Top Guidance Pill
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.80f),
                        shape = RoundedCornerShape(30.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.TouchApp,
                                contentDescription = null,
                                tint = Color(0xFFF59E0B),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (autoAnnounceEnabled) "Auto-Speech Active • Tap screen to repeat" else "Tap screen anytime to hear detected currency",
                                color = TextWhite,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                // Bottom Result Panel & Controls
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(Color.Black.copy(alpha = 0.90f))
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    AnimatedVisibility(
                        visible = currentResult != null,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        currentResult?.let { res ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.speakFeedback(res.spokenAlert) }
                                    .border(1.5.dp, Color(0xFF10B981), RoundedCornerShape(16.dp)),
                                colors = CardDefaults.cardColors(containerColor = SurfaceDark)
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFF10B981).copy(alpha = 0.2f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = if (res.isCoin) "🪙 ₹${res.denomination}" else "₹${res.denomination}",
                                                color = Color(0xFF10B981),
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 15.sp
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(14.dp))

                                        Column {
                                            Text(
                                                text = res.label,
                                                style = MaterialTheme.typography.titleMedium,
                                                color = TextWhite,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = "${res.colorSignature} • Tap to hear",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextMuted
                                            )
                                        }
                                    }

                                    IconButton(
                                        onClick = { viewModel.speakFeedback(res.spokenAlert) }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.VolumeUp,
                                            contentDescription = "Speak Result",
                                            tint = Color(0xFF10B981)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Supported Denomination Badges
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        listOf("🪙 ₹1", "🪙 ₹2", "🪙 ₹5", "₹10", "₹20", "₹50", "₹100", "₹200", "₹500").forEach { item ->
                            Surface(
                                color = SurfaceDark,
                                shape = RoundedCornerShape(20.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
                            ) {
                                Text(
                                    text = item,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextWhite
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Speak Detected Note Button
                        Button(
                            onClick = {
                                val alertMsg = currentResult?.spokenAlert
                                    ?: "No currency detected yet. Hold banknote or coin inside the target frame."
                                viewModel.speakFeedback(alertMsg)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.VolumeUp,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Read Aloud",
                                color = Color.Black,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }

                        // Voice Assistant Mic Button
                        Button(
                            onClick = {
                                if (!hasMicPermission) {
                                    onRequestMicPermission()
                                } else {
                                    viewModel.onMicButtonClicked()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B)),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Voice Command",
                                color = Color.Black,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
