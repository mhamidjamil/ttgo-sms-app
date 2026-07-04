package com.textgate.app.presentation.profile

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.textgate.app.core.theme.TextGateTheme
import com.textgate.app.core.theme.WarningAmber
import com.textgate.app.core.theme.WarningAmberBorder
import com.textgate.app.domain.model.User
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

@Composable
fun ProfileScreen(
    onSignOut: () -> Unit,
    onVerifyPhone: () -> Unit,
    onNavigateToSettings: () -> Unit = {},
    onNavigateToWhatsApp: () -> Unit = {},
    viewModel: ProfileViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }
    ProfileContent(
        uiState = uiState,
        onSignOut = { scope.launch { viewModel.signOut(); onSignOut() } },
        onVerifyPhone = onVerifyPhone,
        onNavigateToSettings = onNavigateToSettings,
        onNavigateToWhatsApp = onNavigateToWhatsApp,
        onSendEmailCode = viewModel::sendEmailCode,
        onVerifyEmailCode = viewModel::verifyEmailCode,
    )
}

@Composable
private fun ProfileContent(
    uiState: ProfileUiState,
    onSignOut: () -> Unit,
    onVerifyPhone: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToWhatsApp: () -> Unit = {},
    onSendEmailCode: () -> Unit = {},
    onVerifyEmailCode: (String) -> Unit = {},
) {
    var showSignOutDialog by remember { mutableStateOf(false) }

    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            title = { Text("Sign Out") },
            text = { Text("Are you sure you want to sign out?") },
            confirmButton = {
                TextButton(onClick = {
                    showSignOutDialog = false
                    onSignOut()
                }) { Text("Sign Out") }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutDialog = false }) { Text("Cancel") }
            },
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

        // No early returns — Compose requires a stable node tree across recompositions.
        when {
            uiState.isLoading -> {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            uiState.user == null -> {
                Text("Could not load profile", color = MaterialTheme.colorScheme.error)
            }
            else -> {
                val user = uiState.user

                Box(
                    modifier = Modifier.size(80.dp).clip(CircleShape)
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
                Text(
                    user.email, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )

                Spacer(Modifier.height(24.dp))

                if (!user.emailVerified) {
                    EmailVerifyBanner(
                        uiState = uiState,
                        onSendEmailCode = onSendEmailCode,
                        onVerifyEmailCode = onVerifyEmailCode,
                    )
                    Spacer(Modifier.height(12.dp))
                }

                if (!user.phoneVerified) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(WarningAmber)
                            .border(1.dp, WarningAmberBorder)
                            .padding(12.dp),
                    ) {
                        Column {
                            Text(
                                "⚠ Phone not verified — sending is disabled",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "Verify your number to unlock ${user.assignedQuota} SMS/day.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = onVerifyPhone) { Text("Verify Phone Number") }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Daily SMS Quota", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(4.dp))
                        val verifiedStr = buildList {
                            if (user.emailVerified) add("email")
                            if (user.phoneVerified) add("phone")
                        }.let { if (it.isEmpty()) "none verified" else "${it.joinToString(" + ")} verified" }
                        Text(
                            verifiedStr, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                        Spacer(Modifier.height(8.dp))
                        val remaining = user.remainingQuota.coerceAtMost(uiState.effectiveQuota)
                        val total = uiState.effectiveQuota.coerceAtLeast(1)
                        LinearProgressIndicator(
                            progress = { remaining.toFloat() / total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "$remaining / $total remaining today",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                        Text(
                            "Resets daily at midnight",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Arrival Monitoring", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Set up guardian SMS notifications when you arrive home or at office",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = onNavigateToSettings,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Setup Arrival Settings (V2)") }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("WhatsApp", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (user.emailVerified && user.phoneVerified) {
                                "Set up automatically — choose whether messages come from the shared TextGate number or your own WhatsApp."
                            } else {
                                "Verify your phone and email first — WhatsApp is then set up automatically."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = onNavigateToWhatsApp,
                            enabled = user.emailVerified && user.phoneVerified,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("WhatsApp Settings") }
                    }
                }

                Spacer(Modifier.height(24.dp))
                OutlinedButton(
                    onClick = { showSignOutDialog = true },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Sign Out") }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

// Email verification banner — request a 6-digit code (sent over SMTP) and
// enter it inline, mirroring the phone-verify flow.
@Composable
private fun EmailVerifyBanner(
    uiState: ProfileUiState,
    onSendEmailCode: () -> Unit,
    onVerifyEmailCode: (String) -> Unit,
) {
    var code by remember { mutableStateOf("") }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(WarningAmber)
            .border(1.dp, WarningAmberBorder)
            .padding(12.dp),
    ) {
        Column {
            Text(
                "⚠ Email not verified",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Email verification is needed for WhatsApp linking and admin contact (it does not affect your SMS quota).",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onSendEmailCode,
                enabled = !uiState.isSendingEmailCode && !uiState.isVerifyingEmail &&
                    uiState.emailCooldownSeconds == 0,
            ) {
                when {
                    uiState.isSendingEmailCode ->
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    uiState.emailCooldownSeconds > 0 ->
                        Text("Resend in ${uiState.emailCooldownSeconds}s")
                    uiState.emailCodeSent -> Text("Resend Code")
                    else -> Text("Send Verification Code")
                }
            }
            if (uiState.emailCodeSent) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "A 6-digit code was emailed to you. It is valid for 1 hour.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) code = it },
                    label = { Text("Verification Code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("123456") },
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onVerifyEmailCode(code) },
                    enabled = code.length == 6 && !uiState.isVerifyingEmail,
                ) {
                    if (uiState.isVerifyingEmail) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Text("Verify Email")
                    }
                }
            }
            uiState.emailVerifyError?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ── Preview helpers ───────────────────────────────────────────────────────────

private val previewUser = User(
    uid = "preview", email = "alice@textgate.com", name = "Alice",
    emailVerified = true, phoneVerified = true,
    assignedQuota = 10, remainingQuota = 6, lastQuotaResetDate = "2026-06-24",
    phoneNumber = "+923001234567",
)

@Preview(showBackground = true, name = "Profile — Fully verified")
@Composable
private fun ProfileVerifiedPreview() {
    TextGateTheme {
        ProfileContent(
            uiState = ProfileUiState(user = previewUser, effectiveQuota = 10),
            onSignOut = {}, onVerifyPhone = {}, onNavigateToSettings = {},
        )
    }
}

@Preview(showBackground = true, name = "Profile — Email + Phone unverified")
@Composable
private fun ProfileUnverifiedPreview() {
    TextGateTheme {
        ProfileContent(
            uiState = ProfileUiState(
                user = previewUser.copy(emailVerified = false, phoneVerified = false),
                effectiveQuota = 2,
            ),
            onSignOut = {}, onVerifyPhone = {}, onNavigateToSettings = {},
        )
    }
}

@Preview(showBackground = true, name = "Profile — Loading")
@Composable
private fun ProfileLoadingPreview() {
    TextGateTheme {
        ProfileContent(
            uiState = ProfileUiState(isLoading = true),
            onSignOut = {}, onVerifyPhone = {}, onNavigateToSettings = {},
        )
    }
}
