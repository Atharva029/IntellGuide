package com.intellguide.saarthi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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

data class DashboardCardItem(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val iconBgColor: Color,
    val targetScreen: Screen,
    val spokenConfirmation: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: VoiceViewModel,
    hasMicPermission: Boolean,
    onRequestMicPermission: () -> Unit,
    onNavigateTo: (Screen) -> Unit
) {
    var selectedBottomTab by remember { mutableStateOf(0) }
    val uiState by viewModel.uiState.collectAsState()

    val dashboardCards = remember {
        listOf(
            DashboardCardItem(
                title = "Start Navigation",
                subtitle = "Get voice guidance",
                icon = Icons.Default.Navigation,
                iconBgColor = Color(0xFF2563EB), // Blue
                targetScreen = Screen.LiveCamera,
                spokenConfirmation = "Navigation started. Scanning your pathway for obstacles."
            ),
            DashboardCardItem(
                title = "Live Camera",
                subtitle = "See & understand surroundings",
                icon = Icons.Default.Videocam,
                iconBgColor = Color(0xFF10B981), // Green
                targetScreen = Screen.LiveCamera,
                spokenConfirmation = "Live camera detection activated. Analyzing scene."
            ),
            DashboardCardItem(
                title = "Read Text (OCR)",
                subtitle = "Read signs, boards and more",
                icon = Icons.Default.TextFields,
                iconBgColor = Color(0xFF8B5CF6), // Purple
                targetScreen = Screen.OcrReader,
                spokenConfirmation = "Text reading mode activated. Point camera at the text."
            ),
            DashboardCardItem(
                title = "Currency Detection",
                subtitle = "Identify Indian currency notes",
                icon = Icons.Default.CurrencyRupee,
                iconBgColor = Color(0xFFF59E0B), // Orange / Gold
                targetScreen = Screen.CurrencyDetector,
                spokenConfirmation = "Currency detection activated. Hold the note in front of the camera."
            ),
            DashboardCardItem(
                title = "Daily Routes",
                subtitle = "Your learned frequent routes",
                icon = Icons.AutoMirrored.Filled.AltRoute,
                iconBgColor = Color(0xFFEC4899), // Pink
                targetScreen = Screen.DailyRoutes,
                spokenConfirmation = "Daily routes opened."
            ),
            DashboardCardItem(
                title = "Emergency SOS",
                subtitle = "Get help instantly",
                icon = Icons.Default.Sos,
                iconBgColor = Color(0xFFEF4444), // Red
                targetScreen = Screen.EmergencySos,
                spokenConfirmation = "Emergency assistance alert triggered."
            )
        )
    }

    Scaffold(
        topBar = {
            DashboardTopBar(
                onMenuClick = { viewModel.speakFeedback("Menu options opened.") },
                onSettingsClick = { viewModel.speakFeedback("Settings opened.") }
            )
        },
        bottomBar = {
            DashboardBottomNavigation(
                selectedTab = selectedBottomTab,
                onTabSelected = { index ->
                    selectedBottomTab = index
                    when (index) {
                        0 -> viewModel.speakFeedback("Home Dashboard selected.")
                        1 -> viewModel.speakFeedback("History selected. No past logs yet.")
                        2 -> {
                            viewModel.speakFeedback("Daily Routes selected.")
                            onNavigateTo(Screen.DailyRoutes)
                        }
                        3 -> {
                            viewModel.speakFeedback("Opening Emergency Setup and Registration.")
                            onNavigateTo(Screen.Registration)
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            // Accessible Floating Microphone button on Dashboard
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
                    .size(64.dp)
                    .shadow(12.dp, CircleShape)
            ) {
                Icon(
                    imageVector = if (uiState == VoiceUiState.LISTENING) Icons.Default.GraphicEq else Icons.Default.Mic,
                    contentDescription = "Voice Assistant - Tap to Speak",
                    modifier = Modifier.size(32.dp)
                )
            }
        },
        containerColor = DarkBackground
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Voice status strip if listening or speaking
            if ((uiState == VoiceUiState.LISTENING) || (uiState == VoiceUiState.SPEAKING) || (uiState == VoiceUiState.PROCESSING)) {
                DashboardVoiceStatusStrip(viewModel = viewModel)
                Spacer(modifier = Modifier.height(12.dp))
            }

            // 2x3 Grid of Accessible Cards
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = 80.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(dashboardCards) { item ->
                    DashboardCard(
                        item = item,
                        onClick = {
                            viewModel.speakFeedback(item.spokenConfirmation)
                            onNavigateTo(item.targetScreen)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun DashboardTopBar(
    onMenuClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkBackground)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        IconButton(
            onClick = onMenuClick,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(SurfaceDark)
        ) {
            Icon(
                imageVector = Icons.Default.Menu,
                contentDescription = "Navigation Drawer Menu",
                tint = TextWhite,
                modifier = Modifier.size(22.dp)
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Hello, User",
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
                    color = TextWhite,
                    fontWeight = FontWeight.Bold
                )
                Text(text = "👋", fontSize = 18.sp)
            }
            Text(
                text = "How can I assist you today?",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                color = TextMuted
            )
        }

        IconButton(
            onClick = onSettingsClick,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(SurfaceDark)
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "Settings",
                tint = TextWhite,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
fun DashboardCard(
    item: DashboardCardItem,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(175.dp)
            .border(1.dp, CardBorder, RoundedCornerShape(20.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Icon Circle
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(item.iconBgColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = item.title,
                    tint = Color.White,
                    modifier = Modifier.size(30.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
                color = TextWhite,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = item.subtitle,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                color = TextMuted,
                textAlign = TextAlign.Center,
                lineHeight = 14.sp
            )
        }
    }
}

@Composable
fun DashboardVoiceStatusStrip(viewModel: VoiceViewModel) {
    val statusMessage by viewModel.statusMessage.collectAsState()
    val recognizedText by viewModel.recognizedText.collectAsState()
    val spokenResponse by viewModel.spokenResponse.collectAsState()

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = SurfaceDark,
        border = androidx.compose.foundation.BorderStroke(1.dp, PrimaryBlue.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = PrimaryBlue,
                strokeWidth = 2.dp
            )
            Column {
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = AccentCyan
                )
                val displayText = recognizedText.ifEmpty { spokenResponse }
                if (displayText.isNotEmpty()) {
                    Text(
                        text = "\"$displayText\"",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                        color = TextWhite
                    )
                }
            }
        }
    }
}

@Composable
fun DashboardBottomNavigation(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit
) {
    val tabs = listOf(
        Pair("Home", Icons.Default.Home),
        Pair("History", Icons.Default.History),
        Pair("Routes", Icons.Default.AltRoute),
        Pair("Profile", Icons.Default.Person)
    )

    NavigationBar(
        containerColor = SurfaceDark,
        tonalElevation = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
    ) {
        tabs.forEachIndexed { index, (label, icon) ->
            val isSelected = selectedTab == index
            NavigationBarItem(
                selected = isSelected,
                onClick = { onTabSelected(index) },
                icon = {
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        tint = if (isSelected) PrimaryBlue else TextMuted
                    )
                },
                label = {
                    Text(
                        text = label,
                        color = if (isSelected) PrimaryBlue else TextMuted,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = PrimaryBlue.copy(alpha = 0.15f)
                )
            )
        }
    }
}
