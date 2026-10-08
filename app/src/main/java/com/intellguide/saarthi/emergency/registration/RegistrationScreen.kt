package com.intellguide.saarthi.emergency.registration

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellguide.saarthi.emergency.data.EmergencyContactEntity
import com.intellguide.saarthi.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegistrationScreen(
    existingRegistration: EmergencyContactEntity?,
    onSaveRegistration: (userName: String, c1Name: String, c1Phone: String, c2Name: String, c2Phone: String) -> Unit,
    onBack: () -> Unit,
    onSpeakFeedback: (String) -> Unit
) {
    var userName by remember(existingRegistration) { mutableStateOf(existingRegistration?.userName ?: "") }
    var contact1Name by remember(existingRegistration) { mutableStateOf(existingRegistration?.contact1Name ?: "") }
    var contact1Phone by remember(existingRegistration) { mutableStateOf(existingRegistration?.contact1Phone ?: "") }
    var contact2Name by remember(existingRegistration) { mutableStateOf(existingRegistration?.contact2Name ?: "") }
    var contact2Phone by remember(existingRegistration) { mutableStateOf(existingRegistration?.contact2Phone ?: "") }

    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }

    val isEditing = existingRegistration != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (isEditing) "Edit Emergency Setup" else "Guardian Registration",
                        color = TextWhite,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextWhite
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
            )
        },
        containerColor = DarkBackground
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Description Card
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = PrimaryBlue,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Emergency Setup",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextWhite,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Configure 2 emergency contacts for instant voice SOS dispatch.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                }
            }

            if (errorMessage != null) {
                Surface(
                    color = Color(0xFF7F1D1D),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = Color(0xFFFCA5A5))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = errorMessage ?: "",
                            color = Color(0xFFFCA5A5),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            if (successMessage != null) {
                Surface(
                    color = Color(0xFF065F46),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF6EE7B7))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = successMessage ?: "",
                            color = Color(0xFF6EE7B7),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Section 1: User Information
            RegistrationSectionHeader(title = "User Information", icon = Icons.Default.Person)

            OutlinedTextField(
                value = userName,
                onValueChange = {
                    userName = it
                    errorMessage = null
                },
                label = { Text("User Name *") },
                placeholder = { Text("Enter user's full name") },
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next
                ),
                colors = textFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )

            // Section 2: Emergency Contact 1
            RegistrationSectionHeader(title = "Primary Emergency Contact (Contact 1)", icon = Icons.Default.ContactPhone)

            OutlinedTextField(
                value = contact1Name,
                onValueChange = {
                    contact1Name = it
                    errorMessage = null
                },
                label = { Text("Contact 1 Name *") },
                placeholder = { Text("e.g. Parent / Spouse / Guardian") },
                leadingIcon = { Icon(Icons.Default.Badge, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next
                ),
                colors = textFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = contact1Phone,
                onValueChange = {
                    contact1Phone = it
                    errorMessage = null
                },
                label = { Text("Contact 1 Phone Number *") },
                placeholder = { Text("e.g. +919876543210") },
                leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Phone,
                    imeAction = ImeAction.Next
                ),
                colors = textFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )

            // Section 3: Emergency Contact 2
            RegistrationSectionHeader(title = "Secondary Emergency Contact (Contact 2)", icon = Icons.Default.ContactPhone)

            OutlinedTextField(
                value = contact2Name,
                onValueChange = {
                    contact2Name = it
                    errorMessage = null
                },
                label = { Text("Contact 2 Name *") },
                placeholder = { Text("e.g. Secondary Relative / Friend") },
                leadingIcon = { Icon(Icons.Default.Badge, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next
                ),
                colors = textFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = contact2Phone,
                onValueChange = {
                    contact2Phone = it
                    errorMessage = null
                },
                label = { Text("Contact 2 Phone Number *") },
                placeholder = { Text("e.g. +919876543211") },
                leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Phone,
                    imeAction = ImeAction.Done
                ),
                colors = textFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Save Registration Button
            Button(
                onClick = {
                    onSaveRegistration(
                        userName,
                        contact1Name,
                        contact1Phone,
                        contact2Name,
                        contact2Phone
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isEditing) "Update Emergency Contacts" else "Save Emergency Registration",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite
                )
            }
        }
    }
}

@Composable
private fun RegistrationSectionHeader(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    ) {
        Icon(icon, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = AccentCyan,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun textFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = PrimaryBlue,
    unfocusedBorderColor = CardBorder,
    focusedLabelColor = PrimaryBlue,
    unfocusedLabelColor = TextMuted,
    focusedTextColor = TextWhite,
    unfocusedTextColor = TextWhite,
    focusedLeadingIconColor = PrimaryBlue,
    unfocusedLeadingIconColor = TextMuted,
    cursorColor = PrimaryBlue
)
