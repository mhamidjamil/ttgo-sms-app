package com.textgate.app.presentation.whatsapp

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.textgate.app.core.theme.TextGateTheme
import com.textgate.app.domain.repository.WhatsAppRepository.Companion.MODE_OWN
import com.textgate.app.domain.repository.WhatsAppRepository.Companion.MODE_SHARED
import org.koin.androidx.compose.koinViewModel

@Composable
fun WhatsAppScreen(
    onBack: () -> Unit,
    viewModel: WhatsAppViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    WhatsAppContent(
        uiState = uiState,
        onSelectShared = viewModel::selectShared,
        onSelectOwn = viewModel::selectOwn,
        onStartLinking = viewModel::startLinking,
        onRefresh = viewModel::refreshStatuses,
        onSendTest = viewModel::sendTest,
        onRetrySetup = viewModel::setup,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WhatsAppContent(
    uiState: WhatsAppUiState,
    onSelectShared: () -> Unit,
    onSelectOwn: () -> Unit,
    onStartLinking: () -> Unit,
    onRefresh: () -> Unit,
    onSendTest: () -> Unit,
    onRetrySetup: () -> Unit,
    onBack: () -> Unit,
) {
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
        when {
            uiState.isLoading -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            !uiState.eligible -> Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Almost there!", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Verify your phone number and email first — WhatsApp is set up " +
                        "automatically right after, nothing else to configure.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            uiState.setupError != null -> Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    uiState.setupError,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onRetrySetup) { Text("Try Again") }
            }

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "Your WhatsApp account was set up automatically. Choose how arrival " +
                        "messages are sent:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))

                // ── Mode: shared number (default) ─────────────────────────────
                ModeCard(
                    selected = uiState.mode == MODE_SHARED,
                    title = "TextGate shared number (default)",
                    subtitle = when (uiState.sharedConnected) {
                        true -> "Ready — no setup needed"
                        false -> "Temporarily offline — the admin needs to re-link it"
                        null -> "Checking availability…"
                    },
                    onClick = onSelectShared,
                )
                Spacer(Modifier.height(12.dp))

                // ── Mode: own WhatsApp ────────────────────────────────────────
                ModeCard(
                    selected = uiState.mode == MODE_OWN,
                    title = "Use My WhatsApp",
                    subtitle = when {
                        uiState.ownStatus == "connected" -> "Linked — messages come from your own number"
                        uiState.isLinking -> "Scan the QR below with WhatsApp → Linked Devices"
                        else -> "Requires a one-time QR scan with your phone"
                    },
                    onClick = onSelectOwn,
                )

                // ── QR flow ───────────────────────────────────────────────────
                if (uiState.mode == MODE_OWN && uiState.ownStatus != "connected") {
                    Spacer(Modifier.height(16.dp))
                    val qrBitmap: ImageBitmap? = remember(uiState.qrBase64) {
                        uiState.qrBase64?.let { dataUrl ->
                            runCatching {
                                val bytes = Base64.decode(dataUrl.substringAfter("base64,"), Base64.DEFAULT)
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                            }.getOrNull()
                        }
                    }
                    when {
                        qrBitmap != null -> Column(
                            Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Image(
                                bitmap = qrBitmap,
                                contentDescription = "WhatsApp link QR",
                                modifier = Modifier.size(240.dp),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "WhatsApp → Settings → Linked Devices → Link a Device",
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                            )
                        }
                        uiState.isLinking -> Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Preparing your QR code…", style = MaterialTheme.typography.bodySmall)
                        }
                        else -> OutlinedButton(onClick = onStartLinking, modifier = Modifier.fillMaxWidth()) {
                            Text("Link My WhatsApp")
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))
                Row {
                    OutlinedButton(onClick = onRefresh, enabled = !uiState.isBusy) { Text("Refresh") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = onSendTest,
                        enabled = !uiState.isBusy &&
                            (uiState.mode == MODE_SHARED && uiState.sharedConnected == true ||
                                uiState.mode == MODE_OWN && uiState.ownStatus == "connected"),
                    ) { Text("Send Test") }
                    if (uiState.isBusy) {
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(
                            Modifier.size(18.dp).align(Alignment.CenterVertically),
                            strokeWidth = 2.dp,
                        )
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
                    "Arrival notifications go out via WhatsApp using the mode above and fall " +
                        "back to SMS automatically when WhatsApp is unavailable.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
private fun ModeCard(
    selected: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val colors = if (selected) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    } else {
        CardDefaults.cardColors()
    }
    Card(modifier = Modifier.fillMaxWidth(), colors = colors, onClick = onClick) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onClick)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Preview(showBackground = true, name = "WhatsApp — Shared ready")
@Composable
private fun WhatsAppSharedPreview() {
    TextGateTheme {
        WhatsAppContent(
            uiState = WhatsAppUiState(
                isLoading = false, provisioned = true, sharedConnected = true, ownStatus = "disconnected",
            ),
            onSelectShared = {}, onSelectOwn = {}, onStartLinking = {},
            onRefresh = {}, onSendTest = {}, onRetrySetup = {}, onBack = {},
        )
    }
}

@Preview(showBackground = true, name = "WhatsApp — Own linked")
@Composable
private fun WhatsAppOwnPreview() {
    TextGateTheme {
        WhatsAppContent(
            uiState = WhatsAppUiState(
                isLoading = false, provisioned = true, mode = MODE_OWN,
                sharedConnected = true, ownStatus = "connected",
            ),
            onSelectShared = {}, onSelectOwn = {}, onStartLinking = {},
            onRefresh = {}, onSendTest = {}, onRetrySetup = {}, onBack = {},
        )
    }
}

@Preview(showBackground = true, name = "WhatsApp — Maintenance")
@Composable
private fun WhatsAppMaintenancePreview() {
    TextGateTheme {
        WhatsAppContent(
            uiState = WhatsAppUiState(
                isLoading = false,
                setupError = "WhatsApp service is currently under maintenance. Please try again in a few hours.",
            ),
            onSelectShared = {}, onSelectOwn = {}, onStartLinking = {},
            onRefresh = {}, onSendTest = {}, onRetrySetup = {}, onBack = {},
        )
    }
}

@Preview(showBackground = true, name = "WhatsApp — Not eligible")
@Composable
private fun WhatsAppNotEligiblePreview() {
    TextGateTheme {
        WhatsAppContent(
            uiState = WhatsAppUiState(isLoading = false, eligible = false),
            onSelectShared = {}, onSelectOwn = {}, onStartLinking = {},
            onRefresh = {}, onSendTest = {}, onRetrySetup = {}, onBack = {},
        )
    }
}
