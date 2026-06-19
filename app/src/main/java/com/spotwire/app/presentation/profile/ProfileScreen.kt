package com.spotwire.app.presentation.profile

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.spotwire.app.BuildConfig
import com.spotwire.app.core.theme.SpotwireTheme
import com.spotwire.app.core.theme.OnWarningAmber
import com.spotwire.app.core.theme.WarningAmber
import com.spotwire.app.core.theme.WarningAmberBorder
import com.spotwire.app.data.local.PreferencesDataSource
import com.spotwire.app.domain.model.User
import com.spotwire.app.services.ArrivalService
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

// Play requires a policy link inside the app as well as in the listing.
const val PRIVACY_POLICY_URL = "https://mhamidjamil.github.io/ttgo-sms-app/privacy-policy.html"

// Default History tab choices, in the order the History page shows them.
private val historyTabOptions = listOf(
    PreferencesDataSource.HISTORY_TAB_AUTOMATED to "Automated",
    PreferencesDataSource.HISTORY_TAB_MANUAL to "Manual",
)

@Composable
fun ProfileScreen(
    onSignOut: () -> Unit,
    onVerifyPhone: () -> Unit,
    onNavigateToChangeHistory: () -> Unit = {},
    onNavigateToWhatsApp: () -> Unit = {},
    onNavigateToAlertSources: () -> Unit = {},
    onNavigateToLinkedAccounts: () -> Unit = {},
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
    // The account is gone, so monitoring has to stop and the app has to leave
    // every signed-in screen behind.
    LaunchedEffect(uiState.accountDeleted) {
        if (uiState.accountDeleted) {
            ArrivalService.stop(context)
            Toast.makeText(context, "Your account has been deleted", Toast.LENGTH_LONG).show()
            onSignOut()
        }
    }
    ProfileContent(
        uiState = uiState,
        onSignOut = {
            ArrivalService.stop(context)
            scope.launch { viewModel.signOut(); onSignOut() }
        },
        onVerifyPhone = onVerifyPhone,
        onNavigateToChangeHistory = onNavigateToChangeHistory,
        onNavigateToWhatsApp = onNavigateToWhatsApp,
        onNavigateToAlertSources = onNavigateToAlertSources,
        onNavigateToLinkedAccounts = onNavigateToLinkedAccounts,
        onSendEmailCode = viewModel::sendEmailCode,
        onVerifyEmailCode = viewModel::verifyEmailCode,
        onDeleteAccount = viewModel::deleteAccount,
        onUpdateName = viewModel::updateName,
        onDefaultHistoryTabChange = viewModel::setDefaultHistoryTab,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileContent(
    uiState: ProfileUiState,
    onSignOut: () -> Unit,
    onVerifyPhone: () -> Unit,
    onNavigateToChangeHistory: () -> Unit = {},
    onNavigateToWhatsApp: () -> Unit = {},
    onNavigateToAlertSources: () -> Unit = {},
    onNavigateToLinkedAccounts: () -> Unit = {},
    onSendEmailCode: () -> Unit = {},
    onVerifyEmailCode: () -> Unit = {},
    onDeleteAccount: () -> Unit = {},
    onUpdateName: (String) -> Unit = {},
    onDefaultHistoryTabChange: (String) -> Unit = {},
) {
    var showSignOutDialog by remember { mutableStateOf(false) }
    var showEditNameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete your account?") },
            text = {
                Text(
                    "This permanently erases your profile, your message history, your saved " +
                        "places and your WhatsApp settings, and closes your sign-in. It cannot " +
                        "be undone, and messages already delivered are not recalled.",
                )
            },
            confirmButton = {
                TextButton(onClick = { showDeleteDialog = false; onDeleteAccount() }) {
                    Text("Delete everything", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            },
        )
    }

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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(user.name, style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = { showEditNameDialog = true }) {
                        Icon(
                            Icons.Default.Edit, contentDescription = "Edit name",
                            Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                }
                Text(
                    user.email, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )

                if (showEditNameDialog) {
                    EditNameDialog(
                        currentName = user.name,
                        onSave = { onUpdateName(it); showEditNameDialog = false },
                        onDismiss = { showEditNameDialog = false },
                    )
                }

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
                        CompositionLocalProvider(LocalContentColor provides OnWarningAmber) {
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
                        Text("Preferences", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Which half of the History page opens first.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                        Spacer(Modifier.height(10.dp))
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            historyTabOptions.forEachIndexed { index, (value, label) ->
                                SegmentedButton(
                                    selected = uiState.defaultHistoryTab == value,
                                    onClick = { onDefaultHistoryTabChange(value) },
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index, historyTabOptions.size,
                                    ),
                                ) { Text(label) }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Settings History", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "See when a setting changed and what it changed to. Arrival monitoring " +
                                "itself now lives in the Arrival tab.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = onNavigateToChangeHistory,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("View Change History") }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Linked Accounts", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Share your arrival status with people you trust, and choose what each " +
                                "of them is allowed to see.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = onNavigateToLinkedAccounts,
                            enabled = user.phoneVerified,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Manage Linked Accounts") }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Alerts You Receive", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (user.phoneVerified) {
                                "People sending you automated location alerts. You can stop any of " +
                                    "them yourself."
                            } else {
                                "Verify your phone number to see who is sending you automated alerts."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = onNavigateToAlertSources,
                            enabled = user.phoneVerified,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Manage Incoming Alerts") }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("WhatsApp", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (user.emailVerified && user.phoneVerified) {
                                "Set up automatically — choose whether messages come from the shared Spotwire number or your own WhatsApp."
                            } else {
                                "Verify your phone and email for automatic setup, or connect your own WhatsApp gateway now."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = onNavigateToWhatsApp,
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

                Spacer(Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                TextButton(
                    onClick = { uriHandler.openUri(PRIVACY_POLICY_URL) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Privacy policy") }

                // Google Play requires an in-app way to delete the account for
                // any app that lets people create one, and it must really erase
                // the data rather than just disable the login.
                TextButton(
                    onClick = { showDeleteDialog = true },
                    enabled = !uiState.isDeletingAccount,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    if (uiState.isDeletingAccount) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Deleting your account…")
                    } else {
                        Text("Delete my account and data")
                    }
                }
                uiState.deleteError?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    "Spotwire ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
    onVerifyEmailCode: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(WarningAmber)
            .border(1.dp, WarningAmberBorder)
            .padding(12.dp),
    ) {
        // The banner paints its own light amber background, so the text colour
        // is pinned too. Inheriting it would put near-white on near-white the
        // moment the phone is in dark mode.
        CompositionLocalProvider(LocalContentColor provides OnWarningAmber) {
            Column {
                Text(
                    "⚠ Email not verified",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Email verification is needed for automatic WhatsApp setup and admin contact (it does not affect your SMS quota).",
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
                        uiState.emailCodeSent -> Text("Resend Email")
                        else -> Text("Send Verification Email")
                    }
                }
                if (uiState.emailCodeSent) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Open the link in the email we just sent, then tap below. " +
                            "Check your spam folder if it has not arrived.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onVerifyEmailCode,
                        enabled = !uiState.isVerifyingEmail,
                    ) {
                        if (uiState.isVerifyingEmail) {
                            CircularProgressIndicator(
                                Modifier.size(16.dp), strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text("I've opened the link")
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
}

@Composable
private fun EditNameDialog(
    currentName: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your name") },
        text = {
            Column {
                Text(
                    "Shown in arrival messages, e.g. \"<name> arrived at Home\".",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Full name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ── Preview helpers ───────────────────────────────────────────────────────────

private val previewUser = User(
    uid = "preview", email = "alice@spotwire.com", name = "Alice",
    emailVerified = true, phoneVerified = true,
    assignedQuota = 10, remainingQuota = 6, lastQuotaResetDate = "2026-06-24",
    phoneNumber = "+923001234567",
)

@Preview(showBackground = true, name = "Profile — Fully verified")
@Composable
private fun ProfileVerifiedPreview() {
    SpotwireTheme {
        ProfileContent(
            uiState = ProfileUiState(user = previewUser, effectiveQuota = 10),
            onSignOut = {}, onVerifyPhone = {},
        )
    }
}

@Preview(showBackground = true, name = "Profile — Email + Phone unverified")
@Composable
private fun ProfileUnverifiedPreview() {
    SpotwireTheme {
        ProfileContent(
            uiState = ProfileUiState(
                user = previewUser.copy(emailVerified = false, phoneVerified = false),
                effectiveQuota = 2,
            ),
            onSignOut = {}, onVerifyPhone = {},
        )
    }
}

@Preview(showBackground = true, name = "Profile — Loading")
@Composable
private fun ProfileLoadingPreview() {
    SpotwireTheme {
        ProfileContent(
            uiState = ProfileUiState(isLoading = true),
            onSignOut = {}, onVerifyPhone = {},
        )
    }
}
