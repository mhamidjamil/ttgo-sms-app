package com.spotwire.app.presentation.whatsapp

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.spotwire.app.core.theme.SpotwireTheme
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
        onStartLinking = viewModel::startLinking,
        onRefresh = viewModel::refreshStatuses,
        onCheckGateway = viewModel::checkGateway,
        onSendTest = viewModel::sendTest,
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
    onStartLinking: () -> Unit,
    onRefresh: () -> Unit,
    onCheckGateway: () -> Unit,
    onSendTest: () -> Unit,
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
            GatewayHealthCard(uiState, onCheckGateway)
            Spacer(Modifier.height(16.dp))

            if (uiState.provisioned) {
                OwnGatewayCard(uiState, onDisconnectKey)
                if (uiState.ownStatus != "connected") {
                    Spacer(Modifier.height(16.dp))
                    QrPanel(uiState, onStartLinking)
                }
            }

            // ── Your own gateway ──────────────────────────────────────────────
            // The only way to send: every message leaves from a WhatsApp number
            // its own owner linked, never from a number shared with strangers.
            if (!uiState.provisioned) {
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
                        enabled = !uiState.isBusy,
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
                "Arrival notifications go out over WhatsApp from your own number. A Pakistani " +
                    "recipient also falls back to a text message when WhatsApp cannot deliver.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** What the user sees once their own portal key is connected. */
/**
 * Whether the service itself is answering, asked without any credential. Kept at
 * the top because it is the first question worth asking when nothing sends: a
 * key problem and an outage look identical from inside a failed message.
 */
@Composable
private fun GatewayHealthCard(uiState: WhatsAppUiState, onCheck: () -> Unit) {
    val (dot, headline) = when {
        uiState.gatewayUp == null -> MaterialTheme.colorScheme.outline to "Gateway not checked yet"
        !uiState.gatewayUp -> MaterialTheme.colorScheme.error to "Gateway is not answering"
        uiState.gatewayWhatsAppConnected == false ->
            MaterialTheme.colorScheme.error to "Gateway is up, but no WhatsApp number is linked to it"
        else -> MaterialTheme.colorScheme.primary to "Gateway is up"
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(8.dp))
                Text(headline, style = MaterialTheme.typography.titleSmall)
            }
            uiState.gatewayCheckedLabel?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            if (uiState.gatewayUp == false) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Messages cannot be delivered on WhatsApp until it is back. Nothing is lost: " +
                        "send them by hand for now and try again shortly.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onCheck, enabled = !uiState.isCheckingGateway) {
                if (uiState.isCheckingGateway) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("Check gateway")
                }
            }
        }
    }
}

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
    // Masked: the portal shows this once and never again, so it is usually being
    // typed or pasted somewhere other people can see the screen.
    var secretVisible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = uiState.keySecret,
        onValueChange = onKeySecretChange,
        label = { Text("Secret") },
        placeholder = { Text("was_…") },
        singleLine = true,
        enabled = !uiState.isSavingKey,
        isError = uiState.keyError != null,
        visualTransformation =
            if (secretVisible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { secretVisible = !secretVisible }) {
                Icon(
                    imageVector = if (secretVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (secretVisible) "Hide secret" else "Show secret",
                )
            }
        },
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

@Preview(showBackground = true, name = "WhatsApp - nothing connected yet")
@Composable
private fun WhatsAppNotConnectedPreview() {
    SpotwireTheme {
        WhatsAppContent(
            uiState = WhatsAppUiState(
                isLoading = false,
                portalUrl = "https://w2.innovorix.com",
                showKeyForm = true,
                gatewayUp = true,
                gatewayCheckedLabel = "Checked at 4:12 PM",
            ),
            onStartLinking = {}, onRefresh = {},
            onCheckGateway = {}, onSendTest = {}, onKeyIdChange = {}, onKeySecretChange = {},
            onToggleKeyForm = {}, onSaveKey = {}, onDisconnectKey = {}, onOpenPortal = {}, onBack = {},
        )
    }
}

@Preview(showBackground = true, name = "WhatsApp - own gateway connected")
@Composable
private fun WhatsAppOwnKeyPreview() {
    SpotwireTheme {
        WhatsAppContent(
            uiState = WhatsAppUiState(
                isLoading = false, provisioned = true,
                ownKeyPhone = "923001234567", ownStatus = "connected",
                portalUrl = "https://w2.innovorix.com",
                gatewayUp = true,
                gatewayCheckedLabel = "Checked at 4:12 PM",
            ),
            onStartLinking = {}, onRefresh = {},
            onCheckGateway = {}, onSendTest = {}, onKeyIdChange = {}, onKeySecretChange = {},
            onToggleKeyForm = {}, onSaveKey = {}, onDisconnectKey = {}, onOpenPortal = {}, onBack = {},
        )
    }
}

@Preview(showBackground = true, name = "WhatsApp - gateway down")
@Composable
private fun WhatsAppGatewayDownPreview() {
    SpotwireTheme {
        WhatsAppContent(
            uiState = WhatsAppUiState(
                isLoading = false, provisioned = true,
                ownKeyPhone = "923001234567", ownStatus = "connected",
                portalUrl = "https://w2.innovorix.com",
                gatewayUp = false,
                gatewayCheckedLabel = "Checked at 4:12 PM",
            ),
            onStartLinking = {}, onRefresh = {},
            onCheckGateway = {}, onSendTest = {}, onKeyIdChange = {}, onKeySecretChange = {},
            onToggleKeyForm = {}, onSaveKey = {}, onDisconnectKey = {}, onOpenPortal = {}, onBack = {},
        )
    }
}
