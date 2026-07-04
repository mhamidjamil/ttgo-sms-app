package com.textgate.app.presentation.send

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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.textgate.app.core.theme.TextGateTheme
import com.textgate.app.core.theme.WarningAmber
import com.textgate.app.core.theme.WarningAmberBorder
import com.textgate.app.core.utils.PhoneNormalizer
import com.textgate.app.domain.model.User
import com.textgate.app.domain.usecase.sms.EnqueueSmsUseCase
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

@Composable
private fun SendContent(
    uiState: SendUiState,
    onSend: (String, String) -> Unit,
    onClearMessage: () -> Unit,
    onRequestMore: (String) -> Unit = {},
    onClearRequestMoreResult: () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val phoneNormalizer = remember { PhoneNormalizer() }
    var showRequestDialog by remember { mutableStateOf(false) }

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
            snackbarHostState.showSnackbar(it)
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
            if (user != null && !user.phoneVerified) {
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
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            if (user != null) {
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

            var phone by remember { mutableStateOf("") }
            var message by remember { mutableStateOf("") }
            val phoneError = phoneNormalizer.validationError(phone)

            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text("Phone Number (Pakistani only, e.g. 03001234567)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
                isError = phoneError != null,
                supportingText = phoneError?.let { { Text(it) } },
            )
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
                    Text("${message.length}/$maxChars — a \"Sent by ${uiState.user?.phoneNumber ?: "your number"} via TextGate\" signature is added automatically")
                },
            )

            uiState.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(24.dp))
            val canSend = phone.isNotBlank() && message.isNotBlank() &&
                    phoneError == null && !uiState.isSending && uiState.canSendMore

            Button(
                onClick = { onSend(phone, message) },
                enabled = canSend,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (uiState.isSending) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text("Send SMS", style = MaterialTheme.typography.titleLarge)
                }
            }

            if (uiState.isLoading) {
                Spacer(Modifier.height(24.dp))
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            }
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
    uid = "preview", email = "alice@textgate.com", name = "Alice",
    emailVerified = true, phoneVerified = true,
    assignedQuota = 10, remainingQuota = 7, lastQuotaResetDate = "2026-06-24",
    phoneNumber = "+923001234567",
)

private val unverifiedUser = previewUser.copy(emailVerified = false, phoneVerified = false)

@Preview(showBackground = true, name = "Send — Ready")
@Composable
private fun SendReadyPreview() {
    TextGateTheme {
        SendContent(
            uiState = SendUiState(user = previewUser, effectiveQuota = 10),
            onSend = { _, _ -> }, onClearMessage = {},
        )
    }
}

@Preview(showBackground = true, name = "Send — Quota low")
@Composable
private fun SendQuotaLowPreview() {
    TextGateTheme {
        SendContent(
            uiState = SendUiState(user = previewUser.copy(remainingQuota = 1), effectiveQuota = 10),
            onSend = { _, _ -> }, onClearMessage = {},
        )
    }
}

@Preview(showBackground = true, name = "Send — Phone unverified (0 SMS)")
@Composable
private fun SendUnverifiedPreview() {
    TextGateTheme {
        SendContent(
            uiState = SendUiState(user = unverifiedUser, effectiveQuota = 0),
            onSend = { _, _ -> }, onClearMessage = {},
        )
    }
}
