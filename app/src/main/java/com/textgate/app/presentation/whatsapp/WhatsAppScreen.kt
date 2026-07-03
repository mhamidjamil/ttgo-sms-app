package com.textgate.app.presentation.whatsapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.textgate.app.BuildConfig
import com.textgate.app.core.theme.TextGateTheme
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsAppScreen(
    onBack: () -> Unit,
    viewModel: WhatsAppViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    WhatsAppContent(
        uiState = uiState,
        onSaveLink = viewModel::saveLink,
        onCheckStatus = viewModel::checkStatus,
        onSendTest = viewModel::sendTest,
        onUnlink = viewModel::unlink,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WhatsAppContent(
    uiState: WhatsAppUiState,
    onSaveLink: (String, String) -> Unit,
    onCheckStatus: () -> Unit,
    onSendTest: () -> Unit,
    onUnlink: () -> Unit,
    onBack: () -> Unit,
) {
    var apiKey by remember { mutableStateOf("") }
    var sessionId by remember { mutableStateOf("") }
    var prefilled by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.savedSessionId) {
        if (!prefilled && uiState.savedSessionId.isNotBlank()) {
            sessionId = uiState.savedSessionId
            prefilled = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WhatsApp") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // ── How it works ─────────────────────────────────────────────────
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("How to link WhatsApp", fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        """
                        1. Open ${BuildConfig.WHATSAPP_SERVICE_URL} in a browser and sign up (email + password).
                        2. Verify your email with the 6-digit code the service sends you.
                        3. On the service dashboard, create a session (any name, e.g. "myphone") and scan the QR with WhatsApp → Linked Devices.
                        4. Copy your personal API key (starts with "wa_") from the dashboard.
                        5. Paste the key and the session name below.
                        """.trimIndent(),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            // ── Link form ────────────────────────────────────────────────────
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("wa_…") },
                supportingText = if (apiKey.isNotBlank() && !apiKey.trim().startsWith("wa_")) {
                    { Text("Keys usually start with \"wa_\" — double-check, but non-standard keys are accepted") }
                } else null,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = sessionId,
                onValueChange = { sessionId = it },
                label = { Text("Session name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("myphone") },
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onSaveLink(apiKey, sessionId) },
                enabled = !uiState.isBusy && apiKey.isNotBlank() && sessionId.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text(if (uiState.isLinked) "Update Link" else "Save Link") }

            // ── Status + actions ─────────────────────────────────────────────
            if (uiState.isLinked) {
                Spacer(Modifier.height(16.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("Session status: ", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                uiState.status ?: "unknown",
                                fontWeight = FontWeight.SemiBold,
                                color = when (uiState.status) {
                                    "connected" -> MaterialTheme.colorScheme.primary
                                    null -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                    else -> MaterialTheme.colorScheme.error
                                },
                            )
                            if (uiState.isBusy) {
                                Spacer(Modifier.width(8.dp))
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row {
                            OutlinedButton(onClick = onCheckStatus, enabled = !uiState.isBusy) {
                                Text("Check Status")
                            }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = onSendTest,
                                enabled = !uiState.isBusy && uiState.status == "connected",
                            ) { Text("Send Test") }
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onUnlink) {
                            Text("Unlink WhatsApp", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            uiState.error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            uiState.info?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "Once linked and connected, automatic arrival notifications are sent via WhatsApp " +
                    "(free, no SMS quota) and fall back to SMS when WhatsApp is unavailable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

@Preview(showBackground = true, name = "WhatsApp — Not linked")
@Composable
private fun WhatsAppNotLinkedPreview() {
    TextGateTheme {
        WhatsAppContent(
            uiState = WhatsAppUiState(),
            onSaveLink = { _, _ -> }, onCheckStatus = {}, onSendTest = {}, onUnlink = {}, onBack = {},
        )
    }
}

@Preview(showBackground = true, name = "WhatsApp — Connected")
@Composable
private fun WhatsAppConnectedPreview() {
    TextGateTheme {
        WhatsAppContent(
            uiState = WhatsAppUiState(isLinked = true, savedSessionId = "myphone", status = "connected"),
            onSaveLink = { _, _ -> }, onCheckStatus = {}, onSendTest = {}, onUnlink = {}, onBack = {},
        )
    }
}

@Preview(showBackground = true, name = "WhatsApp — Error")
@Composable
private fun WhatsAppErrorPreview() {
    TextGateTheme {
        WhatsAppContent(
            uiState = WhatsAppUiState(
                isLinked = true, savedSessionId = "myphone",
                error = "WhatsApp session not connected — re-link it on the service dashboard",
            ),
            onSaveLink = { _, _ -> }, onCheckStatus = {}, onSendTest = {}, onUnlink = {}, onBack = {},
        )
    }
}
