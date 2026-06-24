package com.textgate.app.presentation.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.textgate.app.core.theme.WarningAmber
import com.textgate.app.core.theme.WarningAmberBorder
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

@Composable
fun ProfileScreen(
    onSignOut: () -> Unit,
    onVerifyPhone: () -> Unit,
    viewModel: ProfileViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    var showSignOutDialog by remember { mutableStateOf(false) }

    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            title = { Text("Sign Out") },
            text = { Text("Are you sure you want to sign out?") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        viewModel.signOut()
                        onSignOut()
                    }
                }) { Text("Sign Out") }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutDialog = false }) { Text("Cancel") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))
        Text("Profile", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))

        if (uiState.isLoading) {
            CircularProgressIndicator()
            return@Column
        }

        val user = uiState.user ?: run {
            Text("Could not load profile", color = MaterialTheme.colorScheme.error)
            return@Column
        }

        // Avatar circle
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                user.name.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(Modifier.height(16.dp))
        Text(user.name, style = MaterialTheme.typography.titleLarge)
        Text(user.email, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))

        Spacer(Modifier.height(24.dp))

        // Email verification banner
        if (!user.emailVerified) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(WarningAmber)
                    .border(1.dp, WarningAmberBorder)
                    .padding(12.dp),
            ) {
                Column {
                    Text("⚠ Email not verified",
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text("Verify both email and phone to unlock ${user.assignedQuota} SMS/day.",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { viewModel.resendVerification() }) {
                        Text("Resend Verification Email")
                    }
                    if (uiState.verificationSent) {
                        Text("Sent! Check your inbox.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // Phone verification banner
        if (!user.phoneVerified) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(WarningAmber)
                    .border(1.dp, WarningAmberBorder)
                    .padding(12.dp),
            ) {
                Column {
                    Text("⚠ Phone not verified",
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    if (user.phoneNumber.isNotBlank()) {
                        Text("${user.phoneNumber} — tap below to enter your verification code.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onVerifyPhone) {
                        Text("Verify Phone Number")
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // Quota card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Daily SMS Quota", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                val verifiedStr = buildList {
                    if (user.emailVerified) add("email")
                    if (user.phoneVerified) add("phone")
                }.let { if (it.isEmpty()) "none verified" else "${it.joinToString(" + ")} verified" }
                Text(verifiedStr, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Spacer(Modifier.height(8.dp))
                val remaining = user.remainingQuota.coerceAtMost(uiState.effectiveQuota)
                val total = uiState.effectiveQuota.coerceAtLeast(1)
                LinearProgressIndicator(
                    progress = { remaining.toFloat() / total },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text("$remaining / $total remaining today",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                Text("Resets daily at midnight",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
        }

        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(24.dp))
        OutlinedButton(
            onClick = { showSignOutDialog = true },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            ),
        ) { Text("Sign Out") }
        Spacer(Modifier.height(16.dp))
    }
}
