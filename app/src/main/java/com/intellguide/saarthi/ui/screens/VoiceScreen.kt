package com.intellguide.saarthi.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellguide.saarthi.ui.Screen
import com.intellguide.saarthi.ui.VoiceUiState
import com.intellguide.saarthi.ui.VoiceViewModel
import com.intellguide.saarthi.ui.theme.*
import kotlin.math.sin

@Composable
fun VoiceScreen(
    viewModel: VoiceViewModel,
    hasMicPermission: Boolean,
    onRequestPermission: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val recognizedText by viewModel.recognizedText.collectAsState()
    val spokenResponse by viewModel.spokenResponse.collectAsState()
    val rmsLevel by viewModel.rmsLevel.collectAsState()

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(horizontal = 20.dp, vertical = 20.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // --- TOP HEADER ---
        TopHeaderSection()

        // --- GREETING PROMPT CARD ---
        GreetingPromptCard()

        // --- CENTER: LARGE ACCESSIBLE MIC BUTTON WITH AUDIO RIPPLES ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            AccessibleMicButton(
                uiState = uiState,
                rmsLevel = rmsLevel,
                hasPermission = hasMicPermission,
                onMicClick = {
                    if (hasMicPermission) {
                        viewModel.onMicButtonClicked()
                    } else {
                        onRequestPermission()
                    }
                }
            )
        }

        // --- DYNAMIC AUDIO WAVEFORM VISUALIZER ---
        AudioWaveformVisualizer(
            isActive = (uiState == VoiceUiState.LISTENING) || (uiState == VoiceUiState.SPEAKING),
            intensity = if (uiState == VoiceUiState.LISTENING) rmsLevel else 0.6f
        )

        // --- LIVE STATUS & TRANSCRIPTION DISPLAY ---
        LiveSpeechTranscriptCard(
            uiState = uiState,
            statusMessage = statusMessage,
            recognizedText = recognizedText,
            spokenResponse = spokenResponse
        )

        // --- QUICK TEST COMMAND CHIPS ---
        QuickCommandChipsSection(
            onCommandSelected = { command ->
                viewModel.simulateCommand(command)
            }
        )

        // --- DIRECT BUTTON TO DASHBOARD FOR PARTIAL SIGHT ---
        Button(
            onClick = {
                viewModel.navigateTo(Screen.HomeDashboard, "Opening Home Dashboard.")
            },
            colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark),
            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Dashboard,
                contentDescription = null,
                tint = PrimaryBlue,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Open Home Dashboard", color = TextWhite, fontWeight = FontWeight.SemiBold)
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
fun TopHeaderSection() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Visibility,
                contentDescription = null,
                tint = PrimaryBlue,
                modifier = Modifier.size(28.dp)
            )
            Text(
                text = "Saarthi AI",
                style = MaterialTheme.typography.headlineLarge.copy(fontSize = 28.sp),
                color = TextWhite,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Your intelligent navigation assistant",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            color = TextMuted,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun GreetingPromptCard() {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = SurfaceDark,
        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(text = "👋", fontSize = 22.sp)
            Column {
                Text(
                    text = "Welcome!",
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
                    color = TextWhite,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "How can I help you today? Speak a command or say 'Go to dashboard'.",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                    color = TextMuted
                )
            }
        }
    }
}

@Composable
fun AccessibleMicButton(
    uiState: VoiceUiState,
    rmsLevel: Float,
    hasPermission: Boolean,
    onMicClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "ripplePulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.30f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val isListening = uiState == VoiceUiState.LISTENING
    val isSpeaking = uiState == VoiceUiState.SPEAKING

    val primaryColor = when (uiState) {
        VoiceUiState.LISTENING -> AccentCyan
        VoiceUiState.SPEAKING -> PrimaryBlue
        VoiceUiState.PROCESSING -> Color(0xFFA855F7)
        VoiceUiState.ERROR -> Color(0xFFEF4444)
        VoiceUiState.IDLE -> PrimaryBlue
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(220.dp)
    ) {
        // Outer animated ripple ring 2
        if (isListening || isSpeaking) {
            Box(
                modifier = Modifier
                    .size(210.dp)
                    .scale(if (isListening) (1f + rmsLevel * 0.35f) else pulseScale)
                    .clip(CircleShape)
                    .background(primaryColor.copy(alpha = 0.10f))
                    .border(1.5.dp, primaryColor.copy(alpha = 0.25f), CircleShape)
            )
        }

        // Concentric animated ripple ring 1
        if (isListening || isSpeaking) {
            Box(
                modifier = Modifier
                    .size(185.dp)
                    .scale(if (isListening) (1f + rmsLevel * 0.20f) else (pulseScale * 0.92f))
                    .clip(CircleShape)
                    .background(primaryColor.copy(alpha = 0.18f))
                    .border(2.dp, primaryColor.copy(alpha = 0.45f), CircleShape)
            )
        }

        // Central Large Microphone Action Button (160dp)
        Surface(
            modifier = Modifier
                .size(160.dp)
                .shadow(
                    elevation = if (isListening) 20.dp else 10.dp,
                    shape = CircleShape,
                    ambientColor = primaryColor,
                    spotColor = primaryColor
                )
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onMicClick
                ),
            color = SurfaceDark,
            shape = CircleShape,
            border = androidx.compose.foundation.BorderStroke(
                width = 3.dp,
                brush = Brush.linearGradient(
                    listOf(
                        primaryColor,
                        primaryColor.copy(alpha = 0.6f)
                    )
                )
            )
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            listOf(
                                primaryColor.copy(alpha = if (isListening) 0.35f else 0.15f),
                                Color.Transparent
                            )
                        )
                    )
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = getButtonIcon(uiState, hasPermission),
                        contentDescription = "Microphone Button - Tap to Speak",
                        tint = primaryColor,
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = getButtonLabel(uiState, hasPermission),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        ),
                        color = TextWhite
                    )
                }
            }
        }
    }
}

