package com.spotwire.app.presentation.send

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.spotwire.app.BuildConfig
import com.spotwire.app.core.theme.SpotwireTheme
import com.spotwire.app.core.theme.OnWarningAmber
import com.spotwire.app.core.theme.WarningAmber
import com.spotwire.app.core.theme.WarningAmberBorder
import com.spotwire.app.core.utils.PhoneNormalizer
import com.spotwire.app.domain.model.User
import com.spotwire.app.domain.usecase.sms.EnqueueSmsUseCase
import com.spotwire.app.presentation.components.PhoneNumberField
import com.spotwire.app.presentation.components.rememberDefaultCountry
import org.koin.androidx.compose.koinViewModel

@Composable
fun SendScreen(viewModel: SendViewModel = koinViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    SendContent(
        uiState = uiState,
        onSend = viewModel::send,
        onClearMessage = viewModel::clearSentMessage,
        onRequestMore = viewModel::requestMore,
        onClearRequestMoreResult = viewModel::clearRequestMoreResult,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SendContent(
    uiState: SendUiState,
    onSend: (String, String, String, Boolean) -> Unit,
    onClearMessage: () -> Unit,
    onRequestMore: (String) -> Unit = {},
    onClearRequestMoreResult: () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val phoneNormalizer = remember { PhoneNormalizer() }
    val context = LocalContext.current
    var showRequestDialog by remember { mutableStateOf(false) }
    // Hoisted so the queued-toast effect can clear the field for the next message.
    var phone by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val defaultCountry = rememberDefaultCountry()
    var country by remember { mutableStateOf(defaultCountry) }

    if (showRequestDialog) {
        RequestMoreDialog(
            isSending = uiState.isRequestingMore,
            onSubmit = { note ->
                onRequestMore(note)
                showRequestDialog = false
            },
            onDismiss = { showRequestDialog = false },
        )
    }

    LaunchedEffect(uiState.sentMessage) {
        uiState.sentMessage?.let {
            // Queued: toast + clear the message box so the next text can be
            // typed immediately (the recipient number is kept on purpose).
            message = ""
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            onClearMessage()
        }
    }
    LaunchedEffect(uiState.requestMoreResult) {
        uiState.requestMoreResult?.let {
            snackbarHostState.showSnackbar(it)
            onClearRequestMoreResult()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("Send SMS", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))

            val user = uiState.user
            // An account outside Pakistan can never spend a text allowance, so
            // it is told that plainly instead of being shown a locked one.
            val smsIsForThisAccount =
                user == null || phoneNormalizer.isPakistaniMobile(user.phoneNumber)
            if (user != null && !smsIsForThisAccount) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(WarningAmber)
                        .border(1.dp, WarningAmberBorder)
                        .padding(12.dp),
                ) {
                    Text(
                        "Text messages are sent by one device with a Pakistani SIM, so they are " +
                            "not available on your number. Your alerts go over WhatsApp and to " +
                            "people you are linked with inside the app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnWarningAmber,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            if (user != null && smsIsForThisAccount && !user.phoneVerified) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(WarningAmber)
                        .border(1.dp, WarningAmberBorder)
                        .padding(12.dp),
                ) {
                    Text(
                        "⚠ Phone not verified — sending is disabled (0 SMS/day). Verify your number from Profile to unlock ${user.assignedQuota} SMS/day.",
                        style = MaterialTheme.typography.bodySmall,
                        // The amber background is fixed in both themes, so the
                        // text colour has to be too — inheriting it puts white
                        // on near-white in dark mode.
                        color = OnWarningAmber,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            if (user != null && smsIsForThisAccount) {
                Text(
                    "${uiState.remainingToday} / ${uiState.effectiveQuota} SMS remaining today",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                LinearProgressIndicator(
                    progress = { uiState.remainingToday.toFloat() / uiState.effectiveQuota.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    color = if (uiState.canSendMore) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                )
                if (user.phoneVerified) {
                    TextButton(
                        onClick = { showRequestDialog = true },
                        enabled = !uiState.isRequestingMore && uiState.canRequestMoreToday,
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        when {
                            uiState.isRequestingMore ->
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            !uiState.canRequestMoreToday -> Text("Request sent for today")
                            else -> Text("Request more SMS/day")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            val normalizedPhone = phoneNormalizer.normalize(phone, country)
            // The device holds a Pakistani SIM, so it is the recipient's country
            // that decides whether a text can be sent at all.
            val smsReaches = normalizedPhone != null && phoneNormalizer.isPakistaniMobile(normalizedPhone)

            PhoneNumberField(
                number = phone,
                onNumberChange = { phone = it },
                country = country,
                onCountryChange = { country = it },
                label = "Phone Number",
                modifier = Modifier.fillMaxWidth(),
            )
            // Which routes this message could actually take. Offering one that
            // cannot work is how a message gets "sent" and never arrives.
            val canUseWhatsApp = uiState.whatsAppAvailable && normalizedPhone != null
            var overWhatsApp by remember(canUseWhatsApp, smsReaches) {
                mutableStateOf(canUseWhatsApp && !smsReaches)
            }
            if (canUseWhatsApp && smsReaches) {
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !overWhatsApp,
                        onClick = { overWhatsApp = false },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text("Text message") }
                    SegmentedButton(
                        selected = overWhatsApp,
                        onClick = { overWhatsApp = true },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text("WhatsApp") }
                }
            } else if (normalizedPhone != null && !smsReaches) {
                Spacer(Modifier.height(4.dp))
                Text(
                    if (canUseWhatsApp) {
                        "Text messages go through one device with a Pakistani SIM, so this number " +
                            "will be reached on WhatsApp instead."
                    } else {
                        "Text messages go through one device with a Pakistani SIM, so this number " +
                            "cannot be reached. Connect your WhatsApp gateway in Profile to message it."
                    },
                    color =
                        if (canUseWhatsApp) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(12.dp))
            val maxChars = EnqueueSmsUseCase.MAX_USER_MESSAGE_CHARS
            OutlinedTextField(
                value = message,
                onValueChange = { if (it.length <= maxChars) message = it },
                label = { Text("Message") },
                minLines = 4,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
                supportingText = {
                    Text("${message.length}/$maxChars — a \"Sent by ${uiState.user?.phoneNumber ?: "your number"} via Spotwire\" signature is added automatically")
                },
            )

            uiState.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(24.dp))
            val canSend = message.isNotBlank() && !uiState.isSending &&
                    if (overWhatsApp) canUseWhatsApp else smsReaches && uiState.canSendMore

            Button(
                onClick = { onSend(phone, message, country, overWhatsApp) },
                enabled = canSend,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (uiState.isSending) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text(
                        if (overWhatsApp) "Send on WhatsApp" else "Send SMS",
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            }

            if (uiState.isLoading) {
                Spacer(Modifier.height(24.dp))
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            }

            Spacer(Modifier.height(32.dp))
            Text(
                "v${BuildConfig.VERSION_NAME}",
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
            )
        }
    }
}

// Quota-increase request form — lets the user write a custom note to the admin.
@Composable
private fun RequestMoreDialog(
    isSending: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var note by remember { mutableStateOf("") }
    val maxChars = 300
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Request more SMS/day") },
        text = {
            Column {
                Text(
                    "Tell the admin why you need a higher daily quota. Your email and " +
                        "phone number are included automatically. You can send one request per day.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { if (it.length <= maxChars) note = it },
                    label = { Text("Your message") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text("I'm a student and can't afford the pro version, please allocate X quota")
                    },
                    supportingText = { Text("${note.length}/$maxChars") },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(note) },
                enabled = note.isNotBlank() && !isSending,
            ) {
                if (isSending) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text("Send Request")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ── Preview helpers ───────────────────────────────────────────────────────────

private val previewUser = User(
    uid = "preview", email = "alice@spotwire.com", name = "Alice",
    emailVerified = true, phoneVerified = true,
    assignedQuota = 10, remainingQuota = 7, lastQuotaResetDate = "2026-06-24",
    phoneNumber = "+923001234567",
)

private val unverifiedUser = previewUser.copy(emailVerified = false, phoneVerified = false)

@Preview(showBackground = true, name = "Send — Ready")
@Composable
private fun SendReadyPreview() {
    SpotwireTheme {
        SendContent(
            uiState = SendUiState(user = previewUser, effectiveQuota = 10),
            onSend = { _, _, _, _ -> }, onClearMessage = {},
        )
    }
}

@Preview(showBackground = true, name = "Send — Quota low")
@Composable
private fun SendQuotaLowPreview() {
    SpotwireTheme {
        SendContent(
            uiState = SendUiState(user = previewUser.copy(remainingQuota = 1), effectiveQuota = 10),
            onSend = { _, _, _, _ -> }, onClearMessage = {},
        )
    }
}

@Preview(showBackground = true, name = "Send — Phone unverified (0 SMS)")
@Composable
private fun SendUnverifiedPreview() {
    SpotwireTheme {
        SendContent(
            uiState = SendUiState(user = unverifiedUser, effectiveQuota = 0),
            onSend = { _, _, _, _ -> }, onClearMessage = {},
        )
    }
}
