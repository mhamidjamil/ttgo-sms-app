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
import androidx.compose.ui.unit.dp
import com.textgate.app.core.theme.WarningAmber
import com.textgate.app.core.theme.WarningAmberBorder
import com.textgate.app.core.utils.PhoneNormalizer
import org.koin.androidx.compose.koinViewModel

@Composable
fun SendScreen(viewModel: SendViewModel = koinViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val phoneNormalizer = remember { PhoneNormalizer() }

    LaunchedEffect(uiState.sentMessage) {
        uiState.sentMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSentMessage()
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

            // Unverified account banner
            val user = uiState.user
            if (user != null && (!user.emailVerified || !user.phoneVerified)) {
                val reasons = buildList {
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
                        "⚠ ${reasons.joinToString(" & ").replaceFirstChar { it.uppercaseChar() }} not verified — limited to ${uiState.effectiveQuota} SMS/day.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            // Quota progress
            if (user != null) {
                val remaining = user.remainingQuota.coerceAtMost(uiState.effectiveQuota)
                val total = uiState.effectiveQuota.coerceAtLeast(1)
                Text(
                    "$remaining / $total SMS remaining today",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                LinearProgressIndicator(
                    progress = { remaining.toFloat() / total },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    color = if (remaining > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
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
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(24.dp))
            val canSend = phone.isNotBlank() && message.isNotBlank() &&
                    phoneError == null &&
                    !uiState.isSending && (uiState.user?.remainingQuota ?: 0) > 0

            Button(
                onClick = { viewModel.send(phone, message) },
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
