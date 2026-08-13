package com.spotwire.app.presentation.auth

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.spotwire.app.core.theme.SpotwireTheme
import org.koin.androidx.compose.koinViewModel

@Composable
fun PhoneVerifyScreen(
    onVerified: () -> Unit,
    onSkip: () -> Unit,
    viewModel: PhoneVerifyViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(uiState.success) { if (uiState.success) onVerified() }
    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }
    PhoneVerifyContent(
        uiState = uiState,
        onOpenWhatsApp = {
            val opened = runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uiState.waLink)))
            }.isSuccess
            // Watch either way: they may have sent it from another device, and a
            // phone with no WhatsApp installed still gets a way forward.
            if (opened) viewModel.watchForOptIn()
        },
        onCheckNow = viewModel::watchForOptIn,
        onRequestCode = viewModel::requestCode,
        onRetryGateway = viewModel::checkGateway,
        onUseSms = viewModel::useSmsFallback,
        onVerify = viewModel::verify,
        onSkip = onSkip,
    )
}

@Composable
private fun PhoneVerifyContent(
    uiState: PhoneVerifyUiState,
    onOpenWhatsApp: () -> Unit,
    onCheckNow: () -> Unit,
    onRequestCode: () -> Unit,
    onRetryGateway: () -> Unit,
    onUseSms: () -> Unit,
    onVerify: (String) -> Unit,
    onSkip: () -> Unit,
) {
    var code by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Verify your number", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            uiState.phoneNumber.ifBlank { "No number saved yet" },
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(20.dp))

        when {
            uiState.checkingGateway -> {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                Spacer(Modifier.height(12.dp))
                Text("Checking the verification service", style = MaterialTheme.typography.bodyMedium)
            }

            uiState.gatewayUp == false && !uiState.usingSmsFallback -> GatewayDownSection(
                uiState = uiState,
                onRetryGateway = onRetryGateway,
                onUseSms = onUseSms,
            )

            else -> {
                if (!uiState.usingSmsFallback) {
                    WhatsAppOptInSection(
                        uiState = uiState,
                        onOpenWhatsApp = onOpenWhatsApp,
                        onCheckNow = onCheckNow,
                        onRequestCode = onRequestCode,
                    )
                } else {
                    Text(
                        "We sent a code by text to ${uiState.phoneNumber}. It is valid for one hour.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }

                if (uiState.codeSent) {
                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(24.dp))
                    CodeEntry(uiState = uiState, code = code, onCodeChange = { code = it }, onVerify = onVerify)
                }
            }
        }

        uiState.error?.let {
            Spacer(Modifier.height(12.dp))
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
            Text("Do this later (no messages can be sent until then)")
        }
    }
}

