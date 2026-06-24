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
import org.koin.androidx.compose.koinViewModel

@Composable
fun SendScreen(viewModel: SendViewModel = koinViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    SendContent(
        uiState = uiState,
        onSend = viewModel::send,
        onClearMessage = viewModel::clearSentMessage,
    )
}

@Composable
private fun SendContent(
    uiState: SendUiState,
    onSend: (String, String) -> Unit,
    onClearMessage: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val phoneNormalizer = remember { PhoneNormalizer() }

    LaunchedEffect(uiState.sentMessage) {
        uiState.sentMessage?.let {
            snackbarHostState.showSnackbar(it)
            onClearMessage()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
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
            if (user != null && (!user.emailVerified || !user.phoneVerified)) {
                val unverified = buildList {
                    if (!user.emailVerified) add("email")
                    if (!user.phoneVerified) add("phone")
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(WarningAmber)
                        .border(1.dp, WarningAmberBorder)
                        .padding(12.dp),
                ) {
                    Text(
                        "⚠ ${unverified.joinToString(" & ").replaceFirstChar { it.uppercaseChar() }} not verified — limited to ${uiState.effectiveQuota} SMS/day.",
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
                Spacer(Modifier.height(16.dp))
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
            OutlinedTextField(
                value = message,
                onValueChange = { if (it.length <= 500) message = it },
                label = { Text("Message") },
                minLines = 4,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("${message.length}/500") },
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

@Preview(showBackground = true, name = "Send — Unverified (2 SMS cap)")
@Composable
private fun SendUnverifiedPreview() {
    TextGateTheme {
        SendContent(
            uiState = SendUiState(user = unverifiedUser, effectiveQuota = 2),
            onSend = { _, _ -> }, onClearMessage = {},
        )
    }
}
