package com.spotwire.app.presentation.auth

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
import com.spotwire.app.presentation.components.PhoneNumberField
import com.spotwire.app.presentation.components.rememberDefaultCountry
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
        onSendCode = viewModel::sendCode,
        onVerify = viewModel::verify,
        onSkip = onSkip,
    )
}

@Composable
private fun PhoneVerifyContent(
    uiState: PhoneVerifyUiState,
    onSendCode: (String, String) -> Unit,
    onVerify: (String) -> Unit,
    onSkip: () -> Unit,
) {
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val defaultCountry = rememberDefaultCountry()
    var country by remember { mutableStateOf(defaultCountry) }
    // Prefill the phone field once the stored number loads (don't clobber edits).
    var prefilled by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.phoneNumber) {
        if (!prefilled && uiState.phoneNumber.isNotBlank()) {
            phone = uiState.phoneNumber
            if (uiState.country.isNotBlank()) country = uiState.country
            prefilled = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Verify Phone", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            if (uiState.codeSent) {
                "A 6-digit code was sent to ${uiState.phoneNumber} as a text message. It is valid for one hour."
            } else {
                "Enter your phone number and ask for a code. Your number has to be verified " +
                    "before any message can be sent, because every message is signed with it."
            },
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))

        PhoneNumberField(
            number = phone,
            onNumberChange = { phone = it },
            country = country,
            onCountryChange = { country = it },
            label = "Phone Number",
            modifier = Modifier.fillMaxWidth(),
        )
        // The code is sent by one device holding a Pakistani SIM. Saying so here
        // stops somebody abroad tapping Send Code over and over at something that
        // was never going to reach them.
        if (!uiState.canReceiveCode) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Codes are sent by text from one device with a Pakistani SIM, so this number " +
                    "cannot receive one. Your account is confirmed by email instead, from Profile.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { onSendCode(phone, country) },
            enabled = phone.isNotBlank() && !uiState.isSending && !uiState.isLoading &&
                uiState.cooldownSeconds == 0,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            when {
                uiState.isSending -> CircularProgressIndicator(
                    Modifier.size(20.dp), strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                uiState.cooldownSeconds > 0 -> Text("Resend in ${uiState.cooldownSeconds}s")
                uiState.codeSent -> Text("Resend Code")
                else -> Text("Send Code")
            }
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = code,
            onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) code = it },
            label = { Text("Verification Code") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("123456") },
        )
        uiState.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
            )
        }
        if (uiState.codeSent && uiState.error == null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Code sent, check your messages.", color = MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
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
                Text("Verify Phone")
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
            Text("Skip for now (no messages can be sent until then)")
        }
    }
}

@Preview(showBackground = true, name = "Phone Verify - before send")
@Composable
private fun PhoneVerifyPreview() {
    SpotwireTheme {
        PhoneVerifyContent(
            uiState = PhoneVerifyUiState(phoneNumber = "+923001234567"),
            onSendCode = { _, _ -> }, onVerify = {}, onSkip = {},
        )
    }
}

@Preview(showBackground = true, name = "Phone Verify - code sent")
@Composable
private fun PhoneVerifySentPreview() {
    SpotwireTheme {
        PhoneVerifyContent(
            uiState = PhoneVerifyUiState(phoneNumber = "+923001234567", codeSent = true),
            onSendCode = { _, _ -> }, onVerify = {}, onSkip = {},
        )
    }
}

@Preview(showBackground = true, name = "Phone Verify - number cannot receive a code")
@Composable
private fun PhoneVerifyForeignPreview() {
    SpotwireTheme {
        PhoneVerifyContent(
            uiState = PhoneVerifyUiState(phoneNumber = "+447700900123", canReceiveCode = false),
            onSendCode = { _, _ -> }, onVerify = {}, onSkip = {},
        )
    }
}