@Composable
private fun WhatsAppOptInSection(
    uiState: PhoneVerifyUiState,
    onOpenWhatsApp: () -> Unit,
    onCheckNow: () -> Unit,
    onRequestCode: () -> Unit,
) {
    // WhatsApp will not let anyone message a stranger first, so the person has to
    // write to us before we may write to them. That message IS the permission.
    Text(
        "Send us one WhatsApp message so we may reply. Tap below and press send: " +
            "the message is already written for you.",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
    if (uiState.waPhrase.isNotBlank()) {
        Spacer(Modifier.height(10.dp))
        Card(Modifier.fillMaxWidth()) {
            Text(
                "\"${uiState.waPhrase}\"",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(14.dp),
            )
        }
    }
    Spacer(Modifier.height(14.dp))
    Button(
        onClick = onOpenWhatsApp,
        enabled = uiState.waLink.isNotBlank() && !uiState.isSending,
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) { Text("Open WhatsApp and send it") }

    when {
        uiState.isSending -> {
            Spacer(Modifier.height(14.dp))
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            Spacer(Modifier.height(8.dp))
            Text("Sending your code", style = MaterialTheme.typography.bodySmall)
        }

        uiState.watching -> {
            Spacer(Modifier.height(14.dp))
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            Spacer(Modifier.height(8.dp))
            Text(
                "Waiting for your message (${uiState.watchSecondsLeft}s)",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        uiState.watchGaveUp -> {
            Spacer(Modifier.height(14.dp))
            Text(
                "We have not seen your message yet. Send it in WhatsApp, then check again.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onCheckNow, modifier = Modifier.fillMaxWidth()) {
                Text("I have sent it, check again")
            }
        }

        !uiState.codeSent -> {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onCheckNow) { Text("I have already sent it") }
        }

        else -> {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRequestCode, enabled = uiState.cooldownSeconds == 0) {
                Text(
                    if (uiState.cooldownSeconds > 0) "Send another code in ${uiState.cooldownSeconds}s"
                    else "Send another code"
                )
            }
        }
    }
}

@Composable
private fun GatewayDownSection(
    uiState: PhoneVerifyUiState,
    onRetryGateway: () -> Unit,
    onUseSms: () -> Unit,
) {
    Text(
        "The verification service is not answering right now. This is on our side, " +
            "not yours, and it is usually back within the hour.",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(16.dp))
    Button(onClick = onRetryGateway, modifier = Modifier.fillMaxWidth().height(48.dp)) {
        Text("Check again")
    }
    if (uiState.smsFallbackOffered) {
        Spacer(Modifier.height(10.dp))
        Text(
            "Your number is Pakistani, so we can send the code as a text instead.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onUseSms,
            enabled = !uiState.isSending,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Send the code by text") }
    }
}

@Composable
private fun CodeEntry(
    uiState: PhoneVerifyUiState,
    code: String,
    onCodeChange: (String) -> Unit,
    onVerify: (String) -> Unit,
) {
    OutlinedTextField(
        value = code,
        onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) onCodeChange(it) },
        label = { Text("Verification Code") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("123456") },
    )
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = { onVerify(code) },
        enabled = code.length == 6 && !uiState.isLoading && !uiState.isSending,
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) {
        if (uiState.isLoading) {
            CircularProgressIndicator(
                Modifier.size(20.dp), strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Text("Verify")
        }
    }
}

@Preview(showBackground = true, name = "Phone Verify - waiting for the message")
@Composable
private fun PhoneVerifyPreview() {
    SpotwireTheme {
        PhoneVerifyContent(
            uiState = PhoneVerifyUiState(
                phoneNumber = "+923001234567",
                checkingGateway = false,
                gatewayUp = true,
                waPhrase = "I allow Spotwire to send me messages",
                waLink = "https://wa.me/923001112222",
                watching = true,
                watchSecondsLeft = 96,
            ),
            onOpenWhatsApp = {}, onCheckNow = {}, onRequestCode = {},
            onRetryGateway = {}, onUseSms = {}, onVerify = {}, onSkip = {},
        )
    }
}

@Preview(showBackground = true, name = "Phone Verify - code sent")
@Composable
private fun PhoneVerifySentPreview() {
    SpotwireTheme {
        PhoneVerifyContent(
            uiState = PhoneVerifyUiState(
                phoneNumber = "+923001234567",
                checkingGateway = false,
                gatewayUp = true,
                waPhrase = "I allow Spotwire to send me messages",
                waLink = "https://wa.me/923001112222",
                optedIn = true,
                codeSent = true,
                cooldownSeconds = 42,
            ),
            onOpenWhatsApp = {}, onCheckNow = {}, onRequestCode = {},
            onRetryGateway = {}, onUseSms = {}, onVerify = {}, onSkip = {},
        )
    }
}

@Preview(showBackground = true, name = "Phone Verify - gateway down")
@Composable
private fun PhoneVerifyGatewayDownPreview() {
    SpotwireTheme {
        PhoneVerifyContent(
            uiState = PhoneVerifyUiState(
                phoneNumber = "+923001234567",
                checkingGateway = false,
                gatewayUp = false,
                smsFallbackOffered = true,
            ),
            onOpenWhatsApp = {}, onCheckNow = {}, onRequestCode = {},
            onRetryGateway = {}, onUseSms = {}, onVerify = {}, onSkip = {},
        )
    }
}