private fun getButtonIcon(state: VoiceUiState, hasPermission: Boolean): ImageVector {
    if (!hasPermission) return Icons.Default.MicOff
    return when (state) {
        VoiceUiState.IDLE -> Icons.Default.Mic
        VoiceUiState.LISTENING -> Icons.Default.GraphicEq
        VoiceUiState.PROCESSING -> Icons.Default.HourglassTop
        VoiceUiState.SPEAKING -> Icons.AutoMirrored.Filled.VolumeUp
        VoiceUiState.ERROR -> Icons.Default.MicOff
    }
}

private fun getButtonLabel(state: VoiceUiState, hasPermission: Boolean): String {
    if (!hasPermission) return "Grant Mic"
    return when (state) {
        VoiceUiState.IDLE -> "Tap to Speak"
        VoiceUiState.LISTENING -> "Listening..."
        VoiceUiState.PROCESSING -> "Thinking..."
        VoiceUiState.SPEAKING -> "Speaking..."
        VoiceUiState.ERROR -> "Tap to Retry"
    }
}

@Composable
fun AudioWaveformVisualizer(
    isActive: Boolean,
    intensity: Float
) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.28f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase"
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(44.dp)
            .padding(horizontal = 24.dp)
    ) {
        for (i in 0 until 18) {
            val waveHeight = if (isActive) {
                val sinValue = (sin((phase + (i * 0.45f)).toDouble()).toFloat() + 1f) / 2f
                (8f + (sinValue * (14f + intensity * 22f))).coerceIn(6f, 42f)
            } else {
                6f
            }

            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(waveHeight.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (isActive) AccentCyan else CardBorder.copy(alpha = 0.4f)
                    )
            )
        }
    }
}

@Composable
fun LiveSpeechTranscriptCard(
    uiState: VoiceUiState,
    statusMessage: String,
    recognizedText: String,
    spokenResponse: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, CardBorder, RoundedCornerShape(18.dp)),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Status Tag
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            when (uiState) {
                                VoiceUiState.LISTENING -> AccentCyan
                                VoiceUiState.SPEAKING -> PrimaryBlue
                                VoiceUiState.PROCESSING -> Color(0xFFA855F7)
                                VoiceUiState.ERROR -> Color(0xFFEF4444)
                                VoiceUiState.IDLE -> SuccessGreen
                            }
                        )
                )
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp),
                    color = TextWhite,
                    fontWeight = FontWeight.SemiBold
                )
            }

            HorizontalDivider(
                color = CardBorder.copy(alpha = 0.6f),
                thickness = 1.dp
            )

            // User's voice input
            if (recognizedText.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(text = "🗣️", fontSize = 14.sp)
                    Column {
                        Text(
                            text = "You said:",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                        Text(
                            text = "\"$recognizedText\"",
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                            color = AccentCyan,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Saarthi's voice output
            if (spokenResponse.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(text = "🤖", fontSize = 14.sp)
                    Column {
                        Text(
                            text = "Saarthi replied:",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                        Text(
                            text = spokenResponse,
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                            color = TextWhite
                        )
                    }
                }
            }

            if (recognizedText.isEmpty() && spokenResponse.isEmpty()) {
                Text(
                    text = "Press the large button above and speak naturally to give a command.",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    color = TextMuted
                )
            }
        }
    }
}

@Composable
fun QuickCommandChipsSection(
    onCommandSelected: (String) -> Unit
) {
    val sampleCommands = listOf(
        "📱 Go to dashboard",
        "🧭 Start navigation",
        "📷 Live camera",
        "📖 Read text",
        "💵 Identify currency",
        "🗺️ Daily routes",
        "🚨 Help me"
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Voice Commands (Tap to test):",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = TextMuted
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            items(sampleCommands) { item ->
                val cleanCommand = item.substringAfter(" ").trim()
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = CardBorder.copy(alpha = 0.5f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
                    modifier = Modifier.clickable { onCommandSelected(cleanCommand) }
                ) {
                    Text(
                        text = item,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                        color = TextWhite,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}
