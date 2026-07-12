package com.textgate.app.presentation.whatsapp

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
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
    val context = LocalContext.current
    WhatsAppContent(
        uiState = uiState,
        onSelectShared = viewModel::selectShared,
        onSelectOwn = viewModel::selectOwn,
        onStartLinking = viewModel::startLinking,
        onRefresh = viewModel::refreshStatuses,
        onSendTest = viewModel::sendTest,
        onRetrySetup = viewModel::setup,
        onKeyIdChange = viewModel::setKeyId,
        onKeySecretChange = viewModel::setKeySecret,
        onToggleKeyForm = viewModel::toggleKeyForm,
        onSaveKey = viewModel::saveOwnKey,
        onDisconnectKey = viewModel::disconnectOwnKey,
        onOpenPortal = {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uiState.portalUrl)))
            }
        },
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
    onKeyIdChange: (String) -> Unit,
    onKeySecretChange: (String) -> Unit,
    onToggleKeyForm: () -> Unit,
    onSaveKey: () -> Unit,
    onDisconnectKey: () -> Unit,
    onOpenPortal: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
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
        if (uiState.isLoading) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            when {
                // The gateway turned the automatic setup down. Its own words are
                // kept, because they say which of several things went wrong.
                uiState.setupError != null -> {
                    Text(
                        uiState.setupError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onRetrySetup) { Text("Try Again") }
                }

                !uiState.eligible -> {
                    Text("Automatic setup needs your phone and email verified", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Verify both in Profile and WhatsApp sets itself up. You can also connect " +
                            "your own gateway below without verifying anything.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                uiState.ownKey -> OwnGatewayCard(uiState, onDisconnectKey)

                else -> {
                    Text(
                        "Your WhatsApp account was set up automatically. Choose how arrival " +
                            "messages are sent:",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(16.dp))
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
                    if (uiState.mode == MODE_OWN && uiState.ownStatus != "connected") {
                        Spacer(Modifier.height(16.dp))
                        QrPanel(uiState, onStartLinking)
                    }
                }
            }

            // ── Your own gateway ──────────────────────────────────────────────
            // Offered whenever nothing is connected yet, whatever the reason:
            // it is the one path that works without the gateway's cooperation.
            if (!uiState.ownKey) {
                Spacer(Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))
                OwnGatewaySetup(
                    uiState = uiState,
                    onKeyIdChange = onKeyIdChange,
                    onKeySecretChange = onKeySecretChange,
                    onToggleKeyForm = onToggleKeyForm,
                    onSaveKey = onSaveKey,
                    onOpenPortal = onOpenPortal,
                )
            }

            if (uiState.provisioned) {
                Spacer(Modifier.height(20.dp))
                Row {
                    OutlinedButton(onClick = onRefresh, enabled = !uiState.isBusy) { Text("Refresh") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = onSendTest,
                        enabled = !uiState.isBusy && (
                            uiState.ownKey ||
                                uiState.mode == MODE_SHARED && uiState.sharedConnected == true ||
                                uiState.mode == MODE_OWN && uiState.ownStatus == "connected"
                            ),
                    ) { Text("Send Test") }
                    if (uiState.isBusy) {
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(
                            Modifier.size(18.dp).align(Alignment.CenterVertically),
                            strokeWidth = 2.dp,
                        )
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
                "Arrival notifications go out via WhatsApp using the setup above and fall " +
                    "back to SMS automatically when WhatsApp is unavailable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** What the user sees once their own portal key is connected. */
@Composable
private fun OwnGatewayCard(uiState: WhatsAppUiState, onDisconnect: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Connected to your own gateway",
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                uiState.ownKeyPhone?.let { "Messages are sent from +$it" }
                    ?: "Messages are sent from your own linked WhatsApp number",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            uiState.ownStatus?.let { status ->
                Spacer(Modifier.height(8.dp))
                val (label, colour) = when (status) {
                    "connected" -> "WhatsApp is connected" to MaterialTheme.colorScheme.primary
                    "qr_ready", "connecting" -> "Still connecting on the portal" to MaterialTheme.colorScheme.onSurfaceVariant
                    else -> "Not connected — re-link the number on the portal" to MaterialTheme.colorScheme.error
                }
                Text(label, style = MaterialTheme.typography.bodySmall, color = colour)
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onDisconnect, enabled = !uiState.isBusy) { Text("Use a different key") }
        }
    }
}

/**
 * The way through when the gateway will not provision an account by itself: the
 * user makes their own account on the portal, mints a key there, and pastes it
 * back here. The secret is only shown once on the portal, which is why the
 * instructions say to copy it before leaving that screen.
 */
@Composable
private fun OwnGatewaySetup(
    uiState: WhatsAppUiState,
    onKeyIdChange: (String) -> Unit,
    onKeySecretChange: (String) -> Unit,
    onToggleKeyForm: () -> Unit,
    onSaveKey: () -> Unit,
    onOpenPortal: () -> Unit,
) {
    Text("Use your own WhatsApp gateway", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    Text(
        "Make your own account on the gateway, link your WhatsApp to it, and paste the " +
            "key it gives you. Messages then go out from your own number.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (!uiState.showKeyForm) {
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onToggleKeyForm) { Text("Set up my own gateway") }
        return
    }

    Spacer(Modifier.height(16.dp))
    Button(onClick = onOpenPortal, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Open ${uiState.portalUrl.substringAfter("://").ifBlank { "the gateway portal" }}")
    }

    Spacer(Modifier.height(12.dp))
    listOf(
        "Sign up with your email and confirm the code it sends you.",
        "Link your WhatsApp number by scanning the QR on that page.",
        "Open API keys, create a key, and copy the key id and secret. The secret is shown only once.",
        "Paste both below and tap Connect.",
    ).forEachIndexed { index, step ->
        Row(Modifier.padding(bottom = 6.dp)) {
            Text(
                "${index + 1}.",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(20.dp),
            )
            Text(step, style = MaterialTheme.typography.bodySmall)
        }
    }

    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = uiState.keyId,
        onValueChange = onKeyIdChange,
        label = { Text("Key id") },
        placeholder = { Text("wak_…") },
        singleLine = true,
        enabled = !uiState.isSavingKey,
        isError = uiState.keyError != null,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = uiState.keySecret,
        onValueChange = onKeySecretChange,
        label = { Text("Secret") },
        placeholder = { Text("was_…") },
        singleLine = true,
        enabled = !uiState.isSavingKey,
        isError = uiState.keyError != null,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
        modifier = Modifier.fillMaxWidth(),
    )

    uiState.keyError?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }

    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = onSaveKey,
            enabled = !uiState.isSavingKey && uiState.keyId.isNotBlank() && uiState.keySecret.isNotBlank(),
        ) { Text("Connect") }
        if (uiState.isSavingKey) {
            Spacer(Modifier.width(12.dp))
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Checking with the gateway…", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun QrPanel(uiState: WhatsAppUiState, onStartLinking: () -> Unit) {
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Preview(showBackground = true, name = "WhatsApp — SSO off, own gateway offered")
@Composable
private fun WhatsAppSsoOffPreview() {
    TextGateTheme {
        WhatsAppContent(
            uiState = WhatsAppUiState(
                isLoading = false,
                setupError = "SSO provisioning is not configured on this server",
                portalUrl = "https://w2.innovorix.com",
                showKeyForm = true,
            ),
            onSelectShared = {}, onSelectOwn = {}, onStartLinking = {}, onRefresh = {},
            onSendTest = {}, onRetrySetup = {}, onKeyIdChange = {}, onKeySecretChange = {},
            onToggleKeyForm = {}, onSaveKey = {}, onDisconnectKey = {}, onOpenPortal = {}, onBack = {},
        )
    }
}

@Preview(showBackground = true, name = "WhatsApp — own gateway connected")
@Composable
private fun WhatsAppOwnKeyPreview() {
    TextGateTheme {
        WhatsAppContent(
            uiState = WhatsAppUiState(
                isLoading = false, provisioned = true, ownKey = true,
                ownKeyPhone = "923001234567", ownStatus = "connected",
                portalUrl = "https://w2.innovorix.com",
            ),
            onSelectShared = {}, onSelectOwn = {}, onStartLinking = {}, onRefresh = {},
            onSendTest = {}, onRetrySetup = {}, onKeyIdChange = {}, onKeySecretChange = {},
            onToggleKeyForm = {}, onSaveKey = {}, onDisconnectKey = {}, onOpenPortal = {}, onBack = {},
        )
    }
}

@Preview(showBackground = true, name = "WhatsApp — shared ready")
@Composable
private fun WhatsAppSharedPreview() {
    TextGateTheme {
        WhatsAppContent(
            uiState = WhatsAppUiState(
                isLoading = false, provisioned = true, sharedConnected = true,
                ownStatus = "disconnected", portalUrl = "https://w2.innovorix.com",
            ),
            onSelectShared = {}, onSelectOwn = {}, onStartLinking = {}, onRefresh = {},
            onSendTest = {}, onRetrySetup = {}, onKeyIdChange = {}, onKeySecretChange = {},
            onToggleKeyForm = {}, onSaveKey = {}, onDisconnectKey = {}, onOpenPortal = {}, onBack = {},
        )
    }
}
